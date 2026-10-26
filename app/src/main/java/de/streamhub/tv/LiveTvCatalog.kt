package de.streamhub.tv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Native, Kodi-independent IPTV playlist catalog.
 * URLs are strictly HTTPS. Playlist parser reads M3U metadata only; it does not
 * fetch or extract streams from arbitrary provider websites.
 */
internal data class LiveChannel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "Eigene Sender",
    val tvgId: String = "",
    val logo: String = "",
    val favorite: Boolean = false
) {
    fun asJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url)
        .put("group", group).put("tvgId", tvgId)
        .put("logo", logo).put("favorite", favorite)

    companion object {
        fun fromJson(data: JSONObject): LiveChannel? {
            val url = data.optString("url", "").trim()
            if (!LiveTvCatalog.validHttpsUrl(url)) return null
            val name = data.optString("name", "").trim().take(100)
            if (name.isBlank()) return null
            return LiveChannel(
                data.optString("id", "").ifBlank { UUID.randomUUID().toString() }.take(90),
                name, url, data.optString("group", "Eigene Sender").take(60),
                data.optString("tvgId", "").take(100),
                data.optString("logo", "").take(500),
                data.optBoolean("favorite", false)
            )
        }
    }
}

internal class LiveTvCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_live_tv", Context.MODE_PRIVATE)

    fun channels(): List<LiveChannel> {
        val array = runCatching {
            JSONArray(prefs.getString("channels", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return (0 until minOf(array.length(), MAX_CHANNELS)).mapNotNull {
            array.optJSONObject(it)?.let(LiveChannel::fromJson)
        }
    }

    fun save(list: List<LiveChannel>) {
        val out = JSONArray()
        list.distinctBy { it.url }.take(MAX_CHANNELS).forEach { out.put(it.asJson()) }
        prefs.edit().putString("channels", out.toString()).apply()
    }

    fun merge(incoming: List<LiveChannel>): Int {
        val existing = channels()
        val favoritesByUrl = existing.associate { it.url to it.favorite }
        val idsByUrl = existing.associate { it.url to it.id }
        val known = existing.map { it.url }.toHashSet()
        val fresh = incoming.filter { known.add(it.url) }.take((MAX_CHANNELS - existing.size).coerceAtLeast(0))
        val changed = fresh.map {
            it.copy(id = idsByUrl[it.url] ?: it.id, favorite = favoritesByUrl[it.url] ?: it.favorite)
        }
        save(existing + changed)
        return changed.size
    }

    fun add(name: String, url: String, group: String = "Eigene Sender"): Boolean {
        if (name.isBlank() || !validHttpsUrl(url)) return false
        return merge(listOf(LiveChannel(
            UUID.randomUUID().toString(), name.trim().take(100), url.trim(),
            group.take(60)
        ))) > 0
    }

    fun toggleFavorite(id: String) {
        save(channels().map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    fun delete(id: String) {
        save(channels().filterNot { it.id == id })
    }

    companion object {
        const val MAX_CHANNELS = 650
        private const val MAX_PLAYLIST_BYTES = 1_800_000
        private val attribute = Regex("""([a-zA-Z0-9_-]+)="([^"]{0,500})"""")

        fun validHttpsUrl(value: String): Boolean {
            if (value.length !in 12..2000 || value.any { it.code < 32 }) return false
            val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return false
            if (parsed.scheme != "https" || parsed.encodedAuthority?.contains("@") == true) return false
            val hostname = parsed.host?.lowercase(Locale.ROOT) ?: return false
            return hostname.matches(Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""")) &&
                hostname.length <= 253
        }

        fun fetchPlaylist(address: String): String {
            require(validHttpsUrl(address)) { "Die Playlist muss eine gültige HTTPS-Adresse haben." }
            var current = address
            repeat(4) { step ->
                val connection = URL(current).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8500
                    connection.readTimeout = 10000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
                    connection.setRequestProperty("User-Agent", "StreamHubTV/2.4")
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val next = URL(URL(current), connection.getHeaderField("Location") ?: "")
                            .toString()
                        require(step < 3 && validHttpsUrl(next)) { "Playlist-Weiterleitung nicht erlaubt." }
                        current = next
                        return@repeat
                    }
                    require(status in 200..299) { "Playlist HTTP $status" }
                    val data = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val block = ByteArray(8192)
                        while (true) {
                            val n = input.read(block)
                            if (n < 0) break
                            require(data.size() + n <= MAX_PLAYLIST_BYTES) { "Playlist zu groß (max. 1,8 MB)." }
                            data.write(block, 0, n)
                        }
                    }
                    return data.toString("UTF-8")
                } finally {
                    connection.disconnect()
                }
            }
            error("Zu viele Weiterleitungen.")
        }

        fun parseM3u(text: String): List<LiveChannel> {
            require(text.length <= MAX_PLAYLIST_BYTES) { "Playlist ist zu groß." }
            val normalized = text.removePrefix("\uFEFF")
            require(normalized.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
                "Keine gültige M3U-Senderliste: #EXTM3U fehlt."
            }
            val list = mutableListOf<LiveChannel>()
            val urls = hashSetOf<String>()
            var attrs = emptyMap<String, String>()
            var name = ""
            var prepared = false
            for (line in normalized.lineSequence()) {
                val value = line.trim()
                if (value.startsWith("#EXTINF:", true)) {
                    val comma = value.indexOf(',')
                    val head = if (comma >= 0) value.substring(0, comma) else value
                    name = if (comma >= 0) value.substring(comma + 1).trim().take(100) else ""
                    attrs = attribute.findAll(head).associate {
                        it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].take(500)
                    }
                    prepared = true
                } else if (value.isNotBlank() && !value.startsWith("#")) {
                    if (prepared && validHttpsUrl(value) && urls.add(value)) {
                        val rawName = name.ifBlank { attrs["tvg-name"].orEmpty() }
                        if (rawName.isNotBlank()) {
                            list.add(LiveChannel(
                                UUID.randomUUID().toString(),
                                rawName.take(100), value,
                                attrs["group-title"].orEmpty().ifBlank { "Eigene Sender" }.take(60),
                                attrs["tvg-id"].orEmpty().take(100),
                                attrs["tvg-logo"].orEmpty().take(500)
                            ))
                        }
                    }
                    prepared = false
                    if (list.size >= MAX_CHANNELS) break
                }
            }
            return list
        }
    }
}
