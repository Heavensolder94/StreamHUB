package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Local, TV-remote-friendly manager for up to 30 authorized MAG/STB profiles. */
class MacPortalProfilesActivity : Activity() {
    private val store by lazy { MacPortalStore(this) }
    private val tester = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        render()
    }

    private fun render() {
        val profiles = store.profiles()
        val activeId = store.active()?.id
        val root = Ui.vertical(this).apply {
            setPadding(dp(25), dp(17), dp(25), dp(12))
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)

        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "⚙  MEINE IPTV-PROFILE", 23f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(58), 1f))
        bar.addView(Ui.button(this, "＋ Neues Profil", true) { showEditDialog(null) },
            Ui.lp(dp(190), dp(48)).apply { rightMargin = dp(13) })
        bar.addView(Ui.button(this, "← Zurück", true) { finish() },
            Ui.lp(dp(130), dp(48)))
        root.addView(bar, Ui.lp(-1, dp(70)))

        val status = if (activeId == null) "Kein Portal aktiv"
            else "Aktiv: " + (profiles.firstOrNull { it.id == activeId }?.name ?: "")
        root.addView(Ui.text(this,
            profiles.size.toString() + " von 30 Profile gespeichert  ·  " + status +
                "\nEin Portal ist jeweils aktiv; die übrigen bleiben jederzeit verfügbar.",
            13f, Ui.SOFT), Ui.lp(-1, dp(64)))

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        val body = Ui.vertical(this).apply { setPadding(0, 0, dp(8), dp(30)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        if (profiles.isEmpty()) {
            body.addView(Ui.text(this,
                "Du hast noch keine Portal-URL/MAC-Kombination gespeichert.\n\n" +
                    "Klicke auf + Neues Profil. Du kannst verschiedene Anbieter " +
                    "hinterlegen und später mit einem Klick wechseln.",
                16f, Ui.SOFT), Ui.lp(-1, dp(184)))
        }

        profiles.forEach { profile ->
            val selected = profile.id == activeId
            body.addView(Ui.text(this,
                (if (selected) "●  AKTIV   " else "○  INAKTIV   ") + profile.name,
                19f, if (selected) Ui.TEXT else Ui.SOFT, selected),
                Ui.lp(-1, dp(41)))
            body.addView(Ui.text(this,
                profile.source.url.take(100) +
                    "     MAC: •••" + profile.source.mac.takeLast(5) +
                    (if (profile.source.allowHttp) "     HTTP erlaubt" else ""),
                12f, Ui.MUTED), Ui.lp(-1, dp(34)))
            val actions = Ui.horizontal(this)
            actions.addView(Ui.button(this,
                if (selected) "⏸  Deaktivieren" else "▶  Aktivieren", true) {
                action {
                    store.activate(if (selected) null else profile.id)
                }
            }, Ui.lp(dp(195), dp(48)).apply { rightMargin = dp(10) })
            actions.addView(Ui.button(this, "✎  Bearbeiten", true) {
                showEditDialog(profile)
            }, Ui.lp(dp(158), dp(48)).apply { rightMargin = dp(10) })
            actions.addView(Ui.button(this, "◉ Test", true) {
                testProfile(profile)
            }, Ui.lp(dp(104), dp(48)).apply { rightMargin = dp(10) })
            actions.addView(Ui.button(this, "🗑  Löschen", true) {
                confirmDelete(profile)
            }, Ui.lp(dp(134), dp(48)))
            body.addView(actions, Ui.lp(-1, dp(57)).apply { bottomMargin = dp(23) })
        }
    }

    private fun error(exception: Throwable) {
        Toast.makeText(this, exception.message ?: "Das Profil konnte nicht gespeichert werden.",
            Toast.LENGTH_LONG).show()
    }

    private inline fun action(operation: () -> Unit) {
        try {
            operation()
            render()
        } catch (exception: Exception) {
            error(exception)
        }
    }

    private fun confirmDelete(profile: MacPortalProfile) {
        AlertDialog.Builder(this)
            .setTitle("Portalprofil löschen?")
            .setMessage("Nur das Profil \"" + profile.name + "\" wird gelöscht. " +
                "Die anderen Portal-Zugänge bleiben gespeichert.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ -> action { store.delete(profile.id) } }
            .show()
    }

    private fun testProfile(profile: MacPortalProfile) {
        Toast.makeText(this, "Prüfe Verbindung zu " + profile.name + " …",
            Toast.LENGTH_SHORT).show()
        tester.execute {
            val outcome = runCatching { MacPortalClient(profile.source).connect() }
            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                val text = outcome.fold(
                    onSuccess = { "Handshake erfolgreich. Portal erreichbar.\n" +
                        "Ob einzelne Streams funktionieren, muss separat geprüft werden." },
                    onFailure = { "Handshake fehlgeschlagen:\n" +
                        (it.message ?: "Unbekannter Fehler").take(250) })
                AlertDialog.Builder(this).setTitle("Verbindungstest: " + profile.name)
                    .setMessage(text).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun showEditDialog(existing: MacPortalProfile?) {
        val activeId = store.active()?.id
        val layout = Ui.vertical(this).apply {
            setPadding(dp(18), dp(11), dp(18), dp(9))
        }
        layout.addView(Ui.text(this,
            "Nur vom Anbieter freigeschaltete MAG-/Stalker-Portale verwenden.",
            12f, Ui.SOFT), Ui.lp(-1, dp(44)))
        val name = EditText(this).apply {
            hint = "Profilname (z. B. IPTV Wohnzimmer)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name.orEmpty())
        }
        val address = EditText(this).apply {
            hint = "https://portal.beispiel.de/c/"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.source?.url.orEmpty())
        }
        val mac = EditText(this).apply {
            hint = "00:1A:79:12:34:56"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(existing?.source?.mac.orEmpty())
        }
        val http = CheckBox(this).apply {
            text = "Unsicheres HTTP ausdrücklich erlauben"
            isChecked = existing?.source?.allowHttp == true
        }
        val activate = CheckBox(this).apply {
            text = "Nach dem Speichern aktivieren"
            isChecked = if (existing == null) activeId == null else activeId == existing.id
        }
        layout.addView(name, Ui.lp(-1, dp(51)))
        layout.addView(address, Ui.lp(-1, dp(51)))
        layout.addView(mac, Ui.lp(-1, dp(51)))
        layout.addView(http, Ui.lp(-1, dp(43)))
        layout.addView(activate, Ui.lp(-1, dp(43)))
        layout.addView(Ui.text(this,
            "Ein Profil kann jederzeit deaktiviert werden, ohne es zu löschen. " +
                "URL und MAC werden lokal verschlüsselt gespeichert. " +
                "Über HTTP kann die MAC im Netzwerk sichtbar sein.",
            12f, Ui.MUTED), Ui.lp(-1, dp(81)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Portalprofil hinzufügen" else "Portalprofil bearbeiten")
            .setView(layout)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val source = MacPortalSource.fromInput(
                        address.text.toString(), mac.text.toString(), http.isChecked)
                    store.upsert(name.text.toString(), source, existing?.id, activate.isChecked)
                    if (existing != null && existing.id == activeId && !activate.isChecked)
                        store.activate(null)
                    dialog.dismiss()
                    render()
                } catch (exception: Exception) {
                    error(exception)
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        tester.shutdownNow()
        super.onDestroy()
    }
}
