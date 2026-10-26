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

/** Xtream Codes client for a user-provided, activated provider login. */
class XtreamActivity : Activity() {
    private val store by lazy { XtreamStore(this) }
    private val pool = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var api: XtreamClient? = null
    private var kind = "itv"
    private var category = "*"
    private var categories: List<XtreamCategory> = listOf(XtreamCategory("*", "Alle Kategorien"))
    private var busy = false
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private fun dp(i: Int) = (resources.displayMetrics.density * i + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        layout()
        if (store.load() != null) connect() else empty()
    }

    private fun layout() {
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(23), dp(15), dp(23), dp(12))
        }
        setContentView(root)
        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "▤  STREAMHUB · XTREAM IPTV", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(56), 1f))
        bar.addView(Ui.button(this, "⚙ Zugang", true) { accountDialog() },
            Ui.lp(dp(149), dp(46)).apply { rightMargin = dp(9) })
        bar.addView(Ui.button(this, "✕ Zurück", true) { finish() }, Ui.lp(dp(137), dp(46)))
        root.addView(bar, Ui.lp(-1, dp(65)))
        val tabs = Ui.horizontal(this)
        for ((id, label) in listOf(
            "itv" to "📺 LIVE", "vod" to "🎬 FILME", "series" to "▤ SERIEN"
        )) {
            tabs.addView(Ui.button(this, label, true) {
                if (busy) return@button
                kind = id
                category = "*"
                loadCategories()
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(10) })
        }
        root.addView(tabs, Ui.lp(-1, dp(63)))
        val control = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        control.addView(Ui.button(this, "☷ Kategorien", true) { categoriesDialog() },
            Ui.lp(dp(190), dp(46)).apply { rightMargin = dp(10) })
        control.addView(Ui.button(this, "↻ Aktualisieren", true) { connect() },
            Ui.lp(dp(205), dp(46)))
        root.addView(control, Ui.lp(-1, dp(58)))
        status = Ui.text(this, "Bereit.", 13f, Ui.SOFT)
        root.addView(status, Ui.lp(-1, dp(47)))
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        body = Ui.vertical(this).apply { setPadding(0, dp(2), dp(10), dp(22)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun empty() {
        body.removeAllViews()
        body.addView(Ui.text(this,
            "Noch kein Xtream-Anbieterzugang hinterlegt.\n\n" +
                "Öffne ⚙ Zugang und gib Server-Adresse, Benutzername und Passwort " +
                "deines berechtigten IPTV-Anbieters ein.\n\n" +
                "Kodi wird nicht benötigt.",
            16f, Ui.SOFT), Ui.lp(-1, dp(190)))
        status.text = "Kein Xtream-Zugang gespeichert."
    }

    private fun accountDialog() {
        val previous = store.load()
        val form = Ui.vertical(this).apply { setPadding(dp(18), dp(12), dp(18), dp(10)) }
        val server = EditText(this).apply {
            hint = "https://anbieter.example:1234"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(previous?.server.orEmpty())
        }
        val username = EditText(this).apply {
            hint = "Benutzername"
            setSingleLine(true)
            setText(previous?.username.orEmpty())
        }
        val password = EditText(this).apply {
            hint = "Passwort"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(previous?.password.orEmpty())
        }
        val http = CheckBox(this).apply {
            text = "Unsicheres HTTP ausdrücklich erlauben"
            isChecked = previous?.allowHttp == true
        }
        form.addView(server, Ui.lp(-1, dp(53)))
        form.addView(username, Ui.lp(-1, dp(53)))
        form.addView(password, Ui.lp(-1, dp(53)))
        form.addView(http, Ui.lp(-1, dp(46)))
        form.addView(Ui.text(this,
            "Zugang wird auf dieser TV-Box verschlüsselt gespeichert. " +
                "HTTPS bevorzugen: HTTP überträgt Benutzernamen und Passwort unverschlüsselt.",
            12f, Ui.MUTED), Ui.lp(-1, dp(86)))

        val dlg = AlertDialog.Builder(this).setTitle("Xtream-Codes-Zugang")
            .setView(form).setNegativeButton("Abbrechen", null)
            .setNeutralButton("Löschen") { _, _ ->
                AlertDialog.Builder(this).setTitle("Xtream-Zugang löschen?")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Löschen") { _, _ ->
                        store.remove()
                        api = null
                        layout()
                        empty()
                    }.show()
            }
            .setPositiveButton("Speichern", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val next = XtreamSource.create(server.text.toString(),
                        username.text.toString(), password.text.toString(), http.isChecked)
                    store.save(next)
                    dlg.dismiss()
                    api = null
                    kind = "itv"
                    layout()
                    connect()
                } catch (exception: Exception) {
                    Toast.makeText(this, exception.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        dlg.show()
    }

    private fun <T> task(message: String, job: () -> T, completed: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        pool.execute {
            val result = runCatching(job)
            handler.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                result.fold(onSuccess = completed, onFailure = {
                    status.text = (it.message ?: "Anbieter nicht erreichbar").take(250)
                })
            }
        }
    }

    private fun connect() {
        val account = store.load() ?: return empty()
        api = null
        task("Prüfe Xtream-Anmeldung …", {
            XtreamClient(account).also { it.verify() }
        }) {
            api = it
            category = "*"
            loadCategories()
        }
    }

    private fun loadCategories() {
        val client = api ?: return connect()
        task("Lade Kategorien …", {
            runCatching { client.categories(kind) }.getOrElse {
                listOf(XtreamCategory("*", "Alle Kategorien"))
            }
        }) {
            categories = it
            category = "*"
            loadTitles()
        }
    }

    private fun categoriesDialog() {
        AlertDialog.Builder(this).setTitle("Xtream-Kategorie")
            .setItems(categories.map { it.name }.toTypedArray()) { _, i ->
                category = categories[i].id
                loadTitles()
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun loadTitles() {
        val client = api ?: return connect()
        task("Lade " + kind + " …", {
            client.titles(kind, category)
        }) { items ->
            body.removeAllViews()
            status.text = items.size.toString() + " Inhalte vom aktiven Xtream-Anbieter."
            if (items.isEmpty()) body.addView(
                Ui.text(this, "Hier wurden keine Inhalte geliefert.", 14f, Ui.SOFT),
                Ui.lp(-1, dp(84)))
            items.forEach { item ->
                body.addView(Ui.button(this, "▶  " + item.name.take(100), true) {
                    if (item.kind == "series") {
                        task("Lade Serienepisoden …", { client.episodes(item) }) { episodes ->
                            if (episodes.isEmpty()) {
                                status.text = "Keine abspielbaren Episoden geliefert."
                            } else {
                                AlertDialog.Builder(this).setTitle(item.name)
                                    .setItems(episodes.map { it.name }.toTypedArray()) { _, index ->
                                        play(client, episodes[index])
                                    }.setNegativeButton("Abbrechen", null).show()
                            }
                        }
                    } else play(client, item)
                }, Ui.lp(-1, dp(55)).apply { bottomMargin = dp(7) })
            }
        }
    }

    private fun play(client: XtreamClient, item: XtreamTitle) {
        val account = store.load() ?: return
        val url = runCatching { client.url(item) }.getOrElse {
            status.text = it.message ?: "Stream nicht abspielbar."
            return
        }
        startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
            putExtra("direct_stream_url", url)
            putExtra("direct_stream_title", item.name)
            putExtra("direct_stream_is_live", item.kind == "itv")
            putExtra("direct_allow_http", account.allowHttp)
            putExtra("history_id", "xtream:" + account.server.hashCode() +
                ":" + item.kind + ":" + item.id)
            putExtra("history_kind", item.kind)
        })
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
