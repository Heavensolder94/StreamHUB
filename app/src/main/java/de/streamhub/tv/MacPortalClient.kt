package de.streamhub.tv

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A user-supplied, authorized Stalker/Ministra (MAG) portal.
 * The MAC is an account identifier, not the Android TV box's hardware MAC.
 */
internal data class MacPortalSource(val url: String, val mac: String, val allowHttp: Boolean) {
    companion object {
        fun fromInput(url: String, mac: String, allowHttp: Boolean): MacPortalSource {
            val address = url.trim().trimEnd('/')
            require(address.length in 12..450 && address.none { it.code < 32 }) {
                "Bitte eine gültige Portal-URL eingeben."
            }
            val uri = Uri.parse(address)
            require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) {
                "HTTPS verwenden oder HTTP ausdrücklich erlauben."
            }
            require(uri.host != null && uri.host!!.isNotBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Die Portal-URL darf keine Zugangsdaten oder Query-Parameter enthalten."
            }
            val digits = mac.uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "")
            require(digits.length == 12 && digits.matches(Regex("[0-9A-F]{12}"))) {
                "MAC-Adresse im Format 00:1A:79:12:34:56 eingeben."
            }
            val normalized = digits.chunked(2).joinToString(":")
            return MacPortalSource(address, normalized, allowHttp)
        }
    }
}

/** A named, saved portal/MAC pair; only one profile is active at a time. */
internal data class MacPortalProfile(
    val id: String,
    val name: String,
    val source: MacPortalSource
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", source.url)
        .put("mac", source.mac).put("allowHttp", source.allowHttp)

    companion object {
        fun fromJson(obj: JSONObject): MacPortalProfile = MacPortalProfile(
            obj.getString("id"),
            obj.getString("name"),
            MacPortalSource.fromInput(obj.getString("url"), obj.getString("mac"),
                obj.optBoolean("allowHttp", false))
        )
    }
}

/**
 * Encrypted, device-local portal profile manager.
 * Migrates the V2.5 single "source" entry without discarding existing credentials.
 */
internal class MacPortalStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_mac_portal", Context.MODE_PRIVATE)
    private val alias = "streamhub_mac_portal_aes_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun encrypt(json: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): JSONObject {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        require(bytes.size >= 29) { "Gespeicherte Portaldaten sind ungültig." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)),
            Charsets.UTF_8))
    }

    private fun persist(doc: JSONObject) {
        check(prefs.edit().putString("profiles", encrypt(doc)).remove("source").commit()) {
            "Portalprofile konnten nicht gespeichert werden."
        }
    }

    private fun document(): JSONObject {
        prefs.getString("profiles", null)?.let { return decrypt(it) }
        val doc = JSONObject().put("activeId", "").put("profiles", JSONArray())
        // First launch after 2.5: preserve the old portal and activate it.
        prefs.getString("source", null)?.let { old ->
            val json = decrypt(old)
            val source = MacPortalSource.fromInput(
                json.getString("url"), json.getString("mac"),
                json.optBoolean("allowHttp", false))
            val profile = MacPortalProfile(UUID.randomUUID().toString(),
                "Mein bisheriges Portal", source)
            doc.put("activeId", profile.id)
            doc.getJSONArray("profiles").put(profile.json())
            persist(doc)
        }
        return doc
    }

    private fun parse(doc: JSONObject): List<MacPortalProfile> {
        val array = doc.getJSONArray("profiles")
        return (0 until array.length()).map { MacPortalProfile.fromJson(array.getJSONObject(it)) }
    }

    fun profiles(): List<MacPortalProfile> = parse(document())

    fun active(): MacPortalProfile? {
        val doc = document()
        val id = doc.optString("activeId")
        return parse(doc).firstOrNull { it.id == id }
    }

    fun upsert(
        name: String, source: MacPortalSource,
        editingId: String? = null, activate: Boolean = false
    ): MacPortalProfile {
        val label = name.trim()
        require(label.isNotBlank()) { "Bitte einen Profilnamen eingeben." }
        require(label.length <= 55) { "Profilname darf höchstens 55 Zeichen haben." }
        val doc = document()
        val previous = parse(doc)
        if (editingId != null) require(previous.any { it.id == editingId }) {
            "Profil nicht mehr vorhanden."
        }
        if (editingId == null) require(previous.size < 30) {
            "Maximal 30 Portalprofile möglich."
        }
        require(previous.none {
            it.id != editingId && it.source.url.equals(source.url, ignoreCase = true) &&
                it.source.mac.equals(source.mac, ignoreCase = true)
        }) { "Diese Portal-URL/MAC-Kombination ist bereits gespeichert." }
        val profile = MacPortalProfile(editingId ?: UUID.randomUUID().toString(), label, source)
        val list = if (editingId == null) previous + profile
            else previous.map { if (it.id == editingId) profile else it }
        doc.put("profiles", JSONArray().also { arr -> list.forEach { arr.put(it.json()) } })
        if (activate) doc.put("activeId", profile.id)
        persist(doc)
        return profile
    }

    /** A null id deactivates IPTV while keeping all saved profiles. */
    fun activate(id: String?) {
        val doc = document()
        require(id == null || parse(doc).any { it.id == id }) {
            "Dieses Profil existiert nicht."
        }
        doc.put("activeId", id ?: "")
        persist(doc)
    }

    fun delete(id: String) {
        val doc = document()
        val remaining = parse(doc).filterNot { it.id == id }
        doc.put("profiles", JSONArray().also { arr ->
            remaining.forEach { arr.put(it.json()) }
        })
        if (doc.optString("activeId") == id) doc.put("activeId", "")
        persist(doc)
    }
}

internal data class MacPortalCategory(val id: String, val title: String)
internal data class MacPortalMedia(
    val id: String,
    val name: String,
    val command: String,
    val image: String,
    val type: String,
    val raw: JSONObject,
    val episode: String = ""
)
internal data class MacPortalPage(val entries: List<MacPortalMedia>, val hasMore: Boolean)

/**
 * Network access runs exclusively on a worker thread. It has no credentials of its own:
 * the MAC/portal must be supplied by the user and activated at the provider.
 * Compatible with common portal.php and server/load.php dialects.
 */
internal class MacPortalClient(private val source: MacPortalSource) {
    private var endpoint: String? = null
    private var token: String = ""

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun candidates(): List<String> {
        val u = Uri.parse(source.url)
        val origin = u.scheme + "://" + u.encodedAuthority
        val path = u.path.orEmpty().trimEnd('/')
        if (path.endsWith("/portal.php") || path.endsWith("/server/load.php")) {
            return listOf(origin + path)
        }
        val parent = if (path.endsWith("/c")) path.removeSuffix("/c") else path
        val base = parent.trimEnd('/')
        return listOf(
            origin + base + "/portal.php",
            origin + base + "/server/load.php",
            origin + base + "/stalker_portal/server/load.php",
            origin + "/portal.php",
            origin + "/server/load.php",
            origin + "/stalker_portal/server/load.php"
        ).distinct()
    }

    fun connect() {
        var last: Exception? = null
        for (candidate in candidates()) {
            try {
                val response = request(candidate, "stb", "handshake",
                    mapOf("token" to ""), "")
                val newToken = response.optJSONObject("js")?.optString("token").orEmpty()
                if (newToken.isNotBlank()) {
                    endpoint = candidate
                    token = newToken
                    return
                }
            } catch (error: Exception) {
                last = error
            }
        }
        throw IllegalStateException(
            "Portal nicht erreichbar oder MAC nicht akzeptiert. Prüfe URL und Anbieterzugang. " +
                (last?.message ?: "Kein Handshake-Token erhalten.")
        )
    }

    private fun request(
        target: String, type: String, action: String,
        params: Map<String, String>, accessToken: String
    ): JSONObject {
        val pairs = linkedMapOf("type" to type, "action" to action,
            "JsHttpRequest" to "1-xml")
        pairs.putAll(params)
        val address = target + "?" + pairs.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8500
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp")
            conn.setRequestProperty("X-User-Agent", "Model: MAG250; Link: Ethernet")
            conn.setRequestProperty("Cookie", "mac=" + encode(source.mac) +
                "; stb_lang=de; timezone=Europe%2FBerlin")
            if (accessToken.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessToken)
            }
            val status = conn.responseCode
            require(status in 200..299) { "HTTP " + status + " (" + action + ")" }
            val bytes = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(bytes.size() + count <= 2_000_000) { "Portal-Antwort zu groß." }
                    bytes.write(chunk, 0, count)
                }
            }
            val result = JSONObject(bytes.toString("UTF-8"))
            val js = result.opt("js")
            if (js is JSONObject && js.optString("error").isNotBlank()) {
                error("Portal: " + js.optString("error").take(150))
            }
            return result
        } finally {
            conn.disconnect()
        }
    }

    private fun call(type: String, action: String, params: Map<String, String> = emptyMap()): JSONObject {
        if (endpoint == null || token.isBlank()) connect()
        val target = endpoint ?: error("Portal nicht verbunden.")
        return try {
            request(target, type, action, params, token)
        } catch (e: Exception) {
            if (!e.message.orEmpty().contains("401") && !e.message.orEmpty().contains("403")) throw e
            connect()
            request(endpoint ?: target, type, action, params, token)
        }
    }

    private fun responseArray(response: JSONObject): JSONArray {
        val js = response.opt("js")
        if (js is JSONArray) return js
        if (js is JSONObject) {
            return js.optJSONArray("data") ?: js.optJSONArray("items") ?: JSONArray()
        }
        return JSONArray()
    }

    /** kind: itv, vod, series. Always includes an "All" category. */
    fun categories(kind: String): List<MacPortalCategory> {
        require(kind in setOf("itv", "vod", "series"))
        val response = call(kind, if (kind == "itv") "get_genres" else "get_categories")
        val array = responseArray(response)
        val categories = (0 until minOf(300, array.length())).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id", obj.optString("category_id")).take(60)
            val title = obj.optString("title", obj.optString("name")).trim().take(100)
            if (id.isBlank() || title.isBlank() || id == "*") null
            else MacPortalCategory(id, title)
        }
        return listOf(MacPortalCategory("*", "Alle Kategorien")) + categories.distinctBy { it.id }
    }

    private fun parseMedia(response: JSONObject, kind: String): List<MacPortalMedia> {
        val entries = responseArray(response)
        return (0 until minOf(300, entries.length())).mapNotNull { i ->
            val obj = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", obj.optString("movie_id")).take(90)
            val title = listOf("name", "title", "o_name").map { obj.optString(it).trim() }
                .firstOrNull { it.isNotBlank() }.orEmpty().take(150)
            if (title.isEmpty()) return@mapNotNull null
            MacPortalMedia(id, title, obj.optString("cmd").take(2000),
                obj.optString("screenshot_uri", obj.optString("logo")).take(500), kind, obj)
        }
    }

    fun page(kind: String, category: String, page: Int): MacPortalPage {
        require(kind in setOf("itv", "vod", "series"))
        require(page in 1..250)
        val args = linkedMapOf("p" to page.toString())
        if (category.isNotBlank() && category != "*") {
            args[if (kind == "itv") "genre" else "category"] = category
        }
        var response = call(kind, "get_ordered_list", args)
        var entries = parseMedia(response, kind)
        // Some portal dialects index the first page at zero instead of one.
        if (entries.isEmpty() && page == 1) {
            response = call(kind, "get_ordered_list", args + ("p" to "0"))
            entries = parseMedia(response, kind)
        }
        val wrapper = response.optJSONObject("js")
        val total = wrapper?.optInt("total_items", 0) ?: 0
        val pageSize = wrapper?.optInt("max_page_items", 0) ?: 0
        val hasMore = if (total > 0) page * (if (pageSize > 0) pageSize else entries.size) < total
            else entries.size >= 14
        return MacPortalPage(entries, hasMore && page < 250)
    }

    /** Resolve embedded series[] episode numbers where the parent command carries the media path. */
    private fun fromSeriesArray(season: MacPortalMedia, parent: MacPortalMedia): List<MacPortalMedia> {
        val values = season.raw.optJSONArray("series") ?: return emptyList()
        val command = season.command.ifBlank { parent.command }
        if (command.isBlank() || command == "null") return emptyList()
        val result = mutableListOf<MacPortalMedia>()
        for (i in 0 until minOf(values.length(), 150)) {
            val value = values.opt(i) ?: continue
            if (value is JSONArray) {
                for (j in 0 until minOf(value.length(), 150)) {
                    val episode = value.optString(j).trim()
                    if (episode.isBlank()) continue
                    result.add(MacPortalMedia(episode,
                        season.name + " · Folge " + (j + 1),
                        command, "", "vod", season.raw, episode))
                }
            } else {
                val episode = if (value is JSONObject)
                    value.optString("id", value.optString("episode")) else value.toString()
                if (episode.isBlank()) continue
                val label = if (value is JSONObject) value.optString("name",
                    "Folge " + (i + 1)) else "Folge " + (i + 1)
                result.add(MacPortalMedia(episode,
                    season.name + " · " + label,
                    if (value is JSONObject) value.optString("cmd").ifBlank { command } else command,
                    "", "vod", season.raw, episode))
            }
            if (result.size >= 300) break
        }
        return result
    }

    fun episodes(show: MacPortalMedia): List<MacPortalMedia> {
        val embedded = show.raw.optJSONArray("episodes")
        if (embedded != null) {
            val result = mutableListOf<MacPortalMedia>()
            for (i in 0 until minOf(embedded.length(), 300)) {
                val episode = embedded.optJSONObject(i) ?: continue
                val command = episode.optString("cmd")
                if (command.isNotBlank() && command != "null") result.add(MacPortalMedia(
                    episode.optString("id"), episode.optString("name",
                        "Folge " + (i + 1)), command, "", "vod", episode))
            }
            if (result.isNotEmpty()) return result
        }
        val fromShow = fromSeriesArray(show, show)
        if (fromShow.isNotEmpty()) return fromShow
        if (show.id.isBlank()) return emptyList()
        val requests = listOf(
            "series" to mapOf("movie_id" to show.id, "p" to "1"),
            "series" to mapOf("series_id" to show.id, "p" to "1"),
            "vod" to mapOf("movie_id" to show.id, "season_id" to "0",
                "episode_id" to "0", "p" to "1")
        )
        for ((type, args) in requests) {
            val children = runCatching {
                parseMedia(call(type, "get_ordered_list", args), type)
            }.getOrDefault(emptyList())
            val expanded = children.flatMap { fromSeriesArray(it, show) }.take(300)
            if (expanded.isNotEmpty()) return expanded
            // Other portals return one directly playable item per episode.
            val directlyPlayable = children.filter {
                it.command.startsWith("http://") ||
                    it.command.startsWith("https://") ||
                    it.command.startsWith("ffmpeg ") ||
                    it.command.startsWith("ffrt ")
            }
            if (directlyPlayable.isNotEmpty()) return directlyPlayable
        }
        return emptyList()
    }

    fun playback(media: MacPortalMedia): String {
        require(media.command.isNotBlank()) {
            "Dieser Eintrag enthält noch keinen Stream. Bei Serien erst eine Episode auswählen."
        }
        val type = if (media.type == "itv") "itv" else if (media.type == "series") "series" else "vod"
        val params = mutableMapOf("cmd" to media.command)
        if (media.episode.isNotBlank()) params["series"] = media.episode
        val resolved = runCatching {
            val js = call(type, "create_link", params).optJSONObject("js")
            js?.optString("cmd").orEmpty()
        }.getOrDefault("")
        val candidate = (resolved.ifBlank { media.command }).trim()
            .removePrefix("ffmpeg ").removePrefix("ffrt ").trim().trim('"', '\'')
        require(candidate.startsWith("https://") ||
            (source.allowHttp && candidate.startsWith("http://"))) {
            "Kein direkt abspielbarer Stream geliefert oder HTTP nicht freigegeben."
        }
        val uri = Uri.parse(candidate)
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Ungültiger Stream-Link vom Portal."
        }
        return candidate
    }
}
