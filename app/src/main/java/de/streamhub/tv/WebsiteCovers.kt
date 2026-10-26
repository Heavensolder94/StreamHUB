package de.streamhub.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Generic, opt-in public-page cover discovery for all saved HTTPS sites.
 * No logins, paywall bypass, captchas, stream extraction, or scraping of protected pages.
 * Every result links back to the page that published it.
 */
internal data class DiscoveredCover(
    val title: String,
    val imageUrl: String,
    val pageUrl: String
)

internal data class SiteCovers(
    val imageUrl: String = "",
    val entries: List<DiscoveredCover> = emptyList(),
    val refreshedAt: Long = 0L
)

internal class WebsiteCovers(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val prefs = app.getSharedPreferences("streamhub_covers_v17", Context.MODE_PRIVATE)
    private val pending = mutableMapOf<String, MutableList<(SiteCovers) -> Unit>>()
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val artworkDir = File(app.cacheDir, "website_artwork").also { it.mkdirs() }

    private fun digest(value: String): String {
        val data = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return data.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun key(site: Site) = digest(site.id + "|" + site.url)

    private fun validPublicHttps(value: String): Boolean {
        return runCatching {
            val u = URI(value)
            val host = u.host?.lowercase()?.trimEnd('.') ?: return@runCatching false
            u.scheme.equals("https", true) &&
                u.userInfo == null && host != "localhost" && !host.endsWith(".local") &&
                !host.endsWith(".internal") && host != "::1" && host != "[::1]" &&
                !host.startsWith("127.") && !host.startsWith("10.") &&
                !host.startsWith("192.168.") && !host.startsWith("169.254.") &&
                !host.startsWith("172.16.") && !host.startsWith("172.17.") &&
                !host.startsWith("172.18.") && !host.startsWith("172.19.") &&
                !host.startsWith("172.2") && !host.startsWith("172.30.") &&
                !host.startsWith("172.31.") && host.matches(Regex("[a-z0-9.-]+")) &&
                !host.matches(Regex("[0-9.]+"))
        }.getOrDefault(false)
    }

    private fun absolute(base: String, raw: String): String {
        if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("blob:")) return ""
        return runCatching {
            URI(base).resolve(raw.trim()).normalize().toString()
        }.getOrDefault("").takeIf { validPublicHttps(it) } ?: ""
    }

    private fun cached(site: Site): SiteCovers? = runCatching {
        val raw = prefs.getString(key(site), null) ?: return null
        val o = JSONObject(raw)
        val age = System.currentTimeMillis() - o.optLong("time")
        val ttl = if (o.optBoolean("ok")) 7L * 24 * 3600_000 else 6L * 3600_000
        if (age < 0 || age > ttl) return null
        val entries = mutableListOf<DiscoveredCover>()
        val list = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until list.length()) {
            val x = list.optJSONObject(i) ?: continue
            entries.add(DiscoveredCover(x.optString("title"), x.optString("image"),
                x.optString("page")))
        }
        SiteCovers(o.optString("image"), entries, o.optLong("time"))
    }.getOrNull()

    private fun save(site: Site, covers: SiteCovers) {
        val items = JSONArray()
        covers.entries.forEach {
            items.put(JSONObject().put("title", it.title).put("image", it.imageUrl)
                .put("page", it.pageUrl))
        }
        val o = JSONObject().put("image", covers.imageUrl).put("items", items)
            .put("time", covers.refreshedAt)
            .put("ok", covers.imageUrl.isNotBlank() || covers.entries.isNotEmpty())
        prefs.edit().putString(key(site), o.toString()).apply()
    }

    /** Returns only cached results immediately; new network requests are backgrounded. */
    fun request(site: Site, force: Boolean = false, onReady: (SiteCovers) -> Unit) {
        if (!validPublicHttps(site.url)) {
            main.post { onReady(SiteCovers()) }
            return
        }
        if (!force) {
            val result = cached(site)
            if (result != null) {
                main.post { onReady(result) }
                return
            }
        }
        val cacheKey = key(site)
        synchronized(pending) {
            val waiters = pending[cacheKey]
            if (waiters != null) {
                waiters.add(onReady)
                return
            }
            pending[cacheKey] = mutableListOf(onReady)
        }
        io.execute {
            val result = runCatching { scan(site) }.getOrDefault(
                SiteCovers(refreshedAt = System.currentTimeMillis()))
            runCatching { save(site, result) }
            main.post {
                val callbacks = synchronized(pending) {
                    pending.remove(cacheKey).orEmpty()
                }
                callbacks.forEach { it(result) }
            }
        }
    }

    private fun meta(doc: Document, selector: String): String =
        doc.selectFirst(selector)?.attr("content").orEmpty()

    private fun scan(site: Site): SiteCovers {
        val doc = Jsoup.connect(site.url)
            .userAgent("StreamHubTV/1.7 (Android TV; public metadata preview)")
            .referrer("")
            .timeout(7000)
            .maxBodySize(1_000_000)
            .followRedirects(true)
            .get()
        val location = doc.location()
        if (!validPublicHttps(location)) return SiteCovers(refreshedAt = System.currentTimeMillis())
        val selected = linkedMapOf<String, DiscoveredCover>()
        // A generic, lightweight extractor that works on any site exposing HTML poster links.
        for (anchor in doc.select("a[href]:has(img)")) {
            if (selected.size >= 8) break
            val image = anchor.selectFirst("img") ?: continue
            val raw = listOf("data-src", "data-original", "data-lazy-src", "src")
                .map { image.attr(it).trim() }.firstOrNull { it.isNotBlank() } ?: continue
            val imageUrl = absolute(location, raw)
            val pageUrl = absolute(location, anchor.attr("href"))
            if (imageUrl.isEmpty() || pageUrl.isEmpty()) continue
            val pageHost = URI(pageUrl).host ?: continue
            val sourceHost = URI(location).host ?: continue
            // Only add same-site detail links; never surface unrelated ad destinations.
            if (pageHost != sourceHost && !pageHost.endsWith(".$sourceHost")) continue
            if (URI(pageUrl).path.orEmpty().trim('/') == URI(location).path.orEmpty().trim('/')) continue
            val label = listOf(image.attr("alt"), anchor.attr("title"), anchor.attr("aria-label"),
                anchor.selectFirst("h2,h3,h4")?.text().orEmpty(), anchor.text())
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .firstOrNull { it.length in 3..100 && !it.equals("mehr", true) }
                .orEmpty()
            if (label.isEmpty()) continue
            val width = image.attr("width").toIntOrNull()
            val height = image.attr("height").toIntOrNull()
            if (width != null && width in 1..69 || height != null && height in 1..69) continue
            if (Regex("(logo|icon|sprite|avatar|tracking|pixel)", RegexOption.IGNORE_CASE).containsMatchIn(imageUrl)) continue
            selected.putIfAbsent(pageUrl, DiscoveredCover(label, imageUrl, pageUrl))
        }
        val preferredMeta = listOf(
            meta(doc, "meta[property=og:image:secure_url]"),
            meta(doc, "meta[property=og:image]"),
            meta(doc, "meta[name=twitter:image]")
        ).firstNotNullOfOrNull { m -> absolute(location, m).takeIf { it.isNotEmpty() } }.orEmpty()
        val cover = selected.values.firstOrNull()?.imageUrl ?: preferredMeta
        return SiteCovers(cover, selected.values.take(8), System.currentTimeMillis())
    }

    /**
     * Async bitmap loader with disk+RAM cache and bounded downloads.
     * Never attempts to decode HTML or large/untrusted image payloads.
     */
    fun loadInto(target: ImageView, url: String) {
        if (!validPublicHttps(url)) return
        target.tag = url
        synchronized(memory) { memory.get(url) }?.let {
            target.setImageBitmap(it)
            return
        }
        io.execute {
            val bitmap = runCatching { downloadBitmap(url) }.getOrNull() ?: return@execute
            synchronized(memory) { memory.put(url, bitmap) }
            main.post {
                if (target.tag == url && target.isAttachedToWindow) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val file = File(artworkDir, digest(url) + ".jpg")
        if (file.isFile && file.length() in 1..2_500_000 &&
            System.currentTimeMillis() - file.lastModified() < 14L * 24 * 3600_000) {
            decode(file.readBytes())?.let { return it }
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6500
            readTimeout = 6500
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "StreamHubTV/1.7")
            setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,*/*;q=0.5")
        }
        try {
            if (connection.responseCode !in 200..299 ||
                !connection.contentType.orEmpty().lowercase().startsWith("image/")) return null
            val length = connection.contentLengthLong
            if (length > 2_500_000) return null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val block = ByteArray(8192)
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    if (output.size() + count > 2_500_000) return null
                    output.write(block, 0, count)
                }
            }
            val bytes = output.toByteArray()
            val result = decode(bytes) ?: return null
            if (artworkDir.listFiles().orEmpty().sumOf { it.length() } < 35_000_000) {
                runCatching {
                    file.outputStream().use { out ->
                        result.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                }
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 20 || bounds.outHeight < 20) return null
        var sample = 1
        while (bounds.outWidth / sample > 1100 || bounds.outHeight / sample > 1100) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun shutdown() {
        io.shutdownNow()
    }
}
