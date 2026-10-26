package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single source chooser on each film/show detail screen.
 * TMDB's DE watch/provider list comes from JustWatch (indicative availability).
 * Stored website bookmarks never claim title availability; a click opens the site's
 * homepage with the chosen title copied for manual search.
 */
internal class TitleSourcesPanel(
    private val activity: Activity,
    private val tmdb: TmdbClient,
    private val title: CatalogTitle,
    private val openPlayer: (String, Boolean) -> Unit,
    private val openEpisode: (String) -> Unit,
    private val openXvault: (String) -> Unit,
    private val configureKodi: () -> Unit,
    private val testKodi: () -> Unit,
    private val openWebsite: (Site) -> Unit,
    private val openDirectory: (String) -> Unit,
    private val openTmdbWatch: (String) -> Unit
) {
    private lateinit var playerHost: LinearLayout
    private lateinit var providerHost: LinearLayout
    private var imdbId: String? = null
    private val storedSites = SiteStore(activity).sites().filter {
        it.id != "devideosrc" && it.url.startsWith("https://") &&
            android.net.Uri.parse(it.url).host != null
    }
    private fun dp(n: Int) = (activity.resources.displayMetrics.density * n + .5f).toInt()

    fun render(into: LinearLayout, isActive: () -> Boolean) {
        val header = Ui.horizontal(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(activity, "▶  ANSEHEN & QUELLEN", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        into.addView(header, Ui.lp(-1, dp(56)))
        info(into, "Wähle zuerst den Film oder die Serie und anschließend den Anbieter. " +
            "Eine Streaming-Verfügbarkeit wird nur angezeigt, wenn TMDB/JustWatch " +
            "sie für Deutschland meldet.", 63)

        heading(into, "1 · Player & Kodi/xVAULT")
        playerHost = Ui.vertical(activity)
        into.addView(playerHost)
        info(playerHost, "IMDb-Zuordnung wird geladen …", 49)

        heading(into, "2 · Bekannte Streamingangebote in Deutschland")
        providerHost = Ui.vertical(activity)
        into.addView(providerHost)
        info(providerHost, "Verfügbarkeit via TMDB / JustWatch wird geladen …", 51)
        loadProviders(isActive)

        heading(into, "3 · Titel auf deinen Webseiten suchen")
        info(into, "Über eine gezielte Seitensuche erreichst du Suchtreffer zum " +
            "gewählten Titel. Das bestätigt weder Verfügbarkeit noch Abspielbarkeit.", 70)
        renderSavedSites(into)
        into.addView(Ui.text(activity,
            "Daten und Bilder: TMDB · Verfügbarkeitsdaten: JustWatch. " +
                "Angebote können sich ändern. Abonnements oder Kaufpreise gelten ggf. zusätzlich.",
            12f, Ui.MUTED), Ui.lp(-1, dp(62)).apply { topMargin = dp(14) })
    }

    fun updateImdb(id: String) {
        imdbId = DeVideoSrc.normalizeId(id)?.takeIf { it.startsWith("tt") }.orEmpty()
        if (::playerHost.isInitialized) renderPlayer()
    }

    private fun renderPlayer() {
        playerHost.removeAllViews()
        val id = imdbId.orEmpty()
        info(playerHost, "xVAULT nutzt die TMDB-ID aus diesem Filmkatalog. " +
            "Kodi und das xVAULT-Addon müssen auf derselben TV-Box installiert sein.", 64)
        val kodiRow = Ui.horizontal(activity)
        kodiRow.addView(Ui.button(activity, "▶  Über Kodi / xVAULT", true) {
            openXvault(id)
        }, Ui.lp(dp(243), dp(48)).apply { rightMargin = dp(9) })
        kodiRow.addView(Ui.button(activity, "Einrichten", true) {
            configureKodi()
        }, Ui.lp(dp(141), dp(48)).apply { rightMargin = dp(9) })
        kodiRow.addView(Ui.button(activity, "Verbindung testen", true) {
            testKodi()
        }, Ui.lp(dp(191), dp(48)))
        playerHost.addView(kodiRow, Ui.lp(-1, dp(65)))
        info(playerHost, "xVAULT übernimmt die Quellenwahl in Kodi. " +
            "Eine tatsächliche Wiedergabe ist nicht garantiert.", 51)
        if (id.isBlank()) {
            info(playerHost, "Keine IMDb-ID in TMDB hinterlegt. " +
                "DeVideoSRC ist deshalb für diesen Titel nicht direkt verfügbar; " +
                "xVAULT kann die TMDB-ID trotzdem verwenden.", 72)
            return
        }
        info(playerHost, "DeVideoSRC · externer Embed-Player über IMDb-ID " + id +
            " · Wiedergabe durch den Drittanbieter nicht garantiert.", 63)
        val row = Ui.horizontal(activity)
        row.addView(Ui.button(activity, "▶  Mit DeVideoSRC starten", true) {
            openPlayer(id, false)
        }, Ui.lp(dp(250), dp(48)).apply { rightMargin = dp(10) })
        if (title.kind == "movie") {
            row.addView(Ui.button(activity, "Custom-Player", true) {
                openPlayer(id, true)
            }, Ui.lp(dp(160), dp(48)))
        } else {
            row.addView(Ui.button(activity, "Staffel / Episode wählen", true) {
                openEpisode(id)
            }, Ui.lp(dp(230), dp(48)))
        }
        playerHost.addView(row, Ui.lp(-1, dp(61)))
    }

    private fun loadProviders(isActive: () -> Boolean) {
        tmdb.request("/" + title.kind + "/" + title.id + "/watch/providers") { result ->
            if (!isActive() || !::providerHost.isInitialized) return@request
            providerHost.removeAllViews()
            result.fold(onSuccess = { json ->
                val germany = json.optJSONObject("results")?.optJSONObject("DE")
                if (germany == null) {
                    info(providerHost, "Keine Verfügbarkeitsangaben für Deutschland " +
                        "hinterlegt. Das bedeutet nicht, dass der Titel nirgendwo läuft.", 79)
                    return@fold
                }
                val link = validatedWatchLink(germany.optString("link"))
                val groups = listOf(
                    "Abo-Streaming" to "flatrate",
                    "Kostenlos" to "free",
                    "Mit Werbung" to "ads",
                    "Leihen" to "rent",
                    "Kaufen" to "buy"
                )
                var found = false
                for ((label, key) in groups) {
                    val providerArray = germany.optJSONArray(key) ?: continue
                    val seen = mutableSetOf<Int>()
                    val providers = (0 until providerArray.length()).mapNotNull { index ->
                        providerArray.optJSONObject(index)?.takeIf {
                            it.optInt("provider_id", 0) > 0 &&
                                it.optString("provider_name").isNotBlank()
                        }
                    }.filter { seen.add(it.optInt("provider_id")) }
                    if (providers.isEmpty()) continue
                    found = true
                    heading(providerHost, label)
                    renderProviderRail(providerHost, providers.take(12), link)
                    if (providers.size > 12) {
                        info(providerHost, "Weitere Anbieter über die TMDB-Verfügbarkeitsseite.", 35)
                    }
                }
                if (!found) {
                    info(providerHost, "Für Deutschland wurden keine konkreten Angebote gemeldet.", 66)
                } else {
                    info(providerHost, "Die Angaben werden von JustWatch bereitgestellt. " +
                        "Sie zeigen keine überprüften direkten Abspiel-Links.", 60)
                }
                providerHost.addView(Ui.button(activity, "Alle Angebote bei TMDB ansehen", true) {
                    openTmdbWatch(link)
                }, Ui.lp(dp(300), dp(48)).apply { bottomMargin = dp(13) })
            }, onFailure = { error ->
                info(providerHost, "Anbieterabfrage nicht möglich: " +
                    (error.message ?: "Netzwerkfehler") + ". Du kannst trotzdem den " +
                    "integrierten Player oder deine Webseiten öffnen.", 85)
            })
        }
    }

    private fun validatedWatchLink(candidate: String): String {
        val parsed = android.net.Uri.parse(candidate)
        if (parsed.scheme == "https" && parsed.host == "www.themoviedb.org" &&
            parsed.path?.startsWith("/" + title.kind + "/" + title.id) == true &&
            parsed.path?.contains("/watch") == true) return candidate
        return "https://www.themoviedb.org/" + title.kind + "/" + title.id + "/watch?locale=DE"
    }

    private fun renderProviderRail(host: LinearLayout, providers: List<JSONObject>, url: String) {
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        val row = Ui.horizontal(activity).apply { setPadding(0, dp(4), 0, dp(8)) }
        providers.forEach { provider ->
            val name = provider.optString("provider_name", "Anbieter").take(52)
            val cell = Ui.vertical(activity).apply {
                setPadding(dp(4), dp(3), dp(4), dp(5))
            }
            val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val image = provider.optString("logo_path", "")
            if (image.isNotBlank()) TmdbArtwork.load(icon, image)
            cell.addView(icon, Ui.lp(-1, dp(55)))
            cell.addView(Ui.button(activity, name, true) { openTmdbWatch(url) },
                Ui.lp(-1, dp(54)))
            row.addView(cell, Ui.lp(dp(194), dp(128)).apply { rightMargin = dp(11) })
        }
        scroll.addView(row)
        host.addView(scroll, Ui.lp(-1, dp(145)))
    }

    private fun renderSavedSites(host: LinearLayout) {
        if (storedSites.isEmpty()) {
            info(host, "Keine Webseiten mit gültiger Adresse gespeichert.", 53)
            host.addView(Ui.button(activity, "Alle Quellen einrichten", true) {
                openDirectory(imdbId.orEmpty())
            }, Ui.lp(dp(270), dp(52)))
            return
        }
        val preferredSection = if (title.kind == "movie") Section.FILME else Section.SERIEN
        val sorted = storedSites.sortedWith(
            compareByDescending<Site> { it.favorite }
                .thenByDescending { it.section == preferredSection }
                .thenByDescending { it.section == Section.MISCHPORTALE }
                .thenBy { it.name.lowercase(java.util.Locale.GERMANY) }
        )
        val featured = sorted.take(6)
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = Ui.horizontal(activity)
        featured.forEach { site ->
            row.addView(Ui.button(activity,
                (if (site.favorite) "♥ " else "⌕ ") + site.name.take(19), true) {
                openSiteWithTitle(site)
            }, Ui.lp(dp(187), dp(54)).apply { rightMargin = dp(10) })
        }
        scroll.addView(row)
        host.addView(scroll, Ui.lp(-1, dp(65)))
        val actions = Ui.horizontal(activity)
        val total = SiteStore(activity).sites().size
        actions.addView(Ui.button(activity,
            "Alle Quellen (" + total + ")", true) { openDirectory(imdbId.orEmpty()) },
            Ui.lp(dp(232), dp(49)).apply { rightMargin = dp(10) })
        actions.addView(Ui.button(activity, "Filmtitel kopieren", true) { copyTitle() },
            Ui.lp(dp(215), dp(49)))
        host.addView(actions, Ui.lp(-1, dp(61)))
        info(host, "Die Schnellwahl startet eine titelbezogene Seitensuche. " +
            "In »Alle Quellen« kannst du Websites filtern, Favoriten ändern und " +
            "fehlende Adressen ergänzen.", 75)
    }

    private fun chooseWebsite() {
        val sorted = storedSites.sortedWith(compareBy<Site>(
            { when(it.section) {
                Section.ANIME -> 0
                Section.FILME -> 1
                Section.SERIEN -> 2
                Section.MISCHPORTALE -> 3
            } }, { it.name.lowercase(java.util.Locale.GERMANY) }
        ))
        val names = sorted.map {
            it.section.label + "  ·  " + (if (it.favorite) "♥ " else "") + it.name
        }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Webseite für »" + title.name + "«")
            .setMessage("Nur Webseite öffnen; dortige Verfügbarkeit ist nicht geprüft.")
            .setItems(names) { _, index -> openSiteWithTitle(sorted[index]) }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun copyTitle() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Filmtitel", title.name))
        Toast.makeText(activity, "Titel kopiert: " + title.name.take(58),
            Toast.LENGTH_SHORT).show()
    }

    private fun openSiteWithTitle(site: Site) {
        copyTitle()
        openWebsite(site)
    }

    private fun heading(where: LinearLayout, label: String) {
        where.addView(Ui.text(activity, label, 18f, Ui.TEXT, true),
            Ui.lp(-1, dp(44)).apply { topMargin = dp(5) })
    }

    private fun info(where: LinearLayout, label: String, h: Int) {
        where.addView(Ui.text(activity, label, 13f, Ui.MUTED), Ui.lp(-1, dp(h)))
    }
}
