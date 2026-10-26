package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Explicit, rate-limited, one-shot lookups of DEVIDEOSRC's documented public JSON endpoints. */
internal object DeVideoApi {
    private val executor = Executors.newSingleThreadExecutor()
    fun inspect(activity: Activity, endpoint: String, heading: String) {
        if (endpoint != "/api/providers" &&
            !endpoint.matches(Regex("^/api/resolve\\?id=(tt[0-9]{5,12}|[1-9][0-9]{0,11})$"))) {
            Toast.makeText(activity, "Ungültige API-Abfrage.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "API wird abgefragt …", Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = runCatching {
                val connection = URL("https://devideosrc.co" + endpoint).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    if (code !in 200..299) error("HTTP " + code + " – API-Antwort nicht verfügbar.")
                    if (!(connection.contentType ?: "").contains("json", ignoreCase = true))
                        error("Der Dienst hat kein JSON zurückgegeben.")
                    val body = connection.inputStream.use { input ->
                        val buffer = ByteArray(65537)
                        var total = 0
                        while (total < buffer.size) {
                            val count = input.read(buffer, total, buffer.size - total)
                            if (count <= 0) break
                            total += count
                        }
                        if (total > 65536) error("API-Antwort ist zu groß.")
                        String(buffer, 0, total, Charsets.UTF_8)
                    }
                    val data = if (body.trimStart().startsWith("[")) JSONArray(body).toString(2)
                        else JSONObject(body).toString(2)
                    data.take(7000) + if (data.length > 7000) "\n\n…gekürzt" else ""
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { "Abfrage fehlgeschlagen:\n" + (it.message ?: "Unbekannter Fehler") }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AlertDialog.Builder(activity)
                        .setTitle(heading)
                        .setMessage(result)
                        .setPositiveButton("Schließen", null)
                        .show()
                }
            }
        }
    }
}
