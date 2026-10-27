package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StreamHUB V2.9 native-first home.
 * Browsing and source picking are Android TV-native; provider WebViews remain optional.
 * TMDB metadata is separate from confirmed provider playback eligibility.
 */
class NativeHubActivity : Activity() {
    private enum class Page(val title: String) {
        HOME("Deine Streamingwelt"), MOVIES("Filme"), SERIES("Serien"),
        ANIME("Anime"), LIVE("Live TV"), SEARCH("Suche"),
        WATCHLIST("Meine Liste"), RECENT("Weiterschauen"), WEB("Webquellen")
    }
    private val tmdb by lazy { TmdbClient(this) }
    private val watch by lazy { WatchStateStore(this) }
    private val sites by lazy { SiteStore(this) }
    private val covers by lazy { WebsiteCovers(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val resolving = AtomicBoolean(false)
    private var page = Page.HOME
    private var currentTitle: CatalogTitle? = null
    private var nativeDetailVisible = false
    private var currentNative: NativeMediaSource? = null
    private val updater by lazy { GithubUpdateManager(this) }
    private var generation = 0
    private var searchText = ""
    private var snapshot: NativeCatalogSnapshot? = null
    private var waitingSources = false
    private var limit = 48
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private lateinit var focusLabel: TextView
    private var feedItems = emptyList<PublicFeedEntry>()
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        showPage(Page.HOME)
        reloadIndex(false)
        loadFeeds(false)
        updater.check(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) loadFeeds(false)
    }

    private fun loadFeeds(force: Boolean) {
        PublicFeedAdapter.refresh(this, force) { entries, _ ->
            if (isDestroyed || isFinishing) return@refresh
            if (feedItems != entries) {
                feedItems = entries
                if (currentTitle == null && currentNative == null) showPage(page)
                else if (currentTitle != null) showDetail(currentTitle!!)
                else currentNative?.let { showNativeDetail(it) }
            }
        }
    }

    private fun reloadIndex(force: Boolean) {
        if (waitingSources) return
        waitingSources = true
        NativeSourceIndex.refresh(this, force) { result ->
            if (isDestroyed || isFinishing) return@refresh
            waitingSources = false
            snapshot = result
            when {
                currentTitle != null -> showDetail(currentTitle!!)
                currentNative != null -> showNativeDetail(currentNative!!)
                else -> showPage(page)
            }
        }
    }

    private fun showPage(next: Page, keepLimit: Boolean = false) {
        page = next
        currentTitle = null
        currentNative = null
        nativeDetailVisible = false
        generation++
        if (!keepLimit) limit = if (next == Page.LIVE) 80 else 48
        shell()
        when (page) {
            Page.HOME -> home()
            Page.MOVIES -> discovery("vod")
            Page.SERIES -> discovery("series")
            Page.ANIME -> discovery("anime")
            Page.LIVE -> liveTv()
            Page.SEARCH -> search()
            Page.WATCHLIST -> saved(true)
            Page.RECENT -> saved(false)
            Page.WEB -> websites()
        }
    }

    private fun shell() {
        val root = Ui.horizontal(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)
        val side = Ui.vertical(this).apply {
            setPadding(dp(14), dp(21), dp(14), dp(16))
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0xFF14263D.toInt(), 0xFF090E19.toInt()), 0f)
        }
        root.addView(side, Ui.lp(dp(207), -1))
        side.addView(Ui.text(this, "▶ STREAMHUB", 21f, Ui.TEXT, true),
            Ui.lp(-1, dp(49)))
        side.addView(Ui.text(this, "NATIVE PREMIUM · V2.9", 10f, Ui.FOCUS, true),
            Ui.lp(-1, dp(42)))
        val menu = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        val items = Ui.vertical(this)
        menu.addView(items)
        side.addView(menu, LinearLayout.LayoutParams(-1, 0, 1f))
        val labels = listOf(
            Page.HOME to "⌂  Startseite",
            Page.MOVIES to "▣  Filme",
            Page.SERIES to "▤  Serien",
            Page.ANIME to "✦  Anime",
            Page.LIVE to "📺  Live TV",
            Page.SEARCH to "⌕  Suche",
            Page.WATCHLIST to "♥  Meine Liste",
            Page.RECENT to "↺  Weiterschauen",
            Page.WEB to "◉  Anbieter & Feeds"
        )
        var selectedButton: TextView? = null
        labels.forEach { (screen, label) ->
            val button = Ui.button(this, label, true) { showPage(screen) }
            if (screen == page) {
                button.setTextColor(Ui.FOCUS)
                selectedButton = button
            }
            items.addView(button, Ui.lp(-1, dp(46)).apply {
                bottomMargin = dp(7)
            })
        }
        side.addView(Ui.button(this, "⚙  Quellen & Setup", true) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("native_entry", true))
        }, Ui.lp(-1, dp(45)))
        side.addView(Ui.text(this, "Steuerkreuz · OK · Zurück", 10f, Ui.MUTED),
            Ui.lp(-1, dp(36)))

        val right = Ui.vertical(this).apply {
            setPadding(dp(26), dp(16), dp(21), 0)
        }
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        val top = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading = Ui.text(this, page.title, 27f, Ui.TEXT, true)
        top.addView(heading, LinearLayout.LayoutParams(0, dp(55), 1f))
        top.addView(Ui.button(this, "⌕ Suchen", true) { showPage(Page.SEARCH) },
            Ui.lp(dp(129), dp(46)).apply { rightMargin = dp(9) })
        top.addView(Ui.button(this, "↻ Quellen", true) {
            reloadIndex(true)
            Toast.makeText(this, "Aktualisiere verbundene Kataloge …",
                Toast.LENGTH_SHORT).show()
        }, Ui.lp(dp(128), dp(46)))
        right.addView(top, Ui.lp(-1, dp(66)))
        focusLabel = Ui.text(this,
            "●  TV-MODUS  ·  Mit dem Steuerkreuz auswählen und OK drücken.",
            12f, Ui.FOCUS, true)
        focusLabel.setPadding(dp(14), 0, dp(14), 0)
        focusLabel.background = Ui.bg(0xFF142B43.toInt(), 8f,
            0xFF26577B.toInt(), this)
        right.addView(focusLabel, Ui.lp(-1, dp(37)).apply { bottomMargin = dp(8) })
        val pane = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        content = Ui.vertical(this).apply {
            setPadding(dp(4), dp(8), dp(13), dp(45))
            clipChildren = false
            clipToPadding = false
        }
        pane.addView(content)
        right.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        selectedButton?.requestFocus()
    }

    private fun text(value: String, size: Float = 14f, color: Int = Ui.SOFT, height: Int = 55) {
        content.addView(Ui.text(this, value, size, color), Ui.lp(-1, dp(height)))
    }

    private fun section(value: String) {
        content.addView(Ui.text(this, value, 22f, Ui.TEXT, true),
            Ui.lp(-1, dp(57)).apply { topMargin = dp(7) })
    }

    private fun home() {
        val hero = FrameLayout(this).apply {
            clipToOutline = true
            background = Ui.bg(Ui.PANEL, 18f, context = this@NativeHubActivity)
        }
        hero.addView(CinematicArtwork(this, "STREAMHUB NATIVE", "Filme", hero = true),
            FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply {
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0xF4080F1E.toInt(), 0xC8163250.toInt(), 0x33101B35),
                18f)
        }, FrameLayout.LayoutParams(-1, -1))
        val heroText = Ui.vertical(this).apply {
            setPadding(dp(28), dp(17), dp(20), dp(12))
        }
        heroText.addView(Ui.text(this, "STREAMHUB · NATIVE FIRST",
            12f, Ui.FOCUS, true), Ui.lp(-1, dp(31)))
        heroText.addView(Ui.text(this, "Alles entdecken.\nEine Oberfläche.", 32f, Ui.TEXT, true),
            Ui.lp(-1, dp(97)))
        heroText.addView(Ui.text(this,
            "Filme, Serien, Anime und deine IPTV-Kataloge. " +
                "Webseiten nur noch bei Bedarf öffnen.", 14f, Ui.SOFT),
            Ui.lp(-1, dp(44)))
        val actions = Ui.horizontal(this)
        actions.addView(Ui.button(this, "▶  Filme entdecken", true) {
            showPage(Page.MOVIES)
        }, Ui.lp(dp(202), dp(48)).apply { rightMargin = dp(9) })
        actions.addView(Ui.button(this, "📺  Live TV", true) {
            showPage(Page.LIVE)
        }, Ui.lp(dp(153), dp(48)).apply { rightMargin = dp(9) })
        actions.addView(Ui.button(this, "⌕  Alle Quellen", true) {
            showPage(Page.SEARCH)
        }, Ui.lp(dp(196), dp(48)))
        heroText.addView(actions, Ui.lp(-1, dp(54)))
        hero.addView(heroText, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, Ui.lp(-1, dp(259)).apply { bottomMargin = dp(13) })

        val saved = watch.recent(8)
        if (saved.isNotEmpty()) {
            section("↺ Weiterschauen")
            val group = saved.mapNotNull { entry ->
                snapshot?.entries?.firstOrNull { it.key == entry.id }
            }
            if (group.isNotEmpty()) nativeRail(group, 8)
            else text("Wiedergabeverlauf gespeichert · Über »Weiterschauen« erneut suchen.", 13f)
        }
        sourcesShelf()
        if (feedItems.isNotEmpty()) {
            section("Direkt eingebundene Web-Feeds")
            feedRail(feedItems.take(12))
        } else {
            content.addView(Ui.button(this, "＋  Öffentlichen Anbieter-Feed anbinden", true) {
                startActivity(Intent(this, PublicFeedActivity::class.java))
            }, Ui.lp(dp(354), dp(49)).apply { bottomMargin = dp(12) })
        }
        val websitePreviews = sites.sites().asSequence()
            .filter { SourceSearchRouter.validHomepage(it) != null }
            .flatMap { site ->
                covers.cachedPreview(site)?.entries.orEmpty().asSequence()
                    .map { site to it }
            }.take(12).toList()
        if (websitePreviews.isNotEmpty()) {
            section("Öffentliche Webquellen-Vorschauen · keine Streamgarantie")
            val host = Ui.vertical(this)
            content.addView(host)
            showWebPreviews(host, websitePreviews)
        } else {
            content.addView(Ui.button(this, "◉  Webquellen nativ entdecken", true) {
                showPage(Page.WEB)
            }, Ui.lp(dp(310), dp(48)).apply { bottomMargin = dp(13) })
        }
        if (tmdb.hasCredential()) {
            tmdbShelf("Beliebte Filme", "/movie/popular", "movie")
            tmdbShelf("Serien-Highlights", "/tv/popular", "tv")
            tmdbShelf("Anime entdecken", "/discover/tv", "tv",
                mapOf("with_genres" to "16", "with_original_language" to "ja",
                    "sort_by" to "popularity.desc", "include_adult" to "false"))
        } else tmdbKeyNotice()
        text("Filmdaten/Poster: TMDB. Anbieter-Katalogtreffer sind noch keine " +
            "Garantie, dass ein Stream funktioniert.", 12f, Ui.MUTED, 80)
    }

    private fun sourcesShelf() {
        section("Aus deinen verbundenen Quellen")
        val snapshot = snapshot
        if (snapshot == null) {
            text("Lese deine konfigurierten IPTV-Kataloge …", 13f, Ui.MUTED)
            return
        }
        if (snapshot.entries.isEmpty()) {
            text("Noch keine Inhalte geladen. Verbinde in »Einstellungen« ein " +
                "M3U-, Xtream- oder MAC-Portal.", 14f, Ui.SOFT, 80)
        } else {
            val films = snapshot.byKind("vod").take(12)
            val series = snapshot.byKind("series").take(12)
            if (films.isNotEmpty()) {
                section("Filme deiner Anbieter")
                nativeRail(films, 12)
            }
            if (series.isNotEmpty()) {
                section("Serien deiner Anbieter")
                nativeRail(series, 12)
            }
            val channels = snapshot.byKind("itv").take(12)
            if (channels.isNotEmpty()) {
                section("Deine Live-TV-Sender")
                nativeRail(channels, 12)
            }
        }
        if (snapshot.errors.isNotEmpty()) {
            text("Einige Quellen waren nicht erreichbar: " +
                snapshot.errors.take(2).joinToString(" · ").take(150),
                12f, Ui.MUTED, 76)
        }
    }

    private fun tmdbKeyNotice() {
        section("Filme und Poster aktivieren")
        text("Ein eigener TMDB API-Key aktiviert Filmcover, Beschreibungen und " +
            "Katalogentdeckung. Deine verbundenen IPTV-Kataloge funktionieren " +
            "auch ohne TMDB-Key.", 14f, Ui.SOFT, 84)
        content.addView(Ui.button(this, "TMDB API-Key einrichten", true) {
            keyDialog()
        }, Ui.lp(dp(255), dp(48)).apply { bottomMargin = dp(12) })
    }

    private fun keyDialog() {
        val field = EditText(this).apply {
            hint = "TMDB API-Key oder Read Access Token"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(tmdb.credential())
        }
        val form = Ui.vertical(this).apply { setPadding(dp(18), dp(8), dp(18), dp(9)) }
        form.addView(field, Ui.lp(-1, dp(60)))
        AlertDialog.Builder(this).setTitle("TMDB für Poster & Kataloge")
            .setView(form).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                tmdb.saveCredential(field.text.toString().trim())
                showPage(page)
            }.show()
    }

    private fun discovery(kind: String) {
        val bucket = if (kind == "anime") "series"
            else kind
        section(if (kind == "anime") "Anime entdecken" else
            if (kind == "vod") "Deine Filme" else "Deine Serien")
        val candidates = snapshot?.entries?.filter {
            it.kind == bucket && kind != "anime"
        }.orEmpty()
        if (kind == "anime") {
            text("Anime aus TMDB entdecken; IPTV-Inhalte werden nur bei " +
                "eindeutigen Titeltreffern zugeordnet. Nicht jeder Anbieter " +
                "markiert Anime gesondert.", 13f, Ui.MUTED, 74)
        }
        if (candidates.isNotEmpty()) {
            nativeGrid(candidates.distinctBy { NativeSourceIndex.normalized(it.title) },
                limit)
        } else if (snapshot == null) text("Lade verbundene Anbieter …", 14f)
        else if (kind != "anime") text("In deinen verbundenen Portalen wurden " +
            "hier noch keine Inhalte geladen.", 14f)
        if (tmdb.hasCredential()) {
            val path = when (kind) {
                "vod" -> "/movie/popular"
                "series" -> "/tv/popular"
                else -> "/discover/tv"
            }
            val options = if (kind == "anime")
                mapOf("with_genres" to "16", "with_original_language" to "ja",
                    "sort_by" to "popularity.desc", "include_adult" to "false")
                else emptyMap()
            tmdbShelf("Entdecken · TMDB (Verfügbarkeit separat prüfen)",
                path, if (kind == "vod") "movie" else "tv", options)
        } else tmdbKeyNotice()
    }

    private fun liveTv() {
        section("Sender aus deinen IPTV-Zugängen")
        content.addView(Ui.button(this, "↗ Live-TV-Verwaltung & EPG", true) {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }, Ui.lp(dp(290), dp(46)).apply { bottomMargin = dp(13) })
        val items = snapshot?.byKind("itv").orEmpty()
        if (items.isEmpty()) text("Noch keine TV-Kanäle eingelesen oder Anbieter nicht erreichbar.",
            14f, Ui.SOFT, 92)
        else nativeGrid(items, limit)
    }

    private fun search() {
        section("Alles in einer Suche")
        val input = EditText(this).apply {
            hint = "Film, Serie, Anime oder Sender"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(searchText)
        }
        val form = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        form.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            rightMargin = dp(12)
        })
        form.addView(Ui.button(this, "⌕ Jetzt suchen", true) {
            searchText = input.text.toString().trim().take(90)
            showPage(Page.SEARCH)
        }, Ui.lp(dp(195), dp(50)))
        content.addView(form, Ui.lp(-1, dp(70)))
        text("Suche lokal im geladenen IPTV-Katalog und optional im TMDB-Katalog. " +
            "Externe Webseiten werden nicht als bestätigte Streamingquellen ausgegeben.",
            12f, Ui.MUTED, 65)
        if (searchText.length < 2) return
        val query = NativeSourceIndex.normalized(searchText)
        val matches = snapshot?.entries?.filter {
            NativeSourceIndex.normalized(it.title).contains(query)
        }.orEmpty()
        if (matches.isNotEmpty()) {
            section("Treffer deiner IPTV-Anbieter")
            nativeGrid(uniqueOffers(matches), 48)
        } else text("Keine Treffer im derzeit geladenen IPTV-Ausschnitt.", 13f, Ui.MUTED)
        val feedHits = feedItems.filter {
            NativeSourceIndex.normalized(it.title).contains(query)
        }.take(24)
        if (feedHits.isNotEmpty()) {
            section("Treffer aus deinen öffentlichen Anbieter-Feeds")
            feedRail(feedHits)
        }
        val webHits = sites.sites().asSequence()
            .filter { SourceSearchRouter.validHomepage(it) != null }
            .flatMap { site ->
                covers.cachedPreview(site)?.entries.orEmpty().asSequence()
                    .filter { NativeSourceIndex.normalized(it.title).contains(query) }
                    .map { site to it }
            }.take(16).toList()
        if (webHits.isNotEmpty()) {
            section("Öffentliche Webseiten-Vorschauen · kein bestätigter Stream")
            webHits.forEach { (site, preview) ->
                content.addView(Ui.button(this,
                    "↗ " + preview.title.take(74) + " · " + site.name, true) {
                    openPreview(site, preview)
                }, Ui.lp(-1, dp(52)).apply { bottomMargin = dp(8) })
            }
        }
        if (tmdb.hasCredential()) tmdbShelf(
            "Titel aus TMDB", "/search/multi", "",
            mapOf("query" to searchText, "include_adult" to "false"))
        else tmdbKeyNotice()
        content.addView(Ui.button(this, "Weitere Portal-Katalogseiten durchsuchen", true) {
            startActivity(Intent(this, UnifiedSearchActivity::class.java)
                .putExtra("prefill_query", searchText))
        }, Ui.lp(dp(340), dp(48)))
    }

    private fun saved(favorite: Boolean) {
        section(if (favorite) "♥ Gemerkte IPTV-Titel" else "↺ Zuletzt angesehen")
        val entries = if (favorite) watch.favorites() else watch.recent()
        if (entries.isEmpty()) {
            text("Noch keine Einträge. Öffne einen IPTV-Titel und wähle »Merken« " +
                "oder starte einen Film.", 14f, Ui.SOFT, 106)
            return
        }
        entries.take(60).forEach { entry ->
            val offer = snapshot?.entries?.firstOrNull { it.key == entry.id }
            content.addView(Ui.button(this,
                "▶ " + entry.title + if (offer == null) "  ·  neu suchen" else
                    if (entry.durationMs > 0L)
                        "  ·  " + (entry.positionMs * 100 / entry.durationMs) + "%" else "", true) {
                if (offer != null) showNativeDetail(offer)
                else {
                    searchText = entry.title
                    showPage(Page.SEARCH)
                }
            }, Ui.lp(-1, dp(56)).apply { bottomMargin = dp(8) })
        }
    }

    private fun websites() {
        section("Deine externen Dienste und Webseiten")
        content.addView(Ui.button(this, "＋  Native RSS-/Atom-/JSON-Feeds verwalten", true) {
            startActivity(Intent(this, PublicFeedActivity::class.java))
        }, Ui.lp(dp(375), dp(52)).apply { bottomMargin = dp(14) })
        if (feedItems.isNotEmpty()) {
            section("Katalog aus deinen eingebundenen Feeds")
            feedRail(feedItems.take(24))
        }
        text("Diese Quellen sind in StreamHUB verwaltet. Ihre Inhalte können nur " +
            "bei geeigneten, zugänglichen Schnittstellen nativ angezeigt werden. " +
            "Ein gespeicherter Webseiten-Link bestätigt keine Filmverfügbarkeit.",
            14f, Ui.SOFT, 92)
        val list = sites.sites().filter { SourceSearchRouter.validHomepage(it) != null }
            .sortedWith(compareByDescending<Site> { it.favorite }
                .thenBy { it.section.ordinal }.thenBy { it.name })
        section("Öffentliche Cover-Vorschauen")
        val previews = Ui.vertical(this)
        content.addView(previews)
        val already = list.flatMap { site ->
            covers.cachedPreview(site)?.entries.orEmpty().map { site to it }
        }.take(20)
        if (already.isNotEmpty()) showWebPreviews(previews, already)
        previews.addView(Ui.button(this, "↻  Webseiten-Vorschauen aktualisieren", true) {
            fetchWebPreviews(previews, list.take(6))
        }, Ui.lp(dp(315), dp(49)).apply { bottomMargin = dp(18) })
        text("Cover stammen aus öffentlich sichtbaren Seiten-Metadaten; " +
            "es findet keine automatische Stream-Extraktion statt.",
            12f, Ui.MUTED, 69)
        for (group in list.groupBy { it.section }) {
            section(group.key.label)
            group.value.chunked(4).forEach { chunk ->
                val row = Ui.horizontal(this).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                chunk.forEach { site ->
                    row.addView(webCard(site), LinearLayout.LayoutParams(0, dp(161), 1f)
                        .apply { rightMargin = dp(12); bottomMargin = dp(12) })
                }
                repeat(4 - chunk.size) {
                    row.addView(View(this),
                        LinearLayout.LayoutParams(0, dp(161), 1f).apply {
                            rightMargin = dp(12)
                        })
                }
                content.addView(row, Ui.lp(-1, dp(177)))
            }
        }
    }

    private fun fetchWebPreviews(host: LinearLayout, selections: List<Site>) {
        host.removeAllViews()
        host.addView(Ui.text(this, "Lade öffentliche Cover-Vorschauen …", 13f, Ui.SOFT),
            Ui.lp(-1, dp(54)))
        if (selections.isEmpty()) return
        val request = generation
        val gathered = mutableListOf<Pair<Site, DiscoveredCover>>()
        var remaining = selections.size
        selections.forEach { site ->
            covers.request(site) { result ->
                if (request != generation || isDestroyed || isFinishing) return@request
                gathered.addAll(result.entries.take(8).map { site to it })
                remaining--
                if (remaining == 0) {
                    host.removeAllViews()
                    val found = gathered.distinctBy { it.second.pageUrl }.take(24)
                    if (found.isNotEmpty()) showWebPreviews(host, found)
                    else host.addView(Ui.text(this,
                        "Diese Seiten haben keine unterstützten öffentlichen Coverdaten geliefert.",
                        13f, Ui.MUTED), Ui.lp(-1, dp(66)))
                    host.addView(Ui.button(this, "↻ Erneut prüfen", true) {
                        fetchWebPreviews(host, selections)
                    }, Ui.lp(dp(215), dp(48)).apply { bottomMargin = dp(14) })
                }
            }
        }
    }

    private fun showWebPreviews(
        host: LinearLayout, items: List<Pair<Site, DiscoveredCover>>
    ) {
        val rail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
        }
        val row = Ui.horizontal(this).apply {
            setPadding(dp(5), dp(7), dp(6), dp(15))
            clipChildren = false
            clipToPadding = false
        }
        items.take(24).forEach { (site, preview) ->
            val card = FrameLayout(this).apply {
                background = Ui.bg(Ui.PANEL, 13f, context = this@NativeHubActivity)
            }
            card.addView(CinematicArtwork(this, preview.title, site.section.label),
                FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(image, FrameLayout.LayoutParams(-1, -1))
            covers.loadInto(image, preview.imageUrl)
            card.addView(View(this).apply {
                background = Ui.gradient(this@NativeHubActivity,
                    intArrayOf(0x150A0F1D, 0xDD090E19.toInt()),
                    13f, GradientDrawable.Orientation.TOP_BOTTOM)
            }, FrameLayout.LayoutParams(-1, -1))
            val labels = Ui.vertical(this).apply {
                setPadding(dp(9), dp(7), dp(9), dp(10))
            }
            labels.addView(Ui.text(this, preview.title, 15f, Ui.TEXT, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, Ui.lp(-1, dp(51)))
            labels.addView(Ui.text(this, "↗ " + site.name + " · WEB", 10f, Ui.FOCUS),
                Ui.lp(-1, dp(26)))
            card.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            Ui.focusCard(card, 13f)
            card.setOnClickListener { openPreview(site, preview) }
            row.addView(card, Ui.lp(dp(159), dp(228)).apply { rightMargin = dp(13) })
        }
        rail.addView(row)
        host.addView(rail, Ui.lp(-1, dp(262)))
    }

    private fun openPreview(site: Site, preview: DiscoveredCover) {
        sites.addVisit(site.name, preview.pageUrl)
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra("name", preview.title.take(100))
            putExtra("url", preview.pageUrl)
            putExtra("site_id", site.id)
        })
    }

    private fun webCard(site: Site): View {
        val tile = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 15f, context = this@NativeHubActivity)
        }
        tile.addView(CinematicArtwork(this, site.name, site.section.label),
            FrameLayout.LayoutParams(-1, -1))
        tile.addView(View(this).apply {
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0x44101026, 0xF1080E1A.toInt()), 15f,
                GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val labels = Ui.vertical(this).apply {
            setPadding(dp(13), dp(13), dp(12), dp(12))
        }
        labels.addView(Ui.text(this, site.name, 19f, Ui.TEXT, true),
            Ui.lp(-1, dp(60)))
        labels.addView(Ui.text(this, "↗  WEBQUELLE", 12f, Ui.FOCUS, true),
            Ui.lp(-1, dp(28)))
        tile.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        Ui.focusCard(tile, 15f)
        tile.setOnClickListener {
            openBrowser(site)
        }
        return tile
    }

    private fun openBrowser(site: Site) {
        sites.addVisit(site.name, site.url)
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra("site_id", site.id)
            putExtra("name", site.name)
            putExtra("url", site.url)
        })
    }

    private fun tmdbShelf(
        heading: String, path: String, kind: String,
        params: Map<String, String> = emptyMap()
    ) {
        val current = generation
        val host = Ui.vertical(this)
        section(heading)
        content.addView(host)
        host.addView(Ui.text(this, "Lade Filmdaten …", 13f, Ui.MUTED),
            Ui.lp(-1, dp(60)))
        tmdb.request(path, params) { outcome ->
            if (current != generation || isDestroyed || isFinishing) return@request
            host.removeAllViews()
            outcome.fold(onSuccess = { response ->
                val titles = TmdbClient.entries(response, kind)
                if (titles.isEmpty()) {
                    host.addView(Ui.text(this, "Keine Titel gefunden.", 13f, Ui.MUTED),
                        Ui.lp(-1, dp(60)))
                } else tmdbRail(host, titles)
            }, onFailure = { error ->
                host.addView(Ui.text(this,
                    "TMDB: " + (error.message ?: "Katalog nicht erreichbar"),
                    13f, Ui.MUTED), Ui.lp(-1, dp(68)))
            })
        }
    }

    private fun tmdbRail(host: LinearLayout, titles: List<CatalogTitle>) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = Ui.horizontal(this).apply {
            setPadding(dp(5), dp(6), dp(6), dp(16))
            clipToPadding = false
            clipChildren = false
        }
        titles.take(24).forEach { film ->
            row.addView(tmdbCard(film), Ui.lp(dp(163), dp(246)).apply {
                rightMargin = dp(15)
            })
        }
        scroll.addView(row)
        host.addView(scroll, Ui.lp(-1, dp(283)))
    }

    private fun tmdbCard(item: CatalogTitle): View {
        val card = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 14f, context = this@NativeHubActivity)
            clipToOutline = false
        }
        card.addView(CinematicArtwork(this, item.name,
            if (item.kind == "tv") "Serien" else "Filme"),
            FrameLayout.LayoutParams(-1, -1))
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(poster, FrameLayout.LayoutParams(-1, -1))
        TmdbArtwork.load(poster, item.poster)
        card.addView(View(this).apply {
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0x080A1020, 0x6608101D, 0xF2090E18.toInt()),
                14f, GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val text = Ui.vertical(this).apply {
            setPadding(dp(11), 0, dp(9), dp(11))
        }
        text.addView(Ui.text(this, item.name, 16f, Ui.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(-1, dp(52)))
        text.addView(Ui.text(this, item.year + " · TMDB",
            11f, Ui.SOFT), Ui.lp(-1, dp(24)))
        card.addView(text, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        Ui.focusCard(card, 14f, 1.075f)
        card.setOnClickListener { showDetail(item) }
        return card
    }

    private fun uniqueOffers(items: List<NativeMediaSource>): List<NativeMediaSource> =
        items.distinctBy { it.kind + ":" + NativeSourceIndex.normalized(it.title) }

    private fun nativeRail(items: List<NativeMediaSource>, count: Int = 12) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = Ui.horizontal(this).apply {
            setPadding(dp(5), dp(6), dp(5), dp(16))
            clipToPadding = false
            clipChildren = false
        }
        uniqueOffers(items).take(count).forEach { native ->
            row.addView(nativeCard(native), Ui.lp(dp(177), dp(182)).apply {
                rightMargin = dp(14)
            })
        }
        scroll.addView(row)
        content.addView(scroll, Ui.lp(-1, dp(212)))
    }

    private fun nativeGrid(items: List<NativeMediaSource>, maxCount: Int) {
        val distinct = uniqueOffers(items)
        val visible = distinct.take(maxCount)
        visible.chunked(4).forEach { four ->
            val row = Ui.horizontal(this).apply {
                clipToPadding = false
                clipChildren = false
            }
            four.forEach { item ->
                row.addView(nativeCard(item), LinearLayout.LayoutParams(0, dp(163), 1f)
                    .apply { rightMargin = dp(13); bottomMargin = dp(13) })
            }
            repeat(4 - four.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(163), 1f)
                    .apply { rightMargin = dp(13) })
            }
            content.addView(row, Ui.lp(-1, dp(180)))
        }
        if (distinct.size > maxCount) {
            content.addView(Ui.button(this,
                "Weitere Titel anzeigen · " + (distinct.size - maxCount) + " verbleibend", true) {
                limit = maxCount + 48
                showPage(page, keepLimit = true)
            }, Ui.lp(dp(350), dp(49)).apply { bottomMargin = dp(15) })
        }
    }

    private fun nativeCard(item: NativeMediaSource): View {
        val card = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 14f, context = this@NativeHubActivity)
        }
        card.addView(CinematicArtwork(this, item.title,
            if (item.kind == "series") "Serien" else if (item.kind == "itv") "Home" else "Filme"),
            FrameLayout.LayoutParams(-1, -1))
        card.addView(View(this).apply {
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0x300B1423, 0xC5090E1B.toInt()),
                14f, GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val label = Ui.vertical(this).apply {
            setPadding(dp(12), dp(8), dp(10), dp(12))
        }
        label.addView(Ui.text(this,
            when (item.kind) {
                "itv" -> "● LIVE TV"
                "series" -> "▤ SERIE"
                else -> "▶ FILM"
            }, 11f, Ui.FOCUS, true), Ui.lp(-1, dp(29)))
        label.addView(Ui.text(this, item.title, 17f, Ui.TEXT, true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(-1, dp(69)))
        val sourceCount = snapshot?.lookup(item.kind, item.title)?.size ?: 1
        label.addView(Ui.text(this,
            if (sourceCount > 1) sourceCount.toString() + " QUELLEN"
            else item.provider, 11f, Ui.FOCUS, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(-1, dp(24)))
        card.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val mediaArt = item.mac?.image.orEmpty().ifBlank {
            item.xtream?.raw?.optString("stream_icon").orEmpty().ifBlank {
                item.xtream?.raw?.optString("cover").orEmpty()
            }
        }
        if (mediaArt.startsWith("https://")) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(image, 1, FrameLayout.LayoutParams(-1, -1))
            covers.loadInto(image, mediaArt)
        }
        Ui.focusCard(card, 14f, 1.085f) { focused ->
            if (focused && ::focusLabel.isInitialized) {
                val count = snapshot?.lookup(item.kind, item.title)?.size ?: 1
                focusLabel.text = "●  AUSGEWÄHLT: " + item.title.take(52) +
                    "  ·  " + count + " Quelle(n)  ·  OK für Details"
            }
        }
        card.setOnClickListener { showNativeDetail(item) }
        return card
    }

    private fun showNativeDetail(item: NativeMediaSource) {
        currentTitle = null
        currentNative = item
        nativeDetailVisible = true
        generation++
        shell()
        heading.text = item.title
        section("In deinem Katalog · " + item.provider)
        val kind = when (item.kind) {
            "itv" -> "LIVE-TV-SENDER"
            "series" -> "SERIE"
            else -> "FILM"
        }
        text(kind + " · Native Quellenwahl in StreamHUB", 15f, Ui.FOCUS, 43)
        val matches = snapshot?.lookup(item.kind, item.title).orEmpty()
            .ifEmpty { listOf(item) }
        text(matches.size.toString() + " Katalogeintrag/Einträge mit diesem Titel. " +
            "Die tatsächliche Abspielbarkeit hängt von deinem Anbieter ab.",
            14f, Ui.SOFT, 76)
        renderOfferPicker(matches, item.title)
        content.addView(Ui.button(this, if (watch.get(item.key)?.favorite == true)
            "♥ Aus Merkliste entfernen" else "♡ In Merkliste speichern", true) {
            val changed = watch.setFavorite(item.key, item.title, item.kind)
            Toast.makeText(this, if (changed) "Gemerkt" else "Entfernt",
                Toast.LENGTH_SHORT).show()
            showNativeDetail(item)
        }, Ui.lp(dp(275), dp(50)).apply { bottomMargin = dp(14) })
        content.addView(Ui.button(this, "← Zurück zur Übersicht", true) {
            showPage(page)
        }, Ui.lp(dp(260), dp(49)))
        if (tmdb.hasCredential() && item.kind != "itv") {
            section("Metadaten ergänzen")
            text("TMDB-Zuordnung erfolgt nur bei einem eindeutigen, exakt " +
                "übereinstimmenden Titel. Ein Treffer bestätigt noch keine Stream-Rechte.",
                13f, Ui.MUTED, 78)
            val gen = generation
            tmdb.request("/search/multi", mapOf("query" to item.title,
                "include_adult" to "false")) { result ->
                if (gen != generation || isDestroyed || isFinishing) return@request
                val target = if (item.kind == "vod") "movie" else "tv"
                val found = result.getOrNull()?.let(TmdbClient::entries).orEmpty().filter {
                    it.kind == target &&
                        NativeSourceIndex.normalized(it.name) ==
                            NativeSourceIndex.normalized(item.title)
                }
                if (found.size == 1) {
                    content.addView(Ui.button(this,
                        "▣ Film-/Seriendetails zu »" + found.first().name.take(35) + "«", true) {
                        showDetail(found.first())
                    }, Ui.lp(-1, dp(49)))
                }
            }
        }
    }

    private fun showDetail(title: CatalogTitle) {
        currentTitle = title
        currentNative = null
        nativeDetailVisible = true
        generation++
        shell()
        heading.text = title.name
        val hero = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 16f, context = this@NativeHubActivity)
        }
        hero.addView(CinematicArtwork(this, title.name, "Filme", hero = true),
            FrameLayout.LayoutParams(-1, -1))
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        hero.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        TmdbArtwork.load(backdrop, title.backdrop, backdrop = true)
        hero.addView(View(this).apply {
            background = Ui.gradient(this@NativeHubActivity,
                intArrayOf(0xF90A1020.toInt(), 0xCB101F33.toInt(), 0x440B1220),
                16f)
        }, FrameLayout.LayoutParams(-1, -1))
        val details = Ui.vertical(this).apply {
            setPadding(dp(24), dp(17), dp(16), dp(13))
        }
        details.addView(Ui.text(this,
            if (title.kind == "movie") "STREAMHUB · FILM" else "STREAMHUB · SERIE",
            12f, Ui.FOCUS, true), Ui.lp(-1, dp(29)))
        details.addView(Ui.text(this, title.name, 33f, Ui.TEXT, true),
            Ui.lp(-1, dp(85)))
        details.addView(Ui.text(this,
            title.year + " · ★ " +
                "%.1f".format(java.util.Locale.GERMANY, title.rating) +
                " / 10 · TMDB", 14f, Ui.SOFT), Ui.lp(-1, dp(33)))
        hero.addView(details, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, Ui.lp(-1, dp(205)))
        text(title.summary.ifBlank { "Für diesen Titel ist keine Beschreibung vorhanden." },
            15f, Ui.SOFT, 116)

        val kind = if (title.kind == "movie") "vod" else "series"
        val matchingOffers = snapshot?.lookup(kind, title.name).orEmpty()
        section("▶ Quellen für diesen Titel")
        if (matchingOffers.isNotEmpty()) {
            text("Katalogtreffer mit gleichem Namen – Anbieterzugriff und Abspielbarkeit " +
                "noch nicht geprüft. Bitte Quelle auswählen.", 12f, Ui.MUTED, 65)
            renderOfferPicker(matchingOffers, title.name)
        } else {
            text("Kein Treffer im bisher geladenen IPTV-Ausschnitt. Das bedeutet nicht, " +
                "dass der Titel nirgendwo verfügbar ist.", 14f, Ui.MUTED, 78)
        }
        content.addView(Ui.button(this, "Weitere IPTV-Kataloge durchsuchen", true) {
            startActivity(Intent(this, UnifiedSearchActivity::class.java)
                .putExtra("prefill_query", title.name))
        }, Ui.lp(dp(306), dp(49)).apply { bottomMargin = dp(9) })
        content.addView(Ui.button(this, "Webseiten manuell nach Titel durchsuchen", true) {
            startActivity(Intent(this, SourceDirectoryActivity::class.java).apply {
                putExtra("title", title.name)
                putExtra("kind", title.kind)
                putExtra("year", title.year)
                putExtra("tmdb_id", title.id)
            })
        }, Ui.lp(dp(360), dp(49)).apply { bottomMargin = dp(10) })
        content.addView(Ui.button(this, "⌂ Zurück zur Übersicht", true) {
            showPage(page)
        }, Ui.lp(dp(249), dp(49)))
        text("Filmdaten: TMDB. StreamHUB überprüft keine externen Webseiten " +
            "automatisch auf Wiedergaberecht oder Verfügbarkeit.",
            12f, Ui.MUTED, 77)
    }

    private fun renderOfferPicker(offers: List<NativeMediaSource>, title: String) {
        offers.distinctBy { it.key }.take(16).forEach { offer ->
            content.addView(Ui.button(this,
                "▶ " + offer.provider + " · " + offer.title.take(65), true) {
                openNative(offer)
            }, Ui.lp(-1, dp(53)).apply { bottomMargin = dp(9) })
        }
    }

    private fun openNative(offer: NativeMediaSource) {
        if (offer.m3u != null) {
            startActivity(Intent(this, LiveTvPlayerActivity::class.java)
                .putExtra("channel_id", offer.m3u.id))
            return
        }
        if (!resolving.compareAndSet(false, true)) {
            Toast.makeText(this, "Ein Stream wird bereits vorbereitet.",
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Lade Stream von " + offer.provider + " …",
            Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching {
                if (offer.xtream != null) {
                    val account = XtreamStore(this).load()
                        ?: error("Xtream-Zugang ist nicht mehr gespeichert.")
                    val client = XtreamClient(account)
                    client.verify()
                    if (offer.xtream.kind == "series") {
                        val episodes = client.episodes(offer.xtream)
                        PlaybackOptions.Xtream(account, client, episodes)
                    } else PlaybackOptions.Direct(
                        client.url(offer.xtream), offer.title, offer.kind,
                        account.allowHttp, offer.key)
                } else if (offer.mac != null) {
                    val profile = MacPortalStore(this).active()
                        ?.takeIf { it.id == offer.profileId }
                        ?: error("Dieses MAC-Profil ist nicht mehr aktiv. " +
                            "Bitte aktiviere es in der Profilverwaltung.")
                    val client = MacPortalClient(profile.source)
                    client.connect()
                    if (offer.mac.type == "series") {
                        PlaybackOptions.Mac(profile, client, client.episodes(offer.mac))
                    } else PlaybackOptions.Direct(
                        client.playback(offer.mac), offer.title, offer.kind,
                        profile.source.allowHttp, offer.key)
                } else error("Keine native Quelle verfügbar.")
            }
            runOnUiThread {
                resolving.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(onSuccess = { choices ->
                    when (choices) {
                        is PlaybackOptions.Direct -> play(choices)
                        is PlaybackOptions.Xtream -> if (choices.episodes.isEmpty()) {
                            warn("Dieses Portal liefert keine unterstützten Serienfolgen.")
                        } else AlertDialog.Builder(this)
                            .setTitle(offer.title + " · Episoden")
                            .setItems(choices.episodes.map { it.name }.toTypedArray()) { _, n ->
                                val episode = choices.episodes[n]
                                val direct = runCatching {
                                    choices.client.url(episode)
                                }.getOrNull()
                                if (direct == null) warn("Diese Episode konnte nicht geöffnet werden.")
                                else play(PlaybackOptions.Direct(
                                    direct, episode.name, "vod",
                                    choices.account.allowHttp, offer.key + ":" + episode.id))
                            }.setNegativeButton("Abbrechen", null).show()
                        is PlaybackOptions.Mac -> if (choices.episodes.isEmpty()) {
                            warn("Dieses Portal liefert keine unterstützten Serienfolgen.")
                        } else AlertDialog.Builder(this)
                            .setTitle(offer.title + " · Episoden")
                            .setItems(choices.episodes.map { it.name }.toTypedArray()) { _, n ->
                                playMacEpisode(offer.key, choices, choices.episodes[n])
                            }.setNegativeButton("Abbrechen", null).show()
                    }
                }, onFailure = { warn(it.message ?: "Der Anbieter ist nicht erreichbar.") })
            }
        }
    }

    private sealed class PlaybackOptions {
        data class Direct(
            val url: String, val title: String, val kind: String,
            val allowHttp: Boolean, val historyKey: String
        ) : PlaybackOptions()
        data class Xtream(
            val account: XtreamSource,
            val client: XtreamClient,
            val episodes: List<XtreamTitle>
        ) : PlaybackOptions()
        data class Mac(
            val profile: MacPortalProfile,
            val client: MacPortalClient,
            val episodes: List<MacPortalMedia>
        ) : PlaybackOptions()
    }

    private fun playMacEpisode(
        parentKey: String, choice: PlaybackOptions.Mac, media: MacPortalMedia
    ) {
        if (!resolving.compareAndSet(false, true)) return
        io.execute {
            val result = runCatching { choice.client.playback(media) }
            runOnUiThread {
                resolving.set(false)
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold(onSuccess = { url ->
                    play(PlaybackOptions.Direct(url, media.name, "vod",
                        choice.profile.source.allowHttp,
                        parentKey + ":" + media.id + ":" + media.episode))
                }, onFailure = { warn(it.message ?: "Episode nicht abspielbar.") })
            }
        }
    }

    private fun play(result: PlaybackOptions.Direct) {
        startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
            putExtra("direct_stream_url", result.url)
            putExtra("direct_stream_title", result.title)
            putExtra("direct_stream_is_live", result.kind == "itv")
            putExtra("direct_allow_http", result.allowHttp)
            putExtra("history_id", result.historyKey)
            putExtra("history_kind", result.kind)
        })
    }

    private fun warn(message: String) {
        AlertDialog.Builder(this).setTitle("Quelle nicht verfügbar")
            .setMessage(message.take(280))
            .setPositiveButton("OK", null).show()
    }

    @Deprecated("Android TV navigation")
    override fun onBackPressed() {
        if (nativeDetailVisible) showPage(page)
        else if (page != Page.HOME) showPage(Page.HOME)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        updater.shutdown()
        tmdb.close()
        covers.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }
}
