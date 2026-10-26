package de.streamhub.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/** Local-only provider reference. No playable URLs or provider credentials are written to disk. */
internal data class NativeMediaSource(
    val key: String,
    val title: String,
    val kind: String,
    val provider: String,
    val profileId: String = "",
    val mac: MacPortalMedia? = null,
    val xtream: XtreamTitle? = null,
    val m3u: LiveChannel? = null
) {
    val playable: Boolean get() = m3u != null || xtream != null ||
        mac?.command?.isNotBlank() == true || mac?.type == "series"
}

internal data class NativeCatalogSnapshot(
    val entries: List<NativeMediaSource>,
    val errors: List<String>,
    val updatedAt: Long
) {
    fun byKind(kind: String): List<NativeMediaSource> = entries.filter { it.kind == kind }
    fun lookup(kind: String, title: String): List<NativeMediaSource> {
        val match = NativeSourceIndex.normalized(title)
        return entries.filter {
            it.kind == kind && NativeSourceIndex.normalized(it.title) == match
        }
    }
}

/**
 * Adapter orchestration for authorized M3U, Xtream and the ONE active MAC profile.
 * Heavy requests run on one worker; background indexing is explicitly bounded.
 * Unselected/inactive MAC profiles are never queried here.
 */
internal object NativeSourceIndex {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cached: NativeCatalogSnapshot? = null
    private var cacheKey = ""
    private var loading = false
    private val pending = mutableListOf<(NativeCatalogSnapshot) -> Unit>()
    private const val TTL = 10L * 60L * 1000L

    fun normalized(value: String): String = Normalizer.normalize(
        value.trim(), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("\\b(19|20)[0-9]{2}\\b"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    fun current(): NativeCatalogSnapshot? = cached

    fun refresh(context: Context, force: Boolean = false, done: (NativeCatalogSnapshot) -> Unit) {
        val app = context.applicationContext
        val selected = MacPortalStore(app).active()
        val xtream = XtreamStore(app).load()
        val signature = listOf(
            selected?.id.orEmpty(), selected?.source?.url.orEmpty(),
            selected?.source?.mac.orEmpty(), xtream?.server.orEmpty(),
            xtream?.username.orEmpty(), xtream?.password.orEmpty()
        ).joinToString("|").hashCode().toString()
        synchronized(this) {
            val now = System.currentTimeMillis()
            val snapshot = cached
            if (!force && !loading && snapshot != null &&
                cacheKey == signature && now - snapshot.updatedAt in 0..TTL) {
                main.post { done(snapshot) }
                return
            }
            pending.add(done)
            if (loading) return
            loading = true
        }
        worker.execute {
            val items = mutableListOf<NativeMediaSource>()
            val issues = mutableListOf<String>()
            try {
                for (channel in LiveTvCatalog(app).channels()) {
                    items.add(NativeMediaSource(
                        "m3u:" + channel.id, channel.name, "itv", "Eigene M3U-Liste",
                        m3u = channel
                    ))
                }
                if (xtream != null) {
                    try {
                        val client = XtreamClient(xtream)
                        client.verify()
                        for (kind in listOf("itv", "vod", "series")) {
                            val titles = runCatching { client.titles(kind) }.getOrElse {
                                issues.add("Xtream " + kind + ": " +
                                    (it.message ?: "Nicht erreichbar").take(85))
                                emptyList()
                            }
                            titles.take(1200).forEach { media ->
                                items.add(NativeMediaSource(
                                    "xtream:" + xtream.server.hashCode() + ":" +
                                        kind + ":" + media.id,
                                    media.name, kind, "Xtream Codes", xtream = media
                                ))
                            }
                        }
                    } catch (error: Exception) {
                        issues.add("Xtream: " +
                            (error.message ?: "Anmeldung fehlgeschlagen").take(90))
                    }
                }
                if (selected != null) {
                    try {
                        val client = MacPortalClient(selected.source)
                        client.connect()
                        for (kind in listOf("itv", "vod", "series")) {
                            for (page in 1..3) {
                                val attempt = runCatching { client.page(kind, "*", page) }
                                if (attempt.isFailure) {
                                    issues.add(selected.name + " " + kind + ": " +
                                        (attempt.exceptionOrNull()?.message ?: "Katalogfehler").take(85))
                                    break
                                }
                                val result = attempt.getOrThrow()
                                result.entries.take(150).forEach { media ->
                                    items.add(NativeMediaSource(
                                        "mac:" + selected.id + ":" + kind + ":" +
                                            media.id.ifBlank { normalized(media.name) },
                                        media.name, kind, selected.name,
                                        profileId = selected.id, mac = media
                                    ))
                                }
                                if (!result.hasMore) break
                            }
                        }
                    } catch (error: Exception) {
                        issues.add(selected.name + ": " +
                            (error.message ?: "Verbindung fehlgeschlagen").take(90))
                    }
                }
            } catch (error: Exception) {
                issues.add((error.message ?: "Katalog konnte nicht geladen werden.").take(90))
            }
            val snapshot = NativeCatalogSnapshot(
                items.distinctBy { it.key }.take(5000),
                issues.distinct().take(12), System.currentTimeMillis()
            )
            val callbacks: List<(NativeCatalogSnapshot) -> Unit>
            synchronized(this) {
                cached = snapshot
                cacheKey = signature
                loading = false
                callbacks = pending.toList()
                pending.clear()
            }
            main.post { callbacks.forEach { callback -> callback(snapshot) } }
        }
    }
}
