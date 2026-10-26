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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

internal data class TvProgram(
    val channel: String, val title: String,
    val startMs: Long, val endMs: Long, val desc: String = ""
)

/** Own XMLTV EPG source for user's imported M3U channel IDs. Kodi-free. */
internal class EpgStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("streamhub_epg_v27", Context.MODE_PRIVATE)

    fun source(): String = prefs.getString("url", "").orEmpty()

    fun setSource(url: String) {
        val v = url.trim()
        require(v.isEmpty() || LiveTvCatalog.validHttpsUrl(v)) {
            "Bitte eine gültige HTTPS-XMLTV-Adresse angeben."
        }
        prefs.edit().putString("url", v).apply()
    }

    fun load(): List<TvProgram> {
        val url = source()
        require(url.isNotBlank()) { "Zuerst eine XMLTV-Quelle hinterlegen." }
        // Follow no redirects: XMLTV could contain private credentials in its path.
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 14000
            c.setRequestProperty("Accept", "application/xml,text/xml,*/*")
            require(c.responseCode in 200..299) { "XMLTV HTTP " + c.responseCode }
            val bytes = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(bytes.size() + n <= 8_000_000) {
                        "EPG-Quelle zu groß (maximal 8 MB)."
                    }
                    bytes.write(buffer, 0, n)
                }
            }
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            runCatching {
                parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false)
            }
            parser.setInput(bytes.toByteArray().inputStream(), "UTF-8")
            val records = mutableListOf<TvProgram>()
            var channel = ""
            var start = 0L
            var end = 0L
            var title = ""
            var description = ""
            var inProgramme = false
            val lowerBound = System.currentTimeMillis() - 2L * 60 * 60 * 1000
            val upperBound = System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && records.size < 20_000) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            inProgramme = true
                            channel = parser.getAttributeValue(null, "channel").orEmpty().take(140)
                            start = xmltvTime(parser.getAttributeValue(null, "start").orEmpty())
                            end = xmltvTime(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""
                            description = ""
                        }
                        "title" -> if (inProgramme) title = parser.nextText().take(180)
                        "desc" -> if (inProgramme) description = parser.nextText().take(220)
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                    if (inProgramme && channel.isNotBlank() && title.isNotBlank() &&
                        end > lowerBound && start < upperBound && end > start) {
                        records.add(TvProgram(channel, title, start, end, description))
                    }
                    inProgramme = false
                }
                event = parser.next()
            }
            return records
        } finally {
            c.disconnect()
        }
    }

    private fun xmltvTime(raw: String): Long {
        val clean = raw.trim()
        return runCatching {
            val formatter = SimpleDateFormat(
                if (clean.length >= 19) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss",
                Locale.ROOT)
            formatter.isLenient = false
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            formatter.parse(clean)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

class EpgActivity : Activity() {
    private val epg by lazy { EpgStore(this) }
    private val channels by lazy { LiveTvCatalog(this) }
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var body: LinearLayout
    private var busy = false
    private fun dp(i: Int) = (resources.displayMetrics.density * i + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(21), dp(16), dp(21), dp(10))
        }
        setContentView(root)
        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "📅  STREAMHUB TV-PROGRAMM", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(Ui.button(this, "⚙ XMLTV", true) { sourceDialog() },
            Ui.lp(dp(140), dp(47)).apply { rightMargin = dp(10) })
        bar.addView(Ui.button(this, "↻ Laden", true) { reload() },
            Ui.lp(dp(139), dp(47)).apply { rightMargin = dp(10) })
        bar.addView(Ui.button(this, "✕ Zurück", true) { finish() },
            Ui.lp(dp(139), dp(47)))
        root.addView(bar, Ui.lp(-1, dp(64)))
        info = Ui.text(this, "", 13f, Ui.SOFT)
        root.addView(info, Ui.lp(-1, dp(59)))
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        body = Ui.vertical(this).apply { setPadding(0, dp(2), dp(9), dp(20)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (epg.source().isNotBlank()) reload()
        else {
            info.text = "Verbinde eine HTTPS-XMLTV-Quelle, um das Programm deiner M3U-Sender zu sehen."
            body.addView(Ui.text(this,
                "Noch keine EPG-Adresse eingerichtet.\n\n" +
                    "Der TV-Programmführer übernimmt Sender aus deiner eigenen M3U-Liste " +
                    "und gleicht diese anhand der tvg-id mit der XMLTV-Datei ab.\n\n" +
                    "Für MAC-/Stalker-Portale ist ein separates EPG-Format notwendig.",
                15f, Ui.SOFT), Ui.lp(-1, dp(165)))
        }
    }

    private fun sourceDialog() {
        val input = EditText(this).apply {
            hint = "https://anbieter.example/guide.xml"
            setText(epg.source())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = Ui.vertical(this).apply {
            setPadding(dp(19), dp(15), dp(19), dp(12))
        }
        container.addView(input, Ui.lp(-1, dp(58)))
        container.addView(Ui.text(this,
            "HTTPS-XMLTV-Adresse deines berechtigten Anbieters. " +
                "Öffentliche Zugänge können nach Registrierung erforderlich sein.",
            12f, Ui.MUTED), Ui.lp(-1, dp(82)))
        AlertDialog.Builder(this).setTitle("XMLTV-Programmquelle")
            .setView(container).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                try {
                    epg.setSource(input.text.toString())
                    if (epg.source().isNotBlank()) reload()
                    else { info.text = "EPG deaktiviert."; body.removeAllViews() }
                } catch (exception: Exception) {
                    Toast.makeText(this, exception.message, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun reload() {
        if (busy) return
        busy = true
        info.text = "Lade EPG, gleiche tvg-id ab …"
        body.removeAllViews()
        work.execute {
            val response = runCatching { epg.load() }
            handler.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                response.fold(onSuccess = { records ->
                    val now = System.currentTimeMillis()
                    val grouped = records.groupBy { it.channel }
                    var matched = 0
                    val date = SimpleDateFormat("HH:mm", Locale.GERMANY)
                    for (channel in channels.channels()) {
                        val items = grouped[channel.tvgId].orEmpty()
                            .filter { it.endMs > now }.sortedBy { it.startMs }.take(4)
                        if (items.isEmpty()) continue
                        matched++
                        body.addView(Ui.text(this, "📺 " + channel.name,
                            17f, Ui.TEXT, true), Ui.lp(-1, dp(42)))
                        items.forEach { show ->
                            val title = date.format(Date(show.startMs)) + " – " +
                                date.format(Date(show.endMs)) + "   " + show.title
                            val line = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
                            line.addView(Ui.text(this,
                                (if (show.startMs <= now) "● JETZT  " else "") + title.take(110),
                                13f, Ui.SOFT), LinearLayout.LayoutParams(0, dp(37), 1f))
                            line.addView(Ui.button(this, "▶ Sender", true) {
                                startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
                                    putExtra("channel_id", channel.id)
                                })
                            }, Ui.lp(dp(134), dp(43)))
                            body.addView(line, Ui.lp(-1, dp(47)))
                        }
                    }
                    info.text = matched.toString() + " M3U-Sender mit EPG-Treffern · " +
                        records.size + " Programme gelesen."
                    if (matched == 0) body.addView(Ui.text(this,
                        "Keine Übereinstimmungen. Prüfe die tvg-id der importierten Sender " +
                            "und die channel-ID in deiner XMLTV-Datei.",
                        15f, Ui.SOFT), Ui.lp(-1, dp(113)))
                }, onFailure = {
                    info.text = "EPG konnte nicht geladen werden: " +
                        (it.message ?: "Netzwerkfehler")
                })
            }
        }
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }
}
