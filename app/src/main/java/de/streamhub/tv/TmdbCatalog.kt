package de.streamhub.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

internal data class CatalogTitle(
    val id: Int,
    val kind: String,
    val name: String,
    val date: String,
    val summary: String,
    val poster: String,
    val backdrop: String,
    val rating: Double
) {
    val identity get() = kind + ":" + id
    val year get() = date.take(4).ifBlank { "—" }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("media_type", kind)
        .put("title", name).put("release_date", date).put("overview", summary)
        .put("poster_path", poster).put("backdrop_path", backdrop).put("vote_average", rating)
    companion object {
        fun fromJson(item: JSONObject, forcedKind: String = ""): CatalogTitle? {
            val kind = forcedKind.ifBlank { item.optString("media_type", "") }
            if (kind != "movie" && kind != "tv") return null
            val id = item.optInt("id", 0)
            if (id <= 0) return null
            val name = item.optString(if (kind == "tv") "name" else "title", "").trim()
            if (name.isBlank()) return null
            val date = item.optString(if (kind == "tv") "first_air_date" else "release_date", "")
                .ifBlank { item.optString("release_date", "") }
            val poster = item.optString("poster_path", "").takeIf { it != "null" } ?: ""
            val backdrop = item.optString("backdrop_path", "").takeIf { it != "null" } ?: ""
            return CatalogTitle(id, kind, name.take(200), date.take(10),
                item.optString("overview", "").take(1800), poster, backdrop,
                item.optDouble("vote_average", 0.0))
        }
    }
}

internal class TmdbClient(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_tmdb", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3)
    fun credential() = prefs.getString("credential", "").orEmpty()
    fun hasCredential() = credential().isNotBlank()
    fun saveCredential(value: String) { prefs.edit().putString("credential", value).apply() }
    fun close() { workers.shutdownNow() }
    fun request(path: String, params: Map<String, String> = emptyMap(),
                done: (Result<JSONObject>) -> Unit) {
        if (!path.matches(Regex("^/(trending/(movie|tv)/week|movie/popular|tv/popular|discover/tv|search/multi|(movie|tv)/[1-9][0-9]{0,9})$"))) {
            done(Result.failure(IllegalArgumentException("Ungültiger TMDB-Pfad.")))
            return
        }
        val token = credential()
        if (token.isBlank()) {
            done(Result.failure(IllegalStateException("TMDB API-Key fehlt.")))
            return
        }
        workers.execute {
            val outcome = runCatching {
                val values = params + mapOf("language" to "de-DE")
                val query = values.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" +
                        URLEncoder.encode(it.value, "UTF-8")
                } + if (token.length == 32 && token.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                    "&api_key=" + URLEncoder.encode(token, "UTF-8")
                } else ""
                val connection = URL("https://api.themoviedb.org/3" + path + "?" + query)
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 9000
                    connection.readTimeout = 9000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "StreamHubTV/2.0")
                    if (!(token.length == 32 && token.matches(Regex("^[0-9a-fA-F]{32}$")))) {
                        connection.setRequestProperty("Authorization", "Bearer " + token)
                    }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val reason = when(code) {
                            401 -> "TMDB-Zugang ungültig. API-Key/Token prüfen."
                            429 -> "TMDB-Limit erreicht. Bitte später erneut versuchen."
                            else -> "TMDB HTTP " + code
                        }
                        error(reason)
                    }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n <= 0) break
                            if (output.size() + n > 700_000) error("TMDB-Antwort zu groß.")
                            output.write(chunk, 0, n)
                        }
                    }
                    JSONObject(output.toString("UTF-8"))
                } finally { connection.disconnect() }
            }
            main.post { done(outcome) }
        }
    }
    companion object {
        fun entries(response: JSONObject, forcedKind: String = ""): List<CatalogTitle> {
            val array: JSONArray = response.optJSONArray("results") ?: return emptyList()
            return (0 until minOf(array.length(), 30)).mapNotNull { index ->
                array.optJSONObject(index)?.let { CatalogTitle.fromJson(it, forcedKind) }
            }
        }
    }
}

/** Small bounded in-memory poster cache, TMDB CDN only; never trusts arbitrary image URLs. */
internal object TmdbArtwork {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val posterPath = Regex("^/[a-zA-Z0-9_-]{6,80}\\.(jpg|jpeg|png|webp)$")
    fun load(view: ImageView, path: String, backdrop: Boolean = false) {
        val size = if (backdrop) "w780" else "w342"
        val marker = size + ":" + path
        view.tag = marker
        if (!posterPath.matches(path)) return
        val cached = synchronized(cache) { cache.get(marker) }
        if (cached != null) { view.setImageBitmap(cached); return }
        workers.execute {
            val bitmap = runCatching {
                val con = URL("https://image.tmdb.org/t/p/" + size + path)
                    .openConnection() as HttpURLConnection
                try {
                    con.connectTimeout = 8000
                    con.readTimeout = 8000
                    if (con.responseCode != 200) error("Poster nicht verfügbar")
                    val bytes = ByteArrayOutputStream()
                    con.inputStream.use { input ->
                        val chunk = ByteArray(8192)
                        while(true) {
                            val n = input.read(chunk)
                            if(n <= 0) break
                            if(bytes.size() + n > 2_500_000) error("Bild zu groß")
                            bytes.write(chunk, 0, n)
                        }
                    }
                    val raw = bytes.toByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    var sample = 1
                    val maxWidth = if(backdrop) 1000 else 500
                    val maxHeight = if(backdrop) 600 else 800
                    while(bounds.outWidth / sample > maxWidth * 2 ||
                          bounds.outHeight / sample > maxHeight * 2) sample *= 2
                    BitmapFactory.decodeByteArray(raw, 0, raw.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                } finally { con.disconnect() }
            }.getOrNull() ?: return@execute
            synchronized(cache) { cache.put(marker, bitmap) }
            main.post {
                if (view.tag == marker) view.setImageBitmap(bitmap)
            }
        }
    }
}
