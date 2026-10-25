package app.orionmd.omniplay

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One subtitle line on screen from [start] to [end] (ms). [text] keeps simple HTML (<i>, <b>, <u>, <font color>) and \n. */
data class SubCue(val start: Long, val end: Long, val text: String)

/**
 * Reads subtitle files for the video player's own subtitle renderer (which allows a subtitle delay in both directions
 * and a chosen text encoding, like VLC): SubRip (.srt), WebVTT (.vtt) and SubStation Alpha (.ass / .ssa).
 */
object SubParser {
    /** Text encodings offered in the player (auto = UTF-8/UTF-16 when it's valid, otherwise Western Windows-1252). */
    val ENCODINGS = listOf(
        "auto" to "Automatic", "UTF-8" to "Unicode (UTF-8)", "windows-1252" to "Western European (Windows-1252)",
        "ISO-8859-15" to "Western European (ISO-8859-15)", "windows-1250" to "Central European (Windows-1250)",
        "windows-1251" to "Cyrillic (Windows-1251)", "KOI8-R" to "Cyrillic (KOI8-R)", "windows-1253" to "Greek (Windows-1253)",
        "windows-1254" to "Turkish (Windows-1254)", "windows-1255" to "Hebrew (Windows-1255)", "windows-1256" to "Arabic (Windows-1256)",
        "windows-1257" to "Baltic (Windows-1257)", "windows-1258" to "Vietnamese (Windows-1258)", "TIS-620" to "Thai (TIS-620)",
        "GB18030" to "Chinese Simplified (GB18030)", "Big5" to "Chinese Traditional (Big5)", "Shift_JIS" to "Japanese (Shift_JIS)",
        "EUC-KR" to "Korean (EUC-KR)",
    )

    fun decode(bytes: ByteArray, enc: String): String {
        // byte-order marks win
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (enc != "auto") return try { String(bytes, Charset.forName(enc)) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { String(bytes, Charset.forName("windows-1252")) }
    }

    fun parse(text: String, name: String = ""): List<SubCue> {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        val ext = name.substringAfterLast('.', "").lowercase()
        val out = when {
            ext == "ass" || ext == "ssa" || t.contains("[Events]") -> ass(t)
            else -> srtOrVtt(t)   // SRT and WebVTT share the "start --> end" layout
        }
        return out.filter { it.end > it.start && it.text.isNotBlank() && !Subs.isAd(it.text) }.sortedBy { it.start }
    }

    private val timeLine = Regex("""((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})""")

    /** "01:02:03,456", "02:03.456" or "1:02:03.4" → ms */
    fun time(s: String): Long {
        val p = s.trim().replace(',', '.').split(':')
        val (sec, frac) = p.last().split('.').let { it[0].toLong() to (it.getOrNull(1) ?: "0") }
        val ms = (frac + "00").take(3).toLong()
        val min = p.getOrNull(p.size - 2)?.toLong() ?: 0; val h = if (p.size >= 3) p[p.size - 3].toLong() else 0
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }

    private fun srtOrVtt(t: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val lines = t.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = timeLine.find(lines[i])
            if (m == null) { i++; continue }
            val start = time(m.groupValues[1]); val end = time(m.groupValues[2])
            val sb = StringBuilder(); i++
            while (i < lines.size && lines[i].isNotBlank()) {
                // a cue number directly followed by the next timing line means this cue had no blank line after it
                if (i + 1 < lines.size && lines[i].trim().all { it.isDigit() } && timeLine.containsMatchIn(lines[i + 1])) break
                if (timeLine.containsMatchIn(lines[i])) break
                if (sb.isNotEmpty()) sb.append('\n'); sb.append(lines[i].trim()); i++
            }
            // WebVTT voice/class tags (<v Name>, <c.yellow>) aren't styling we use — keep their text
            val txt = sb.toString().replace(Regex("</?(v|c|lang|ruby|rt)(\\.[^ >]*)?( [^>]*)?>"), "").replace(Regex("<\\d+:\\d{2}[:.][\\d.]+>"), "")
            out += SubCue(start, end, txt)
        }
        return out
    }

    private fun ass(t: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var fmt = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        for (raw in t.split('\n')) {
            val l = raw.trim()
            if (l.startsWith("[")) { inEvents = l.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (l.startsWith("Format:", true)) { fmt = l.substringAfter(':').split(',').map { it.trim().lowercase() }; continue }
            if (!l.startsWith("Dialogue:", true)) continue
            val parts = l.substringAfter(':').split(',', limit = fmt.size)
            if (parts.size < fmt.size) continue
            val start = time(parts[fmt.indexOf("start")]); val end = time(parts[fmt.indexOf("end")])
            var txt = parts[fmt.indexOf("text").let { if (it < 0) parts.lastIndex else it }]
            // italics / bold overrides → HTML; other override blocks are dropped
            txt = txt.replace("{\\i1}", "<i>").replace("{\\i0}", "</i>").replace("{\\b1}", "<b>").replace("{\\b0}", "</b>")
                .replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            out += SubCue(start, end, txt.trim())
        }
        return out
    }

    /** The cues showing at [pos] ms (several can overlap). [hint] speeds up the search during normal playback. */
    fun at(cues: List<SubCue>, pos: Long): List<SubCue> {
        if (cues.isEmpty()) return emptyList()
        // binary search for the last cue starting at or before pos
        var lo = 0; var hi = cues.size - 1; var idx = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (cues[mid].start <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        if (idx < 0) return emptyList()
        val out = ArrayList<SubCue>(2)
        var k = idx
        while (k >= 0 && idx - k < 30) { val c = cues[k]; if (c.end > pos && c.start <= pos) out.add(0, c); k-- }
        return out
    }
}

/** Per-video bookmarks (ms), kept in prefs like VLC's. */
object Bookmarks {
    private fun all(store: Store) = try { org.json.JSONObject(store.getPref("vbm", "{}")) } catch (_: Exception) { org.json.JSONObject() }
    fun of(store: Store, id: Long): List<Long> = all(store).optJSONArray(id.toString())?.let { a -> (0 until a.length()).map { a.getLong(it) } }?.sorted() ?: emptyList()
    fun add(store: Store, id: Long, ms: Long) { val o = all(store); val l = (of(store, id) + ms).distinct().sorted(); o.put(id.toString(), org.json.JSONArray(l)); store.setPref("vbm", o.toString()) }
    fun remove(store: Store, id: Long, ms: Long) { val o = all(store); val l = of(store, id) - ms; if (l.isEmpty()) o.remove(id.toString()) else o.put(id.toString(), org.json.JSONArray(l)); store.setPref("vbm", o.toString()) }
}
