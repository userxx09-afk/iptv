package io.tapper.core.model

/**
 * Xtream panels almost universally encode two dimensions in one category
 * string: a country token and a genre, joined by whatever separator the
 * provider happened to like.
 *
 *     "US | Sports"     -> us, Sports
 *     "UK - News"       -> gb, News
 *     "CA: Kids"        -> ca, Kids
 *     "FR|CINEMA"       -> fr, CINEMA
 *     "24/7 Shows"      -> null, 24/7 Shows      (no country token)
 *     "Sports"          -> null, Sports
 *
 * Splitting them gives the browse screen two real axes instead of dumping every
 * channel into one flat list, and it is why an Xtream source previously showed
 * up entirely as "Ungrouped" - the panel has no country field of its own, so
 * this string is the only place the information exists.
 */
object CategoryName {

    data class Parsed(val countryCode: String?, val category: String)

    /** Separators seen in the wild, in rough order of frequency. */
    private val SPLIT = Regex("""^\s*([A-Za-z]{2,3})\s*[|\-:/~>]+\s*(.+)$""")

    /**
     * Country tokens providers actually use. Deliberately not an exhaustive
     * ISO list: a two-letter word that happens to start a genre name ("TV
     * Shows") must not be mistaken for a country, so only known tokens count.
     */
    private val TOKENS = mapOf(
        "us" to "us", "usa" to "us", "uk" to "gb", "gb" to "gb", "en" to "gb",
        "ca" to "ca", "can" to "ca", "au" to "au", "nz" to "nz", "ie" to "ie",
        "fr" to "fr", "de" to "de", "ger" to "de", "es" to "es", "esp" to "es",
        "it" to "it", "ita" to "it", "pt" to "pt", "br" to "br", "bra" to "br",
        "mx" to "mx", "ar" to "ar", "cl" to "cl", "co" to "co", "pe" to "pe",
        "nl" to "nl", "be" to "be", "ch" to "ch", "at" to "at", "se" to "se",
        "no" to "no", "dk" to "dk", "fi" to "fi", "pl" to "pl", "cz" to "cz",
        "sk" to "sk", "hu" to "hu", "ro" to "ro", "bg" to "bg", "gr" to "gr",
        "hr" to "hr", "rs" to "rs", "si" to "si", "ru" to "ru", "ua" to "ua",
        "tr" to "tr", "il" to "il", "sa" to "sa", "ae" to "ae", "eg" to "eg",
        "ma" to "ma", "dz" to "dz", "tn" to "tn", "za" to "za", "ng" to "ng",
        "in" to "in", "pk" to "pk", "bd" to "bd", "lk" to "lk", "np" to "np",
        "cn" to "cn", "jp" to "jp", "kr" to "kr", "th" to "th", "vn" to "vn",
        "ph" to "ph", "id" to "id", "my" to "my", "sg" to "sg", "afg" to "af",
        "alb" to "al",
    )

    fun parse(raw: String?): Parsed {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return Parsed(null, "Uncategorised")

        val m = SPLIT.find(s) ?: return Parsed(null, s)
        val token = m.groupValues[1].lowercase()
        val rest = m.groupValues[2].trim()

        // Unknown token: the separator was part of the name itself, as in
        // "A-Z Movies", "EX-YU Channels" or "VIP | PPV". Keeping the original
        // string matters - stripping a leading token that is not a country
        // silently loses meaning the provider intended.
        val code = TOKENS[token] ?: return Parsed(null, s)

        return Parsed(code, if (rest.isEmpty()) s else rest)
    }
}
