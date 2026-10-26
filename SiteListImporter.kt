package de.streamhub.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** The input is always an explicitly selected local document. */
data class ImportEntry(val name: String, val url: String, val section: Section)
data class ImportPreview(val entries: List<ImportEntry>, val invalid: Int, val repeated: Int)

object SiteListImporter {
    fun parse(text: String, filename: String = ""): ImportPreview {
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isBlank()) return ImportPreview(emptyList(), 0, 0)
        val candidates = when {
            trimmed.startsWith("[") -> parseJson(trimmed)
            filename.endsWith(".json", ignoreCase = true) -> parseJson(trimmed)
            filename.endsWith(".csv", ignoreCase = true) || firstLine(trimmed).contains(',') ||
                firstLine(trimmed).contains(';') -> parseCsv(trimmed)
            else -> parseLines(trimmed)
        }
        val entries = mutableListOf<ImportEntry>()
        val seen = mutableSetOf<String>()
        var invalid = 0
        var repeated = 0
        candidates.take(1001).forEach { candidate ->
            val item = normalize(candidate)
            if (item == null) invalid++
            else if (!seen.add(item.url.trimEnd('/').lowercase(Locale.ROOT))) repeated++
            else entries.add(item)
        }
        if (candidates.size > 1000) throw IllegalArgumentException("Pro Import höchstens 1000 Zeilen/Einträge.")
        return ImportPreview(entries, invalid, repeated)
    }

    private data class Raw(val name: String, val url: String, val category: String)
    private fun firstLine(text: String) = text.lineSequence().firstOrNull()?.trim().orEmpty()

    private fun parseJson(text: String): List<Raw> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            when (val row = array.get(i)) {
                is JSONObject -> Raw(
                    row.optString("name", row.optString("title", "")),
                    row.optString("url", ""),
                    row.optString("category", row.optString("section", "Mischportale"))
                )
                is String -> Raw("", row, "Mischportale")
                else -> Raw("", "", "")
            }
        }
    }

    private fun parseCsv(text: String): List<Raw> {
        val delimiter = if (firstLine(text).count { it == ';' } > firstLine(text).count { it == ',' }) ';' else ','
        val rows = csvRows(text, delimiter)
        if (rows.isEmpty()) return emptyList()
        val header = rows[0].map { it.trim().lowercase(Locale.ROOT) }
        val hasHeader = header.any { it in setOf("url", "adresse", "link") }
        val nameIdx = if (hasHeader) header.indexOfFirst { it in setOf("name", "titel", "title", "seite") } else 0
        val urlIdx = if (hasHeader) header.indexOfFirst { it in setOf("url", "adresse", "link") } else 1
        val catIdx = if (hasHeader) header.indexOfFirst { it in setOf("category", "kategorie", "section") } else 2
        return rows.drop(if (hasHeader) 1 else 0).filter { it.any(String::isNotBlank) }.map { row ->
            fun value(index: Int): String = if (index < 0) "" else row.getOrNull(index).orEmpty()
            Raw(value(nameIdx), value(urlIdx), value(catIdx))
        }
    }

    /** Supports RFC4180-like quoting (including commas/semicolons and newlines). */
    private fun csvRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> { current.add(cell.toString()); cell.setLength(0) }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    current.add(cell.toString()); cell.setLength(0)
                    rows.add(current); current = mutableListOf()
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (quoted) throw IllegalArgumentException("CSV: ein Anführungszeichen wurde nicht geschlossen.")
        if (cell.isNotEmpty() || current.isNotEmpty()) {
            current.add(cell.toString()); rows.add(current)
        }
        return rows
    }

    /** TXT: HTTPS links, or name | https://... | category. */
    private fun parseLines(text: String): List<Raw> = text.lineSequence()
        .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }
        .map { line ->
            val parts = line.split('|').map { it.trim() }
            when {
                parts.size >= 2 -> Raw(parts[0], parts[1], parts.getOrElse(2) { "Mischportale" })
                else -> Raw("", line, "Mischportale")
            }
        }.toList()

    private fun normalize(raw: Raw): ImportEntry? {
        val uri = try { URI(raw.url.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || raw.url.length > 2048) return null
        val url = uri.toASCIIString()
        val name = raw.name.trim().ifBlank {
            uri.host.removePrefix("www.")
        }
        if (name.length !in 1..100) return null
        val section = when (raw.category.trim().lowercase(Locale.ROOT)) {
            "anime" -> Section.ANIME
            "film", "filme", "movies" -> Section.FILME
            "serie", "serien", "series" -> Section.SERIEN
            "", "mix", "gemischt", "mischportale", "filme & serien", "film & serien" -> Section.MISCHPORTALE
            else -> return null
        }
        return ImportEntry(name, url, section)
    }
}
