package io.github.kickoman.qiyaa.lyrics

object LyricsText {
    const val MAX_BYTES = 256 * 1024
    const val MAX_LINES = 2_000
    const val MAX_LINE_CHARACTERS = 500

    private const val MAX_MINUTE_DIGITS = 7
    private const val MAX_OFFSET_MS = 24L * 60 * 60 * 1000
    private const val BYTE_ORDER_MARK = 0xFEFF
    private val ID_TAGS =
        setOf("ar", "al", "ti", "au", "by", "length", "offset", "re", "tool", "ve", "la", "id", "#")
    private val LINE_END = Regex("\r\n|\r|\n")
    private val ID_TAG = Regex("""^\[([A-Za-z#]+):(.*)]$""")
    private val WORD_TAG = Regex("""<\d+:\d{2}(?:[.:]\d+)?>""")

    fun parse(text: String): Lyrics? {
        val lines = linesOf(text) ?: return null
        var offset: Long? = null
        val timed = ArrayList<LyricsLine>()
        for (raw in lines) {
            val line = raw.trim()
            val times = ArrayList<Long>()
            var at = 0
            while (true) {
                val tag = timeTagAt(line, at) ?: break
                times += tag.ms
                at += tag.length
            }
            if (times.isNotEmpty()) {
                val words = cut(line.substring(at).replace(WORD_TAG, "").trim())
                for (ms in times) timed += LyricsLine(ms, words)
                continue
            }
            val idTag = idTagOf(line)
            if (idTag != null && idTag.first == "offset" && offset == null) {
                idTag.second.toLongOrNull()?.let { offset = it.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS) }
            }
        }
        if (timed.isEmpty()) return plainOf(lines)
        val shift = offset ?: 0
        val synced =
            timed
                .map { it.copy(ms = ((it.ms ?: 0) - shift).coerceAtLeast(0)) }
                .sortedBy { it.ms }
                .take(MAX_LINES)
        return Lyrics(LyricsKind.SYNCED, synced)
    }

    fun plain(text: String): Lyrics? = linesOf(text)?.let(::plainOf)

    private fun linesOf(text: String): List<String>? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return text.removePrefix(Char(BYTE_ORDER_MARK).toString()).split(LINE_END)
    }

    private fun plainOf(lines: List<String>): Lyrics? {
        val kept = ArrayList<LyricsLine>()
        for (raw in lines) {
            val line = raw.trim()
            if (idTagOf(line) != null) continue
            if (line.isNotEmpty()) {
                kept += LyricsLine(null, cut(line))
            } else if (kept.isNotEmpty() && kept.last().text.isNotEmpty()) {
                kept += LyricsLine(null, "")
            }
        }
        val limited = kept.take(MAX_LINES).dropLastWhile { it.text.isEmpty() }
        return if (limited.isEmpty()) null else Lyrics(LyricsKind.PLAIN, limited)
    }

    private data class TimeTag(val ms: Long, val length: Int)

    private fun timeTagAt(line: String, from: Int): TimeTag? {
        var at = from
        if (at >= line.length || line[at] != '[') return null
        at++
        var minutes = 0L
        val minutesFrom = at
        while (at < line.length && line[at].isAsciiDigit() && at - minutesFrom < MAX_MINUTE_DIGITS) {
            minutes = minutes * 10 + (line[at] - '0')
            at++
        }
        if (at == minutesFrom || at + 3 > line.length || line[at] != ':' ||
            !line[at + 1].isAsciiDigit() || !line[at + 2].isAsciiDigit()
        ) {
            return null
        }
        val seconds = (line[at + 1] - '0') * 10 + (line[at + 2] - '0')
        at += 3
        if (seconds > 59) return null
        var fractionMs = 0L
        if (at < line.length && (line[at] == '.' || line[at] == ':')) {
            at++
            val fractionFrom = at
            var scale = 100
            while (at < line.length && line[at].isAsciiDigit()) {
                if (scale > 0) {
                    fractionMs += (line[at] - '0') * scale
                    scale /= 10
                }
                at++
            }
            if (at == fractionFrom) return null
        }
        if (at >= line.length || line[at] != ']') return null
        return TimeTag((minutes * 60 + seconds) * 1000 + fractionMs, at + 1 - from)
    }

    private fun idTagOf(line: String): Pair<String, String>? {
        val match = ID_TAG.find(line) ?: return null
        val key = match.groupValues[1].lowercase()
        return if (key in ID_TAGS) key to match.groupValues[2].trim() else null
    }

    private fun cut(text: String): String {
        if (text.length <= MAX_LINE_CHARACTERS) return text
        val splitsPair = text[MAX_LINE_CHARACTERS - 1].isHighSurrogate()
        return text.take(if (splitsPair) MAX_LINE_CHARACTERS - 1 else MAX_LINE_CHARACTERS)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
