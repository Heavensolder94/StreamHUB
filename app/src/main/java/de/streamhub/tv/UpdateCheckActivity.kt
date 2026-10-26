package de.streamhub.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Read-only update checker. It never downloads or installs unsigned APK files.
 * Users install releases from their own GitHub Actions artifacts.
 */
class UpdateCheckActivity : Activity() {
    private val workers = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(30), dp(27), dp(30), dp(22))
        }
        setContentView(root)
        root.addView(Ui.text(this, "↻  STREAMHUB · UPDATE-CHECK", 26f, Ui.TEXT, true),
            Ui.lp(-1, dp(70)))
        val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        root.addView(Ui.text(this, "Installierte Version: " + installed, 17f, Ui.SOFT),
            Ui.lp(-1, dp(54)))
        status = Ui.text(this, "Prüfe GitHub-Hauptbranch …", 15f, Ui.SOFT)
        root.addView(status, Ui.lp(-1, dp(94)))
        root.addView(Ui.text(this,
            "Es erfolgt nur ein Versionsvergleich. Es wird nichts automatisch " +
                "heruntergeladen oder installiert. APKs sollten ausschließlich aus " +
                "dem vertrauenswürdigen StreamHUB-Repository stammen.",
            13f, Ui.MUTED), Ui.lp(-1, dp(85)))
        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "↗ GitHub Builds", true) {
            startActivity(Intent(this, BrowserActivity::class.java).apply {
                putExtra("name", "StreamHUB Builds")
                putExtra("url", "https://github.com/Heavensolder94/StreamHUB/actions")
            })
        }, Ui.lp(dp(250), dp(55)).apply { rightMargin = dp(13) })
        row.addView(Ui.button(this, "← Zurück", true) { finish() },
            Ui.lp(dp(160), dp(55)))
        root.addView(row, Ui.lp(-1, dp(67)))
        workers.execute {
            val result = runCatching {
                val url = URL("https://raw.githubusercontent.com/Heavensolder94/StreamHUB/main/app/build.gradle.kts")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 9000
                    connection.instanceFollowRedirects = false
                    require(connection.responseCode == 200) { "GitHub HTTP " + connection.responseCode }
                    val lines = connection.inputStream.bufferedReader().use { reader ->
                        reader.readText().take(10_000)
                    }
                    Regex("versionName\\s*=\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+)\"")
                        .find(lines)?.groupValues?.get(1) ?: error("Keine Version gefunden.")
                } finally {
                    connection.disconnect()
                }
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                status.text = result.fold(
                    onSuccess = { latest ->
                        val cmp = compareVersions(latest, installed)
                        if (cmp > 0) "Neue Version im Hauptbranch: " + latest +
                            ". Öffne GitHub Builds und lade die neue APK herunter."
                        else if (cmp == 0) "Du verwendest bereits Version " + latest + "."
                        else "Online-Hauptbranch: " + latest +
                            " · Installiert: " + installed + "."
                    },
                    onFailure = { "Prüfung derzeit nicht möglich: " +
                        (it.message ?: "Netzwerkfehler") })
            }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val first = a.split(".").map { it.toIntOrNull() ?: 0 }
        val second = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val diff = first.getOrElse(i) { 0 }.compareTo(second.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }
}
