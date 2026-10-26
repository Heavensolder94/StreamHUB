package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks official StreamHUB GitHub Releases for a newer, signed APK.
 * An update always requires the user's confirmation in Android's installer.
 */
class GithubUpdateManager(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences("streamhub_update", Activity.MODE_PRIVATE)
    private val endpoint = "https://api.github.com/repos/Heavensolder94/StreamHUB/releases/latest"
    private val maxApkBytes = 150L * 1024L * 1024L

    private data class Release(
        val version: String,
        val code: Long,
        val notes: String,
        val url: String,
        val bytes: Long,
        val sha256: String
    )

    fun check(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_check", 0L)
        if (!manual && now - last in 0 until (12L * 60L * 60L * 1000L)) return
        preferences.edit().putLong("last_check", now).apply()
        executor.execute {
            val result = runCatching { getNewRelease() }
            ui.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { release ->
                    if (release != null) showUpdate(release)
                    else if (manual) Toast.makeText(activity, "StreamHUB ist aktuell oder noch kein Release vorhanden.", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    if (manual) AlertDialog.Builder(activity)
                        .setTitle("Update-Prüfung nicht möglich")
                        .setMessage(error.message ?: "GitHub ist derzeit nicht erreichbar.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun installedCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun getNewRelease(): Release? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "StreamHUB-Android-TV-Updater")
        }
        try {
            // GitHub responds 404 when the repository has not published a release yet.
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) error("GitHub antwortet mit HTTP ${connection.responseCode}.")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val versionCode = Regex("^StreamHUB-.*-vc([0-9]+)\\.apk$").matchEntire(name)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (versionCode <= installedCode()) continue
                val url = asset.optString("browser_download_url")
                val parsed = Uri.parse(url)
                if (parsed.scheme != "https" || parsed.host != "github.com" ||
                    !parsed.path.orEmpty().startsWith("/Heavensolder94/StreamHUB/releases/download/")) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) continue
                val size = asset.optLong("size")
                if (size !in 1..maxApkBytes) continue
                return Release(
                    json.optString("tag_name").ifBlank { name },
                    versionCode,
                    json.optString("body").take(1000),
                    url,
                    size,
                    digest.lowercase()
                )
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdate(release: Release) {
        val notes = release.notes.ifBlank { "Verbesserungen und Fehlerbehebungen." }
        AlertDialog.Builder(activity)
            .setTitle("StreamHUB Update verfügbar")
            .setMessage("Neue Version: ${release.version}\n\n${notes}\n\nJetzt herunterladen und installieren? Deine App-Daten bleiben bei regulären Updates erhalten.")
            .setPositiveButton("Update installieren") { _, _ -> downloadAndInstall(release) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun downloadAndInstall(release: Release) {
        val cancelled = AtomicBoolean(false)
        val progress = AlertDialog.Builder(activity)
            .setTitle("StreamHUB Update")
            .setMessage("APK wird heruntergeladen …")
            .setNegativeButton("Abbrechen") { _, _ -> cancelled.set(true) }
            .create()
        progress.setCancelable(false)
        progress.show()

        executor.execute {
            val result = runCatching {
                val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
                val destination = File(folder, "streamhub-update.apk")
                if (destination.exists() && destination.length() == release.bytes &&
                    sha256(destination).equals(release.sha256, true)) return@runCatching destination
                val partial = File(folder, "streamhub-update.partial")
                partial.delete()
                try {
                    val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "StreamHUB-Android-TV-Updater")
                    }
                    try {
                        if (connection.responseCode != 200) error("APK-Download fehlgeschlagen (HTTP ${connection.responseCode}).")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var received = 0L
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    if (cancelled.get()) error("Download abgebrochen.")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received > maxApkBytes || received > release.bytes) error("Unerwartete Dateigröße.")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                }
                            }
                        }
                        if (received != release.bytes) error("APK ist unvollständig.")
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(release.sha256, true)) error("Prüfsumme stimmt nicht. Update verworfen.")
                        if (cancelled.get()) error("Download abgebrochen.")
                        if (destination.exists()) destination.delete()
                        if (!partial.renameTo(destination)) error("APK konnte nicht gespeichert werden.")
                        destination
                    } finally {
                        connection.disconnect()
                    }
                } finally {
                    partial.delete()
                }
            }
            ui.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                progress.dismiss()
                result.onSuccess { file -> if (!cancelled.get()) install(file) }
                    .onFailure { error ->
                        if (!cancelled.get()) AlertDialog.Builder(activity)
                            .setTitle("Update fehlgeschlagen")
                            .setMessage(error.message ?: "Download konnte nicht abgeschlossen werden.")
                            .setPositiveButton("OK", null).show()
                    }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes < 0) break
                digest.update(buffer, 0, bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Installation erlauben")
                .setMessage("Android benötigt einmalig die Freigabe „Apps aus dieser Quelle installieren“ für StreamHUB. Kehre anschließend zurück und starte das Update erneut.")
                .setPositiveButton("Einstellungen öffnen") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Abbrechen", null).show()
            return
        }
        try {
            val apk = FileProvider.getUriForFile(activity, "${activity.packageName}.updateprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apk, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (error: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Installer nicht verfügbar")
                .setMessage(error.message ?: "Auf dieser Android-TV-Box konnte kein APK-Installer gestartet werden.")
                .setPositiveButton("OK", null).show()
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
