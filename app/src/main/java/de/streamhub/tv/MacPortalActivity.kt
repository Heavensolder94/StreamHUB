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

/** MAC IPTV section: live TV, movies and series via one authorized Stalker portal. */
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
        if (store.load() != null) connect() else renderEmpty()
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
        bar.addView(Ui.button(this, "⚙ Portal", true) { showPortalDialog() },
            Ui.lp(dp(144), dp(48)).apply { rightMargin = dp(9) })
        bar.addView(Ui.button(this, "✕ Zurück", true) { finish() },
            Ui.lp(dp(138), dp(48)))
        root.addView(bar, Ui.lp(-1, dp(61)))

        val source = store.load()
        val sourceText = if (source == null) "Kein MAC-Portal verbunden."
            else "Portal: " + source.url.take(75) + "  ·  MAC: •••" + source.mac.takeLast(5)
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
        content.removeAllViews()
        content.addView(Ui.text(this,
            "Noch kein MAC-Portal eingerichtet.\n\n" +
                "Du benötigst die Portal-Adresse und die vom IPTV-Anbieter " +
                "freigeschaltete MAC-Adresse. Tippe auf ⚙ Portal.\n\n" +
                "StreamHUB liefert keine IPTV-Abos oder Zugangsdaten.",
            16f, Ui.SOFT), Ui.lp(-1, dp(195)))
        status.text = "Portal hinzufügen, um Live TV, Filme und Serien zu laden."
        pagination.text = ""
        canNext = false
    }

    private fun showPortalDialog() {
        val existing = store.load()
        val layout = Ui.vertical(this).apply {
            setPadding(dp(17), dp(12), dp(17), dp(9))
        }
        layout.addView(Ui.text(this, "Eigener, vom Anbieter autorisierter MAG-/Stalker-Zugang.",
            13f, Ui.SOFT), Ui.lp(-1, dp(46)))
        val address = EditText(this).apply {
            hint = "https://portal.beispiel.de/c/"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
        }
        val mac = EditText(this).apply {
            hint = "00:1A:79:12:34:56"
            setSingleLine(true)
            setText(existing?.mac.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val http = CheckBox(this).apply {
            text = "Unsicheres HTTP ausdrücklich erlauben"
            isChecked = existing?.allowHttp == true
        }
        layout.addView(address, Ui.lp(-1, dp(56)))
        layout.addView(mac, Ui.lp(-1, dp(56)))
        layout.addView(http, Ui.lp(-1, dp(49)))
        layout.addView(Ui.text(this,
            "Bei HTTP kann die MAC-Adresse im Netzwerk mitgelesen werden. " +
                "Verwende möglichst HTTPS. MAC und URL werden verschlüsselt auf diesem Gerät gespeichert.",
            12f, Ui.MUTED), Ui.lp(-1, dp(72)))

        val dialog = AlertDialog.Builder(this).setTitle("MAC-Portal verbinden")
            .setView(layout)
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Entfernen") { _, _ ->
                AlertDialog.Builder(this).setTitle("Portal entfernen?")
                    .setMessage("Gespeicherte Portal-Adresse und MAC-Adresse löschen?")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Entfernen") { _, _ ->
                        store.remove()
                        api = null
                        createView()
                        renderEmpty()
                    }.show()
            }
            .setPositiveButton("Speichern", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    MacPortalSource.fromInput(address.text.toString(), mac.text.toString(), http.isChecked)
                }
                result.fold(onSuccess = { source ->
                    store.save(source)
                    dialog.dismiss()
                    api = null
                    createView()
                    connect()
                }, onFailure = {
                    Toast.makeText(this, it.message ?: "Eingaben ungültig.",
                        Toast.LENGTH_LONG).show()
                })
            }
        }
        dialog.show()
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
        val source = store.load()
        if (source == null) { renderEmpty(); return }
        task("Verbinde mit MAC-Portal …", {
            MacPortalClient(source).also { it.connect() }
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
        if (item.type == "series") {
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
                putExtra("direct_allow_http", store.load()?.allowHttp == true)
            })
            status.text = "Stream an den integrierten Player übergeben."
        }
    }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }
}
