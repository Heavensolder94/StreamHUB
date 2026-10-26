package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** A native TV sender hub, entirely independent of Kodi. */
class LiveTvActivity : Activity() {
    private val catalog by lazy { LiveTvCatalog(this) }
    private val workers = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var group: String? = null
    private var favOnly = false
    private var search = ""
    private var currentPage = 0
    private var busy = false
    private lateinit var channelList: LinearLayout
    private lateinit var count: TextView
    private lateinit var status: TextView
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        build()
    }

    private fun build() {
        val root = Ui.vertical(this).apply {
            setPadding(dp(21), dp(12), dp(21), dp(10))
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)
        val header = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "📺  STREAMHUB LIVE TV", 24f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(Ui.button(this, "＋ Sender", true) { addChannelDialog() },
            Ui.lp(dp(136), dp(44)).apply { rightMargin = dp(9) })
        header.addView(Ui.button(this, "⋮ Playlists", true) { playlistMenu() },
            Ui.lp(dp(148), dp(44)).apply { rightMargin = dp(9) })
        header.addView(Ui.button(this, "📡 MAC IPTV", true) {
            startActivity(Intent(this, MacPortalActivity::class.java))
        }, Ui.lp(dp(156), dp(44)).apply { rightMargin = dp(9) })
        header.addView(Ui.button(this, "📅 EPG", true) {
            startActivity(Intent(this, EpgActivity::class.java))
        }, Ui.lp(dp(105), dp(44)).apply { rightMargin = dp(8) })
        header.addView(Ui.button(this, "← Zurück", true) { finish() },
            Ui.lp(dp(132), dp(44)))
        root.addView(header, Ui.lp(-1, dp(65)))
        root.addView(Ui.text(this,
            "Fernsehen direkt in StreamHUB – inklusive MAC-IPTV-Portalen, ohne Kodi. " +
                "Importierte HTTPS-Senderstreams verwenden den integrierten Player.",
            13f, Ui.MUTED), Ui.lp(-1, dp(51)))

        root.addView(Ui.button(this, "⌕  Alle IPTV-Quellen durchsuchen", true) {
            startActivity(Intent(this, UnifiedSearchActivity::class.java))
        }, Ui.lp(dp(340), dp(44)))
        root.addView(Ui.text(this, "OFFIZIELLE LIVE-TV-WEBSITES", 16f, Ui.TEXT, true),
            Ui.lp(-1, dp(38)))
        val webScroll = HorizontalScrollView(this).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
        }
        val webRow = Ui.horizontal(this)
        val websites = listOf(
            Triple("Das Erste / ARD", "https://www.ardmediathek.de/live", "ARD Live"),
            Triple("ZDF Live-TV", "https://www.zdf.de/live-tv", "ZDF Live"),
            Triple("Pluto TV", "https://pluto.tv/de/live-tv", "Pluto TV"),
            Triple("Joyn Live-TV", "https://www.joyn.de/live-tv", "Joyn Live")
        )
        websites.forEach { (name, url, label) ->
            webRow.addView(Ui.button(this, "↗  " + name, true) {
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    putExtra("name", label)
                    putExtra("url", url)
                })
            }, Ui.lp(dp(202), dp(48)).apply { rightMargin = dp(10) })
        }
        webScroll.addView(webRow)
        root.addView(webScroll, Ui.lp(-1, dp(59)))
        root.addView(Ui.text(this,
            "Diese Schaltflächen öffnen die offiziellen Webseiten, nicht deren " +
                "Videostreams. Ein WebView kann bei DRM geschützten Sendern eingeschränkt sein.",
            12f, Ui.MUTED), Ui.lp(-1, dp(46)))

        val sub = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sub.addView(Ui.text(this, "MEINE LIVE-SENDER", 18f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(47), 1f))
        sub.addView(Ui.button(this, "M3U-Link", true) { importUrlDialog() },
            Ui.lp(dp(152), dp(45)).apply { rightMargin = dp(9) })
        sub.addView(Ui.button(this, "M3U-Datei", true) { importFile() },
            Ui.lp(dp(154), dp(45)))
        root.addView(sub, Ui.lp(-1, dp(59)))
        status = Ui.text(this,
            "Keine Streams vorinstalliert – du bestimmst deine Senderquellen.", 12f, Ui.MUTED)
        root.addView(status, Ui.lp(-1, dp(32)))

        val filterRow = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filterRow.addView(Ui.button(this, "Alle", true) {
            group = null; favOnly = false; currentPage = 0; updateList()
        }, Ui.lp(dp(92), dp(45)).apply { rightMargin = dp(8) })
        filterRow.addView(Ui.button(this, "♥ Favoriten", true) {
            group = null; favOnly = true; currentPage = 0; updateList()
        }, Ui.lp(dp(147), dp(45)).apply { rightMargin = dp(8) })
        filterRow.addView(Ui.button(this, "Kategorien", true) { showGroups() },
            Ui.lp(dp(144), dp(45)).apply { rightMargin = dp(8) })
        val input = EditText(this).apply {
            hint = "Sender suchen"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(search)
        }
        filterRow.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            rightMargin = dp(8)
        })
        filterRow.addView(Ui.button(this, "Suchen", true) {
            search = input.text.toString().trim().take(65)
            currentPage = 0
            updateList()
        }, Ui.lp(dp(113), dp(45)))
        root.addView(filterRow, Ui.lp(-1, dp(65)))
        count = Ui.text(this, "", 13f, Ui.SOFT)
        root.addView(count, Ui.lp(-1, dp(32)))
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        channelList = Ui.vertical(this).apply { setPadding(0, dp(2), dp(8), dp(30)) }
        scroll.addView(channelList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        updateList()
    }

    private fun updateList() {
        val all = catalog.channels()
        val groups = all.map { it.group }.distinct()
        if (group != null && groups.none { it == group }) group = null
        val selection = all.filter { !favOnly || it.favorite }
            .filter { group == null || it.group == group }
            .filter { search.isBlank() ||
                it.name.contains(search, ignoreCase = true) ||
                it.group.contains(search, ignoreCase = true) }
            .sortedWith(compareByDescending<LiveChannel> { it.favorite }
                .thenBy { it.name.lowercase(java.util.Locale.GERMANY) })
        val maxPage = (selection.size - 1).coerceAtLeast(0) / 60
        currentPage = currentPage.coerceIn(0, maxPage)
        count.text = selection.size.toString() + " Treffer  ·  " + all.size +
            " eigene Sender  ·  Seite " + (currentPage + 1) + "/" + (maxPage + 1)
        channelList.removeAllViews()
        if (all.isEmpty()) {
            channelList.addView(Ui.text(this,
                "Noch keine eigenen Sender eingerichtet.\n\n" +
                    "Importiere eine HTTPS-M3U-Senderliste, wähle eine M3U-Datei " +
                    "oder füge einen Sender mit einer direkten HTTPS-Stream-Adresse hinzu.\n\n" +
                    "Unterstützung für HLS (.m3u8), DASH (.mpd) und direkte Medienstreams.",
                16f, Ui.SOFT), Ui.lp(-1, dp(186)))
            return
        }
        if (selection.isEmpty()) {
            channelList.addView(Ui.text(this, "Keine Sender für diesen Filter gefunden.",
                15f, Ui.SOFT), Ui.lp(-1, dp(80)))
        }
        selection.drop(currentPage * 60).take(60).forEach { channel ->
            val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = Ui.vertical(this)
            label.addView(Ui.button(this, "▶  " + channel.name.take(80), true) {
                play(channel)
            }, Ui.lp(-1, dp(49)))
            label.addView(Ui.text(this, channel.group.take(50), 11f, Ui.MUTED),
                Ui.lp(-1, dp(24)))
            row.addView(label, LinearLayout.LayoutParams(0, dp(79), 1f).apply {
                rightMargin = dp(10)
            })
            row.addView(Ui.button(this, if (channel.favorite) "♥" else "♡", true) {
                catalog.toggleFavorite(channel.id); updateList()
            }, Ui.lp(dp(62), dp(49)).apply { rightMargin = dp(8) })
            row.addView(Ui.button(this, "⋮", true) { showChannelMenu(channel) },
                Ui.lp(dp(58), dp(49)))
            channelList.addView(row, Ui.lp(-1, dp(84)))
        }
        if (maxPage > 0) {
            val pagination = Ui.horizontal(this)
            pagination.addView(Ui.button(this, "‹ Zurückblättern", true) {
                if (currentPage > 0) { currentPage--; updateList() }
            }, Ui.lp(dp(207), dp(49)).apply { rightMargin = dp(12) })
            pagination.addView(Ui.button(this, "Weiterblättern ›", true) {
                if (currentPage < maxPage) { currentPage++; updateList() }
            }, Ui.lp(dp(203), dp(49)))
            channelList.addView(pagination, Ui.lp(-1, dp(60)))
        }
    }

    private fun play(channel: LiveChannel) {
        startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
            putExtra("channel_id", channel.id)
        })
    }

    private fun showChannelMenu(channel: LiveChannel) {
        AlertDialog.Builder(this).setTitle(channel.name)
            .setItems(arrayOf("▶ Abspielen", "♥ Favorit umschalten", "Sender entfernen")) { _, which ->
                when (which) {
                    0 -> play(channel)
                    1 -> { catalog.toggleFavorite(channel.id); updateList() }
                    2 -> AlertDialog.Builder(this).setTitle("Sender entfernen?")
                        .setMessage(channel.name).setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Entfernen") { _, _ ->
                            catalog.delete(channel.id); updateList()
                        }.show()
                }
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun showGroups() {
        val groups = catalog.channels().map { it.group }.distinct().sorted()
        val labels = arrayOf("Alle Kategorien") + groups.toTypedArray()
        AlertDialog.Builder(this).setTitle("Senderkategorie")
            .setItems(labels) { _, which ->
                group = if (which == 0) null else groups[which - 1]
                favOnly = false
                currentPage = 0
                updateList()
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun playlistMenu() {
        AlertDialog.Builder(this).setTitle("Senderlisten verwalten")
            .setItems(arrayOf("M3U-Link importieren", "M3U-Datei öffnen",
                "Einzelnen Sender hinzufügen", "Alle eigenen Sender löschen")) { _, which ->
                when (which) {
                    0 -> importUrlDialog()
                    1 -> importFile()
                    2 -> addChannelDialog()
                    3 -> AlertDialog.Builder(this).setTitle("Alle Sender löschen?")
                        .setMessage("Alle importierten Sender und Favoriten werden gelöscht.")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen") { _, _ ->
                            catalog.save(emptyList()); currentPage = 0; updateList()
                        }.show()
                }
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun addChannelDialog() {
        val layout = Ui.vertical(this).apply { setPadding(dp(18), dp(9), dp(18), dp(9)) }
        val name = EditText(this).apply {
            hint = "Sendername"
            setSingleLine(true)
        }
        val url = EditText(this).apply {
            hint = "https://anbieter.de/live.m3u8"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val category = EditText(this).apply {
            hint = "Kategorie"
            setText("Eigene Sender")
            setSingleLine(true)
        }
        layout.addView(name, Ui.lp(-1, dp(53)))
        layout.addView(url, Ui.lp(-1, dp(53)))
        layout.addView(category, Ui.lp(-1, dp(53)))
        val dialog = AlertDialog.Builder(this).setTitle("Live-Sender hinzufügen")
            .setView(layout).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (catalog.add(name.text.toString(), url.text.toString(),
                        category.text.toString().ifBlank { "Eigene Sender" })) {
                    dialog.dismiss(); updateList()
                } else Toast.makeText(this,
                    "Bitte Sendername und gültige HTTPS-Stream-Adresse eingeben (nicht doppelt).",
                    Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun importUrlDialog() {
        val layout = Ui.vertical(this).apply { setPadding(dp(19), dp(9), dp(19), dp(9)) }
        layout.addView(Ui.text(this,
            "Nutze nur Senderlisten, die du verwenden darfst. " +
                "Playlist und Senderstreams müssen HTTPS nutzen. " +
                "Kein Import aus Kodi oder xVAULT erforderlich.",
            13f, Ui.SOFT), Ui.lp(-1, dp(83)))
        val url = EditText(this).apply {
            hint = "https://anbieter.de/sender.m3u"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(url, Ui.lp(-1, dp(56)))
        val dialog = AlertDialog.Builder(this).setTitle("HTTPS-M3U importieren")
            .setView(layout).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Importieren", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                if (!LiveTvCatalog.validHttpsUrl(address))
                    Toast.makeText(this, "Gültige HTTPS-URL eingeben.", Toast.LENGTH_LONG).show()
                else if (!busy) {
                    dialog.dismiss()
                    importRemote(address)
                }
            }
        }
        dialog.show()
    }

    private fun importRemote(url: String) {
        if (busy) return
        busy = true
        status.text = "Importiere M3U-Senderliste …"
        workers.execute {
            val result = runCatching {
                LiveTvCatalog.parseM3u(LiveTvCatalog.fetchPlaylist(url))
            }
            handler.post {
                busy = false
                result.fold(
                    onSuccess = { finishImport(it) },
                    onFailure = {
                        status.text = "M3U-Import fehlgeschlagen."
                        AlertDialog.Builder(this).setTitle("M3U-Import")
                            .setMessage(it.message ?: "Senderliste nicht verfügbar.")
                            .setPositiveButton("OK", null).show()
                    }
                )
            }
        }
    }

    private fun importFile() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(picker, PICK_M3U) }.onFailure {
            Toast.makeText(this, "Kein Dateimanager für M3U-Dateien installiert.",
                Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_M3U || resultCode != RESULT_OK || busy) return
        val uri = data?.data ?: return
        busy = true
        status.text = "Lade lokale M3U-Datei …"
        workers.execute {
            val parsed = runCatching {
                val output = ByteArrayOutputStream()
                contentResolver.openInputStream(uri)?.use { input ->
                    val block = ByteArray(8192)
                    while (true) {
                        val n = input.read(block)
                        if (n < 0) break
                        require(output.size() + n <= 1_800_000) { "Playlist zu groß." }
                        output.write(block, 0, n)
                    }
                } ?: error("Datei konnte nicht geöffnet werden.")
                LiveTvCatalog.parseM3u(output.toString("UTF-8"))
            }
            handler.post {
                busy = false
                parsed.fold(onSuccess = { finishImport(it) }, onFailure = {
                    status.text = "Dateiimport fehlgeschlagen."
                    Toast.makeText(this, it.message ?: "Ungültige M3U-Datei.",
                        Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun finishImport(channels: List<LiveChannel>) {
        if (channels.isEmpty()) {
            status.text = "Keine nutzbaren HTTPS-Sender in der Playlist."
            Toast.makeText(this,
                "Keine HTTPS-Sender mit Namen gefunden. HTTP-Streams werden übersprungen.",
                Toast.LENGTH_LONG).show()
            return
        }
        val added = catalog.merge(channels)
        status.text = added.toString() + " neue Sender gespeichert, " +
            channels.size.toString() + " gültige Einträge erkannt."
        currentPage = 0
        updateList()
        Toast.makeText(this, added.toString() + " Sender importiert.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val PICK_M3U = 3415 }
}
