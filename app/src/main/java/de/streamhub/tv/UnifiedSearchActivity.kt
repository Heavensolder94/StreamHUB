package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

private data class UnifiedSource(
    val key: String, val label: String, val kind: String,
    val profileId: String? = null,
    val title: String,
    val channel: LiveChannel? = null, val item: MacPortalMedia? = null
)

/**
 * Unified on-device search over existing M3U channels and provider catalogs.
 * Disabled portals are NEVER queried unless the user explicitly checks multi-provider search.
 * No provider index, credentials, or search terms are uploaded to StreamHUB servers.
 */
class UnifiedSearchActivity : Activity() {
    private val portals by lazy { MacPortalStore(this) }
    private val live by lazy { LiveTvCatalog(this) }
    private val watch by lazy { WatchStateStore(this) }
    private val pool = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var results: LinearLayout
    private lateinit var info: TextView
    private lateinit var queryInput: EditText
    private lateinit var allProfiles: CheckBox
    private var currentHits = emptyList<UnifiedSource>()
    private var busy = false
    private var activeRequest = 0
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        render()
    }

    private fun render() {
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(22), dp(14), dp(22), dp(12))
        }
        setContentView(root)
        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "⌕  STREAMHUB · QUELLEN-SUCHE", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(54), 1f))
        bar.addView(Ui.button(this, "♥ Merkliste", true) { showSaved(true) },
            Ui.lp(dp(152), dp(48)).apply { rightMargin = dp(8) })
        bar.addView(Ui.button(this, "↺ Weiterschauen", true) { showSaved(false) },
            Ui.lp(dp(180), dp(48)).apply { rightMargin = dp(8) })
        bar.addView(Ui.button(this, "✕ Zurück", true) { finish() }, Ui.lp(dp(123), dp(48)))
        root.addView(bar, Ui.lp(-1, dp(64)))

        val search = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        queryInput = EditText(this).apply {
            hint = "Sender, Film oder Serie"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(intent.getStringExtra("prefill_query").orEmpty().take(90))
        }
        search.addView(queryInput, LinearLayout.LayoutParams(0, dp(53), 1f).apply {
            rightMargin = dp(12)
        })
        search.addView(Ui.button(this, "⌕ Suchen", true) { performSearch() }, Ui.lp(dp(146), dp(50)))
        root.addView(search, Ui.lp(-1, dp(64)))

        allProfiles = CheckBox(this).apply {
            text = "Auch in gespeicherten, inaktiven Portalen suchen (max. 5 pro Suche)"
            isChecked = false
            setTextColor(Ui.SOFT)
        }
        root.addView(allProfiles, Ui.lp(-1, dp(48)))
        root.addView(Ui.text(this,
            "Sucht in importierten M3U-Sendern und in LIVE TV, FILMEN und SERIEN " +
                "der ausgewählten MAC-Portale. Nur selbst konfigurierte Zugänge.",
            12f, Ui.MUTED), Ui.lp(-1, dp(58)))
        info = Ui.text(this, "Suchbegriff eingeben oder Merkliste öffnen.", 13f, Ui.SOFT)
        root.addView(info, Ui.lp(-1, dp(37)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
        }
        results = Ui.vertical(this).apply { setPadding(0, 0, dp(8), dp(25)) }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (queryInput.text.length > 1) performSearch()
    }

    private fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun same(search: String, title: String): Boolean =
        normalize(title).contains(normalize(search))

    private fun stableId(profileId: String, kind: String, item: MacPortalMedia) =
        "mac:" + profileId + ":" + kind + ":" +
            item.id.ifBlank { normalize(item.name).take(80) }

    private fun performSearch() {
        if (busy) {
            Toast.makeText(this, "Die vorherige Suche läuft noch.", Toast.LENGTH_SHORT).show()
            return
        }
        val search = queryInput.text.toString().trim().take(90)
        if (search.length < 2) {
            info.text = "Bitte mindestens zwei Zeichen eingeben."
            return
        }
        val savedProfiles = portals.profiles()
        val active = portals.active()
        val targets = if (allProfiles.isChecked) savedProfiles.take(5)
            else listOfNotNull(active)
        val message = if (allProfiles.isChecked && savedProfiles.size > 5)
            "Es werden nur die ersten 5 gespeicherten Profile abgefragt." else ""
        busy = true
        val request = ++activeRequest
        results.removeAllViews()
        info.text = "Durchsuche M3U und " + targets.size + " Portal(e) … " + message
        pool.execute {
            val hits = mutableListOf<UnifiedSource>()
            val failures = mutableListOf<String>()
            try {
                for (channel in live.channels()) {
                    if (!same(search, channel.name)) continue
                    hits.add(UnifiedSource("m3u:" + channel.id, "Meine M3U-Sender",
                        "itv", title = channel.name, channel = channel))
                }
                for (profile in targets) {
                    try {
                        val api = MacPortalClient(profile.source)
                        api.connect()
                        for (kind in listOf("itv", "vod", "series")) {
                            // Bounded provider calls avoid loading full catalogs on every search.
                            for (pageNumber in 1..2) {
                                val attempt = runCatching { api.page(kind, "*", pageNumber) }
                                if (attempt.isFailure) {
                                    failures.add(profile.name + " / " + kind)
                                    break
                                }
                                val response = attempt.getOrThrow()
                                response.entries.filter { same(search, it.name) }
                                    .take(75).forEach { media ->
                                        hits.add(UnifiedSource(
                                            stableId(profile.id, kind, media), profile.name, kind,
                                            profile.id, media.name, item = media
                                        ))
                                    }
                                if (!response.hasMore) break
                            }
                        }
                    } catch (_: Exception) {
                        failures.add(profile.name + " (nicht erreichbar)")
                    }
                }
            } catch (error: Exception) {
                failures.add(error.message ?: "Suchfehler")
            }
            val reduced = hits.distinctBy { it.key }.take(500)
            handler.post {
                if (request != activeRequest || isFinishing || isDestroyed) return@post
                busy = false
                currentHits = reduced
                val grouped = reduced.groupBy { it.kind + "|" + normalize(it.title) }
                info.text = reduced.size.toString() + " Quellen-Treffer in " +
                    grouped.size + " Titeln" +
                    (if (failures.isEmpty()) "" else " · Nicht erreichbar: " +
                        failures.distinct().take(3).joinToString(", "))
                present(reduced)
            }
        }
    }

    private fun present(hits: List<UnifiedSource>) {
        results.removeAllViews()
        if (hits.isEmpty()) {
            results.addView(Ui.text(this,
                "Keine Treffer im abgefragten Ausschnitt der Kataloge.\n" +
                    "Größere IPTV-Kataloge werden seitenweise geladen; " +
                    "eine vollständige serverseitige Volltextsuche wird nicht von jedem Anbieter unterstützt.",
                14f, Ui.SOFT), Ui.lp(-1, dp(150)))
            return
        }
        for (kind in listOf("itv", "vod", "series")) {
            val section = hits.filter { it.kind == kind }
            if (section.isEmpty()) continue
            val label = when (kind) {
                "itv" -> "📺 LIVE TV"
                "vod" -> "🎬 FILME"
                else -> "▤ SERIEN"
            }
            results.addView(Ui.text(this, label, 19f, Ui.TEXT, true),
                Ui.lp(-1, dp(53)))
            // A title appears only once; providers remain individually selectable.
            val groups = section.groupBy { normalize(it.title) }
            groups.forEach { (_, sources) ->
                val sourceNames = sources.map { it.label }.distinct().joinToString(" · ").take(105)
                val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val body = Ui.vertical(this)
                body.addView(Ui.button(this,
                    "▶ " + sources.first().title.take(75), true) {
                    chooseSource(sources)
                }, Ui.lp(-1, dp(48)))
                body.addView(Ui.text(this,
                    sources.size.toString() + " Quelle(n): " + sourceNames,
                    11f, Ui.MUTED), Ui.lp(-1, dp(29)))
                row.addView(body, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                    rightMargin = dp(8)
                })
                val first = sources.first()
                val favorite = watch.get(first.key)?.favorite == true
                row.addView(Ui.button(this, if (favorite) "♥" else "♡", true) {
                    val changed = watch.setFavorite(first.key, first.title, kind)
                    Toast.makeText(this, if (changed) "Gemerkt" else "Entfernt",
                        Toast.LENGTH_SHORT).show()
                    present(currentHits)
                }, Ui.lp(dp(62), dp(46)))
                results.addView(row, Ui.lp(-1, dp(90)))
            }
        }
    }

    private fun chooseSource(sources: List<UnifiedSource>) {
        if (sources.size == 1) { openSource(sources.first()); return }
        AlertDialog.Builder(this).setTitle(sources.first().title + " · Quelle wählen")
            .setItems(sources.map { it.label }.toTypedArray()) { _, index ->
                openSource(sources[index])
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun showSaved(favorites: Boolean) {
        results.removeAllViews()
        val saved = if (favorites) watch.favorites() else watch.recent()
        info.text = (if (favorites) "Merkliste" else "Zuletzt angesehen") +
            " · " + saved.size + " Titel"
        if (saved.isEmpty()) {
            results.addView(Ui.text(this, "Noch keine Einträge vorhanden.", 15f, Ui.SOFT),
                Ui.lp(-1, dp(90)))
        }
        saved.forEach { entry ->
            val label = if (!favorites && entry.durationMs > 0)
                " (" + (entry.positionMs * 100 / entry.durationMs) + " %)" else ""
            results.addView(Ui.button(this, "▶ " + entry.title + label, true) {
                // Durable identifiers never contain tokenized IPTV URLs. Re-query and resolve.
                queryInput.setText(entry.title)
                performSearch()
            }, Ui.lp(-1, dp(57)).apply { bottomMargin = dp(7) })
        }
    }

    private fun openSource(hit: UnifiedSource) {
        val channel = hit.channel
        if (channel != null) {
            startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
                putExtra("channel_id", channel.id)
            })
            return
        }
        val profile = portals.profiles().firstOrNull { it.id == hit.profileId }
        val media = hit.item
        if (profile == null || media == null) {
            Toast.makeText(this, "Quelle nicht mehr verfügbar.", Toast.LENGTH_LONG).show()
            return
        }
        if (busy) return
        busy = true
        info.text = "Lade " + media.name + " von " + profile.name + " …"
        pool.execute {
            val outcome = runCatching {
                val api = MacPortalClient(profile.source).also { it.connect() }
                if (media.type == "series" || media.raw.optJSONArray("series") != null ||
                    media.raw.optJSONArray("episodes") != null) {
                    Pair(api, api.episodes(media))
                } else Pair(api, emptyList())
            }
            handler.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                outcome.fold(onSuccess = { (api, episodes) ->
                    if (episodes.isNotEmpty()) {
                        AlertDialog.Builder(this).setTitle(media.name + " · Episoden")
                            .setItems(episodes.map { it.name }.toTypedArray()) { _, n ->
                                resolveAndPlay(profile, api, episodes[n], hit.key)
                            }.setNegativeButton("Abbrechen", null).show()
                    } else if (media.type == "series") {
                        info.text = "Dieses Portal liefert keine unterstützte Episodenliste."
                    } else resolveAndPlay(profile, api, media, hit.key)
                }, onFailure = { info.text = it.message ?: "Quelle nicht erreichbar." })
            }
        }
    }

    private fun resolveAndPlay(
        profile: MacPortalProfile, api: MacPortalClient,
        media: MacPortalMedia, historyKey: String
    ) {
        if (busy) return
        busy = true
        info.text = "Lade abspielbaren Stream-Link …"
        pool.execute {
            val stream = runCatching { api.playback(media) }
            handler.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                stream.fold(onSuccess = { url ->
                    startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
                        putExtra("direct_stream_url", url)
                        putExtra("direct_stream_title", media.name)
                        putExtra("direct_stream_is_live", media.type == "itv")
                        putExtra("direct_allow_http", profile.source.allowHttp)
                        putExtra("history_id", historyKey + ":" + media.episode)
                        putExtra("history_kind", media.type)
                    })
                    info.text = "Stream an Player übergeben."
                }, onFailure = { info.text = it.message ?: "Nicht abspielbar." })
            }
        }
    }

    override fun onDestroy() {
        activeRequest++
        pool.shutdownNow()
        super.onDestroy()
    }
}
