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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Username/password Xtream profile provided by an authorized IPTV provider. */
internal data class XtreamSource(
    val server: String,
    val username: String,
    val password: String,
    val allowHttp: Boolean
) {
    companion object {
        fun create(server: String, username: String, password: String, allowHttp: Boolean): XtreamSource {
            val address = server.trim().trimEnd('/')
            require(address.length in 10..400) { "Server-Adresse fehlt oder ist zu lang." }
            val uri = Uri.parse(address)
            require(uri.host?.isNotBlank() == true && uri.userInfo == null &&
                uri.query == null && uri.fragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && allowHttp))) {
                "Gültige HTTPS-URL verwenden oder HTTP ausdrücklich erlauben."
            }
            require(username.isNotBlank() && username.length <= 90 &&
                username.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "Ungültiger Benutzername."
            }
            require(password.isNotBlank() && password.length <= 130 &&
                password.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "Ungültiges Passwort."
            }
            return XtreamSource(address, username, password, allowHttp)
        }
    }
}

/** AndroidKeyStore-backed credential storage, never plaintext SharedPreferences. */
internal class XtreamStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_xtream_v27", Context.MODE_PRIVATE)
    private val alias = "streamhub_xtream_aes_v27"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = ks.getKey(alias, null) as? SecretKey
        if (old != null) return old
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun save(source: XtreamSource) {
        val data = JSONObject().put("server", source.server)
            .put("username", source.username).put("password", source.password)
            .put("allowHttp", source.allowHttp).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(data)
        check(prefs.edit().putString("credentials",
            Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    fun load(): XtreamSource? = runCatching {
        val stored = prefs.getString("credentials", null) ?: return null
        val bytes = Base64.decode(stored, Base64.DEFAULT)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val j = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        XtreamSource.create(j.getString("server"), j.getString("username"),
            j.getString("password"), j.optBoolean("allowHttp", false))
    }.getOrNull()

    fun remove() { prefs.edit().remove("credentials").apply() }
}

internal data class XtreamCategory(val id: String, val name: String)
internal data class XtreamTitle(
    val id: String, val name: String, val kind: String, val extension: String,
    val categoryId: String = "", val raw: JSONObject = JSONObject()
)

internal class XtreamClient(private val source: XtreamSource) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun path(s: String) = Uri.encode(s)
    private val base = source.server.trimEnd('/')

    private fun call(action: String = "", extra: Map<String, String> = emptyMap()): Any {
        val params = mutableMapOf("username" to source.username, "password" to source.password)
        if (action.isNotBlank()) params["action"] = action
        params.putAll(extra)
        val target = base + "/player_api.php?" +
            params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val conn = URL(target).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8500
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            require(conn.responseCode == 200) { "Anbieter antwortet HTTP " + conn.responseCode }
            val data = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(data.size() + n <= 8_000_000) {
                        "Antwort zu groß (8 MB). Kleinere Kategorie auswählen."
                    }
                    data.write(chunk, 0, n)
                }
            }
            val body = data.toString("UTF-8").trim()
            require(body.startsWith("{") || body.startsWith("[")) { "Keine Xtream-API-Antwort." }
            return if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    fun verify(): String {
        val json = call() as? JSONObject ?: error("Keine gültige Anmeldung.")
        val info = json.optJSONObject("user_info") ?: error("Keine Xtream-Login-Daten.")
        require(info.optString("auth") == "1") { "Anbieter hat Zugang nicht freigeschaltet." }
        val status = info.optString("status", "Unbekannt")
        require(status.equals("Active", true) || status.equals("Unbekannt", true)) {
            "Zugang ist nicht aktiv: " + status
        }
        return status
    }

    fun categories(kind: String): List<XtreamCategory> {
        val action = when (kind) {
            "itv" -> "get_live_categories"
            "vod" -> "get_vod_categories"
            "series" -> "get_series_categories"
            else -> error("Unbekannte Kategorie")
        }
        val items = call(action) as? JSONArray ?: JSONArray()
        val list = (0 until minOf(items.length(), 200)).mapNotNull { i ->
            val value = items.optJSONObject(i) ?: return@mapNotNull null
            XtreamCategory(value.optString("category_id"),
                value.optString("category_name", "Unbekannt"))
        }
        return listOf(XtreamCategory("*", "Alle Kategorien")) +
            list.filter { it.id.isNotBlank() }
    }

    fun titles(kind: String, categoryId: String = "*"): List<XtreamTitle> {
        val action = when (kind) {
            "itv" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            "series" -> "get_series"
            else -> error("Unbekannter Typ")
        }
        val opts = if (categoryId == "*" || categoryId.isBlank()) emptyMap()
            else mapOf("category_id" to categoryId)
        val data = call(action, opts) as? JSONArray ?: JSONArray()
        return (0 until minOf(3000, data.length())).mapNotNull { i ->
            val raw = data.optJSONObject(i) ?: return@mapNotNull null
            val name = raw.optString("name", raw.optString("title")).trim()
            val id = if (kind == "series") raw.optString("series_id")
                else raw.optString("stream_id")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val ext = raw.optString("container_extension",
                if (kind == "itv") "ts" else "mp4")
            XtreamTitle(id.take(30), name.take(150), kind, ext.take(8),
                raw.optString("category_id"), raw)
        }
    }

    fun episodes(show: XtreamTitle): List<XtreamTitle> {
        val info = call("get_series_info", mapOf("series_id" to show.id)) as? JSONObject
            ?: return emptyList()
        val container = info.optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<XtreamTitle>()
        val seasons = container.keys()
        while (seasons.hasNext() && out.size < 300) {
            val season = seasons.next()
            val entries = container.optJSONArray(season) ?: continue
            for (i in 0 until minOf(200, entries.length())) {
                val data = entries.optJSONObject(i) ?: continue
                val id = data.optString("id")
                if (id.isEmpty()) continue
                val extension = data.optJSONObject("info")
                    ?.optString("container_extension", "mp4") ?: "mp4"
                out.add(XtreamTitle(id,
                    "Staffel " + season + " · " +
                        data.optString("title", "Folge " + (i + 1)).take(100),
                    "series-episode", extension))
                if (out.size >= 300) break
            }
        }
        return out
    }

    fun url(title: XtreamTitle): String {
        val folder = when (title.kind) {
            "itv" -> "live"
            "vod" -> "movie"
            "series-episode" -> "series"
            else -> error("Wähle eine Serienepisode aus.")
        }
        val extension = title.extension.lowercase()
        require(extension.matches(Regex("[a-z0-9]{2,7}"))) {
            "Unbekanntes Videoformat vom Anbieter."
        }
        require(title.id.matches(Regex("[0-9]{1,30}"))) {
            "Ungültige Medien-ID vom Anbieter."
        }
        return base + "/" + folder + "/" +
            path(source.username) + "/" + path(source.password) +
            "/" + title.id + "." + extension
    }
}
