package de.streamhub.tv

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Direct, explicitly configured media streams for named films or series.
 * Never guesses a playable URL from an arbitrary web page.
 * URLs can contain tokens; all values are encrypted on device with Android Keystore.
 */
internal data class DirectTitleSource(
    val id: String, val kind: String, val title: String,
    val provider: String, val streamUrl: String
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title)
        .put("provider", provider).put("streamUrl", streamUrl)

    companion object {
        fun fromJson(j: JSONObject) = DirectTitleSource(
            j.optString("id"), j.optString("kind"), j.optString("title"),
            j.optString("provider"), j.optString("streamUrl")
        )
    }
}

internal class DirectTitleSourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("streamhub_direct_titles_v30",
        Context.MODE_PRIVATE)
    private val alias = "streamhub_direct_titles_aes_v30"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val previous = ks.getKey(alias, null) as? SecretKey
        if (previous != null) return previous
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun all(): List<DirectTitleSource> = runCatching {
        val saved = prefs.getString("encrypted", null) ?: return emptyList()
        val data = Base64.decode(saved, Base64.DEFAULT)
        require(data.size > 28) { "Ungültige gespeicherte Medienlinks." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val decoded = String(cipher.doFinal(data.copyOfRange(12, data.size)),
            Charsets.UTF_8)
        val array = JSONArray(decoded)
        (0 until minOf(array.length(), 150)).mapNotNull { i ->
            array.optJSONObject(i)?.let(DirectTitleSource::fromJson)
                ?.takeIf { it.id.isNotBlank() && it.streamUrl.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun persist(sources: List<DirectTitleSource>) {
        val json = JSONArray()
        sources.take(150).forEach { json.put(it.json()) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(
            cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        require(prefs.edit().putString("encrypted", encoded).commit()) {
            "Stream-Link konnte nicht sicher gespeichert werden."
        }
    }

    fun forTitle(kind: String, title: String): List<DirectTitleSource> {
        val normalized = NativeSourceIndex.normalized(title)
        return all().filter {
            it.kind == kind && NativeSourceIndex.normalized(it.title) == normalized
        }
    }

    fun add(kind: String, title: String, provider: String, rawUrl: String) {
        val display = title.trim().take(170)
        val sourceName = provider.trim().take(65)
        val uri = Uri.parse(rawUrl.trim())
        val url = rawUrl.trim().take(2048)
        require(kind == "vod" || kind == "series" || kind == "itv") {
            "Unbekannte Medienart."
        }
        require(display.length >= 2 && sourceName.length >= 2) {
            "Film- und Quellenname sind erforderlich."
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.encodedAuthority?.contains("@") != true &&
            uri.fragment == null && rawUrl.length <= 2048) {
            "Bitte eine gültige HTTPS-Video-URL verwenden."
        }
        val path = uri.path.orEmpty().lowercase()
        val accepted = listOf(".m3u8", ".mpd", ".mp4", ".m4v",
            ".webm", ".mkv", ".ts", ".mov").any { path.endsWith(it) }
        require(accepted) {
            "StreamHUB benötigt einen direkten Medienlink (.m3u8, .mpd, .mp4 usw.), " +
                "keine normale Webseiten-URL."
        }
        val items = all()
        require(items.size < 150) { "Maximal 150 eigene Medienlinks." }
        require(items.none {
            it.kind == kind && it.title == display && it.streamUrl == url
        }) { "Dieser Stream-Link ist bereits für den Titel gespeichert." }
        persist(items + DirectTitleSource(UUID.randomUUID().toString(),
            kind, display, sourceName, url))
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }
}
