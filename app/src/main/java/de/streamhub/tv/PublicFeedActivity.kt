package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Native RSS/Atom/JSON Feed integration; media enclosures only when published. */
class PublicFeedActivity : Activity() {
    private val store by lazy { PublicFeedStore(this) }
    private val covers by lazy { WebsiteCovers(this) }
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        draw()
    }

    private fun draw() {
        val root = Ui.vertical(this).apply {
            setPadding(dp(22), dp(15), dp(22), dp(12))
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)
        val bar = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "▧ STREAMHUB · NATIVE WEB-FEEDS", 22f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, dp(59), 1f))
        bar.addView(Ui.button(this, "＋ Feed", true) { edit(null) },
            Ui.lp(dp(131), dp(48)).apply { rightMargin = dp(8) })
        bar.addView(Ui.button(this, "↻ Laden", true) { refresh(true) },
            Ui.lp(dp(135), dp(48)).apply { rightMargin = dp(8) })
        bar.addView(Ui.button(this, "← Zurück", true) { finish() },
            Ui.lp(dp(135), dp(48)))
        root.addView(bar, Ui.lp(-1, dp(68)))
        root.addView(Ui.text(this,
            "Öffentliche RSS-/Atom-/JSON-Feeds werden hier direkt als StreamHUB-Karten " +
                "angezeigt. Originalseiten bleiben beim Anbieter. Nur explizit " +
                "veröffentlichte direkte Videos werden nativ abgespielt.",
            13f, Ui.SOFT), Ui.lp(-1, dp(66)))
        status = Ui.text(this, "Deine Feeds", 13f, Ui.FOCUS)
        root.addView(status, Ui.lp(-1, dp(42)))
        val scroller = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        list = Ui.vertical(this).apply { setPadding(0, 0, dp(10), dp(23)) }
        scroller.addView(list)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh(false)
    }

    private fun refresh(force: Boolean) {
        list.removeAllViews()
        val profiles = store.profiles()
        if (profiles.isEmpty()) {
            list.addView(Ui.text(this,
                "Noch keine Feeds gespeichert. Wähle „+ Feed“ und trage eine " +
                    "öffentliche HTTPS-Feed-URL deines Anbieters ein.",
                15f, Ui.SOFT), Ui.lp(-1, dp(102)))
        }
        profiles.forEach { profile ->
            val row = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = Ui.vertical(this)
            labels.addView(Ui.text(this,
                (if (profile.enabled) "● " else "○ ") + profile.name, 17f,
                if (profile.enabled) Ui.TEXT else Ui.MUTED, true), Ui.lp(-1, dp(37)))
            labels.addView(Ui.text(this, profile.url, 11f, Ui.MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, Ui.lp(-1, dp(28)))
            row.addView(labels, LinearLayout.LayoutParams(0, dp(72), 1f))
            row.addView(Ui.button(this, if (profile.enabled) "Ausschalten" else "Aktivieren", true) {
                store.toggle(profile.id)
                draw()
            }, Ui.lp(dp(139), dp(47)).apply { rightMargin = dp(8) })
            row.addView(Ui.button(this, "Ändern", true) { edit(profile) },
                Ui.lp(dp(104), dp(47)).apply { rightMargin = dp(8) })
            row.addView(Ui.button(this, "Löschen", true) {
                AlertDialog.Builder(this).setTitle(profile.name + " entfernen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        store.delete(profile.id)
                        draw()
                    }.setNegativeButton("Abbrechen", null).show()
            }, Ui.lp(dp(106), dp(47)))
            list.addView(row, Ui.lp(-1, dp(81)))
        }
        if (profiles.none { it.enabled }) {
            status.text = "Keine aktiven Feed-Quellen."
            return
        }
        status.text = "Lade Katalogdaten …"
        val holder = Ui.vertical(this)
        list.addView(holder)
        PublicFeedAdapter.refresh(this, force) { entries, errors ->
            if (isDestroyed || isFinishing || holder.parent == null) return@refresh
            holder.removeAllViews()
            status.text = entries.size.toString() + " Katalogeinträge" +
                (if (errors.isNotEmpty()) " · " + errors.size + " Fehler" else "")
            if (errors.isNotEmpty()) {
                holder.addView(Ui.text(this, errors.take(3).joinToString("\n"),
                    12f, Ui.MUTED), Ui.lp(-1, dp(80)))
            }
            if (entries.isEmpty()) {
                holder.addView(Ui.text(this, "Keine Einträge im Feed gefunden.",
                    14f, Ui.SOFT), Ui.lp(-1, dp(80)))
            }
            entries.take(80).chunked(4).forEach { batch ->
                val row = Ui.horizontal(this).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                batch.forEach { item ->
                    row.addView(card(item), LinearLayout.LayoutParams(0, dp(197), 1f)
                        .apply { rightMargin = dp(12); bottomMargin = dp(11) })
                }
                repeat(4 - batch.size) {
                    row.addView(android.view.View(this),
                        LinearLayout.LayoutParams(0, dp(197), 1f).apply {
                            rightMargin = dp(12)
                        })
                }
                holder.addView(row, Ui.lp(-1, dp(213)))
            }
        }
    }

    private fun card(entry: PublicFeedEntry): FrameLayout {
        val tile = FrameLayout(this).apply {
            background = Ui.bg(Ui.PANEL, 14f, context = this@PublicFeedActivity)
        }
        tile.addView(CinematicArtwork(this, entry.title, "Filme"),
            FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        tile.addView(image, FrameLayout.LayoutParams(-1, -1))
        covers.loadInto(image, entry.coverUrl)
        tile.addView(android.view.View(this).apply {
            background = Ui.gradient(this@PublicFeedActivity,
                intArrayOf(0x230A1221, 0xF2090F1B.toInt()), 14f,
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val bottom = Ui.vertical(this).apply {
            setPadding(dp(11), 0, dp(10), dp(11))
        }
        bottom.addView(Ui.text(this, entry.title, 15f, Ui.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(-1, dp(54)))
        bottom.addView(Ui.text(this,
            (if (entry.videoUrl.isBlank()) "↗ WEBSITE" else "▶ VIDEO") +
                " · " + entry.provider, 10f, Ui.FOCUS), Ui.lp(-1, dp(25)))
        tile.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        Ui.focusCard(tile, 14f, 1.085f)
        tile.setOnClickListener { open(entry) }
        return tile
    }

    private fun open(item: PublicFeedEntry) {
        val actions = if (item.videoUrl.isBlank()) arrayOf("↗ Originalseite öffnen")
            else arrayOf("▶ StreamHUB-Player", "↗ Originalseite öffnen")
        AlertDialog.Builder(this).setTitle(item.title)
            .setMessage(item.description.ifBlank { "Quelle: " + item.provider })
            .setItems(actions) { _, choice ->
                if (item.videoUrl.isNotBlank() && choice == 0) {
                    startActivity(Intent(this, LiveTvPlayerActivity::class.java).apply {
                        putExtra("direct_stream_url", item.videoUrl)
                        putExtra("direct_stream_title", item.title)
                        putExtra("direct_stream_is_live", false)
                        putExtra("direct_allow_http", false)
                        putExtra("history_id", "feed:" + item.id)
                        putExtra("history_kind", "vod")
                    })
                } else {
                    startActivity(Intent(this, BrowserActivity::class.java).apply {
                        putExtra("url", item.pageUrl)
                        putExtra("name", item.provider + " · " + item.title)
                    })
                }
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun edit(existing: PublicFeedProfile?) {
        val panel = Ui.vertical(this).apply {
            setPadding(dp(18), dp(13), dp(18), dp(10))
        }
        val name = EditText(this).apply {
            hint = "Name des Anbieters"
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        val url = EditText(this).apply {
            hint = "https://anbieter.example/feed.xml"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
        }
        panel.addView(name, Ui.lp(-1, dp(59)))
        panel.addView(url, Ui.lp(-1, dp(59)))
        panel.addView(Ui.text(this,
            "Nur vom Anbieter veröffentlichte, freigegebene HTTPS-Feeds. " +
                "Eine gewöhnliche Website-Adresse ist kein Feed.",
            12f, Ui.MUTED), Ui.lp(-1, dp(78)))
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Neue Feed-Quelle" else "Feed bearbeiten")
            .setView(panel).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.upsert(name.text.toString(), url.text.toString(), existing?.id)
                    dlg.dismiss()
                    draw()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Speichern fehlgeschlagen",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        dlg.show()
    }

    override fun onDestroy() {
        covers.shutdown()
        super.onDestroy()
    }
}
