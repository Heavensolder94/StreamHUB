package de.streamhub.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * User-supplied HTTPS RSS/Atom or JSON Feed sources.
 * Only explicit public feeds are read. No login scraping, DRM extraction or crawling.
 */
internal data class PublicFeedProfile(
    val id: String, val name: String, val url: String, val enabled: Boolean
) {
    fun json() = JSONObject().put("id", id).put("name", name)
        .put("url", url).put("enabled", enabled)
}

internal data class PublicFeedEntry(
    val id: String, val title: String, val description: String,
    val pageUrl: String, val coverUrl: String, val videoUrl: String,
    val provider: String
)

internal class PublicFeedStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_public_feeds_v3", Context.MODE_PRIVATE)
    fun profiles(): List<PublicFeedProfile> = runCatching {
        val a = JSONArray(prefs.getString("profiles", "[]") ?: "[]")
        (0 until minOf(a.length(), 24)).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                PublicFeedProfile(it.optString("id"), it.optString("name"),
                    it.optString("url"), it.optBoolean("enabled", true))
                    .takeIf { p -> p.id.isNotBlank() && p.url.isNotBlank() }
            }
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<PublicFeedProfile>) {
        val a = JSONArray()
        items.take(24).forEach { a.put(it.json()) }
        check(prefs.edit().putString("profiles", a.toString()).commit()) {
            "Feed-Konfiguration konnte nicht gespeichert werden."
        }
    }

    fun upsert(name: String, url: String, editingId: String? = null) {
        val title = name.trim().take(60)
        require(title.length >= 2) { "Bitte einen Quellennamen eingeben." }
        require(PublicFeedAdapter.safeHttps(url)) {
            "Nur öffentliche HTTPS-Feed-Adressen sind erlaubt."
        }
        val current = profiles()
        require(editingId != null || current.size < 24) {
            "Maximal 24 Feeds. Bitte zuerst einen Eintrag entfernen."
        }
        val candidate = PublicFeedProfile(editingId ?: UUID.randomUUID().toString(),
            title, url.trim(), true)
        require(current.none { it.id != editingId && it.url == candidate.url }) {
            "Feed-Adresse ist bereits gespeichert."
        }
        save(current.filterNot { it.id == editingId } + candidate)
    }

    fun toggle(id: String) = save(profiles().map {
        if (it.id == id) it.copy(enabled = !it.enabled) else it
    })

    fun delete(id: String) = save(profiles().filterNot { it.id == id })
}

internal object PublicFeedAdapter {
    private val pool = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var lastKey = ""
    private var lastTimestamp = 0L
    private var lastResult: Pair<List<PublicFeedEntry>, List<String>> =
        emptyList<PublicFeedEntry>() to emptyList()
    private var loading = false
    private val callbacks = mutableListOf<(List<PublicFeedEntry>, List<String>) -> Unit>()

    /** Reject HTTP and obvious local/private endpoints before connecting. */
    fun safeHttps(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return@runCatching false
        uri.scheme.equals("https", true) && uri.userInfo == null &&
            uri.port in -1..65535 &&
            uri.fragment == null && host.length in 4..253 &&
            host.contains(".") && !host.matches(Regex("[0-9.]+")) &&
            !host.contains(":") && !host.startsWith("localhost") &&
            !host.endsWith(".local") && !host.endsWith(".internal") &&
            !host.endsWith(".test") && !host.endsWith(".invalid") &&
            !host.startsWith("127.") && !host.startsWith("10.") &&
            !host.startsWith("192.168.") && !host.startsWith("169.254.") &&
            !host.startsWith("172.16.") && !host.startsWith("172.17.") &&
            !host.startsWith("172.18.") && !host.startsWith("172.19.") &&
            !host.startsWith("172.2") && !host.startsWith("172.30.") &&
            !host.startsWith("172.31.") &&
            host.matches(Regex("[a-z0-9.-]+"))
    }.getOrDefault(false)

    fun cached(): List<PublicFeedEntry> = lastResult.first

    fun refresh(context: Context, force: Boolean = false,
        done: (List<PublicFeedEntry>, List<String>) -> Unit) {
        val profiles = PublicFeedStore(context).profiles().filter { it.enabled }
        val signature = profiles.joinToString("|") { it.id + it.url }
        synchronized(this) {
            if (!force && !loading && signature == lastKey &&
                System.currentTimeMillis() - lastTimestamp in 0..(15L * 60L * 1000L)) {
                val (entries, errors) = lastResult
                ui.post { done(entries, errors) }
                return
            }
            callbacks.add(done)
            if (loading) return
            loading = true
        }
        pool.execute {
            val entries = mutableListOf<PublicFeedEntry>()
            val errors = mutableListOf<String>()
            profiles.take(12).forEach { profile ->
                runCatching { fetch(profile) }.fold(
                    onSuccess = { entries.addAll(it) },
                    onFailure = {
                        errors.add(profile.name + ": " +
                            (it.message ?: "Feed nicht erreichbar").take(80))
                    })
            }
            if (profiles.size > 12) errors.add("Nur die ersten 12 aktiven Feeds abgefragt.")
            val outcome = entries.distinctBy { it.id }.take(360) to errors
            val waiting: List<(List<PublicFeedEntry>, List<String>) -> Unit>
            synchronized(this) {
                lastResult = outcome
                lastTimestamp = System.currentTimeMillis()
                lastKey = signature
                loading = false
                waiting = callbacks.toList()
                callbacks.clear()
            }
            ui.post { waiting.forEach { it(outcome.first, outcome.second) } }
        }
    }

    private fun fetch(profile: PublicFeedProfile): List<PublicFeedEntry> {
        require(safeHttps(profile.url))
        val connection = URL(profile.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/rss+xml,application/atom+xml,application/feed+json,application/json,application/xml,text/xml")
            connection.setRequestProperty("User-Agent", "StreamHUBNativeTV/3.0")
            require(connection.responseCode in 200..299) {
                "HTTP " + connection.responseCode
            }
            require(connection.contentLengthLong <= 1_500_000L) {
                "Feed zu groß."
            }
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    require(buffer.size() + n <= 1_500_000) { "Feed zu groß." }
                    buffer.write(chunk, 0, n)
                }
            }
            val text = buffer.toString("UTF-8").trim()
            return if (text.startsWith("{")) parseJson(profile, text)
                else parseXml(profile, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun sanitize(text: String): String =
        Jsoup.parse(text).text().replace(Regex("\\s+"), " ").trim().take(500)

    private fun url(raw: String, base: String): String {
        val value = runCatching { URI(base).resolve(raw.trim()).toString() }
            .getOrDefault("")
        return value.take(2000).takeIf { safeHttps(it) } ?: ""
    }

    private fun validVideo(mime: String, link: String): Boolean {
        if (!safeHttps(link)) return false
        val m = mime.lowercase()
        return m.startsWith("video/") || m == "application/vnd.apple.mpegurl" ||
            m == "application/x-mpegurl" || m == "application/dash+xml"
    }

    private fun parseJson(profile: PublicFeedProfile, raw: String): List<PublicFeedEntry> {
        val root = JSONObject(raw)
        require(root.optString("version").contains("jsonfeed.org/version") ||
            root.optString("version").isBlank() && root.has("items")) {
            "Nicht unterstütztes JSON-Feed-Format."
        }
        val list = root.optJSONArray("items") ?: JSONArray()
        return (0 until minOf(list.length(), 90)).mapNotNull { i ->
            val obj = list.optJSONObject(i) ?: return@mapNotNull null
            val name = sanitize(obj.optString("title"))
            val link = url(obj.optString("url", obj.optString("external_url")),
                profile.url)
            if (name.length < 2 || link.isBlank()) return@mapNotNull null
            val attachments = obj.optJSONArray("attachments") ?: JSONArray()
            var playback = ""
            for (a in 0 until minOf(attachments.length(), 6)) {
                val entry = attachments.optJSONObject(a) ?: continue
                val candidate = url(entry.optString("url"), profile.url)
                if (validVideo(entry.optString("mime_type"), candidate)) {
                    playback = candidate
                    break
                }
            }
            PublicFeedEntry(profile.id + ":" +
                obj.optString("id", link).take(240), name,
                sanitize(obj.optString("summary", obj.optString("content_text",
                    obj.optString("content_html")))), link,
                url(obj.optString("image", obj.optString("banner_image")), profile.url),
                playback, profile.name)
        }
    }

    private fun parseXml(profile: PublicFeedProfile, raw: String): List<PublicFeedEntry> {
        require(!raw.contains("<!DOCTYPE", ignoreCase = true) &&
            !raw.contains("<!ENTITY", ignoreCase = true)) {
            "XML mit externen Entities wird nicht unterstützt."
        }
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(raw.reader())
        val out = mutableListOf<PublicFeedEntry>()
        var inside = false
        var title = ""
        var desc = ""
        var id = ""
        var link = ""
        var image = ""
        var video = ""
        var element = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < 90) {
            val local = parser.name?.substringAfter(':').orEmpty()
            if (event == XmlPullParser.START_TAG) {
                if (local == "item" || local == "entry") {
                    inside = true
                    element = local
                    title = ""; desc = ""; id = ""; link = ""; image = ""; video = ""
                } else if (inside) {
                    when (local) {
                        "title" -> title = sanitize(parser.nextText())
                        "description", "summary" -> desc = sanitize(parser.nextText())
                        "guid", "id" -> id = parser.nextText().take(240)
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel").orEmpty()
                            val mime = parser.getAttributeValue(null, "type").orEmpty()
                            if (href != null) {
                                val resolved = url(href, profile.url)
                                if (rel == "enclosure" && validVideo(mime, resolved))
                                    video = resolved
                                else if (rel.isEmpty() || rel == "alternate") link = resolved
                            } else {
                                link = url(parser.nextText(), profile.url)
                            }
                        }
                        "enclosure", "content" -> {
                            val href = parser.getAttributeValue(null, "url")
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val resolved = if (href == null) "" else url(href, profile.url)
                            if (validVideo(type, resolved)) video = resolved
                        }
                        "thumbnail" -> {
                            val href = parser.getAttributeValue(null, "url").orEmpty()
                            if (image.isBlank()) image = url(href, profile.url)
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG &&
                inside && local == element) {
                if (title.length >= 2 && link.isNotBlank()) {
                    out.add(PublicFeedEntry(profile.id + ":" +
                        id.ifBlank { link }.take(240), title.take(160), desc,
                        link, image, video, profile.name))
                }
                inside = false
            }
            event = parser.next()
        }
        return out
    }
}
