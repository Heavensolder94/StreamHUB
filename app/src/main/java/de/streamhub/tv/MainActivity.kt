package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.text.DateFormat
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import android.net.Uri

class MainActivity : Activity() {
    private enum class Tab(val label: String, val heading: String) {
        HOME("Startseite", "Deine Streaming-Zentrale"),
        SEARCH("Suche", "Webseiten durchsuchen"),
        ANIME("Anime", "Anime-Webseiten"),
        FILME("Filme", "Film-Webseiten"),
        SERIEN("Serien", "Serien-Webseiten"),
        MIX("Mischportale", "Film- und Serien-Mischportale"),
        FAVORITES("Favoriten", "Deine Favoriten"),
        HISTORY("Verlauf", "Zuletzt geöffnet"),
        MANAGE("Verwalten", "Webseiten verwalten")
    }

    private val store by lazy { SiteStore(this) }
    private var tab = Tab.HOME
    private var searchTerm = ""
    private var onlyUnavailable = false
    private var bulkRunning = false
    private val checker = Executors.newFixedThreadPool(4)
    private val pickSitesCode = 3313
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        showTab(Tab.HOME)
    }

    override fun onResume() {
        super.onResume()
        showTab(tab)
    }

    private fun showTab(next: Tab) {
        tab = next
        val root = Ui.horizontal(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }
        setContentView(root)

        val sidebar = Ui.vertical(this).apply {
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = Ui.bg(0xFF0E1825.toInt(), 15f, context = this@MainActivity)
        }
        root.addView(sidebar, Ui.lp(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))

        val brand = Ui.text(this, "▶  StreamHub", 26f, Ui.BLUE, true)
        sidebar.addView(brand, Ui.lp(-1, dp(70)))
        sidebar.addView(Ui.text(this, "DEINE TV-ZENTRALE", 11f, Ui.MUTED, true), Ui.lp(-1, dp(32)))

        val navs = listOf(Tab.HOME, Tab.SEARCH, Tab.ANIME, Tab.FILME, Tab.SERIEN, Tab.MIX, Tab.FAVORITES, Tab.HISTORY, Tab.MANAGE)
        navs.forEach { choice ->
            val label = (if (choice == tab) "●  " else "    ") + choice.label
            val button = Ui.button(this, label) { showTab(choice) }
            val params = Ui.lp(-1, dp(48)).apply { bottomMargin = dp(7) }
            sidebar.addView(button, params)
        }
        val spacer = View(this)
        sidebar.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        sidebar.addView(Ui.text(this, "V1.4  •  Unabhängig von Kodi", 12f, Ui.MUTED), Ui.lp(-1, dp(36)))

        val column = Ui.vertical(this).apply { setPadding(dp(25), dp(12), dp(8), 0) }
        root.addView(column, LinearLayout.LayoutParams(0, -1, 1f))
        val headingBar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = Ui.vertical(this)
        heading.addView(Ui.text(this, tab.heading, 29f, Ui.TEXT, true))
        heading.addView(Ui.text(this, subtitle(), 14f, Ui.MUTED), Ui.lp(-1, dp(30)))
        headingBar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val add = Ui.button(this, "+ Webseite hinzufügen", compact = true) { editSite() }
        headingBar.addView(add, Ui.lp(-2, dp(45)))
        column.addView(headingBar, Ui.lp(-1, dp(88)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isFocusable = false
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val contents = Ui.vertical(this).apply { setPadding(dp(2), dp(8), dp(14), dp(30)) }
        scroll.addView(contents)

        when (tab) {
            Tab.HOME -> renderHome(contents)
            Tab.SEARCH -> renderSearch(contents)
            Tab.ANIME -> renderGrid(contents, store.sites().filter { it.section == Section.ANIME })
            Tab.FILME -> renderGrid(contents, store.sites().filter { it.section == Section.FILME })
            Tab.SERIEN -> renderGrid(contents, store.sites().filter { it.section == Section.SERIEN })
            Tab.MIX -> renderGrid(contents, store.sites().filter { it.section == Section.MISCHPORTALE })
            Tab.FAVORITES -> renderGrid(contents, store.sites().filter { it.favorite })
            Tab.HISTORY -> renderHistory(contents)
            Tab.MANAGE -> renderManage(contents)
        }
        // Enable D-pad navigation immediately.
        sidebar.getChildAt(2)?.requestFocus()
    }

    private fun subtitle(): String = when (tab) {
        Tab.HOME -> "Alle deine Streaming-Webseiten an einem Ort"
        Tab.SEARCH -> "Auch Portale ohne bestätigte Adresse im Katalog finden"
        Tab.MANAGE -> "Seiten verwalten, Verfügbarkeit prüfen und Listen importieren"
        Tab.HISTORY -> "Lokal auf deinem Gerät gespeichert"
        else -> "Mit deiner Fernbedienung auswählen und öffnen"
    }

    private fun renderHome(into: LinearLayout) {
        val feature = Ui.vertical(this).apply {
            background = Ui.bg(0xFF14314F.toInt(), 18f, context = this@MainActivity)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        feature.addView(Ui.text(this, "Willkommen bei StreamHub", 25f, Ui.TEXT, true))
        feature.addView(Ui.text(this, "Anime • Filme • Serien • Mischportale — alles über deine Fernbedienung", 16f, Ui.MUTED), Ui.lp(-1, dp(34)))
        val openFavorites = Ui.button(this, "★  Favoriten ansehen", true) { showTab(Tab.FAVORITES) }
        feature.addView(openFavorites, Ui.lp(dp(220), dp(42)))
        into.addView(feature, Ui.lp(-1, dp(144)).apply { bottomMargin = dp(22) })
        val allSites = store.sites()
        into.addView(Ui.text(this, "${allSites.size} Webseiten im Katalog • Nicht jede ist erreichbar oder lizenziert.", 14f, Ui.MUTED), Ui.lp(-1, dp(37)))
        store.sites().groupBy { it.section }.forEach { (section, sites) ->
            addHeading(into, "${section.label} (${sites.size})")
            renderGrid(into, sites, maxItems = 6)
            val category = when (section) {
                Section.ANIME -> Tab.ANIME
                Section.FILME -> Tab.FILME
                Section.SERIEN -> Tab.SERIEN
                Section.MISCHPORTALE -> Tab.MIX
            }
            into.addView(Ui.button(this, "Alle ${section.label}-Seiten ansehen", true) { showTab(category) },
                Ui.lp(dp(290), dp(43)).apply { bottomMargin = dp(17) })
        }
        val recent = store.history().take(3)
        if (recent.isNotEmpty()) {
            addHeading(into, "Zuletzt besucht")
            recent.forEach { visit ->
                val row = Ui.button(this, "↻   ${visit.name}", true) { launchBrowser(visit.name, visit.url) }
                into.addView(row, Ui.lp(-1, dp(48)).apply { bottomMargin = dp(7) })
            }
        }
    }

    private fun addHeading(into: LinearLayout, title: String) {
        into.addView(Ui.text(this, title, 21f, Ui.TEXT, true), Ui.lp(-1, dp(45)))
    }

    private fun renderGrid(into: LinearLayout, sites: List<Site>, maxItems: Int = Int.MAX_VALUE) {
        val shown = sites.take(maxItems)
        if (shown.isEmpty()) {
            into.addView(Ui.text(this, "Noch keine Seiten vorhanden. Über + kannst du welche hinzufügen.", 17f, Ui.MUTED), Ui.lp(-1, dp(80)))
            return
        }
        shown.chunked(3).forEach { chunk ->
            val row = Ui.horizontal(this)
            chunk.forEach { site ->
                val tile = createTile(site)
                row.addView(tile, LinearLayout.LayoutParams(0, dp(200), 1f).apply {
                    rightMargin = dp(14)
                    bottomMargin = dp(16)
                })
            }
            repeat(3 - chunk.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(200), 1f).apply { rightMargin = dp(14) })
            }
            into.addView(row, Ui.lp(-1, dp(218)))
        }
    }

    private fun createTile(site: Site): View {
        val color = when(site.section) {
            Section.ANIME -> 0xFF173656.toInt()
            Section.FILME -> 0xFF29244E.toInt()
            Section.SERIEN -> 0xFF163E42.toInt()
            Section.MISCHPORTALE -> 0xFF3D2E38.toInt()
        }
        val card = Ui.vertical(this).apply {
            background = Ui.bg(color, 15f, context = this@MainActivity)
            setPadding(dp(15), dp(10), dp(15), dp(12))
        }
        card.addView(Ui.text(this, site.section.label.uppercase(), 11f, Ui.MUTED, true), Ui.lp(-1, dp(20)))
        card.addView(Ui.text(this, site.name, 22f, Ui.TEXT, true), Ui.lp(-1, dp(40)))
        val domain = if (site.url.isBlank()) "Adresse ergänzen" else Uri.parse(site.url).host ?: site.url
        card.addView(Ui.text(this, domain, 12f, Ui.MUTED), Ui.lp(-1, dp(21)))
        card.addView(Ui.text(this, site.sourceKind.label, 11f, Ui.MUTED), Ui.lp(-1, dp(19)))
        card.addView(Ui.text(this, "● ${statusOf(site)}", 11f, statusColor(site)), Ui.lp(-1, dp(20)))
        val controls = Ui.horizontal(this)
        val open = Ui.button(this, if (site.url.isBlank()) "✎ Adresse" else "▶ Öffnen", true) {
            if (site.url.isBlank()) editSite(site) else launchBrowser(site.name, site.url)
        }
        controls.addView(open, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        val star = Ui.button(this, if (site.favorite) "★" else "☆", true) {
            store.toggleFavorite(site.id)
            showTab(tab)
        }
        controls.addView(star, Ui.lp(dp(51), dp(42)))
        card.addView(controls, Ui.lp(-1, dp(42)))
        return card
    }

    private fun renderSearch(into: LinearLayout) {
        val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Name oder Kategorie suchen"
            setText(searchTerm)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(55), 1f).apply { rightMargin = dp(12) })
        row.addView(Ui.button(this, "Suchen", true) {
            searchTerm = input.text.toString().trim()
            showTab(Tab.SEARCH)
        }, Ui.lp(dp(140), dp(46)))
        into.addView(row, Ui.lp(-1, dp(66)).apply { bottomMargin = dp(18) })
        val matching = store.sites().filter {
            searchTerm.isBlank() || it.name.contains(searchTerm, ignoreCase = true) ||
                it.section.label.contains(searchTerm, ignoreCase = true) ||
                it.url.contains(searchTerm, ignoreCase = true) ||
                statusOf(it).contains(searchTerm, ignoreCase = true)
        }
        addHeading(into, "${matching.size} Treffer")
        renderGrid(into, matching)
    }

    private fun renderHistory(into: LinearLayout) {
        val visits = store.history()
        if (visits.isEmpty()) {
            into.addView(Ui.text(this, "Der Verlauf ist noch leer.", 18f, Ui.MUTED))
            return
        }
        into.addView(Ui.button(this, "Verlauf löschen", true) {
            AlertDialog.Builder(this).setTitle("Verlauf wirklich löschen?")
                .setPositiveButton("Löschen") { _, _ -> store.clearHistory(); showTab(Tab.HISTORY) }
                .setNegativeButton("Abbrechen", null).show()
        }, Ui.lp(dp(210), dp(48)).apply { bottomMargin = dp(20) })
        visits.forEach { visit ->
            val btn = Ui.button(this, "↻  ${visit.name}     •     ${Uri.parse(visit.url).host ?: visit.url}") {
                launchBrowser(visit.name, visit.url)
            }
            into.addView(btn, Ui.lp(-1, dp(56)).apply { bottomMargin = dp(10) })
        }
    }

    private fun statusOf(site: Site): String = if (site.url.isBlank()) "Adresse fehlt" else site.reachability.label

    private fun statusColor(site: Site): Int = when {
        site.url.isBlank() -> Ui.MUTED
        site.reachability == Reachability.ONLINE -> 0xFF8BE3B0.toInt()
        site.reachability == Reachability.OFFLINE -> 0xFFFFA5A5.toInt()
        site.reachability == Reachability.RESTRICTED -> 0xFFFFDA85.toInt()
        else -> Ui.MUTED
    }

    private fun renderManage(into: LinearLayout) {
        val top = Ui.horizontal(this)
        top.addView(Ui.button(this, "↓ Liste importieren", true) { pickImportFile() },
            Ui.lp(dp(205), dp(48)).apply { rightMargin = dp(12) })
        top.addView(Ui.button(this, "◉ Alle Adressen prüfen", true) { confirmCheckAll() },
            Ui.lp(dp(220), dp(48)).apply { rightMargin = dp(12) })
        top.addView(Ui.button(this, if (onlyUnavailable) "Alle anzeigen" else "Nur nicht erreichbar", true) {
            onlyUnavailable = !onlyUnavailable
            showTab(Tab.MANAGE)
        }, Ui.lp(dp(207), dp(48)))
        into.addView(top, Ui.lp(-1, dp(64)))
        val stored = store.sites()
        val sites = if (onlyUnavailable) stored.filter {
            it.url.isBlank() || it.reachability == Reachability.OFFLINE
        } else stored
        into.addView(Ui.text(this,
            "${sites.size} Einträge • Der Status stammt nur vom letzten manuellen Test. 'Adresse fehlt' ist kein Ausfall.",
            13f, Ui.MUTED), Ui.lp(-1, dp(38)))
        sites.forEach { site ->
            val line = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val info = Ui.vertical(this)
            info.addView(Ui.text(this, "${site.name}  •  ${site.section.label}", 18f, Ui.TEXT, true))
            info.addView(Ui.text(this, if (site.url.isBlank()) "Keine Adresse gespeichert" else site.url,
                12f, Ui.MUTED))
            val date = if (site.checkedAt > 0L) " · " + DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(java.util.Date(site.checkedAt)) else ""
            info.addView(Ui.text(this, "${statusOf(site)}$date", 12f, statusColor(site)))
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            if (site.url.isNotBlank()) {
                line.addView(Ui.button(this, "Prüfen", true) { checkOne(site) },
                    Ui.lp(dp(98), dp(42)).apply { rightMargin = dp(8) })
            }
            line.addView(Ui.button(this, "Bearbeiten", true) { editSite(site) },
                Ui.lp(dp(117), dp(42)).apply { rightMargin = dp(8) })
            line.addView(Ui.button(this, "Löschen", true) {
                AlertDialog.Builder(this).setTitle("${site.name} entfernen?")
                    .setPositiveButton("Entfernen") { _, _ -> store.delete(site.id); showTab(Tab.MANAGE) }
                    .setNegativeButton("Abbrechen", null).show()
            }, Ui.lp(dp(92), dp(42)))
            into.addView(line, Ui.lp(-1, dp(91)).apply { bottomMargin = dp(7) })
        }
    }

    private fun checkOne(site: Site) {
        Toast.makeText(this, "Prüfe ${site.name} …", Toast.LENGTH_SHORT).show()
        checker.execute {
            val status = SiteChecker.check(site.url)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                store.saveReachability(site.id, site.url, status)
                Toast.makeText(this, "${site.name}: ${status.label}", Toast.LENGTH_LONG).show()
                if (tab == Tab.MANAGE) showTab(Tab.MANAGE)
            }
        }
    }

    private fun confirmCheckAll() {
        if (bulkRunning) {
            Toast.makeText(this, "Prüfung läuft bereits.", Toast.LENGTH_SHORT).show()
            return
        }
        val sites = store.sites().filter { it.url.isNotBlank() }
        if (sites.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("${sites.size} Webseiten prüfen?")
            .setMessage("StreamHub kontaktiert jede gespeicherte HTTPS-Adresse. " +
                "Ein Fehler, eine Sperre oder ein CAPTCHA bedeutet nicht zwingend, dass eine Webseite dauerhaft offline ist. " +
                "Die Prüfung wird nur auf deinen Wunsch gestartet.")
            .setPositiveButton("Prüfung starten") { _, _ -> checkAll(sites) }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun checkAll(sites: List<Site>) {
        bulkRunning = true
        val remaining = AtomicInteger(sites.size)
        Toast.makeText(this, "Prüfung für ${sites.size} Adressen gestartet.", Toast.LENGTH_LONG).show()
        sites.forEach { site ->
            checker.execute {
                val result = SiteChecker.check(site.url)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        store.saveReachability(site.id, site.url, result)
                        if (remaining.decrementAndGet() == 0) {
                            bulkRunning = false
                            Toast.makeText(this, "Alle ${sites.size} Adressen überprüft.", Toast.LENGTH_LONG).show()
                            if (tab == Tab.MANAGE) showTab(Tab.MANAGE)
                        }
                    }
                }
            }
        }
    }

    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/csv", "application/json", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, pickSitesCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Kein Dateiauswahldialog verfügbar.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Compatibility with Android TV API 23+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickSitesCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            var filename = ""
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) filename = cursor.getString(0) ?: ""
            }
            val out = ByteArrayOutputStream()
            contentResolver.openInputStream(uri)?.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    if (out.size() + n > 1024 * 1024) throw IllegalArgumentException("Datei ist größer als 1 MB.")
                    out.write(chunk, 0, n)
                }
            } ?: throw IllegalArgumentException("Datei konnte nicht geöffnet werden.")
            val preview = SiteListImporter.parse(out.toString(Charsets.UTF_8.name()), filename)
            val examples = preview.entries.take(4).joinToString("\n") { "• ${it.name} (${it.section.label})" }
            AlertDialog.Builder(this)
                .setTitle("Webseitenliste importieren")
                .setMessage("${preview.entries.size} gültige Einträge, ${preview.invalid} ungültig, " +
                    "${preview.repeated} doppelte Einträge innerhalb der Datei.\n\n" +
                    "$examples\n\n" +
                    "Vorhandene Seiten, Links und Favoriten bleiben erhalten. " +
                    "Leere Platzhalter können ergänzt werden.")
                .setPositiveButton("Importieren") { _, _ ->
                    val result = store.importSites(preview.entries)
                    Toast.makeText(this,
                        "${result.added} hinzugefügt, ${result.completed} Adressen ergänzt, ${result.skipped} übersprungen.",
                        Toast.LENGTH_LONG).show()
                    showTab(Tab.MANAGE)
                }
                .setNegativeButton("Abbrechen", null).show()
        } catch (error: Exception) {
            AlertDialog.Builder(this).setTitle("Import nicht möglich")
                .setMessage(error.message ?: "Die Datei hat ein ungültiges Format.")
                .setPositiveButton("OK", null).show()
        }
    }

    override fun onDestroy() {
        checker.shutdownNow()
        super.onDestroy()
    }

    private fun editSite(existing: Site? = null) {
        val outer = Ui.vertical(this).apply { setPadding(dp(18), dp(8), dp(18), 0) }
        val name = EditText(this).apply {
            setSingleLine(true); hint = "Name der Webseite"; setText(existing?.name ?: "")
        }
        val url = EditText(this).apply {
            setSingleLine(true); hint = "https://beispiel.de"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url ?: "https://")
        }
        val category = Spinner(this)
        val options = Section.entries.map { it.label }
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        category.setSelection(Section.entries.indexOf(existing?.section ?: Section.ANIME))
        outer.addView(name)
        outer.addView(url)
        outer.addView(Ui.text(this, "Kategorie", 15f, Ui.MUTED), Ui.lp(-1, dp(31)))
        outer.addView(category)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Webseite hinzufügen" else "Webseite bearbeiten")
            .setView(outer)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = name.text.toString().trim()
                val address = url.text.toString().trim()
                val parsed = runCatching { Uri.parse(address) }.getOrNull()
                val isMissingAllowed = existing != null && address.isBlank()
                if (displayName.isBlank() || (!isMissingAllowed && (parsed?.scheme != "https" || parsed.host.isNullOrBlank()))) {
                    Toast.makeText(this, "Bitte Name und gültige HTTPS-Adresse eingeben.", Toast.LENGTH_LONG).show()
                } else {
                    store.upsert(displayName, address, Section.entries[category.selectedItemPosition], existing?.id)
                    dialog.dismiss()
                    showTab(tab)
                }
            }
        }
        dialog.show()
    }

    private fun launchBrowser(name: String, url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Bitte zuerst eine HTTPS-Adresse eintragen.", Toast.LENGTH_LONG).show()
            return
        }
        store.addVisit(name, url)
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra("name", name)
            putExtra("url", url)
        })
    }

    @Deprecated("Legacy back navigation for Android TV devices")
    override fun onBackPressed() {
        if (tab == Tab.HOME) super.onBackPressed() else showTab(Tab.HOME)
    }
}
