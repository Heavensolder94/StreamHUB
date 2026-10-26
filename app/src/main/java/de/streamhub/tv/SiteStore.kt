package de.streamhub.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Section(val label: String) {
    ANIME("Anime"), FILME("Filme"), SERIEN("Serien"), MISCHPORTALE("Mischportale")
}

enum class SourceKind(val label: String) {
    OFFIZIELLER_DIENST("Offizieller Dienst"),
    NICHT_GEPRUEFT("Rechte/Anbieter ungeprüft")
}

enum class Reachability(val label: String) {
    UNCHECKED("Noch nicht geprüft"),
    ONLINE("Erreichbar"),
    OFFLINE("Nicht erreichbar"),
    RESTRICTED("Zugriff eingeschränkt"),
    UNCERTAIN("Prüfung unklar")
}

data class Site(
    val id: String,
    val name: String,
    val url: String,
    val section: Section,
    val favorite: Boolean = false,
    val sourceKind: SourceKind = SourceKind.NICHT_GEPRUEFT,
    val reachability: Reachability = Reachability.UNCHECKED,
    val checkedAt: Long = 0L
)

data class ImportResult(val added: Int, val completed: Int, val skipped: Int)

data class Visit(val name: String, val url: String, val time: Long)

class SiteStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_local", Context.MODE_PRIVATE)

    /**
     * V1.5 Quellenkatalog. Alle Einträge sind bearbeitbare lokale Lesezeichen.
     * Offizielle Dienste und Portale mit nicht geklärter Lizenzlage sind getrennt
     * gekennzeichnet. Leere URLs sind absichtlich Platzhalter, keine Fantasiedomains.
     */
    private fun starterSites(): List<Site> = listOf(
        // Anime – offizielle Anbieter
        official("crunchyroll", "Crunchyroll", "https://www.crunchyroll.com/de/", Section.ANIME),
        official("adn", "ADN", "https://animationdigitalnetwork.com/de", Section.ANIME),
        official("anisearch", "AniSearch (Datenbank)", "https://www.anisearch.de/", Section.ANIME),
        official("rakuten-viki", "Rakuten Viki", "https://www.viki.com/", Section.ANIME),
        // Anime – Angebote ohne geprüfte Lizenzsituation
        unchecked("aniworld", "AniWorld", "https://aniworld.to/", Section.ANIME),
        unchecked("anime-loads", "Anime-Loads", "https://www.anime-loads.org/", Section.ANIME),
        unchecked("proxer", "Proxer", "https://proxer.me/", Section.ANIME),
        unchecked("anicloud", "AniCloud", "https://anicloud.io/", Section.ANIME),
        unchecked("animetoast", "AnimeToast", "", Section.ANIME),
        unchecked("anime-stream", "Anime-Stream", "", Section.ANIME),
        unchecked("anime-sensei", "Anime-Sensei", "", Section.ANIME),

        // Film – offizielle Dienste, Mediatheken und Suchdienste
        official("arte", "ARTE", "https://www.arte.tv/de/", Section.FILME),
        official("ard", "ARD Mediathek", "https://www.ardmediathek.de/", Section.FILME),
        official("rakuten", "Rakuten TV", "https://www.rakuten.tv/de", Section.FILME),
        official("netzkino", "Netzkino", "https://www.netzkino.de/", Section.FILME),
        official("plex", "Plex (Gratisfilme)", "https://watch.plex.tv/de", Section.MISCHPORTALE),
        official("wedotv", "wedotv", "https://www.wedotv.com/", Section.FILME),
        official("filmfriend", "filmfriend", "https://www.filmfriend.tv/de", Section.FILME),
        official("3sat", "3sat", "https://www.3sat.de/", Section.FILME),
        official("youtube", "YouTube", "https://www.youtube.com/", Section.FILME),
        official("justwatch", "JustWatch (Suche)", "https://www.justwatch.com/de", Section.FILME),
        official("prime-video", "Prime Video", "https://www.amazon.de/gp/video/storefront", Section.MISCHPORTALE),
        official("netflix", "Netflix", "https://www.netflix.com/de/", Section.MISCHPORTALE),
        official("disney", "Disney+", "https://www.disneyplus.com/de-de", Section.MISCHPORTALE),
        // Film – ungeprüfte Lizenz-/Betreiberlage, nicht jede Adresse verlässlich
        unchecked("kinox", "KinoX", "https://kinox.to/", Section.MISCHPORTALE),
        unchecked("filmpalast", "FilmPalast", "https://filmpalast.to/", Section.MISCHPORTALE),
        unchecked("cine", "Cine.to", "https://cine.to/", Section.MISCHPORTALE),
        unchecked("streamkiste", "Streamkiste", "", Section.MISCHPORTALE),
        unchecked("hdfilme", "HDfilme", "https://hdfilme.to/", Section.MISCHPORTALE),
        unchecked("megakino", "MegaKino", "", Section.MISCHPORTALE),
        unchecked("kinoger", "KINOGER", "", Section.FILME),
        unchecked("kinokiste", "KinoKiste / KKiste", "", Section.FILME),
        unchecked("movie4k", "Movie4k", "", Section.MISCHPORTALE),
        unchecked("filmfans", "FilmFans", "", Section.FILME),
        unchecked("kinodogs", "KinoDogs", "", Section.MISCHPORTALE),
        unchecked("kinogo", "KinoGO", "", Section.FILME),
        unchecked("kinofox", "KinoFox", "", Section.FILME),
        unchecked("kinoking", "KinoKing", "", Section.FILME),
        unchecked("filmo", "Filmo", "https://filmo.to/", Section.FILME),
        unchecked("einschalten", "Einschalten", "", Section.FILME),
        unchecked("streamcloud", "Streamcloud", "", Section.FILME),
        unchecked("movie4u", "Movie4u", "", Section.MISCHPORTALE),
        unchecked("moviewatcher", "MovieWatcher", "", Section.MISCHPORTALE),

        // Serien – offizielle Dienste
        official("zdf", "ZDF", "https://www.zdf.de/", Section.SERIEN),
        official("joyn", "Joyn", "https://www.joyn.de/", Section.MISCHPORTALE),
        official("pluto", "Pluto TV", "https://pluto.tv/de/", Section.MISCHPORTALE),
        official("kika", "KiKA", "https://www.kika.de/", Section.SERIEN),
        official("rtlplus", "RTL+", "https://plus.rtl.de/", Section.MISCHPORTALE),
        official("wow", "WOW", "https://www.wowtv.de/", Section.SERIEN),
        // Serien / gemischte Portale
        unchecked("serienstream", "S.to / SerienStream", "https://serienstream.to/", Section.SERIEN),
        unchecked("burningseries", "BS.to / Burning Series", "https://burningseries.ac/", Section.SERIEN),
        unchecked("serienfans", "Serienfans", "https://serienfans.org/", Section.SERIEN),
        unchecked("moflix", "Moflix", "https://moflix-stream.xyz/", Section.MISCHPORTALE),
        unchecked("huhu", "Huhu", "", Section.SERIEN),
        unchecked("flixnet", "Flixnet", "", Section.MISCHPORTALE),
        unchecked("moviedream", "MovieDream", "", Section.MISCHPORTALE),
        unchecked("couchtuner", "CouchTuner", "", Section.MISCHPORTALE),
        unchecked("devideosrc", "DeVideoSRC (Player/API)", "https://devideosrc.co/", Section.MISCHPORTALE)
    )

    private fun official(id: String, name: String, url: String, section: Section) =
        Site(id, name, url, section, sourceKind = SourceKind.OFFIZIELLER_DIENST)

    private fun unchecked(id: String, name: String, url: String, section: Section) =
        Site(id, name, url, section, sourceKind = SourceKind.NICHT_GEPRUEFT)

    private val originalV10Ids = setOf(
        "crunchyroll", "adn", "arte", "ard", "rakuten", "zdf", "joyn", "pluto"
    )
    private val originalV11Ids = originalV10Ids + setOf(
        "aniworld", "anime-loads", "proxer", "kinox", "filmpalast",
        "netzkino", "serienstream", "burningseries", "serienfans", "kika"
    )

    /**
     * Inserts only the *new* entries of a version. Old favourites/edits and
     * intentionally deleted previous-version starter entries remain untouched.
     */
    fun sites(): MutableList<Site> {
        if (!prefs.contains("sites")) {
            val defaults = starterSites().toMutableList()
            saveSites(defaults)
            prefs.edit().putInt("starter_catalog", 7).apply()
            return defaults
        }
        val array = JSONArray(prefs.getString("sites", "[]") ?: "[]")
        val result = mutableListOf<Site>()
        val defaultsById = starterSites().associateBy { it.id }
        for (i in 0 until array.length()) {
            try {
                val item = array.getJSONObject(i)
                val id = item.getString("id")
                val url = item.optString("url", "")
                val original = defaultsById[id]
                val kind = runCatching {
                    SourceKind.valueOf(item.getString("sourceKind"))
                }.getOrElse {
                    if (original != null && original.url == url) original.sourceKind
                    else SourceKind.NICHT_GEPRUEFT
                }
                result.add(Site(
                    id, item.getString("name"), url,
                    Section.valueOf(item.getString("section")),
                    item.optBoolean("favorite", false), kind,
                    runCatching { Reachability.valueOf(item.getString("reachability")) }
                        .getOrDefault(Reachability.UNCHECKED),
                    item.optLong("checkedAt", 0L)
                ))
            } catch (_: Exception) { /* Ignore only damaged individual records. */ }
        }
        val version = prefs.getInt("starter_catalog", 1)
        if (version < 4) {
            val existingIds = result.map { it.id }.toSet()
            if (version < 3) {
                val alreadyAvailable = if (version < 2) originalV10Ids else originalV11Ids
                result.addAll(starterSites().filter { it.id !in existingIds && it.id !in alreadyAvailable })
            }
            // Only reclassify entries still in their old stock category and with
            // their original URL. Never replace user URLs, favorites, or deletions.
            val defaults = defaultsById
            val oldFilmIds = setOf("prime-video", "netflix", "disney", "plex", "kinox", "filmpalast",
                "cine", "streamkiste", "hdfilme", "megakino", "movie4k", "kinodogs",
                "movie4u", "moviewatcher")
            val oldSeriesIds = setOf("moflix", "flixnet", "moviedream", "couchtuner",
                "joyn", "pluto", "rtlplus")
            for (index in result.indices) {
                val site = result[index]
                val moved = defaults[site.id] ?: continue
                val legacySection = when (site.id) {
                    in oldFilmIds -> Section.FILME
                    in oldSeriesIds -> Section.SERIEN
                    else -> continue
                }
                if (site.section == legacySection &&
                    (site.url == moved.url || (site.id in setOf("hdfilme", "moflix") && site.url.isBlank()))) {
                    result[index] = site.copy(section = Section.MISCHPORTALE)
                }
            }
            saveSites(result)
            prefs.edit().putInt("starter_catalog", 4).apply()
        }
        if (version < 5) {
            // V1.3 already had these two entries with empty URLs. Fill only
            // unchanged default placeholders; preserve personalized names, URLs,
            // categories, favourite flags, and entries the user has deleted.
            val newlyLinked = mapOf(
                "hdfilme" to "https://hdfilme.to/",
                "moflix" to "https://moflix-stream.xyz/"
            )
            for (index in result.indices) {
                val site = result[index]
                val url = newlyLinked[site.id] ?: continue
                val original = defaultsById[site.id] ?: continue
                if (site.url.isBlank() && site.name == original.name &&
                    result.none { it.id != site.id && it.url.equals(url, ignoreCase = true) }) {
                    result[index] = site.copy(
                        url = url,
                        reachability = Reachability.UNCHECKED,
                        checkedAt = 0L
                    )
                }
            }
            saveSites(result)
            prefs.edit().putInt("starter_catalog", 5).apply()
        }
        if (version < 6) {
            // V1.4 contained Filmo as an empty placeholder. Fill its URL only
            // when the entry has not been renamed, moved or given a custom URL.
            // Preserve favorite state and avoid restoring deleted entries.
            val newUrl = "https://filmo.to/"
            val index = result.indexOfFirst { it.id == "filmo" }
            if (index >= 0) {
                val site = result[index]
                if (site.name == "Filmo" && site.section == Section.FILME &&
                    site.url.isBlank() && site.sourceKind == SourceKind.NICHT_GEPRUEFT &&
                    result.none { it.id != site.id && it.url.equals(newUrl, ignoreCase = true) }) {
                    result[index] = site.copy(
                        url = newUrl,
                        reachability = Reachability.UNCHECKED,
                        checkedAt = 0L
                    )
                }
            }
            saveSites(result)
            prefs.edit().putInt("starter_catalog", 6).apply()
        }
        if (version < 7) {
            // Add only the new V1.8 source; never reset edits to existing sites.
            val source = defaultsById["devideosrc"]
            if (source != null && result.none { it.id == source.id ||
                    it.url.equals(source.url, ignoreCase = true) }) {
                result.add(source)
                saveSites(result)
            }
            prefs.edit().putInt("starter_catalog", 7).apply()
        }
        return result
    }

    fun saveSites(list: List<Site>) {
        val array = JSONArray()
        list.forEach { site ->
            array.put(JSONObject().put("id", site.id).put("name", site.name)
                .put("url", site.url).put("section", site.section.name)
                .put("favorite", site.favorite).put("sourceKind", site.sourceKind.name)
                .put("reachability", site.reachability.name).put("checkedAt", site.checkedAt))
        }
        prefs.edit().putString("sites", array.toString()).apply()
    }

    fun upsert(name: String, url: String, section: Section, oldId: String? = null) {
        val all = sites()
        val index = if (oldId == null) -1 else all.indexOfFirst { it.id == oldId }
        val old = all.getOrNull(index)
        val kind = if (old != null && old.url == url) old.sourceKind else SourceKind.NICHT_GEPRUEFT
        val sameUrl = old?.url == url
        val edited = Site(oldId ?: UUID.randomUUID().toString(), name, url, section,
            old?.favorite ?: false, kind,
            if (sameUrl) old?.reachability ?: Reachability.UNCHECKED else Reachability.UNCHECKED,
            if (sameUrl) old?.checkedAt ?: 0L else 0L)
        if (index >= 0) all[index] = edited else all.add(edited)
        saveSites(all)
    }

    fun saveReachability(id: String, testedUrl: String, status: Reachability) {
        saveSites(sites().map { site ->
            if (site.id == id && site.url == testedUrl) site.copy(
                reachability = status, checkedAt = System.currentTimeMillis()
            ) else site
        })
    }

    /** Import never overwrites a link, a user edit, or a favorite. */
    fun importSites(incoming: List<ImportEntry>): ImportResult {
        val all = sites()
        var added = 0
        var completed = 0
        var skipped = 0
        incoming.forEach { entry ->
            val normalizedUrl = entry.url.trimEnd('/').lowercase(java.util.Locale.ROOT)
            val byUrl = all.any { it.url.trimEnd('/').lowercase(java.util.Locale.ROOT) == normalizedUrl }
            if (byUrl) { skipped++; return@forEach }
            val existingIdx = all.indexOfFirst {
                it.name.equals(entry.name, ignoreCase = true) && it.section == entry.section
            }
            if (existingIdx >= 0) {
                val existing = all[existingIdx]
                if (existing.url.isBlank()) {
                    all[existingIdx] = existing.copy(url = entry.url, reachability = Reachability.UNCHECKED, checkedAt = 0L)
                    completed++
                } else skipped++
            } else {
                all.add(Site(UUID.randomUUID().toString(), entry.name, entry.url, entry.section))
                added++
            }
        }
        if (added + completed > 0) saveSites(all)
        return ImportResult(added, completed, skipped)
    }

    fun delete(id: String) = saveSites(sites().filterNot { it.id == id })

    fun toggleFavorite(id: String) = saveSites(sites().map { site ->
        if (site.id == id) site.copy(favorite = !site.favorite) else site
    })

    fun history(): List<Visit> {
        val array = JSONArray(prefs.getString("history", "[]") ?: "[]")
        return buildList {
            for (i in 0 until array.length()) {
                try {
                    val item = array.getJSONObject(i)
                    add(Visit(item.getString("name"), item.getString("url"), item.getLong("time")))
                } catch (_: Exception) { }
            }
        }
    }

    fun addVisit(name: String, url: String) {
        val visits = (listOf(Visit(name, url, System.currentTimeMillis())) + history().filterNot { it.url == url }).take(25)
        val array = JSONArray()
        visits.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url).put("time", it.time)) }
        prefs.edit().putString("history", array.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove("history").apply()
}
