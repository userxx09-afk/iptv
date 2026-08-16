package io.tapper.core.epg

/**
 * XMLTV timestamps: `YYYYMMDDHHMMSS +HHMM`, with everything after the year
 * optional and the offset frequently absent, malformed, or simply wrong.
 *
 * Verified against a reference implementation including leap-year and offset
 * cases. Returns epoch millis UTC, or null if unparseable.
 */
object XmltvTime {

    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 8) return null

        val digits = s.takeWhile { it.isDigit() }
        if (digits.length < 8) return null

        val year = digits.substring(0, 4).toIntOrNull() ?: return null
        val month = digits.substring(4, 6).toIntOrNull() ?: return null
        val day = digits.substring(6, 8).toIntOrNull() ?: return null
        val hour = digits.range(8, 10) ?: 0
        val minute = digits.range(10, 12) ?: 0
        val second = digits.range(12, 14) ?: 0

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 ||
            minute !in 0..59 || second !in 0..60
        ) return null

        val base = toEpochMillisUtc(year, month, day, hour, minute, second)
        // The offset describes local time's distance from UTC, so subtract it.
        return base - (parseOffset(s.drop(digits.length).trim()) ?: 0L)
    }

    private fun String.range(from: Int, to: Int): Int? =
        if (length >= to) substring(from, to).toIntOrNull() else null

    private fun parseOffset(s: String): Long? {
        if (s.isEmpty()) return 0L
        val sign = when (s[0]) { '+' -> 1; '-' -> -1; else -> return 0L }
        val d = s.drop(1).filter { it.isDigit() }
        if (d.length < 4) return 0L
        val h = d.substring(0, 2).toIntOrNull() ?: return 0L
        val m = d.substring(2, 4).toIntOrNull() ?: return 0L
        if (h > 14 || m > 59) return 0L
        return sign * (h * 3_600_000L + m * 60_000L)
    }

    /** Civil date to epoch millis (days_from_civil). No timezone database needed. */
    fun toEpochMillisUtc(y: Int, m: Int, d: Int, hh: Int, mm: Int, ss: Int): Long {
        val yAdj = if (m <= 2) y - 1 else y
        val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
        val yoe = yAdj - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097L + doe - 719_468L
        return days * 86_400_000L + hh * 3_600_000L + mm * 60_000L + ss * 1000L
    }
}
