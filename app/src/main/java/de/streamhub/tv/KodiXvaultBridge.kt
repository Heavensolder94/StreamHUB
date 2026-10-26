package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional, local-only handoff to an already installed Kodi + xVAULT.
 *
 * xVAULT stays within Kodi. Only documented Kodi JSON-RPC methods are called.
 * The HTTP endpoint is hardcoded to 127.0.0.1, never a remote LAN or Internet host.
 * Kodi credentials are stored using Android Keystore-backed AES/GCM, not in GitHub.
 */
internal class KodiXvaultBridge(private val activity: Activity) {
    private val settings = activity.getSharedPreferences("streamhub_xvault", Activity.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val keyAlias = "streamhub_kodi_rpc_password_v1"

    fun close() { executor.shutdownNow() }

    private fun dp(n: Int) = (activity.resources.displayMetrics.density * n + .5f).toInt()
    private fun configured() = settings.getBoolean("configured", false)

    private data class Auth(val port: Int, val username: String, val password: String)

    private fun privateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setKeySize(256)
         .build())
        return generator.generateKey()
    }

    private fun encodePassword(password: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey())
        return Pair(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(
                cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        )
    }

    private fun decodePassword(): String {
        val iv = Base64.decode(settings.getString("iv", "").orEmpty(), Base64.NO_WRAP)
        val bytes = Base64.decode(settings.getString("secret", "").orEmpty(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
    }

    private fun auth(): Auth {
        val port = settings.getInt("port", 8080)
        require(port in 1024..65535) { "Ungültiger Kodi-Port." }
        val user = settings.getString("username", "kodi").orEmpty().trim()
        require(user.isNotBlank()) { "Kodi-Benutzername fehlt." }
        return Auth(port, user, decodePassword())
    }

    fun settingsDialog(then: (() -> Unit)? = null) {
        val layout = Ui.vertical(activity).apply {
            setPadding(dp(20), dp(5), dp(20), dp(10))
        }
        fun label(text: String) {
            layout.addView(Ui.text(activity, text, 13f, Ui.SOFT, true),
                Ui.lp(-1, dp(35)))
        }
        label("HTTP-Port von Kodi (auf derselben TV-Box)")
        val port = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(settings.getInt("port", 8080).toString())
        }
        layout.addView(port, Ui.lp(-1, dp(51)))
        label("Kodi-Benutzername")
        val username = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(settings.getString("username", "kodi"))
        }
        layout.addView(username, Ui.lp(-1, dp(51)))
        label("Kodi-HTTP-Passwort")
        val password = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            hint = if (configured()) "Leer lassen, um vorhandenes Passwort zu behalten"
                else "Passwort aus den Kodi-Einstellungen"
        }
        layout.addView(password, Ui.lp(-1, dp(51)))
        layout.addView(Ui.text(activity,
            "Kodi → Einstellungen → Dienste → Steuerung: HTTP-Fernsteuerung mit " +
                "Authentifizierung einschalten. xVAULT muss bereits in Kodi installiert sein. " +
                "Verbindung ausschließlich zu 127.0.0.1 (selbe TV-Box). " +
                "Kodi-Port niemals im Internet freigeben.", 12f, Ui.MUTED),
            Ui.lp(-1, dp(119)))
        AlertDialog.Builder(activity)
            .setTitle("Kodi + xVAULT verbinden")
            .setView(layout)
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Verbindung löschen") { _, _ ->
                settings.edit().clear().apply()
                Toast.makeText(activity, "Kodi-Verbindung zurückgesetzt.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Speichern") { _, _ ->
                val portNum = port.text.toString().toIntOrNull()
                val user = username.text.toString().trim()
                val entered = password.text.toString()
                if (portNum == null || portNum !in 1024..65535 || user.isBlank() ||
                    user.length > 80 || entered.length > 200) {
                    alert("Bitte Port 1024–65535, Benutzernamen und ein gültiges Passwort eingeben.")
                    return@setPositiveButton
                }
                if (!configured() && entered.isBlank()) {
                    alert("Bitte ein Kodi-HTTP-Passwort eingeben. " +
                        "Aktiviere zuerst die Authentifizierung in Kodi.")
                    return@setPositiveButton
                }
                runCatching {
                    val encrypted = if (entered.isNotEmpty()) encodePassword(entered) else null
                    val editor = settings.edit().putInt("port", portNum)
                        .putString("username", user).putBoolean("configured", true)
                    if (encrypted != null) {
                        editor.putString("iv", encrypted.first)
                            .putString("secret", encrypted.second)
                    }
                    editor.apply()
                }.onSuccess {
                    Toast.makeText(activity,
                        "Kodi-Verbindung gespeichert. Jetzt Verbindung testen.",
                        Toast.LENGTH_LONG).show()
                    then?.invoke()
                }.onFailure {
                    alert("Kodi-Anmeldedaten konnten nicht sicher gespeichert werden.")
                }
            }.show()
    }

    private fun alert(text: String) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            AlertDialog.Builder(activity).setTitle("Kodi / xVAULT")
                .setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    private fun rpc(auth: Auth, method: String, args: JSONObject): JSONObject {
        val call = JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", method).put("params", args)
        val conn = URL("http://127.0.0.1:" + auth.port + "/jsonrpc")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3800
            conn.readTimeout = 6500
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val login = auth.username + ":" + auth.password
            val encoded = Base64.encodeToString(
                login.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic " + encoded)
            conn.outputStream.use {
                it.write(call.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = conn.responseCode
            if (status == 401 || status == 403)
                error("Anmeldung fehlgeschlagen. Benutzername und Passwort in Kodi prüfen.")
            if (status !in 200..299)
                error("Kodi HTTP " + status + ". Läuft Kodi und ist die HTTP-Steuerung aktiviert?")
            val output = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (output.size() + n > 40000) error("Kodi-Antwort zu groß.")
                    output.write(buf, 0, n)
                }
            }
            val result = JSONObject(output.toString("UTF-8"))
            val rpcError = result.optJSONObject("error")
            if (rpcError != null) {
                error("Kodi JSON-RPC: " + rpcError.optString("message", "Unbekannter Fehler"))
            }
            if (!result.has("result")) error("Kodi antwortet ohne Ergebnis.")
            return result
        } finally {
            conn.disconnect()
        }
    }

    private fun checkInstalled(auth: Auth) {
        val result = rpc(auth, "Addons.GetAddonDetails",
            JSONObject().put("addonid", "plugin.video.xvault")
                .put("properties", org.json.JSONArray().put("enabled")))
        val addon = result.optJSONObject("result")?.optJSONObject("addon")
        if (addon == null) error("xVAULT nicht in Kodi gefunden. Addon zuerst dort installieren.")
        if (!addon.optBoolean("enabled", false)) error("xVAULT ist in Kodi deaktiviert.")
    }

    fun test() {
        if (!configured()) {
            settingsDialog()
            return
        }
        Toast.makeText(activity, "Prüfe Kodi + xVAULT …", Toast.LENGTH_SHORT).show()
        executor.execute {
            val outcome = runCatching {
                val a = auth()
                checkInstalled(a)
                "Verbindung erfolgreich! Kodi erreichbar und xVAULT aktiviert."
            }.getOrElse { "Verbindung fehlgeschlagen:\n" +
                (it.message ?: "Kodi nicht erreichbar. Prüfe Kodi und HTTP-Einstellungen.") }
            ui.post { alert(outcome) }
        }
    }

    /**
     * Starts the existing addon using its TMDbHelper-compatible action.
     * This sends only IDs and metadata. No scraping, downloads or stream extraction.
     * Kodi chooses how/if anything can be played.
     */
    fun play(title: CatalogTitle, imdb: String, season: Int = 0, episode: Int = 0) {
        if (!configured()) {
            settingsDialog { play(title, imdb, season, episode) }
            return
        }
        val launch = activity.packageManager.getLaunchIntentForPackage("org.xbmc.kodi")
        if (launch == null) {
            alert("Kodi ist auf dieser Android-TV-Box nicht installiert " +
                "oder kann nicht gestartet werden. Installiere zuerst Kodi " +
                "und anschließend xVAULT darin.")
            return
        }
        if (title.kind == "tv" && (season !in 1..999 || episode !in 1..999)) {
            alert("Für eine Serie bitte Staffel und Episode angeben.")
            return
        }
        Toast.makeText(activity, "Verbinde mit Kodi …", Toast.LENGTH_SHORT).show()
        executor.execute {
            val outcome = runCatching {
                val a = auth()
                checkInstalled(a)
                val params = JSONObject()
                    .put("action", "playTMDbHelper")
                    .put("tmdbhelper", "1")
                    .put("mediatype", if (title.kind == "movie") "movie" else "tvshow")
                    .put("title", title.name.take(180))
                    .put("originaltitle", title.name.take(180))
                    .put("tmdb_id", title.id.toString())
                    .put("year", title.year.takeIf { it.matches(Regex("^[0-9]{4}$")) } ?: "")
                val validImdb = DeVideoSrc.normalizeId(imdb)?.takeIf { it.startsWith("tt") }
                if (validImdb != null) params.put("imdb_id", validImdb)
                if (title.kind == "tv") {
                    params.put("showname", title.name.take(180))
                    params.put("season", season.toString())
                    params.put("episode", episode.toString())
                }
                // Running the add-on must be an explicit, user-initiated action.
                rpc(a, "Addons.ExecuteAddon",
                    JSONObject().put("addonid", "plugin.video.xvault")
                        .put("params", params).put("wait", false))
                Unit
            }
            ui.post {
                outcome.onSuccess {
                    Toast.makeText(activity, "Öffne Kodi – xVAULT übernimmt die Quellenwahl.",
                        Toast.LENGTH_LONG).show()
                    runCatching { activity.startActivity(launch) }.onFailure {
                        alert("Kodi hat den Auftrag empfangen, konnte aber nicht geöffnet werden.")
                    }
                }.onFailure {
                    alert("Übergabe an xVAULT fehlgeschlagen:\n" +
                        (it.message ?: "Kodi nicht erreichbar.") +
                        "\n\nKodi muss laufen und die HTTP-Steuerung muss aktiviert sein.")
                }
            }
        }
    }
}
