package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Search a selected film/series across all user-configured website bookmarks.
 * This is a unified LAUNCHER, not a scraper or provider-availability database.
 * Empty source entries remain editable placeholders; no fake domains are added.
 */
class SourceDirectoryActivity : Activity() {
    private val store by lazy { SiteStore(this) }
    private val kodi by lazy { KodiXvaultBridge(this) }
    private var category: Section? = null
    private var onlyFavorites = false
    private var onlyMissing = false
    private var filter = ""
    private var selectedTitle = ""
    private var year = ""
    private var kind = ""
    private var tmdbId = 0
    private var imdb = ""
    private lateinit var results: LinearLayout
    private lateinit var countLabel: TextView
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    private fun label(text: String, size: Float = 13f) = Ui.text(this, text, size, Ui.MUTED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        selectedTitle = intent.getStringExtra("title").orEmpty().trim().take(150)
        kind = intent.getStringExtra("kind").orEmpty()
        year = intent.getStringExtra("year").orEmpty().take(4)
        tmdbId = intent.getIntExtra("tmdb_id", 0)
        imdb = DeVideoSrc.normalizeId(intent.getStringExtra("imdb").orEmpty())
            ?.takeIf { it.startsWith("tt") }.orEmpty()
        if (selectedTitle.isBlank() || kind !in setOf("movie", "tv") || tmdbId <= 0) {
            Toast.makeText(this, "Bitte einen Titel aus der Bibliothek auswählen.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildScreen()
    }

    private fun buildScreen() {
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(25), dp(14), dp(25), dp(12))
        }
        setContentView(root)
        val top = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, "▶  STREAMHUB  ·  QUELLEN", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        top.addView(Ui.button(this, "← Zurück", true) { finish() }, Ui.lp(dp(142), dp(43)))
        root.addView(top, Ui.lp(-1, dp(63)))

        val heading = Ui.text(this, selectedTitle, 29f, Ui.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(heading, Ui.lp(-1, dp(74)))
        root.addView(label(
            (if (kind == "movie") "Film" else "Serie") + "  ·  " + year +
                "  ·  Quelle aussuchen, Titel suchen oder Webseite verwalten", 14f),
            Ui.lp(-1, dp(36)))

        val playerRow = Ui.horizontal(this)
        playerRow.addView(Ui.button(this, "▶  Kodi / xVAULT", true) { startKodi() },
            Ui.lp(dp(203), dp(49)).apply { rightMargin = dp(10) })
        playerRow.addView(Ui.button(this, "▶  DeVideoSRC", true) { startDeVideo() },
            Ui.lp(dp(190), dp(49)).apply { rightMargin = dp(10) })
        playerRow.addView(Ui.button(this, "Streamingangebote DE", true) { openWatchOffers() },
            Ui.lp(dp(240), dp(49)).apply { rightMargin = dp(10) })
        playerRow.addView(Ui.button(this, "Titel kopieren", true) { copyTitle() },
            Ui.lp(dp(162), dp(49)))
        root.addView(playerRow, Ui.lp(-1, dp(64)))
        root.addView(label(
            "Player: Kodi/xVAULT oder DeVideoSRC. Offizielle Verfügbarkeit: TMDB/JustWatch. " +
                "Webseiten: nur titelbezogene Suche – kein bestätigter Stream.", 12f),
            Ui.lp(-1, dp(49)))

        val filters = Ui.horizontal(this)
        val selection = listOf(
            "Alle" to null,
            "Anime" to Section.ANIME,
            "Filme" to Section.FILME,
            "Serien" to Section.SERIEN,
            "Mix" to Section.MISCHPORTALE
        )
        selection.forEach { (text, chosen) ->
            filters.addView(Ui.button(this, text, true) {
                category = chosen
                onlyFavorites = false
                onlyMissing = false
                renderResults()
            }, Ui.lp(dp(125), dp(43)).apply { rightMargin = dp(8) })
        }
        filters.addView(Ui.button(this, "♥ Favoriten", true) {
            category = null
            onlyFavorites = true
            onlyMissing = false
            renderResults()
        }, Ui.lp(dp(155), dp(43)).apply { rightMargin = dp(8) })
        filters.addView(Ui.button(this, "Ohne Adresse", true) {
            category = null
            onlyFavorites = false
            onlyMissing = true
            renderResults()
        }, Ui.lp(dp(170), dp(43)))
        val filterScroll = android.widget.HorizontalScrollView(this).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
        }
        filterScroll.addView(filters)
        root.addView(filterScroll, Ui.lp(-1, dp(57)))

        val searchRow = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val search = EditText(this).apply {
            hint = "Quellennamen filtern, z. B. AniWorld oder ARTE"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(49), 1f).apply {
            rightMargin = dp(12)
        })
        searchRow.addView(Ui.button(this, "Filtern", true) {
            filter = search.text.toString().trim().take(60)
            renderResults()
        }, Ui.lp(dp(144), dp(46)))
        root.addView(searchRow, Ui.lp(-1, dp(65)))

        countLabel = Ui.text(this, "", 13f, Ui.SOFT)
        root.addView(countLabel, Ui.lp(-1, dp(37)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        results = Ui.vertical(this).apply { setPadding(0, dp(4), dp(9), dp(30)) }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderResults()
    }

    private fun renderResults() {
        val all = store.sites()
        val visible = all
            .filter { category == null || it.section == category }
            .filter { !onlyFavorites || it.favorite }
            .filter { !onlyMissing || SourceSearchRouter.validHomepage(it) == null }
            .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) ||
                it.section.label.contains(filter, ignoreCase = true) }
            .sortedWith(compareByDescending<Site> { it.favorite }
                .thenBy { it.section.ordinal }
                .thenBy { it.name.lowercase(java.util.Locale.ROOT) })
        val usable = all.count { SourceSearchRouter.validHomepage(it) != null }
        countLabel.text = visible.size.toString() + " von " + all.size +
            " Einträgen  ·  " + usable + " mit HTTPS-Adresse  ·  " +
            (all.size - usable) + " ohne nutzbare Adresse"
        results.removeAllViews()
        if (visible.isEmpty()) {
            results.addView(label("Keine Quellen zu diesem Filter vorhanden.", 16f),
                Ui.lp(-1, dp(90)))
            return
        }
        for (site in visible) {
            val configured = SourceSearchRouter.validHomepage(site) != null
            val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = (if (site.favorite) "♥  " else "  ") + site.name +
                "  ·  " + site.section.label
            val subtitle = if (site.id == "devideosrc")
                "Player/API · Für Titel mit IMDb-ID"
            else if (configured)
                "Webadresse hinterlegt · Suchtreffer noch nicht geprüft"
            else
                "Keine Adresse eingetragen · Einrichten erforderlich"
            val card = Ui.vertical(this).apply { setPadding(dp(5), 0, dp(8), 0) }
            val button = Ui.button(this, name.take(65), true) {
                chooseSource(site)
            }
            card.addView(button, Ui.lp(-1, dp(48)))
            card.addView(label(subtitle, 11f), Ui.lp(-1, dp(24)))
            row.addView(card, LinearLayout.LayoutParams(0, dp(75), 1f).apply {
                rightMargin = dp(9)
            })
            val action = if (site.id == "devideosrc") "Player" else if (configured) "⌕ Suchen" else "＋ Adresse"
            row.addView(Ui.button(this, action, true) {
                if (site.id == "devideosrc") startDeVideo()
                else if (configured) searchSite(site)
                else editUrl(site)
            }, Ui.lp(dp(170), dp(47)))
            results.addView(row, Ui.lp(-1, dp(83)))
        }
    }

    private fun chooseSource(site: Site) {
        if (site.id == "devideosrc") {
            AlertDialog.Builder(this).setTitle("DeVideoSRC")
                .setMessage("Der integrierte Player wird über die IMDb-ID aufgerufen.")
                .setPositiveButton("Player starten") { _, _ -> startDeVideo() }
                .setNeutralButton("Webseite öffnen") { _, _ -> openSite(site, false) }
                .setNegativeButton("Abbrechen", null).show()
            return
        }
        val valid = SourceSearchRouter.validHomepage(site) != null
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (valid) {
            labels.add("⌕  Nach »" + selectedTitle.take(55) + "« suchen")
            actions.add { searchSite(site) }
            labels.add("↗  Startseite öffnen")
            actions.add { openSite(site, false) }
        }
        labels.add(if (valid) "Webadresse bearbeiten" else "＋  Webadresse ergänzen")
        actions.add { editUrl(site) }
        labels.add(if (site.favorite) "♥  Favorit entfernen" else "♡  Als Favorit merken")
        actions.add { store.toggleFavorite(site.id); renderResults() }
        labels.add("Filmtitel kopieren")
        actions.add { copyTitle() }
        AlertDialog.Builder(this)
            .setTitle(site.name + " · " + site.section.label)
            .setMessage(if (valid)
                "Suche auf dieser Domain (nicht automatisch ein verfügbarer Stream)."
                else "Noch keine nutzbare HTTPS-Adresse eingerichtet.")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Schließen", null).show()
    }

    private fun editUrl(site: Site) {
        val field = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://beispiel.de/"
            setText(site.url)
        }
        val layout = Ui.vertical(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(label("Nur die echte HTTPS-Adresse selbst eintragen. " +
                "Die App prüft dabei noch nicht, ob die Webseite erreichbar ist.", 13f),
                Ui.lp(-1, dp(68)))
            addView(field, Ui.lp(-1, dp(55)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(site.name + " · Adresse einrichten")
            .setView(layout)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.isNotEmpty() &&
                    SourceSearchRouter.validHomepage(site.copy(url = value)) == null) {
                    Toast.makeText(this, "Bitte eine gültige HTTPS-Webadresse eingeben.",
                        Toast.LENGTH_LONG).show()
                } else {
                    store.upsert(site.name, value, site.section, oldId = site.id)
                    renderResults()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun searchSite(site: Site) {
        val url = SourceSearchRouter.searchUrl(site, selectedTitle)
        if (url == null) {
            Toast.makeText(this, "Quelle ohne gültige Suchadresse.", Toast.LENGTH_SHORT).show()
            return
        }
        copyTitle(silent = true)
        openBrowser(site.name + " · Suche: " + selectedTitle, url)
    }

    private fun openSite(site: Site, search: Boolean) {
        val url = if (search) SourceSearchRouter.searchUrl(site, selectedTitle)
            else SourceSearchRouter.validHomepage(site)
        if (url == null) {
            Toast.makeText(this, "Keine Webadresse vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }
        openBrowser(site.name, url)
    }

    private fun openBrowser(label: String, address: String) {
        store.addVisit(label, address)
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra("name", label.take(150))
            putExtra("url", address)
        })
    }

    private fun copyTitle(silent: Boolean = false) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("StreamHUB: Titel", selectedTitle))
        if (!silent) {
            Toast.makeText(this, "Titel kopiert: " + selectedTitle.take(65),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun catalogTitle(): CatalogTitle = CatalogTitle(tmdbId, kind, selectedTitle,
        year, "", "", "", 0.0)

    private fun startKodi() {
        if (kind == "movie") {
            kodi.play(catalogTitle(), imdb)
            return
        }
        chooseEpisode("Kodi / xVAULT") { season, episode ->
            kodi.play(catalogTitle(), imdb, season, episode)
        }
    }

    private fun startDeVideo() {
        if (imdb.isBlank()) {
            Toast.makeText(this, "Keine IMDb-ID verfügbar – öffne den Kodi-Player " +
                "oder eine andere Quelle.", Toast.LENGTH_LONG).show()
            return
        }
        if (kind == "movie") {
            openDeVideo(DeVideoSrc.movie(imdb, true, DeVideoSrc.options(this)), selectedTitle)
        } else {
            chooseEpisode("DeVideoSRC") { season, episode ->
                openDeVideo(DeVideoSrc.episode(imdb, season, episode, DeVideoSrc.options(this)),
                    selectedTitle + " · S" + season + "E" + episode)
            }
        }
    }

    private fun chooseEpisode(provider: String, launch: (Int, Int) -> Unit) {
        val layout = Ui.vertical(this).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val season = EditText(this).apply {
            setSingleLine(true)
            hint = "Staffel"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val episode = EditText(this).apply {
            setSingleLine(true)
            hint = "Episode"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(label("Staffel", 14f))
        layout.addView(season)
        layout.addView(label("Episode", 14f))
        layout.addView(episode)
        val dialog = AlertDialog.Builder(this).setTitle(provider + " · Folge auswählen")
            .setView(layout).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Weiter", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = season.text.toString().toIntOrNull()
                val e = episode.text.toString().toIntOrNull()
                if (s == null || e == null || s !in 1..999 || e !in 1..999) {
                    Toast.makeText(this, "Staffel und Episode: jeweils 1 bis 999.",
                        Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    launch(s, e)
                }
            }
        }
        dialog.show()
    }

    private fun openDeVideo(url: String, label: String) {
        startActivity(Intent(this, DeVideoPlayerActivity::class.java).apply {
            putExtra("name", label)
            putExtra("url", url)
        })
    }

    private fun openWatchOffers() {
        val url = "https://www.themoviedb.org/" + kind + "/" + tmdbId +
            "/watch?locale=DE"
        openBrowser("TMDB · Angebote: " + selectedTitle, url)
    }

    override fun onDestroy() {
        kodi.close()
        super.onDestroy()
    }
}
