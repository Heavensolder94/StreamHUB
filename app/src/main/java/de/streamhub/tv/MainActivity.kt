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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.ImageView
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
        COVERS("Cover entdecken", "Film- und Anime-Cover aus deinen Webseiten"),
        PLAYER("DeVideoSRC", "Filme und Serien direkt öffnen"),
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
    private val covers by lazy { WebsiteCovers(this) }
    private var forceCoverRefresh = false
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
        val root = Ui.horizontal(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)

        val sidebar = Ui.vertical(this).apply {
            setPadding(dp(19), dp(23), dp(16), dp(19))
            background = Ui.gradient(this@MainActivity, intArrayOf(0xFF10192A.toInt(), 0xFF0A101B.toInt()), 0f)
        }
        root.addView(sidebar, Ui.lp(dp(198), -1))

        val branding = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = Ui.text(this, "▶", 31f, Ui.BLUE, true)
        branding.addView(logo, Ui.lp(dp(38), dp(48)))
        val names = Ui.vertical(this)
        names.addView(Ui.text(this, "STREAMHUB", 22f, Ui.TEXT, true))
        names.addView(Ui.text(this, "PREMIUM EDITION", 10f, Ui.BLUE, true))
        branding.addView(names, Ui.lp(-1, dp(51)))
        sidebar.addView(branding, Ui.lp(-1, dp(82)))
        sidebar.addView(Ui.button(this, "▣  FILM-BIBLIOTHEK", true) {
            startActivity(Intent(this, MediaLibraryActivity::class.java))
        }, Ui.lp(-1, dp(50)).apply { bottomMargin = dp(13) })
        sidebar.addView(Ui.button(this, "📺  LIVE TV", true) {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }, Ui.lp(-1, dp(50)).apply { bottomMargin = dp(8) })
        sidebar.addView(Ui.button(this, "⌕  ALLE QUELLEN", true) {
            startActivity(Intent(this, UnifiedSearchActivity::class.java))
        }, Ui.lp(-1, dp(50)).apply { bottomMargin = dp(13) })
        sidebar.addView(Ui.text(this, "ENTDECKEN", 10f, Ui.MUTED, true),
            Ui.lp(-1, dp(31)))

        val navs = listOf(
            Tab.HOME to "⌂", Tab.COVERS to "▧", Tab.PLAYER to "▶", Tab.SEARCH to "⌕", Tab.ANIME to "✦",
            Tab.FILME to "▣", Tab.SERIEN to "▤", Tab.MIX to "◈",
            Tab.FAVORITES to "♥", Tab.HISTORY to "↺", Tab.MANAGE to "⚙"
        )
        // Keep all sidebar entries reachable on smaller Android TV screens.
        val navScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val navColumn = Ui.vertical(this)
        navScroll.addView(navColumn)
        sidebar.addView(navScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val firstNav = arrayOfNulls<View>(1)
        navs.forEach { (choice, symbol) ->
            val selected = choice == tab
            val entry = Ui.text(this, "$symbol   ${choice.label}", 15f,
                if (selected) Ui.TEXT else Ui.SOFT, selected)
            entry.isFocusable = true
            entry.isClickable = true
            entry.setPadding(dp(14), 0, dp(5), 0)
            entry.background = Ui.bg(if (selected) 0xFF1D3859.toInt() else Color.TRANSPARENT,
                11f, if (selected) 0xFF366E9C.toInt() else null, this)
            entry.setOnFocusChangeListener { view, focused ->
                view.background = Ui.bg(
                    if (focused) Ui.BLUE else if (selected) 0xFF1D3859.toInt() else Color.TRANSPARENT,
                    11f, if (focused) Color.WHITE else if (selected) 0xFF366E9C.toInt() else null, this)
                entry.setTextColor(if (focused) Ui.BG else if (selected) Ui.TEXT else Ui.SOFT)
                view.animate().scaleX(if (focused) 1.045f else 1f)
                    .scaleY(if (focused) 1.045f else 1f).setDuration(145).start()
            }
            entry.setOnClickListener { showTab(choice) }
            navColumn.addView(entry, Ui.lp(-1, dp(43)).apply { bottomMargin = dp(6) })
            if (choice == tab) firstNav[0] = entry
        }
        sidebar.addView(Ui.text(this, "STREAMHUB  2.7", 11f, Ui.MUTED, true), Ui.lp(-1, dp(20)))
        sidebar.addView(Ui.text(this, "Deine Seiten. Deine Regeln.", 10f, Ui.MUTED), Ui.lp(-1, dp(29)))

        val column = Ui.vertical(this).apply { setPadding(dp(24), dp(22), dp(17), 0) }
        root.addView(column, LinearLayout.LayoutParams(0, -1, 1f))
        val topbar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val headings = Ui.vertical(this)
        headings.addView(Ui.text(this, if (tab == Tab.HOME) "Willkommen zurück" else tab.heading,
            25f, Ui.TEXT, true))
        headings.addView(Ui.text(this, subtitle(), 12f, Ui.MUTED), Ui.lp(-1, dp(26)))
        topbar.addView(headings, LinearLayout.LayoutParams(0, -2, 1f))
        topbar.addView(Ui.button(this, "＋  Neue Seite", compact = true) { editSite() }, Ui.lp(-2, dp(43)))
        column.addView(topbar, Ui.lp(-1, dp(82)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = Ui.vertical(this).apply { setPadding(dp(3), dp(8), dp(12), dp(38)) }
        scroll.addView(content)
        when(tab) {
            Tab.HOME -> renderHome(content)
            Tab.COVERS -> renderCoverDiscovery(content)
            Tab.PLAYER -> DeVideoSrcPanel(this) { name, url -> launchBrowser(name, url) }.render(content)
            Tab.SEARCH -> renderSearch(content)
            Tab.ANIME -> renderGrid(content, store.sites().filter { it.section == Section.ANIME })
            Tab.FILME -> renderGrid(content, store.sites().filter { it.section == Section.FILME })
            Tab.SERIEN -> renderGrid(content, store.sites().filter { it.section == Section.SERIEN })
            Tab.MIX -> renderGrid(content, store.sites().filter { it.section == Section.MISCHPORTALE })
            Tab.FAVORITES -> renderGrid(content, store.sites().filter { it.favorite })
            Tab.HISTORY -> renderHistory(content)
            Tab.MANAGE -> renderManage(content)
        }
        firstNav[0]?.requestFocus()
    }

    private fun subtitle(): String = when(tab) {
        Tab.HOME -> "Alles an einem Ort. Für deinen Fernseher."
        Tab.COVERS -> "Öffentlich sichtbare Poster aus allen gespeicherten Webseiten mit Adresse."
        Tab.PLAYER -> "IMDb-ID oder DeVideoSRC-ID eingeben und im StreamHUB-Player öffnen."
        Tab.SEARCH -> "Finde deine Lieblingsportale und gespeicherte Webseiten."
        Tab.MANAGE -> "Webseiten verwalten, prüfen und Listen importieren."
        Tab.HISTORY -> "Zuletzt geöffnete Seiten auf diesem Gerät."
        else -> "Entdecken, auswählen, direkt öffnen."
    }

    private fun renderHome(into: LinearLayout) {
        val all = store.sites()
        val hero = FrameLayout(this).apply {
            background = Ui.bg(0xFF111F32.toInt(), 18f, context = this@MainActivity)
            clipToOutline = true
        }
        hero.addView(CinematicArtwork(this, "StreamHub Premium", "Home", hero = true),
            FrameLayout.LayoutParams(-1, -1))
        val veil = View(this).apply {
            background = Ui.gradient(this@MainActivity,
                intArrayOf(0xF009101A.toInt(), 0xDD101A2A.toInt(), 0x15132643),
                18f)
        }
        hero.addView(veil, FrameLayout.LayoutParams(-1, -1))
        val heroContent = Ui.vertical(this).apply {
            setPadding(dp(30), dp(19), dp(19), dp(18))
        }
        heroContent.addView(Ui.text(this, "●   STREAMHUB ORIGINAL EXPERIENCE", 11f, Ui.BLUE, true),
            Ui.lp(-1, dp(31)))
        val slogan = Ui.text(this, "DEINE WELT.\nDEIN STREAM.", 35f, Ui.TEXT, true)
        slogan.setLineSpacing(dp(1).toFloat(), 1.0f)
        heroContent.addView(slogan, Ui.lp(-1, dp(99)))
        heroContent.addView(Ui.text(this,
            "Anime, Filme und Serien – alles in deiner persönlichen TV-Zentrale.",
            14f, Ui.SOFT), Ui.lp(-1, dp(42)))
        val heroActions = Ui.horizontal(this)
        heroActions.addView(Ui.button(this, "▶  FILMBIBLIOTHEK", true) {
            startActivity(Intent(this, MediaLibraryActivity::class.java))
        }, Ui.lp(dp(194), dp(41)).apply { rightMargin = dp(9) })
        heroActions.addView(Ui.button(this, "📺  LIVE TV", true) {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }, Ui.lp(dp(162), dp(41)).apply { rightMargin = dp(9) })
        heroActions.addView(Ui.button(this, "▧  COVER", true) { showTab(Tab.COVERS) },
            Ui.lp(dp(150), dp(41)).apply { rightMargin = dp(9) })
        heroActions.addView(Ui.button(this, "▶  DEVIDEOSRC", true) { showTab(Tab.PLAYER) },
            Ui.lp(dp(163), dp(41)))
        heroContent.addView(heroActions, Ui.lp(-1, dp(47)))
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1))
        into.addView(hero, Ui.lp(-1, dp(250)).apply { bottomMargin = dp(24) })

        val visiting = store.history().take(8)
        if (visiting.isNotEmpty()) {
            val recentSites = visiting.mapNotNull { visit ->
                all.firstOrNull { it.url == visit.url }
            }.distinctBy { it.id }
            if (recentSites.isNotEmpty()) {
                renderShelf(into, "Zuletzt besucht", recentSites, Tab.HISTORY)
            }
        }
        val liked = all.filter { it.favorite }
        if (liked.isNotEmpty()) renderShelf(into, "Deine Favoriten", liked, Tab.FAVORITES)
        renderShelf(into, "Anime entdecken", all.filter { it.section == Section.ANIME }, Tab.ANIME)
        renderShelf(into, "Filmwelten", all.filter { it.section == Section.FILME }, Tab.FILME)
        renderShelf(into, "Serien-Highlights", all.filter { it.section == Section.SERIEN }, Tab.SERIEN)
        renderShelf(into, "Alles auf einer Seite", all.filter { it.section == Section.MISCHPORTALE }, Tab.MIX)
        into.addView(Ui.text(this,
            "${all.size} Webseiten im Katalog   •   Inhalte und Videoplayer hängen vom jeweiligen Anbieter ab.",
            12f, Ui.MUTED), Ui.lp(-1, dp(42)))
    }

    private fun renderShelf(into: LinearLayout, name: String, sites: List<Site>, category: Tab) {
        if (sites.isEmpty()) return
        val heading = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(this, name, 22f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(49), 1f))
        heading.addView(Ui.button(this, "ALLE ANSEHEN   ›", compact = true) { showTab(category) },
            Ui.lp(dp(146), dp(36)))
        into.addView(heading, Ui.lp(-1, dp(56)))

        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val line = Ui.horizontal(this).apply {
            setPadding(dp(4), dp(8), dp(6), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        sites.forEach { site ->
            val tile = createTile(site)
            line.addView(tile, Ui.lp(dp(173), dp(237)).apply { rightMargin = dp(17) })
        }
        strip.addView(line)
        into.addView(strip, Ui.lp(-1, dp(268)).apply { bottomMargin = dp(12) })
    }

    private fun addHeading(into: LinearLayout, title: String) {
        into.addView(Ui.text(this, title, 23f, Ui.TEXT, true),
            Ui.lp(-1, dp(54)).apply { topMargin = dp(7) })
    }

    private fun renderGrid(into: LinearLayout, sites: List<Site>, maxItems: Int = Int.MAX_VALUE) {
        val shown = sites.take(maxItems)
        if (shown.isEmpty()) {
            into.addView(Ui.text(this, "Hier ist noch nichts gespeichert. Oben rechts kannst du eine Seite hinzufügen.",
                16f, Ui.MUTED), Ui.lp(-1, dp(85)))
            return
        }
        val heading = Ui.text(this, "${shown.size} SEITEN  •  AUSWÄHLEN UND ÖFFNEN", 11f, Ui.MUTED, true)
        into.addView(heading, Ui.lp(-1, dp(40)))
        shown.chunked(3).forEach { chunk ->
            val line = Ui.horizontal(this).apply {
                clipChildren = false
                clipToPadding = false
            }
            chunk.forEach { site ->
                line.addView(createTile(site), LinearLayout.LayoutParams(0, dp(248), 1f).apply {
                    rightMargin = dp(17)
                    bottomMargin = dp(15)
                })
            }
            repeat(3 - chunk.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(248), 1f).apply { rightMargin = dp(17) })
            }
            into.addView(line, Ui.lp(-1, dp(267)))
        }
    }

    private fun createTile(site: Site): View {
        val card = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            clipToOutline = true
            background = Ui.bg(Ui.PANEL, 15f, context = this@MainActivity)
        }
        card.addView(CinematicArtwork(this, site.name, site.section.label),
            FrameLayout.LayoutParams(-1,-1))
        // Source artwork: public HTML/OpenGraph metadata, cached locally.
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(poster, FrameLayout.LayoutParams(-1, -1))
        if (site.url.isNotBlank()) {
            covers.request(site) { metadata ->
                if (metadata.imageUrl.isNotBlank() && !isDestroyed) {
                    covers.loadInto(poster, metadata.imageUrl)
                }
            }
        }
        val shade = View(this).apply {
            background = Ui.gradient(this@MainActivity,
                intArrayOf(0x08081017, 0x88090C14.toInt(), 0xF9090B10.toInt()),
                15f, GradientDrawable.Orientation.TOP_BOTTOM)
        }
        card.addView(shade, FrameLayout.LayoutParams(-1,-1))

        val kicker = Ui.text(this, site.section.label.uppercase(), 10f, Ui.TEXT, true)
        kicker.setPadding(dp(11), 0, dp(11), 0)
        kicker.background = Ui.bg(0xB80D1729.toInt(), 8f, context = this)
        card.addView(kicker, FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(12); topMargin = dp(12)
        })

        val favorite = Ui.text(this, if (site.favorite) "♥" else "♡", 23f,
            if (site.favorite) Ui.BLUE else Ui.TEXT, true)
        favorite.gravity = Gravity.CENTER
        favorite.isFocusable = true
        favorite.isClickable = true
        favorite.background = Ui.bg(0xAB101827.toInt(), 12f, context = this)
        favorite.setOnClickListener {
            store.toggleFavorite(site.id)
            showTab(tab)
        }
        favorite.setOnFocusChangeListener { view, focus ->
            view.background = Ui.bg(if (focus) Ui.BLUE else 0xAB101827.toInt(), 12f, context = this)
            favorite.setTextColor(if (focus) Ui.BG else if (site.favorite) Ui.BLUE else Ui.TEXT)
        }
        card.addView(favorite, FrameLayout.LayoutParams(dp(37),dp(35),
            Gravity.TOP or Gravity.RIGHT).apply { rightMargin = dp(11); topMargin = dp(9) })

        val details = Ui.vertical(this).apply {
            setPadding(dp(13), dp(8), dp(13), dp(14))
        }
        val title = Ui.text(this, site.name, 20f, Ui.TEXT, true)
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        details.addView(title, Ui.lp(-1, dp(53)))
        val domain = if (site.url.isBlank()) "Adresse ergänzen" else Uri.parse(site.url).host ?: site.url
        val host = Ui.text(this, domain, 12f, Ui.SOFT)
        host.maxLines = 1
        host.ellipsize = android.text.TextUtils.TruncateAt.END
        details.addView(host, Ui.lp(-1, dp(22)))
        details.addView(Ui.text(this,
            if (site.url.isBlank()) "＋  ADRESSE HINZUFÜGEN" else "▶  JETZT ÖFFNEN",
            11f, if (site.url.isBlank()) 0xFFFFCC91.toInt() else Ui.BLUE, true),
            Ui.lp(-1, dp(24)))
        card.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        card.foreground = Ui.bg(Color.TRANSPARENT, 15f, Color.TRANSPARENT, this)
        card.setOnFocusChangeListener { view, focused ->
            card.foreground = Ui.bg(Color.TRANSPARENT, 15f,
                if (focused) Ui.BLUE else Color.TRANSPARENT, this)
            view.animate().scaleX(if (focused) 1.06f else 1f)
                .scaleY(if (focused) 1.06f else 1f).setDuration(160).start()
            view.elevation = if (focused) dp(18).toFloat() else 0f
        }
        card.setOnClickListener {
            if (site.url.isBlank()) editSite(site) else launchBrowser(site.name, site.url)
        }
        return card
    }


    private fun renderCoverDiscovery(into: LinearLayout) {
        val sources = store.sites().filter { it.url.startsWith("https://") }
        val placeholders = store.sites().count { it.url.isBlank() }
        into.addView(Ui.text(this,
            "Bilder direkt aus den eingebundenen Webseiten • ${sources.size} Quellen mit Adresse • " +
                "${placeholders} ohne URL. Nicht alle Webseiten veröffentlichen auslesbare Cover.",
            14f, Ui.SOFT), Ui.lp(-1, dp(62)))

        val action = Ui.button(this, "↻  Cover aller Quellen aktualisieren", true) {
            forceCoverRefresh = true
            showTab(Tab.COVERS)
        }
        into.addView(action, Ui.lp(dp(300), dp(48)).apply { bottomMargin = dp(13) })
        val progress = Ui.text(this, "Lade gespeicherte Poster-Daten …", 13f, Ui.MUTED)
        into.addView(progress, Ui.lp(-1, dp(36)))
        val gallery = Ui.vertical(this)
        into.addView(gallery, Ui.lp(-1, -2))
        val started = forceCoverRefresh
        forceCoverRefresh = false
        var checked = 0
        var shown = 0
        sources.forEach { site ->
            covers.request(site, force = started) { result ->
                if (tab != Tab.COVERS || isFinishing || isDestroyed) return@request
                checked++
                if (result.entries.isNotEmpty()) {
                    shown += result.entries.size
                    addHeading(gallery, "${site.name}  ·  ${result.entries.size} Cover")
                    val rail = HorizontalScrollView(this).apply {
                        isFocusable = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                    }
                    val line = Ui.horizontal(this).apply {
                        clipChildren = false
                        clipToPadding = false
                        setPadding(dp(5), dp(4), dp(8), dp(9))
                    }
                    result.entries.forEach { entry ->
                        line.addView(createCoverTile(site, entry),
                            Ui.lp(dp(177), dp(243)).apply { rightMargin = dp(16) })
                    }
                    rail.addView(line)
                    gallery.addView(rail, Ui.lp(-1, dp(268)))
                }
                progress.text = "$checked/${sources.size} Webseiten geprüft · $shown Cover gefunden"
                if (checked == sources.size && shown == 0) {
                    gallery.addView(Ui.text(this,
                        "Keine auslesbaren Cover gefunden. Manche Seiten laden Bilder nur per JavaScript " +
                            "oder blockieren automatische Anfragen. Die normalen Webseiten-Kacheln bleiben nutzbar.",
                        15f, Ui.MUTED), Ui.lp(-1, dp(90)))
                }
            }
        }
    }

    private fun createCoverTile(site: Site, entry: DiscoveredCover): View {
        val tile = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = Ui.bg(Ui.PANEL, 15f, context = this@MainActivity)
            clipToOutline = true
        }
        tile.addView(CinematicArtwork(this, entry.title, site.section.label),
            FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.addView(image, FrameLayout.LayoutParams(-1, -1))
        covers.loadInto(image, entry.imageUrl)
        tile.addView(View(this).apply {
            background = Ui.gradient(this@MainActivity,
                intArrayOf(0x000A0D13, 0x770A0D13, 0xF5090B12.toInt()),
                15f, GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val label = Ui.vertical(this).apply {
            setPadding(dp(12), 0, dp(11), dp(14))
        }
        val title = Ui.text(this, entry.title, 17f, Ui.TEXT, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        label.addView(title, Ui.lp(-1, dp(53)))
        label.addView(Ui.text(this, site.name + " · ÖFFNEN", 11f, Ui.BLUE, true),
            Ui.lp(-1, dp(28)))
        tile.addView(label, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        tile.setOnFocusChangeListener { view, focused ->
            tile.foreground = Ui.bg(Color.TRANSPARENT, 15f,
                if (focused) Ui.BLUE else Color.TRANSPARENT, this)
            view.animate().scaleX(if (focused) 1.055f else 1f)
                .scaleY(if (focused) 1.055f else 1f).setDuration(145).start()
        }
        tile.setOnClickListener { launchBrowser(entry.title, entry.pageUrl) }
        return tile
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
        covers.shutdown()
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
        val uri = Uri.parse(url)
        val isDeVideoPlayer = uri.scheme == "https" && uri.host == "devideosrc.co" &&
            (uri.path?.startsWith("/movie/") == true || uri.path?.startsWith("/serial/") == true ||
             uri.path?.startsWith("/custom/") == true)
        startActivity(Intent(this, if (isDeVideoPlayer) DeVideoPlayerActivity::class.java
            else BrowserActivity::class.java).apply {
            putExtra("name", name)
            putExtra("url", url)
        })
    }

    @Deprecated("Legacy back navigation for Android TV devices")
    override fun onBackPressed() {
        if (tab == Tab.HOME) super.onBackPressed() else showTab(Tab.HOME)
    }
}
