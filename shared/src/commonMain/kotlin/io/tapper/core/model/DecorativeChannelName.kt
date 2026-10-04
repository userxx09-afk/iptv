package io.tapper.core.model

/**
 * Xtream panels commonly mix a non-stream "section divider" entry into an
 * otherwise flat get_live_streams/get_vod_streams/get_series response - a
 * placeholder whose entire name is decoration wrapped around a label
 * ("##### ENTERTAINMENT #####", "===== SPORTS =====", "~~ Movies ~~"),
 * meant to visually separate sections for a flat-list player with no real
 * category breakdown of its own. Tapper already has a real category rail
 * (CategoryName above splits country from genre for exactly that reason),
 * so an entry like this is pure noise here - and worse, sitting first in a
 * category's raw feed order, one of these routinely ends up the very first
 * (default-focused) entry of the whole unfiltered channel list. That is
 * what made opening Live TV on a fresh install look like it "jumped into"
 * a specific category instead of showing everything: the top of the list
 * was never a real channel, it was a label with the category's own name
 * printed on it - non-playable and functionally pointless inside an app
 * that already groups by category on its own.
 *
 * Deliberately conservative: a real channel name occasionally starts or
 * ends with a stray symbol ("24/7 News", "ESPN+"), so this only matches a
 * name that is ENTIRELY wrapped - the same single symbol repeated three or
 * more times on both ends, with nothing but letters/digits/spaces between
 * them. "24/7 News" and "ESPN+" both fail the very first check (the name
 * has to both start AND end on a non-alphanumeric run) and are kept.
 */
fun isDecorativeSectionLabel(name: String): Boolean {
    val t = name.trim()
    if (t.isEmpty()) return false
    val first = t.first()
    val last = t.last()
    if (first.isLetterOrDigit() || last.isLetterOrDigit()) return false
    val leadLen = t.takeWhile { it == first }.length
    val trailLen = t.takeLastWhile { it == last }.length
    if (leadLen < 3 || trailLen < 3) return false
    if (leadLen + trailLen >= t.length) return false
    val middle = t.substring(leadLen, t.length - trailLen).trim()
    return middle.isNotEmpty() && middle.all { it.isLetterOrDigit() || it.isWhitespace() }
}
