package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Netflix-style catalog UI on Android TV, with real TMDB catalog metadata and user-owned key. */
class MediaLibraryActivity : Activity() {
    private enum class Page { HOME, MOVIES, SERIES, ANIME, SEARCH, WATCHLIST }
    private val client by lazy { TmdbClient(this) }
    private val kodiBridge by lazy { KodiXvaultBridge(this) }
    private val saved by lazy { getSharedPreferences("streamhub_tmdb", MODE_PRIVATE) }
    private var page = Page.HOME
    private var detail: CatalogTitle? = null
    private var searchQuery = ""
    private var requestNumber = 0
    private lateinit var content: LinearLayout
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        showPage(Page.HOME)
    }

    private fun showPage(next: Page) {
        page = next
        detail = null
        requestNumber++
        val generation = requestNumber
        createShell()
        if (!client.hasCredential()) {
            renderMissingKey()
            return
        }
        when (page) {
            Page.HOME -> renderHome(generation)
            Page.MOVIES -> renderCollection(generation, "Beliebte Filme", "/movie/popular", "movie")
            Page.SERIES -> renderCollection(generation, "Beliebte Serien", "/tv/popular", "tv")
            Page.ANIME -> renderCollection(generation, "Anime entdecken", "/discover/tv", "tv",
                mapOf("with_genres" to "16", "with_original_language" to "ja",
                    "sort_by" to "popularity.desc", "include_adult" to "false"))
            Page.SEARCH -> renderSearch(generation)
            Page.WATCHLIST -> renderWatchlist()
        }
    }

    private fun createShell() {
        val main = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(21), dp(14), dp(21), 0)
        }
        setContentView(main)
        val brand = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(Ui.text(this, "▶  STREAMHUB", 24f, Ui.TEXT, true),
            Ui.lp(dp(235), dp(53)))
        brand.addView(Ui.text(this, "PREMIUM  ·  BIBLIOTHEK 2.2", 12f, Ui.BLUE, true),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        brand.addView(Ui.button(this, "API-Key", true) { showKeyDialog() },
            Ui.lp(-2, dp(43)).apply { rightMargin = dp(8) })
        brand.addView(Ui.button(this, "Credits", true) { showCredits() },
            Ui.lp(-2, dp(43)).apply { rightMargin = dp(8) })
        brand.addView(Ui.button(this, "✕", true) { finish() }, Ui.lp(dp(48), dp(43)))
        main.addView(brand, Ui.lp(-1, dp(65)))

        val navigation = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        val navRow = Ui.horizontal(this)
        val navItems = listOf(
            Page.HOME to "⌂  Start", Page.MOVIES to "▣  Filme",
            Page.SERIES to "▤  Serien", Page.ANIME to "✦  Anime",
            Page.SEARCH to "⌕  Suche", Page.WATCHLIST to "♥  Meine Liste"
        )
        navItems.forEach { (target, label) ->
            navRow.addView(Ui.button(this, label, true) { showPage(target) },
                Ui.lp(-2, dp(47)).apply { rightMargin = dp(10) })
        }
        navigation.addView(navRow)
        main.addView(navigation, Ui.lp(-1, dp(57)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = Ui.vertical(this).apply {
            setPadding(dp(2), dp(13), dp(9), dp(38))
        }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun valid(generation: Int) = generation == requestNumber &&
        !isFinishing && !isDestroyed

    private fun heading(where: LinearLayout, value: String, size: Float = 23f) {
        where.addView(Ui.text(this, value, size, Ui.TEXT, true),
            Ui.lp(-1, dp(49)).apply { topMargin = dp(4) })
    }

    private fun message(where: LinearLayout, text: String, height: Int = 75) {
        where.addView(Ui.text(this, text, 14f, Ui.MUTED),
            Ui.lp(-1, dp(height)))
    }

    private fun renderMissingKey() {
        heading(content, "Deine Film- und Serienwelt")
        message(content, "Für echte Poster, deutsche Filmbeschreibungen und automatische " +
            "Kataloge benötigt StreamHUB deinen eigenen TMDB-Zugang.", 80)
        content.addView(Ui.button(this, "TMDB API-Key einrichten", true) { showKeyDialog() },
            Ui.lp(dp(310), dp(54)).apply { bottomMargin = dp(20) })
        message(content, "Die Eingabe wird nur auf diesem Gerät gespeichert. " +
            "Es wird kein Zugangsschlüssel in das öffentliche GitHub-Repository geschrieben.", 80)
        message(content, "TMDB-Konto: themoviedb.org → Einstellungen → API. " +
            "Du kannst einen API-Key (v3) oder einen Read Access Token verwenden.", 80)
        content.addView(Ui.button(this, "TMDB öffnen", true) {
            openExternal("https://www.themoviedb.org/settings/api")
        }, Ui.lp(dp(180), dp(45)))
    }

    private fun renderHome(generation: Int) {
        heading(content, "Willkommen in deiner Streaming-Bibliothek", 28f)
        message(content, "Aktuelle Filme und Serien mit Originalpostern, Details und deiner Merkliste.", 55)
        val featured = Ui.vertical(this)
        content.addView(featured)
        val shelfFilms = Ui.vertical(this)
        val shelfSeries = Ui.vertical(this)
        val shelfAnime = Ui.vertical(this)
        content.addView(shelfFilms)
        content.addView(shelfSeries)
        content.addView(shelfAnime)
        addShelf(featured, shelfFilms, "Diese Woche angesagt",
            "/trending/movie/week", "movie", generation, feature = true)
        addShelf(null, shelfSeries, "Serien-Highlights", "/tv/popular", "tv", generation)
        addShelf(null, shelfAnime, "Anime entdecken", "/discover/tv", "tv", generation,
            options = mapOf("with_genres" to "16", "with_original_language" to "ja",
                "sort_by" to "popularity.desc", "include_adult" to "false"))
        showAttribution(content)
    }

    private fun addShelf(hero: LinearLayout?, container: LinearLayout, label: String,
                         path: String, kind: String, generation: Int, feature: Boolean = false,
                         options: Map<String, String> = emptyMap()) {
        heading(container, label)
        val railHost = Ui.vertical(this)
        container.addView(railHost)
        message(railHost, "Lade Filmkatalog …", 60)
        client.request(path, options) { result ->
            if (!valid(generation)) return@request
            railHost.removeAllViews()
            result.fold(onSuccess = { data ->
                val list = TmdbClient.entries(data, kind)
                if (list.isEmpty()) message(railHost, "Keine Titel gefunden.")
                else {
                    if (feature && hero != null) renderHero(hero, list.first())
                    renderRail(railHost, list.take(18))
                }
            }, onFailure = { error ->
                message(railHost, "Katalog momentan nicht verfügbar: " +
                    (error.message ?: "Unbekannter Fehler"), 80)
            })
        }
    }

    private fun renderHero(host: LinearLayout, title: CatalogTitle) {
        host.removeAllViews()
        val banner = FrameLayout(this).apply {
            background = Ui.bg(0xFF182A40.toInt(), 17f, context = this@MediaLibraryActivity)
            clipToOutline = true
        }
        banner.addView(CinematicArtwork(this, title.name, "Filme", hero = true),
            FrameLayout.LayoutParams(-1, -1))
        val backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        banner.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        TmdbArtwork.load(backdrop, title.backdrop, backdrop = true)
        banner.addView(View(this).apply {
            background = Ui.gradient(this@MediaLibraryActivity,
                intArrayOf(0xF5080B12.toInt(), 0xBF101A30.toInt(), 0x3A101A30),
                17f)
        }, FrameLayout.LayoutParams(-1, -1))
        val overlay = Ui.vertical(this).apply {
            setPadding(dp(22), dp(19), dp(18), dp(15))
        }
        overlay.addView(Ui.text(this, "●  DIESE WOCHE IM TREND", 11f, Ui.BLUE, true),
            Ui.lp(-1, dp(36)))
        val name = Ui.text(this, title.name, 30f, Ui.TEXT, true)
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        overlay.addView(name, Ui.lp(-1, dp(87)))
        overlay.addView(Ui.text(this, title.year + "  ·  ★ " +
            "%.1f".format(java.util.Locale.GERMANY, title.rating) + "  ·  TMDB", 14f, Ui.SOFT),
            Ui.lp(-1, dp(32)))
        overlay.addView(Ui.button(this, "▶  Details ansehen", true) { showDetail(title) },
            Ui.lp(dp(215), dp(47)))
        banner.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        host.addView(banner, Ui.lp(-1, dp(255)).apply { bottomMargin = dp(17) })
    }

    private fun renderCollection(generation: Int, title: String, path: String, kind: String,
                                 options: Map<String, String> = emptyMap()) {
        heading(content, title, 29f)
        message(content, "Filmdaten, Poster und Beschreibungen von TMDB.", 45)
        val host = Ui.vertical(this)
        content.addView(host)
        message(host, "Lade Titel …")
        client.request(path, options) { result ->
            if (!valid(generation)) return@request
            host.removeAllViews()
            result.fold(onSuccess = { data ->
                val found = TmdbClient.entries(data, kind)
                if (found.isEmpty()) message(host, "Keine Titel gefunden.")
                else renderGrid(host, found)
            }, onFailure = {
                message(host, "Fehler: " + (it.message ?: "Katalog nicht erreichbar"))
            })
        }
        showAttribution(content)
    }

    private fun renderSearch(generation: Int) {
        heading(content, "Filme und Serien suchen", 29f)
        val form = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Film, Serie oder Anime eingeben"
            setText(searchQuery)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        form.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            rightMargin = dp(13)
        })
        form.addView(Ui.button(this, "⌕  Suchen", true) {
            searchQuery = input.text.toString().trim().take(90)
            showPage(Page.SEARCH)
        }, Ui.lp(dp(148), dp(50)))
        content.addView(form, Ui.lp(-1, dp(72)))
        val host = Ui.vertical(this)
        content.addView(host)
        if (searchQuery.length < 2) {
            message(host, "Mindestens zwei Zeichen eingeben, um den TMDB-Katalog zu durchsuchen.")
        } else {
            message(host, "Suche nach: " + searchQuery)
            client.request("/search/multi",
                mapOf("query" to searchQuery, "include_adult" to "false")) { result ->
                if (!valid(generation)) return@request
                host.removeAllViews()
                result.fold(onSuccess = { data ->
                    val found = TmdbClient.entries(data)
                    heading(host, found.size.toString() + " Treffer", 20f)
                    if (found.isEmpty()) message(host, "Keine Treffer.")
                    else renderGrid(host, found)
                }, onFailure = {
                    message(host, "Suche fehlgeschlagen: " + (it.message ?: "Netzwerkfehler"))
                })
            }
        }
        showAttribution(content)
    }

    private fun renderWatchlist() {
        heading(content, "♥  Meine Liste", 29f)
        val stored = watchlist()
        message(content, stored.size.toString() + " gemerkte Titel · lokal auf dieser TV-Box gespeichert.", 55)
        if (stored.isEmpty()) message(content, "Noch keine Titel gemerkt. Öffne eine Detailseite und wähle ♥ Merken.")
        else renderGrid(content, stored)
    }

    private fun renderRail(into: LinearLayout, titles: List<CatalogTitle>) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val row = Ui.horizontal(this).apply { setPadding(dp(4), dp(8), dp(6), dp(9)) }
        titles.forEach { title ->
            row.addView(posterTile(title), Ui.lp(dp(170), dp(252)).apply { rightMargin = dp(15) })
        }
        scroll.addView(row)
        into.addView(scroll, Ui.lp(-1, dp(279)).apply { bottomMargin = dp(15) })
    }

    private fun renderGrid(into: LinearLayout, titles: List<CatalogTitle>) {
        titles.chunked(4).forEach { items ->
            val row = Ui.horizontal(this)
            items.forEach {
                row.addView(posterTile(it), LinearLayout.LayoutParams(0, dp(256), 1f).apply {
                    rightMargin = dp(14)
                    bottomMargin = dp(14)
                })
            }
            repeat(4 - items.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(256), 1f).apply {
                    rightMargin = dp(14)
                })
            }
            into.addView(row, Ui.lp(-1, dp(272)))
        }
    }

    private fun posterTile(title: CatalogTitle): View {
        val card = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 13f, context = this@MediaLibraryActivity)
            clipToOutline = true
            isFocusable = true
            isClickable = true
        }
        card.addView(CinematicArtwork(this, title.name,
            if (title.kind == "tv") "Serien" else "Filme"), FrameLayout.LayoutParams(-1, -1))
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(poster, FrameLayout.LayoutParams(-1, -1))
        TmdbArtwork.load(poster, title.poster)
        card.addView(View(this).apply {
            background = Ui.gradient(this@MediaLibraryActivity,
                intArrayOf(0x00101624, 0x8A08101C.toInt(), 0xFA080B12.toInt()),
                13f, GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val text = Ui.vertical(this).apply { setPadding(dp(10), 0, dp(9), dp(9)) }
        val caption = Ui.text(this, title.name, 16f, Ui.TEXT, true)
        caption.maxLines = 2
        caption.ellipsize = TextUtils.TruncateAt.END
        text.addView(caption, Ui.lp(-1, dp(50)))
        text.addView(Ui.text(this, title.year + "  ·  ★ " +
            "%.1f".format(java.util.Locale.GERMANY, title.rating), 11f, Ui.SOFT),
            Ui.lp(-1, dp(22)))
        card.addView(text, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        card.foreground = Ui.bg(Color.TRANSPARENT, 13f, Color.TRANSPARENT, this)
        card.setOnFocusChangeListener { view, focused ->
            card.foreground = Ui.bg(Color.TRANSPARENT, 13f,
                if (focused) Ui.BLUE else Color.TRANSPARENT, this)
            view.animate().scaleX(if (focused) 1.045f else 1f)
                .scaleY(if (focused) 1.045f else 1f).setDuration(130).start()
        }
        card.setOnClickListener { showDetail(title) }
        return card
    }

    private fun showDetail(item: CatalogTitle) {
        detail = item
        requestNumber++
        val generation = requestNumber
        createShell()
        val hero = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 15f, context = this@MediaLibraryActivity)
            clipToOutline = true
        }
        hero.addView(CinematicArtwork(this, item.name, "Filme", hero = true),
            FrameLayout.LayoutParams(-1, -1))
        val backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        TmdbArtwork.load(backdrop, item.backdrop, backdrop = true)
        hero.addView(View(this).apply {
            background = Ui.gradient(this@MediaLibraryActivity,
                intArrayOf(0xF9080B12.toInt(), 0xC0080B12.toInt(), 0x44080B12),
                15f)
        }, FrameLayout.LayoutParams(-1, -1))
        val panel = Ui.vertical(this).apply { setPadding(dp(24), dp(18), dp(20), dp(15)) }
        panel.addView(Ui.text(this,
            if (item.kind == "tv") "STREAMHUB  ·  SERIE" else "STREAMHUB  ·  FILM",
            12f, Ui.BLUE, true), Ui.lp(-1, dp(34)))
        val titleText = Ui.text(this, item.name, 34f, Ui.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(titleText, Ui.lp(-1, dp(100)))
        panel.addView(Ui.text(this, item.year + "  ·  ★ " +
            "%.1f".format(java.util.Locale.GERMANY, item.rating) + " / 10",
            15f, Ui.SOFT), Ui.lp(-1, dp(39)))
        val sourceHost = Ui.vertical(this)
        val buttons = Ui.horizontal(this)
        buttons.addView(Ui.button(this, "▶  Jetzt ansehen", true) {
            (content.parent as? ScrollView)?.smoothScrollTo(0, sourceHost.top)
            sourceHost.requestFocus()
        }, Ui.lp(dp(215), dp(51)).apply { rightMargin = dp(11) })
        buttons.addView(Ui.button(this,
            if (isSaved(item)) "♥  Entfernen" else "♡  Merken", true) {
            toggleSaved(item)
            showDetail(item)
        }, Ui.lp(dp(160), dp(51)).apply { rightMargin = dp(11) })
        buttons.addView(Ui.button(this, "← Zurück", true) {
            showPage(page)
        }, Ui.lp(dp(130), dp(51)))
        panel.addView(buttons, Ui.lp(-1, dp(67)))
        hero.addView(panel, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, Ui.lp(-1, dp(290)).apply { bottomMargin = dp(15) })
        content.addView(sourceHost, Ui.lp(-1, -2).apply { bottomMargin = dp(18) })
        val sourcePanel = TitleSourcesPanel(
            activity = this,
            tmdb = client,
            title = item,
            openPlayer = { imdb, custom -> openDeVideo(item, imdb, custom) },
            openEpisode = { imdb -> episodeDialog(item, imdb) },
            openXvault = { imdb ->
                if (item.kind == "tv") kodiEpisodeDialog(item, imdb)
                else kodiBridge.play(item, imdb)
            },
            configureKodi = { kodiBridge.settingsDialog() },
            testKodi = { kodiBridge.test() },
            openWebsite = { site ->
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    putExtra("name", site.name)
                    putExtra("url", site.url)
                })
            },
            openTmdbWatch = { url ->
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    putExtra("name", "TMDB · Bezugsquellen für " + item.name)
                    putExtra("url", url)
                })
            }
        )
        sourcePanel.render(sourceHost) { valid(generation) }
        val infoHost = Ui.vertical(this)
        content.addView(infoHost)
        heading(infoHost, "Über diesen Titel")
        message(infoHost, item.summary.ifBlank { "Keine deutsche Beschreibung verfügbar." }, 98)
        message(infoHost, "Lade weitere Details und IMDb-Zuordnung …", 55)
        client.request("/" + item.kind + "/" + item.id.toString(),
            mapOf("append_to_response" to "external_ids")) { outcome ->
            if (!valid(generation)) return@request
            infoHost.removeAllViews()
            outcome.fold(onSuccess = { data ->
                val overview = data.optString("overview", "").ifBlank { item.summary }
                heading(infoHost, "Handlung")
                message(infoHost, overview.ifBlank { "Keine Beschreibung hinterlegt." }, 132)
                val genres = data.optJSONArray("genres")
                val tags = (0 until (genres?.length() ?: 0)).mapNotNull {
                    genres?.optJSONObject(it)?.optString("name")
                }.filter { it.isNotBlank() }.take(5).joinToString("  ·  ")
                val runtime = if (item.kind == "movie") data.optInt("runtime", 0) else 0
                val imdb = imdbId(data, item.kind)
                sourcePanel.updateImdb(imdb)
                message(infoHost, listOfNotNull(
                    if (tags.isNotBlank()) tags else null,
                    if (runtime > 0) runtime.toString() + " Minuten" else null,
                    if (imdb.isNotBlank()) "IMDb: " + imdb else "IMDb-ID nicht vorhanden"
                ).joinToString("\n"), 100)
                message(infoHost, "Die Quellen-Auswahl befindet sich direkt oberhalb dieser Infos. " +
                    "TMDB/JustWatch-Anbieterangaben sind keine Garantie für einen abspielbaren Stream.", 69)
            }, onFailure = { error ->
                sourcePanel.updateImdb("")
                message(infoHost, "Details nicht verfügbar: " + error.message.orEmpty(), 76)
                message(infoHost, "Gespeicherte Webseiten und Verfügbarkeitsangaben " +
                    "bleiben oben unabhängig nutzbar.", 58)
            })
        }
        showAttribution(content)
    }

    private fun imdbId(json: JSONObject, kind: String): String {
        val raw = if(kind == "movie") json.optString("imdb_id", "").ifBlank {
            json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
        } else json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
        return DeVideoSrc.normalizeId(raw)?.takeIf { it.startsWith("tt") } ?: ""
    }

    private fun selectPlayer(item: CatalogTitle) {
        val generation = requestNumber
        client.request("/" + item.kind + "/" + item.id.toString(),
            mapOf("append_to_response" to "external_ids")) { response ->
            if (!valid(generation)) return@request
            response.fold(onSuccess = {
                showPlayerOptions(item, imdbId(it, item.kind))
            }, onFailure = {
                Toast.makeText(this, "IMDb-Zuordnung fehlgeschlagen. Webseiten können trotzdem geöffnet werden.",
                    Toast.LENGTH_LONG).show()
                selectSavedWebsite()
            })
        }
    }

    private fun showPlayerOptions(item: CatalogTitle, imdb: String) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (imdb.isNotBlank()) {
            labels.add("DeVideoSRC – Standardplayer")
            actions.add { openDeVideo(item, imdb, false) }
            if (item.kind == "movie") {
                labels.add("DeVideoSRC – Custom-Player")
                actions.add { openDeVideo(item, imdb, true) }
            } else {
                labels.add("DeVideoSRC – bestimmte Episode")
                actions.add { episodeDialog(item, imdb) }
            }
        }
        labels.add("Meine Webseiten / andere Quellen")
        actions.add { selectSavedWebsite() }
        labels.add("TMDB-Titelseite öffnen")
        actions.add {
            openExternal("https://www.themoviedb.org/" + item.kind + "/" + item.id)
        }
        val note = if(imdb.isBlank())"Keine IMDb-ID in TMDB hinterlegt. " else ""
        AlertDialog.Builder(this).setTitle("Player für " + item.name)
            .setMessage(note + "Quelle auswählen. Ein Poster bestätigt keine Abspielbarkeit.")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun openDeVideo(item: CatalogTitle, imdb: String, custom: Boolean) {
        val url = if(item.kind=="movie")
            DeVideoSrc.movie(imdb, custom, DeVideoSrc.options(this))
            else DeVideoSrc.series(imdb)
        startActivity(Intent(this, DeVideoPlayerActivity::class.java).apply {
            putExtra("name", item.name)
            putExtra("url", url)
        })
    }

    private fun episodeDialog(item: CatalogTitle, imdb: String) {
        val fields = Ui.vertical(this).apply { setPadding(dp(17), dp(6), dp(17), dp(6)) }
        val season = EditText(this).apply {
            hint = "Staffel"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val episode = EditText(this).apply {
            hint = "Episode"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        fields.addView(Ui.text(this, "Staffel", 15f, Ui.SOFT))
        fields.addView(season)
        fields.addView(Ui.text(this, "Episode", 15f, Ui.SOFT))
        fields.addView(episode)
        AlertDialog.Builder(this).setTitle("Folge von " + item.name)
            .setView(fields).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Öffnen") { _, _ ->
                val s=season.text.toString().toIntOrNull()
                val e=episode.text.toString().toIntOrNull()
                if(s == null || e == null || s !in 1..999 || e !in 1..999) {
                    Toast.makeText(this, "Staffel/Episode müssen zwischen 1 und 999 liegen.",
                        Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, DeVideoPlayerActivity::class.java).apply {
                        putExtra("name", item.name + " · S" + s + "E" + e)
                        putExtra("url", DeVideoSrc.episode(imdb, s, e, DeVideoSrc.options(this@MediaLibraryActivity)))
                    })
                }
            }.show()
    }

    private fun kodiEpisodeDialog(item: CatalogTitle, imdb: String) {
        val fields = Ui.vertical(this).apply { setPadding(dp(17), dp(7), dp(17), dp(7)) }
        val season = EditText(this).apply {
            hint = "Staffel (1–999)"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val episode = EditText(this).apply {
            hint = "Episode (1–999)"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        fields.addView(Ui.text(this, "Staffel", 14f, Ui.SOFT))
        fields.addView(season)
        fields.addView(Ui.text(this, "Episode", 14f, Ui.SOFT))
        fields.addView(episode)
        AlertDialog.Builder(this).setTitle("Mit xVAULT abspielen: " + item.name)
            .setView(fields)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("An Kodi übergeben") { _, _ ->
                val s = season.text.toString().toIntOrNull()
                val e = episode.text.toString().toIntOrNull()
                if (s == null || e == null || s !in 1..999 || e !in 1..999) {
                    Toast.makeText(this, "Bitte Staffel und Episode zwischen 1 und 999 angeben.",
                        Toast.LENGTH_LONG).show()
                } else {
                    kodiBridge.play(item, imdb, s, e)
                }
            }.show()
    }

    private fun selectSavedWebsite() {
        val sites = SiteStore(this).sites().filter { it.url.startsWith("https://") }
        if (sites.isEmpty()) {
            Toast.makeText(this, "Keine Webseiten hinterlegt.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Gespeicherte Webseiten")
            .setMessage("Öffnet die Anbieter-Webseite, nicht automatisch den gewählten Film.")
            .setItems(sites.map { it.name }.toTypedArray()) { _, index ->
                val site=sites[index]
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    putExtra("name", site.name)
                    putExtra("url", site.url)
                })
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun watchlist(): List<CatalogTitle> = runCatching {
        val raw=saved.getString("watchlist", "[]") ?: "[]"
        val list=JSONArray(raw)
        (0 until minOf(200,list.length())).mapNotNull {
            list.optJSONObject(it)?.let { obj -> CatalogTitle.fromJson(obj) }
        }
    }.getOrDefault(emptyList())

    private fun isSaved(item: CatalogTitle): Boolean =
        watchlist().any { it.identity == item.identity }

    private fun toggleSaved(item: CatalogTitle) {
        val all=watchlist().toMutableList()
        if (all.any { it.identity == item.identity }) all.removeAll { it.identity == item.identity }
        else all.add(0, item)
        val array=JSONArray()
        all.take(200).forEach { array.put(it.toJson()) }
        saved.edit().putString("watchlist", array.toString()).apply()
    }

    private fun showKeyDialog() {
        val form=Ui.vertical(this).apply { setPadding(dp(19), dp(6), dp(19), dp(7)) }
        form.addView(Ui.text(this, "TMDB API-Key (v3) oder Read Access Token (v4)",
            15f, Ui.SOFT), Ui.lp(-1, dp(54)))
        val input=EditText(this).apply {
            setText(client.credential())
            setSingleLine(true)
            hint="TMDB-Token hier einfügen"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(input, Ui.lp(-1, dp(55)))
        form.addView(Ui.text(this, "Nur lokal auf diesem Gerät gespeichert. " +
            "Keine Zugangsdaten im Quellcode hinterlegen.", 13f, Ui.MUTED),
            Ui.lp(-1, dp(70)))
        AlertDialog.Builder(this).setTitle("TMDB-Zugang einrichten")
            .setView(form)
            .setNeutralButton("API-Key erhalten") { _, _ ->
                openExternal("https://www.themoviedb.org/settings/api")
            }
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                val key=input.text.toString().trim()
                val isApiKey=key.matches(Regex("^[0-9a-fA-F]{32}$"))
                val isToken=key.startsWith("eyJ") && key.length in 100..2000 &&
                    key.matches(Regex("^[A-Za-z0-9._-]+$"))
                if(key.isNotBlank() && !isApiKey && !isToken) {
                    Toast.makeText(this, "Kein gültiges TMDB-Key/Token-Format.",
                        Toast.LENGTH_LONG).show()
                } else {
                    client.saveCredential(key)
                    showPage(Page.HOME)
                }
            }.show()
    }

    private fun showAttribution(where: LinearLayout) {
        where.addView(Ui.text(this, "Filmdaten und Bilder: TMDB · Unabhängig von TMDB",
            11f, Ui.MUTED), Ui.lp(-1, dp(34)).apply { topMargin = dp(19) })
    }

    private fun showCredits() {
        val web=WebView(this)
        web.settings.javaScriptEnabled=false
        val html=String.rawHtmlCredits()
        web.loadDataWithBaseURL("https://www.themoviedb.org/", html, "text/html", "UTF-8", null)
        val dialog=AlertDialog.Builder(this).setTitle("Credits · TMDB")
            .setView(web).setPositiveButton("Schließen", null).create()
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Kein Browser verfügbar.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        if (detail != null) showPage(page)
        else if(page != Page.HOME) showPage(Page.HOME)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        client.close()
        kodiBridge.close()
        super.onDestroy()
    }
}

/** Official TMDB branding image, displayed unmodified in Credits. */
private fun String.Companion.rawHtmlCredits(): String = """
<!doctype html><html lang="de"><head><meta name="viewport"
 content="width=device-width,initial-scale=1"></head>
<body style="background:#101725;color:#e9f2ff;font:15px sans-serif;padding:20px;">
<img alt="TMDB" style="height:36px;max-width:90%;object-fit:contain"
 src="https://www.themoviedb.org/assets/2/v4/logos/v2/blue_long_2-9665a76b1ae401a510ec1e0ca40ddcb3b0cfe45f1d51b77a308fea0845885648.svg">
<p>This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
<p>TMDB stellt Film- und Serienmetadaten sowie Poster bereit. Keine Wiedergaberechte oder Verfügbarkeitsgarantie.</p>
<p>Branding: https://www.themoviedb.org/about/logos-attribution</p>
</body></html>
""".trimIndent()
