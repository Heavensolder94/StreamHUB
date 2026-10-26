package de.streamhub.tv

import android.net.Uri
import java.net.IDN
import java.util.Locale

/**
 * Generic title-oriented lookup, not a site-specific streaming scraper.
 * Only documented YouTube search routing is used; otherwise this produces
 * ordinary browser search results constrained by Google's site: operator.
 *
 * Search results are NOT evidence that the content can actually be watched.
 */
internal object SourceSearchRouter {
    fun validHomepage(site: Site): String? {
        val uri = runCatching { Uri.parse(site.url.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        if (uri.encodedAuthority?.contains("@") == true) return null
        val hostname = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (!hostname.matches(Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$"))) return null
        if (hostname.length > 253) return null
        return site.url.trim()
    }

    fun searchUrl(site: Site, title: String): String? {
        if (validHomepage(site) == null) return null
        val rawHost = Uri.parse(site.url).host?.lowercase(Locale.ROOT) ?: return null
        val hostname = runCatching { IDN.toASCII(rawHost) }.getOrNull() ?: return null
        val cleanTitle = title.trim().replace(Regex("[\\p{Cntrl}\"\\n\\r]"), " ")
            .replace(Regex("\\s+"), " ").take(130).trim()
        if (cleanTitle.isBlank()) return null

        // Verified public YouTube user-search route; all other providers are
        // searched via an external index rather than invented internal URLs.
        if (hostname == "youtube.com" || hostname == "www.youtube.com" ||
            hostname == "m.youtube.com") {
            return Uri.Builder().scheme("https").authority("www.youtube.com")
                .appendPath("results")
                .appendQueryParameter("search_query", cleanTitle)
                .build().toString()
        }

        val query = "site:" + hostname + " \"" + cleanTitle + "\""
        return Uri.Builder().scheme("https").authority("www.google.com")
            .appendPath("search")
            .appendQueryParameter("q", query)
            .build().toString()
    }
}
