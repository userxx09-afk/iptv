package io.tapper.core.model

/**
 * iptv-org encodes the broadcaster country in the tvg-id suffix:
 * "00sReplay.us@SD" -> us. Measured on the live index.m3u this resolves
 * 11,431 of 13,510 entries across 178 codes; the rest have no tvg-id.
 */
object Country {
    private val SUFFIX = Regex("""\.([a-z]{2})(?:@|$)""")

    fun fromTvgId(tvgId: String?): String? {
        if (tvgId.isNullOrBlank()) return null
        return SUFFIX.find(tvgId)?.groupValues?.get(1)
    }

    enum class Region(val label: String) {
        NORTH_AMERICA("North America"), LATIN_AMERICA("Latin America"),
        EUROPE("Europe"), MIDDLE_EAST("Middle East"), AFRICA("Africa"),
        ASIA("Asia"), OCEANIA("Oceania"), OTHER("Other"),
    }

    private val NA = setOf("us","ca","mx","pr","bm","gl")
    private val LA = "br ar cl co pe ve uy py bo ec cr pa gt hn ni sv do cu jm tt ht bs bb bz aw sx bq mq gy gp cw lc vg sr kn ag vc gf".split(" ").toSet()
    private val EU = "gb uk ie fr de it es pt nl be lu ch at se no dk fi is pl cz sk hu ro bg gr hr si rs ba mk al me ee lv lt ua by md ru mt cy ad mc sm li va fo xk".split(" ").toSet()
    private val ME = "tr il sa ae qa kw bh om ye jo lb sy iq ir ps".split(" ").toSet()
    private val AF = "eg ma dz tn ly sd ng gh ke tz ug et za zw zm mz ao cm ci sn ml bf ne td so rw bi mw bw na mg mu cd tg bj gn cg cv gq er sl gm eh".split(" ").toSet()
    private val AS = "cn jp kr kp in pk bd lk np bt mm th vn kh la my sg id ph tw hk mo mn kz uz kg tj tm af ge am az mv bn".split(" ").toSet()
    private val OC = "au nz fj pg nc pf gu ws to vu sb".split(" ").toSet()

    fun regionOf(code: String?): Region = when (code?.lowercase()) {
        null -> Region.OTHER
        in NA -> Region.NORTH_AMERICA
        in LA -> Region.LATIN_AMERICA
        in EU -> Region.EUROPE
        in ME -> Region.MIDDLE_EAST
        in AF -> Region.AFRICA
        in AS -> Region.ASIA
        in OC -> Region.OCEANIA
        else -> Region.OTHER
    }

    private val NAMES = mapOf(
        "us" to "United States", "gb" to "United Kingdom", "uk" to "United Kingdom",
        "ca" to "Canada", "in" to "India", "de" to "Germany", "ru" to "Russia",
        "se" to "Sweden", "br" to "Brazil", "es" to "Spain", "it" to "Italy",
        "fr" to "France", "mx" to "Mexico", "ar" to "Argentina", "nl" to "Netherlands",
        "pl" to "Poland", "tr" to "Turkey", "ua" to "Ukraine", "jp" to "Japan",
        "kr" to "South Korea", "cn" to "China", "au" to "Australia", "pt" to "Portugal",
        "gr" to "Greece", "ro" to "Romania", "id" to "Indonesia", "ph" to "Philippines",
        "th" to "Thailand", "vn" to "Vietnam", "eg" to "Egypt", "sa" to "Saudi Arabia",
        "ae" to "UAE", "il" to "Israel", "za" to "South Africa", "ng" to "Nigeria",
        "no" to "Norway", "dk" to "Denmark", "fi" to "Finland", "cz" to "Czechia",
        "at" to "Austria", "ch" to "Switzerland", "be" to "Belgium", "ie" to "Ireland",
    )

    fun label(code: String?): String =
        if (code == null) "Ungrouped" else NAMES[code.lowercase()] ?: code.uppercase()
}
