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
import java.util.concurrent.Executors

/** MAC IPTV catalog driven by the currently enabled user profile. */
class MacPortalActivity : Activity() {
    private val store by lazy { MacPortalStore(this) }
    private val workers = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var api: MacPortalClient? = null
    private var kind = "itv"
    private var categories = listOf(MacPortalCategory("*", "Alle Kategorien"))
    private var chosen = "*"
    private var page = 1
    private var busy = false
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var pagination: TextView
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        createView()
        if (store.active() != null) connect() else renderEmpty()
    }

    private fun createView() {
        val root = Ui.vertical(this).apply {
            setPadding(dp(22), dp(15), dp(22), dp(10))
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)

        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "📡  STREAMHUB  ·  MAC IPTV", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        bar.addView(Ui.button(this, "⚙ Profile", true) { showPortalDialog() },
            Ui.lp(dp(144), dp(48)).apply { rightMargin = dp(9) })
        bar.addView(Ui.button(this, "✕ Zurück", true) { finish() },
            Ui.lp(dp(138), dp(48)))
        root.addView(bar, Ui.lp(-1, dp(61)))

        val active = store.active()
        val sourceText = if (active == null) store.profiles().size.toString() +
                " gespeicherte Portale  ·  Keines aktiv"
            else "Aktiv: " + active.name + "  ·  " + active.source.url.take(57) +
                "  ·  MAC: •••" + active.source.mac.takeLast(5)
        root.addView(Ui.text(this, sourceText, 12f, Ui.MUTED), Ui.lp(-1, dp(35)))

        val tabs = Ui.horizontal(this)
        for ((id, label) in listOf(
            "itv" to "📺  LIVE TV", "vod" to "🎬  FILME", "series" to "▤  SERIEN"
        )) {
            tabs.addView(Ui.button(this, label, true) {
                if (busy || kind == id) return@button
                kind = id
                chosen = "*"
                page = 1
                loadCategories()
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(10) })
        }
        root.addView(tabs, Ui.lp(-1, dp(65)))

        val actions = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading = Ui.text(this, "Live TV", 19f, Ui.TEXT, true)
        actions.addView(heading, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(Ui.button(this, "☷  Kategorien", true) { categoryDialog() },
            Ui.lp(dp(172), dp(46)).apply { rightMargin = dp(10) })
        actions.addView(Ui.button(this, "↻  Aktualisieren", true) {
            if (!busy) connect()
        }, Ui.lp(dp(175), dp(46)))
        root.addView(actions, Ui.lp(-1, dp(55)))

        status = Ui.text(this, "Portal wird vorbereitet …", 13f, Ui.SOFT)
        root.addView(status, Ui.lp(-1, dp(37)))

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        content = Ui.vertical(this).apply { setPadding(dp(3), 0, dp(8), dp(22)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Ui.button(this, "‹ Vorherige Seite", true) {
            if (!busy && page > 1) { page--; loadPage() }
        }, Ui.lp(dp(207), dp(48)).apply { rightMargin = dp(10) })
        pagination = Ui.text(this, "Seite 1", 12f, Ui.SOFT)
        controls.addView(pagination, LinearLayout.LayoutParams(0, dp(40), 1f))
        controls.addView(Ui.button(this, "Nächste Seite ›", true) {
            if (!busy && canNext) { page++; loadPage() }
        }, Ui.lp(dp(207), dp(48)))
        root.addView(controls, Ui.lp(-1, dp(55)))
    }

    private var canNext = false

    private fun renderEmpty() {
        api = null
        content.removeAllViews()
        val count = store.profiles().size
        content.addView(Ui.text(this,
            if (count == 0)
                "Noch keine eigenen MAC-IPTV-Portale gespeichert.\n\n" +
                    "Tippe auf ⚙ Profile, um deinen ersten Anbieterzugang einzurichten."
            else
                count.toString() + " Profile gespeichert. Aktuell ist keines aktiviert.\n\n" +
                    "Wähle unter ⚙ Profile einen Zugang und drücke Aktivieren.",
            16f, Ui.SOFT), Ui.lp(-1, dp(186)))
        status.text = if (count == 0) "Erstes Profil hinzufügen."
            else "Alle Portalprofile deaktiviert. Kein Portal wird abgefragt."
        pagination.text = ""
        canNext = false
    }

    private fun showPortalDialog() {
        if (busy) {
            Toast.makeText(this, "Bitte laufende Portalabfrage abschließen.",
                Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(Intent(this, MacPortalProfilesActivity::class.java), PROFILE_REQUEST)
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PROFILE_REQUEST) return
        api = null
        kind = "itv"
        chosen = "*"
        page = 1
        categories = listOf(MacPortalCategory("*", "Alle Kategorien"))
        createView()
        if (store.active() == null) renderEmpty() else connect()
    }

    private fun <T> task(message: String, action: () -> T, completed: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        workers.execute {
            val result = runCatching(action)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                result.fold(onSuccess = completed, onFailure = {
                    status.text = "Verbindung fehlgeschlagen: " +
                        (it.message ?: "Unbekannter Fehler").take(220)
                    Toast.makeText(this, "Portalfehler: " + (it.message ?: "Unbekannt"),
                        Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun connect() {
        val active = store.active()
        if (active == null) { renderEmpty(); return }
        api = null
        content.removeAllViews()
        canNext = false
        pagination.text = ""
        task("Verbinde mit " + active.name + " …", {
            MacPortalClient(active.source).also { it.connect() }
        }) { connected ->
            api = connected
            chosen = "*"
            page = 1
            loadCategories()
        }
    }

    private fun loadCategories() {
        val connected = api ?: return connect()
        task("Lade Kategorien …", {
            // Not all Stalker servers offer separate series categories.
            runCatching { connected.categories(kind) }.getOrElse {
                listOf(MacPortalCategory("*", "Alle Kategorien"))
            }
        }) {
            categories = it
            chosen = "*"
            page = 1
            loadPage()
        }
    }

    private fun categoryDialog() {
        if (categories.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Kategorie wählen")
            .setItems(categories.map { it.title }.toTypedArray()) { _, index ->
                chosen = categories[index].id
                page = 1
                loadPage()
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun loadPage() {
        val connected = api ?: return connect()
        val categoryName = categories.firstOrNull { it.id == chosen }?.title ?: "Alle Kategorien"
        val section = when (kind) {
            "itv" -> "LIVE TV"
            "vod" -> "FILME"
            else -> "SERIEN"
        }
        heading.text = section + "  ·  " + categoryName
        task("Lade " + section + " …", {
            connected.page(kind, chosen, page)
        }) { result ->
            canNext = result.hasMore
            pagination.text = "Seite " + page + if (canNext) "  ·  Weitere vorhanden" else ""
            status.text = result.entries.size.toString() + " Einträge auf dieser Seite."
            content.removeAllViews()
            if (result.entries.isEmpty()) {
                content.addView(Ui.text(this,
                    "Keine Einträge in dieser Kategorie. " +
                        "Möglicherweise bietet dein Portal hier keine Inhalte an.",
                    15f, Ui.SOFT), Ui.lp(-1, dp(115)))
            }
            result.entries.forEach { item ->
                val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val buttonLabel = if (kind == "series") "▤  " else "▶  "
                row.addView(Ui.button(this, buttonLabel + item.name.take(100), true) {
                    openItem(item)
                }, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    rightMargin = dp(10)
                })
                row.addView(Ui.text(this,
                    if (kind == "itv") "LIVE" else if (kind == "vod") "FILM" else "SERIE",
                    11f, Ui.MUTED), Ui.lp(dp(64), dp(40)))
                content.addView(row, Ui.lp(-1, dp(65)))
            }
        }
    }

    private fun openItem(item: MacPortalMedia) {
        val connected = api ?: return
        if (item.type == "series" || item.raw.optString("is_series") == "1" ||
            item.raw.optJSONArray("series") != null || item.raw.optJSONArray("episodes") != null) {
            task("Lade Staffeln und Episoden …", { connected.episodes(item) }) { episodes ->
                if (episodes.isEmpty()) {
                    if (item.command.isNotBlank()) play(item)
                    else AlertDialog.Builder(this).setTitle(item.name)
                        .setMessage("Dieses Portal liefert noch keine unterstützte Episodenliste für diese Serie.")
                        .setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(item.name + " · Episoden")
                        .setItems(episodes.map { it.name }.toTypedArray()) { _, index ->
                            play(episodes[index])
                        }.setNegativeButton("Abbrechen", null).show()
                }
            }
        } else play(item)
    }

    private fun play(item: MacPortalMedia) {
        val connected = api ?: return
        task("Stream-Link wird vom Portal angefordert …",
            { connected.playback(item) }) { url ->
            startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
                putExtra("direct_stream_url", url)
                putExtra("direct_stream_title", item.name)
                putExtra("direct_stream_is_live", item.type == "itv")
                putExtra("direct_allow_http", store.active()?.source?.allowHttp == true)
            })
            status.text = "Stream an den integrierten Player übergeben."
        }
    }

    companion object { private const val PROFILE_REQUEST = 4157 }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }
}
