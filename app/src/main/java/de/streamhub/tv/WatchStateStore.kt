package de.streamhub.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A compact local playback journal. No streaming URLs or portal credentials are persisted. */
internal data class WatchEntry(
    val id: String,
    val title: String,
    val kind: String,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    val favorite: Boolean = false
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("kind", kind)
        .put("position", positionMs).put("duration", durationMs)
        .put("watchedAt", watchedAt).put("favorite", favorite)

    companion object {
        fun fromJson(j: JSONObject): WatchEntry = WatchEntry(
            j.optString("id").take(250), j.optString("title").take(150),
            j.optString("kind").take(24),
            j.optLong("position").coerceAtLeast(0L),
            j.optLong("duration").coerceAtLeast(0L),
            j.optLong("watchedAt").coerceAtLeast(0L),
            j.optBoolean("favorite", false)
        )
    }
}

internal class WatchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_watch_state_v27", Context.MODE_PRIVATE)
    private fun read(): List<WatchEntry> = runCatching {
        val data = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until minOf(data.length(), 250)).mapNotNull { i ->
            data.optJSONObject(i)?.let(WatchEntry::fromJson)?.takeIf { it.id.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<WatchEntry>) {
        val data = JSONArray()
        items.sortedByDescending { it.watchedAt }.take(200).forEach { data.put(it.json()) }
        prefs.edit().putString("items", data.toString()).apply()
    }

    fun get(id: String): WatchEntry? = read().firstOrNull { it.id == id }

    fun recent(limit: Int = 30): List<WatchEntry> =
        read().filter { it.watchedAt > 0L }
            .sortedByDescending { it.watchedAt }.take(limit)

    fun favorites(): List<WatchEntry> = read().filter { it.favorite }
        .sortedByDescending { it.watchedAt }

    fun setFavorite(id: String, title: String, kind: String): Boolean {
        val old = get(id)
        val next = (old?.favorite != true)
        val entry = (old ?: WatchEntry(id, title, kind, 0L, 0L, 0L))
            .copy(favorite = next)
        save(read().filterNot { it.id == id } + entry)
        return next
    }

    fun record(id: String, title: String, kind: String, pos: Long, duration: Long) {
        if (id.isBlank()) return
        val previous = get(id)
        val position = if (duration > 0L && pos > duration * 95L / 100L) 0L
            else pos.coerceAtLeast(0L)
        val next = WatchEntry(id.take(250), title.take(150), kind.take(24),
            position, duration.coerceAtLeast(0L),
            System.currentTimeMillis(), previous?.favorite ?: false)
        save(read().filterNot { it.id == id } + next)
    }

    fun clearRecent() {
        save(read().filter { it.favorite }.map {
            it.copy(positionMs = 0L, durationMs = 0L, watchedAt = 0L)
        })
    }
}
