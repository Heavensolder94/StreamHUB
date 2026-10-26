package de.streamhub.tv

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes published by DEVIDEOSRC. Numeric values are provider-native IDs,
 * not automatically TMDB IDs. No stream URL extraction, scraping or DRM handling.
 */
internal object DeVideoSrc {
    private const val base = "https://devideosrc.co"
    private val validId = Regex("^(tt[0-9]{5,12}|[1-9][0-9]{0,11})$", RegexOption.IGNORE_CASE)

    fun normalizeId(input: String): String? {
        val candidate = input.trim()
        return candidate.takeIf { validId.matches(it) }?.lowercase(java.util.Locale.ROOT)
    }

    data class Options(
        val autoplay: Boolean = false,
        val muted: Boolean = false,
        val controls: Boolean = true,
        val preferred: String = ""
    )

    fun options(context: Context): Options {
        val p = context.getSharedPreferences("streamhub_local", Context.MODE_PRIVATE)
        return Options(
            p.getBoolean("devideosrc_autoplay", false),
            p.getBoolean("devideosrc_muted", false),
            p.getBoolean("devideosrc_controls", true),
            p.getString("devideosrc_server", "").orEmpty().trim()
        )
    }

    private fun params(o: Options): String {
        val query = "?color=%236AB7FF&autoplay=" + (if (o.autoplay) "1" else "0") +
            "&muted=" + (if (o.muted) "1" else "0") +
            "&controls=" + (if (o.controls) "1" else "0")
        val server = o.preferred.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,50}$")) }
        return query + (if (server != null) "&lastserver=" + server else "")
    }

    fun movie(id: String, custom: Boolean = false, o: Options = Options()): String {
        require(normalizeId(id) == id)
        return if (custom) base + "/custom/movie/" + id + params(o)
            else base + "/movie/" + id
    }

    fun series(id: String): String {
        require(normalizeId(id) == id)
        return base + "/serial/" + id
    }

    fun episode(id: String, season: Int, episode: Int, o: Options = Options()): String {
        require(normalizeId(id) == id)
        require(season in 1..999 && episode in 1..999)
        return base + "/custom/serial/" + id + "/" + season + "/" + episode + params(o)
    }
}

/** Native, D-pad-focusable Android TV form; playback stays in BrowserActivity. */
internal class DeVideoSrcPanel(
    private val activity: Activity,
    private val onOpen: (String, String) -> Unit
) {
    private val prefs = activity.getSharedPreferences("streamhub_local", Context.MODE_PRIVATE)
    private fun dp(n: Int) = (activity.resources.displayMetrics.density * n + .5f).toInt()

    private data class Recent(
        val id: String, val kind: String, val season: Int, val episode: Int,
        val custom: Boolean, val label: String
    )

    private fun readRecent(): List<Recent> {
        val raw = prefs.getString("devideosrc_recent", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until minOf(array.length(), 15)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = DeVideoSrc.normalizeId(item.optString("id")) ?: return@mapNotNull null
                val kind = item.optString("kind")
                if (kind !in setOf("movie", "series", "episode")) return@mapNotNull null
                val season = item.optInt("season", 1)
                val episode = item.optInt("episode", 1)
                if (season !in 1..999 || episode !in 1..999) return@mapNotNull null
                Recent(id, kind, season, episode, item.optBoolean("custom", false),
                    item.optString("label", "").take(90))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecent(item: Recent) {
        val key: (Recent) -> String = {
            it.kind + ":" + it.id + ":" + it.season + ":" + it.episode
        }
        val all = (listOf(item) + readRecent().filter { key(it) != key(item) }).take(10)
        val json = JSONArray()
        all.forEach {
            json.put(JSONObject()
                .put("id", it.id).put("kind", it.kind).put("season", it.season)
                .put("episode", it.episode).put("custom", it.custom).put("label", it.label))
        }
        prefs.edit().putString("devideosrc_recent", json.toString()).apply()
    }

    private fun url(item: Recent): String = when (item.kind) {
        "movie" -> DeVideoSrc.movie(item.id, item.custom, DeVideoSrc.options(activity))
        "episode" -> DeVideoSrc.episode(item.id, item.season, item.episode, DeVideoSrc.options(activity))
        else -> DeVideoSrc.series(item.id)
    }

    private fun display(item: Recent): String {
        val prefix = when (item.kind) {
            "movie" -> "Film"
            "episode" -> "S" + item.season + " E" + item.episode
            else -> "Serie"
        }
        val name = item.label.ifBlank { item.id }
        return prefix + " · " + name
    }

    fun render(into: LinearLayout) {
        into.addView(Ui.text(activity, "DEVIDEOSRC · DIREKTER PLAYER", 12f, Ui.BLUE, true),
            Ui.lp(-1, dp(39)))
        into.addView(Ui.text(activity, "Film oder Serie über eine IMDb-ID öffnen", 24f, Ui.TEXT, true),
            Ui.lp(-1, dp(46)))
        into.addView(Ui.text(activity,
            "IMDb-ID (tt...) oder numerische DeVideoSRC-ID eingeben. " +
            "Der Player öffnet sich direkt innerhalb von StreamHUB.",
            14f, Ui.SOFT), Ui.lp(-1, dp(62)))

        val idInput = EditText(activity).apply {
            hint = "IMDb-ID, z. B. tt1234567"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString("devideosrc_last_id", "") ?: "")
        }
        into.addView(Ui.text(activity, "FILM- ODER SERIEN-ID", 12f, Ui.MUTED, true),
            Ui.lp(-1, dp(29)))
        into.addView(idInput, Ui.lp(-1, dp(55)).apply { bottomMargin = dp(12) })

        val nameInput = EditText(activity).apply {
            hint = "Optional: Titel für die Verlaufsliste"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        into.addView(nameInput, Ui.lp(-1, dp(55)).apply { bottomMargin = dp(12) })

        val episodeInputs = Ui.horizontal(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val seasonInput = EditText(activity).apply {
            hint = "Staffel"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("1")
        }
        val episodeInput = EditText(activity).apply {
            hint = "Episode"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("1")
        }
        episodeInputs.addView(Ui.text(activity, "STAFFEL", 12f, Ui.MUTED, true),
            Ui.lp(dp(68), dp(47)))
        episodeInputs.addView(seasonInput, Ui.lp(dp(95), dp(47)).apply { rightMargin = dp(18) })
        episodeInputs.addView(Ui.text(activity, "EPISODE", 12f, Ui.MUTED, true),
            Ui.lp(dp(77), dp(47)))
        episodeInputs.addView(episodeInput, Ui.lp(dp(95), dp(47)))
        into.addView(episodeInputs, Ui.lp(-1, dp(61)))

        val custom = CheckBox(activity).apply {
            text = "Custom-Player für Filme (StreamHUB-Farbe)"
            setTextColor(Ui.SOFT)
            isChecked = prefs.getBoolean("devideosrc_custom", true)
            isFocusable = true
        }
        into.addView(custom, Ui.lp(-1, dp(48)))

        fun setting(name: String, key: String, default: Boolean): CheckBox =
            CheckBox(activity).apply {
                text = name
                setTextColor(Ui.SOFT)
                isFocusable = true
                isChecked = prefs.getBoolean(key, default)
                into.addView(this, Ui.lp(-1, dp(43)))
            }
        val autoplay = setting("Automatisch abspielen", "devideosrc_autoplay", false)
        val muted = setting("Stumm starten", "devideosrc_muted", false)
        val controls = setting("Native Player-Steuerung anzeigen", "devideosrc_controls", true)
        into.addView(Ui.text(activity, "BEVORZUGTER SERVER (OPTIONAL)", 12f, Ui.MUTED, true),
            Ui.lp(-1, dp(28)))
        val preferred = EditText(activity).apply {
            setSingleLine(true)
            hint = "Server-ID, z. B. dropload"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString("devideosrc_server", "") ?: "")
        }
        into.addView(preferred, Ui.lp(-1, dp(54)).apply { bottomMargin = dp(12) })

        fun start(kind: String) {
            val id = DeVideoSrc.normalizeId(idInput.text.toString())
            if (id == null) {
                Toast.makeText(activity,
                    "Bitte eine gültige IMDb-ID (tt...) oder numerische DeVideoSRC-ID eingeben.",
                    Toast.LENGTH_LONG).show()
                idInput.requestFocus()
                return
            }
            val season = seasonInput.text.toString().toIntOrNull()
            val episode = episodeInput.text.toString().toIntOrNull()
            if (kind == "episode" && (season == null || episode == null ||
                        season !in 1..999 || episode !in 1..999)) {
                Toast.makeText(activity, "Bitte Staffel und Episode von 1 bis 999 eingeben.",
                    Toast.LENGTH_LONG).show()
                seasonInput.requestFocus()
                return
            }
            val server = preferred.text.toString().trim()
            if (server.isNotEmpty() && !server.matches(Regex("^[A-Za-z0-9_-]{1,50}$"))) {
                Toast.makeText(activity, "Server-ID: nur Buchstaben, Zahlen, _ und -.",
                    Toast.LENGTH_LONG).show()
                preferred.requestFocus()
                return
            }
            val item = Recent(id, kind, season ?: 1, episode ?: 1, custom.isChecked,
                nameInput.text.toString().trim().take(90))
            prefs.edit().putString("devideosrc_last_id", id)
                .putBoolean("devideosrc_custom", custom.isChecked)
                .putBoolean("devideosrc_autoplay", autoplay.isChecked)
                .putBoolean("devideosrc_muted", muted.isChecked)
                .putBoolean("devideosrc_controls", controls.isChecked)
                .putString("devideosrc_server", server).apply()
            saveRecent(item)
            onOpen(display(item), url(item))
        }

        val buttons = Ui.horizontal(activity)
        buttons.addView(Ui.button(activity, "▶  Film starten", true) { start("movie") },
            Ui.lp(dp(164), dp(48)).apply { rightMargin = dp(12) })
        buttons.addView(Ui.button(activity, "▶  Serie öffnen", true) { start("series") },
            Ui.lp(dp(173), dp(48)).apply { rightMargin = dp(12) })
        buttons.addView(Ui.button(activity, "▶  Folge starten", true) { start("episode") },
            Ui.lp(dp(179), dp(48)))
        into.addView(buttons, Ui.lp(-1, dp(62)).apply { bottomMargin = dp(13) })
        into.addView(Ui.text(activity,
            "Serien öffnen: Staffelwahl im Standardplayer. Folge starten: direkte Episodenwahl. " +
            "Ob ein Video abspielbar ist, hängt vom Drittanbieter und der Android-WebView ab.",
            12f, Ui.MUTED), Ui.lp(-1, dp(58)))

        val apiRow = Ui.horizontal(activity)
        apiRow.addView(Ui.button(activity, "API: Anbieter anzeigen", true) {
            DeVideoApi.inspect(activity, "/api/providers", "DeVideoSRC · Anbieter")
        }, Ui.lp(dp(220), dp(47)).apply { rightMargin = dp(11) })
        apiRow.addView(Ui.button(activity, "API: Titelinformationen", true) {
            val id = DeVideoSrc.normalizeId(idInput.text.toString())
            if (id == null) {
                Toast.makeText(activity, "Zuerst eine gültige ID eingeben.",
                    Toast.LENGTH_SHORT).show()
            } else {
                DeVideoApi.inspect(activity, "/api/resolve?id=" + id, "DeVideoSRC · Titel")
            }
        }, Ui.lp(dp(220), dp(47)))
        into.addView(apiRow, Ui.lp(-1, dp(57)).apply { bottomMargin = dp(9) })
        into.addView(Ui.text(activity,
            "Die API-Endpunkte sind dokumentiert; das genaue Abfrageformat und " +
            "die Antwortfelder wurden vom Betreiber nicht vollständig beschrieben. " +
            "Anbieter-/Titelabfragen sind daher experimentell.",
            12f, Ui.MUTED), Ui.lp(-1, dp(68)))

        val recent = readRecent()
        if (recent.isNotEmpty()) {
            into.addView(Ui.text(activity, "ZULETZT GESTARTET", 19f, Ui.TEXT, true),
                Ui.lp(-1, dp(48)))
            recent.forEach { item ->
                val row = Ui.horizontal(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(Ui.button(activity, "▶  " + display(item), true) {
                    prefs.edit().putString("devideosrc_last_id", item.id).apply()
                    saveRecent(item)
                    onOpen(display(item), url(item))
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    rightMargin = dp(12)
                })
                into.addView(row, Ui.lp(-1, dp(57)))
            }
            into.addView(Ui.button(activity, "Verlauf für diesen Player löschen", true) {
                prefs.edit().remove("devideosrc_recent").apply()
                Toast.makeText(activity, "DeVideoSRC-Verlauf gelöscht.", Toast.LENGTH_SHORT).show()
                // Render refreshes the next time this tab is opened.
                into.removeAllViews()
                render(into)
            }, Ui.lp(dp(274), dp(45)).apply { topMargin = dp(7) })
        }
    }
}
