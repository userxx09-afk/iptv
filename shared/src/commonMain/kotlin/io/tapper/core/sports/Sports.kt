package io.tapper.core.sports

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.tapper.core.epg.XmltvTime
import io.tapper.core.net.tapperHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * My Teams: pick a few teams and see their next games, with the channels each game is likely on.
 *
 * Schedules, scores and TV networks come from ESPN's public site API (what espn.com's own pages
 * use; no key). Each game is then matched against the provider's channels two ways:
 *  - the guide: a listing around the game's start that names the teams ("NFL Football: Bears at
 *    Packers") is the surest sign
 *  - the network: ESPN says "FOX" or "ESPN2", and channels with that network in their name carry
 *    it. For CBS/FOX/NBC/ABC that can be another city's station showing a different game, so
 *    those rank lower and say so.
 * Streaming-only broadcasts (Peacock, Prime Video, ESPN+...) are listed but have no channel.
 *
 * Shared so the iPad app can use the same schedules and matching; the screens are per platform.
 */
enum class League(val label: String, internal val path: String, internal val hours: Double, internal val groups: Int?, internal val words: Regex) {
    NFL("NFL", "football/nfl", 3.5, null, Regex("""\bnfl\b|football""", RegexOption.IGNORE_CASE)),
    NCAAF("College football", "football/college-football", 3.75, 80, Regex("""football|\bncaa""", RegexOption.IGNORE_CASE)),
    NBA("NBA", "basketball/nba", 2.5, null, Regex("""\bnba\b|basketball""", RegexOption.IGNORE_CASE)),
    WNBA("WNBA", "basketball/wnba", 2.25, null, Regex("""\bwnba\b|basketball""", RegexOption.IGNORE_CASE)),
    NCAAM("College basketball (men)", "basketball/mens-college-basketball", 2.25, 50, Regex("""basketball|\bncaa""", RegexOption.IGNORE_CASE)),
    NCAAW("College basketball (women)", "basketball/womens-college-basketball", 2.25, 50, Regex("""basketball|\bncaa""", RegexOption.IGNORE_CASE)),
    MLB("MLB", "baseball/mlb", 3.25, null, Regex("""\bmlb\b|baseball""", RegexOption.IGNORE_CASE)),
    NHL("NHL", "hockey/nhl", 2.75, null, Regex("""\bnhl\b|hockey""", RegexOption.IGNORE_CASE)),
    MLS("MLS", "soccer/usa.1", 2.0, null, Regex("""\bmls\b|soccer|f[uú]tbol""", RegexOption.IGNORE_CASE));

    /** How long a game usually runs, for a recording with no guide listing to go by. */
    val typicalMillis: Long get() = (hours * 3_600_000).toLong()
}

/** A team as picked in Settings (and stored by the app). */
data class Team(val league: League, val id: String, val name: String, val shortName: String, val abbr: String, val logoUrl: String?)

data class Side(val id: String, val name: String, val shortName: String, val nickname: String, val abbr: String, val logoUrl: String?, val score: String?)

data class Broadcast(val name: String, val market: String, val streaming: Boolean)

enum class GameState { PRE, LIVE, FINAL }

data class Game(
    val id: String,
    val league: League,
    val startUtc: Long,
    val state: GameState,
    /** ESPN's short status: "3rd 4:12", "Final", "11/16 - 12:00 PM EST". */
    val detail: String,
    val timeTbd: Boolean,
    val neutralSite: Boolean,
    val home: Side,
    val away: Side,
    val broadcasts: List<Broadcast>,
) {
    val title: String get() = "${away.shortName} ${if (neutralSite) "vs" else "at"} ${home.shortName}"
    val endGuessUtc: Long get() = startUtc + league.typicalMillis
    fun involves(team: Team) = team.league == league && (home.id == team.id || away.id == team.id)
}

/** A channel, as far as matching needs to know it. [guideNames]: names its guide entry goes by. */
data class ChannelRef(val id: String, val name: String, val guideNames: List<String> = emptyList())

/** A guide listing, as far as matching needs to know it. */
data class Listing(val startUtc: Long, val endUtc: Long, val title: String, val description: String?)

data class ChannelMatch(val channelId: String, val score: Int, val why: String, val listing: Listing?)

/**
 * Runs [SportsMatching.match] over a provider's live channels - the logic
 * Fire TV's TeamsScreen has, shared so the iPad gets the same answers. Only
 * US/Canadian channels (or ones with no country) are offered by network name
 * (an iptv-org list has a "FOX" in every country), but any channel whose
 * guide shows the game counts. [epgIds] maps a channel id to the guide id
 * its listings are under; [listings] holds listings by guide id.
 */
object ChannelMatcher {
    data class Hit(val channel: io.tapper.core.model.Channel, val match: ChannelMatch)

    fun matchChannels(
        game: Game,
        channels: List<io.tapper.core.model.Channel>,
        epgIds: Map<String, String>,
        listings: Map<String, List<Listing>>,
    ): List<Hit> {
        val home = setOf(null, "us", "ca", "pr")
        val byId = HashMap<String, io.tapper.core.model.Channel>()
        val refs = ArrayList<ChannelRef>()
        for (ch in channels) {
            if (ch.kind != io.tapper.core.model.ContentKind.LIVE) continue
            val inGuide = listings.containsKey(epgIds[ch.id])
            if (!inGuide && ch.countryCode?.lowercase() !in home) continue
            byId[ch.id] = ch
            refs.add(ChannelRef(ch.id, ch.name))
        }
        return SportsMatching.match(game, refs, { ref -> listings[epgIds[ref.id]].orEmpty() })
            .mapNotNull { m -> byId[m.channelId]?.let { Hit(it, m) } }
    }
}

object SportsMatching {
    // TV networks as ESPN names them -> how they show up in channel names.
    private val networks: List<Pair<String, Regex>> = listOf(
        "CBS" to """\bcbs\b(?!\s*(sports|news))""",
        "FOX" to """(?<!from )\bfox\b(?!\s*(sports|news|business|weather|soul|deportes))""",
        "NBC" to """\bnbc\b(?!\s*(sports|news))""",
        "ABC" to """\babc\b(?!\s*news)""",
        "CW" to """\bcw\b""",
        "ESPN" to """\bespn\b(?!\s*(2|u\b|news|deportes|\+|plus|classic))""",
        "ESPN2" to """\bespn\s*2\b""",
        "ESPNU" to """\bespn\s*u\b""",
        "ESPNews" to """\bespn\s*news\b""",
        "ESPN Deportes" to """\bespn\s*deportes\b""",
        "FS1" to """\b(fs1|fox sports 1)\b""",
        "FS2" to """\b(fs2|fox sports 2)\b""",
        "TNT" to """\btnt\b""",
        "TBS" to """\btbs\b""",
        "truTV" to """\btru\s?tv\b""",
        "USA Net" to """\busa\b(?!\s*today)""",
        "NFL Network" to """\bnfl\s*(network|net)\b""",
        "NBA TV" to """\bnba\s*tv\b""",
        "MLB Network" to """\bmlb\s*(network|net)\b""",
        "NHL Network" to """\bnhl\s*(network|net)\b""",
        "CBS Sports Network" to """\b(cbssn|cbs sports( network)?)\b""",
        "Big Ten Network" to """\b(btn|big ten( network)?|b1g)\b""",
        "SEC Network" to """\bsec\s*(network|net|n)\b|\bsecn\b""",
        "ACC Network" to """\bacc\s*(network|net|n)\b|\baccn\b""",
        "Golf Channel" to """\bgolf\b""",
        "NBC Sports Chicago" to """\b(nbc sports chicago|chicago sports network|chsn)\b""",
        "Marquee Sports Network" to """\bmarquee\b""",
        "ION" to """\bion\b""",
    ).map { (n, re) -> n to Regex(re, RegexOption.IGNORE_CASE) }
    private val broadcastNetworks = setOf("CBS", "FOX", "NBC", "ABC", "CW", "ION")
    private val streamingRe = Regex("""peacock|prime video|amazon|espn\+|espn app|apple tv|netflix|paramount\+|youtube|\bmax\b|nfl\+|league pass|mlb\.tv|nhl\.tv|fubo|dazn|victory\+|\+$|stream""", RegexOption.IGNORE_CASE)

    fun networkOf(name: String): String? =
        networks.firstOrNull { (n, re) -> n.equals(name, ignoreCase = true) || re.containsMatchIn(name) }?.first

    internal fun isStreaming(name: String) = streamingRe.containsMatchIn(name) && networkOf(name) == null

    private fun regexOf(net: String) = networks.first { it.first == net }.second

    private fun fold(s: String?): String = s.orEmpty().lowercase()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')

    private fun mentions(text: String, side: Side): Boolean =
        listOf(side.name, side.shortName, side.nickname).filter { it.length > 2 }
            .any { Regex("""\b${Regex.escape(fold(it))}\b""").containsMatchIn(text) }

    /**
     * Channels that may carry [game], best first (at most [limit]).
     * [listingsFor]: the channel's guide listings around the game (any wider window is fine).
     */
    fun match(game: Game, channels: List<ChannelRef>, listingsFor: (ChannelRef) -> List<Listing>, limit: Int = 8): List<ChannelMatch> {
        val end = game.startUtc + game.league.typicalMillis
        val nets = game.broadcasts.filter { !it.streaming }.mapNotNull { b -> networkOf(b.name)?.let { b to it } }
        val out = ArrayList<ChannelMatch>()
        for (c in channels) {
            val names = (listOf(c.name) + c.guideNames).joinToString(" | ")
            val net = nets.firstOrNull { (_, n) -> regexOf(n).containsMatchIn(names) }
            var best: ChannelMatch? = null
            for (p in listingsFor(c)) {
                if (p.endUtc <= game.startUtc - 30 * 60_000 || p.startUtc >= end) continue
                // The game itself, or coverage that began a little earlier (a pregame show running into it).
                val near = kotlin.math.abs(p.startUtc - game.startUtc) <= 75 * 60_000
                val covers = p.startUtc < game.startUtc && p.endUtc > game.startUtc + 60 * 60_000
                if (!near && !covers) continue
                val title = fold(p.title)
                val desc = fold(p.description)
                val sport = game.league.words.containsMatchIn(title)
                val h = mentions(title, game.home)
                val a = mentions(title, game.away)
                val (score, why) = when {
                    h && a -> 100 to "the guide shows this game"
                    (h || a) && sport -> 90 to "the guide shows this game"
                    sport && mentions(desc, game.home) && mentions(desc, game.away) -> 85 to "the guide shows this game"
                    sport && net != null -> 70 to "the guide shows a game at that time, on ${net.second}"
                    else -> 0 to ""
                }
                if (score > 0 && (best == null || score > best.score)) best = ChannelMatch(c.id, score, why, p)
            }
            if (best == null && net != null) {
                val (b, n) = net
                val national = b.market == "national" && n !in broadcastNetworks
                best = ChannelMatch(
                    c.id, if (national) 75 else 40,
                    if (national) "$n has this game nationally"
                    else "$n has this game${if (b.market == "national") "" else " (${b.market} market)"}, but this station may show a different one",
                    null,
                )
            }
            best?.let { out.add(it) }
        }
        return out.sortedByDescending { it.score }.take(limit)
    }
}

/** Reads ESPN's answers. Internal pieces are visible to the tests. */
object EspnParse {
    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = this as? JsonArray
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** "2026-11-15T18:00Z" or "2026-11-15T18:00:00Z" -> epoch millis. */
    fun parseTime(s: String?): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?Z$""").find(s ?: return null) ?: return null
        val g = m.groupValues
        return XmltvTime.toEpochMillisUtc(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
    }

    private fun side(c: JsonObject): Side {
        val t = c["team"].obj() ?: JsonObject(emptyMap())
        val score = when (val s = c["score"]) {
            is JsonObject -> s.str("displayValue")
            is JsonPrimitive -> s.contentOrNull
            else -> null
        }
        val logo = t.str("logo") ?: t["logos"].arr()?.firstOrNull().obj()?.str("href")
        return Side(
            id = t.str("id").orEmpty(), name = t.str("displayName") ?: t.str("name").orEmpty(),
            shortName = t.str("shortDisplayName") ?: t.str("name").orEmpty(), nickname = t.str("name").orEmpty(),
            abbr = t.str("abbreviation").orEmpty(), logoUrl = logo, score = score?.takeIf { it.isNotEmpty() },
        )
    }

    internal fun broadcasts(comp: JsonObject): List<Broadcast> {
        val out = LinkedHashMap<String, Broadcast>()
        fun add(name: String?, market: String?, kind: String?) {
            val n = name?.trim().orEmpty()
            if (n.isEmpty() || kind == "radio" || n in out) return
            out[n] = Broadcast(n, market?.lowercase()?.ifEmpty { null } ?: "national", kind == "streaming" || SportsMatching.isStreaming(n))
        }
        for (b in comp["broadcasts"].arr().orEmpty()) {
            val o = b.obj() ?: continue
            val names = o["names"].arr()
            if (names != null) {
                // Scoreboard shape: { market: "national", names: ["FOX"] }
                names.forEach { add((it as? JsonPrimitive)?.contentOrNull, o.str("market"), null) }
            } else {
                // Team schedule shape: { type: { shortName: "TV" }, market: { type: "National" }, media: { shortName: "FOX" } }
                val media = o["media"].obj()
                add(media?.str("shortName") ?: media?.str("name"), o["market"].obj()?.str("type") ?: o.str("market"), o["type"].obj()?.str("shortName")?.lowercase())
            }
        }
        for (g in comp["geoBroadcasts"].arr().orEmpty()) {
            val o = g.obj() ?: continue
            val media = o["media"].obj()
            add(media?.str("shortName") ?: media?.str("name"), o["market"].obj()?.str("type"), o["type"].obj()?.str("shortName")?.lowercase())
        }
        return out.values.toList()
    }

    fun event(ev: JsonObject, league: League): Game? {
        val comp = ev["competitions"].arr()?.firstOrNull().obj() ?: return null
        val cs = comp["competitors"].arr().orEmpty().mapNotNull { it.obj() }
        val home = cs.firstOrNull { it.str("homeAway") == "home" } ?: cs.getOrNull(0) ?: return null
        val away = cs.firstOrNull { it.str("homeAway") == "away" } ?: cs.getOrNull(1) ?: return null
        val status = (comp["status"].obj() ?: ev["status"].obj())?.get("type").obj()
        val start = parseTime(comp.str("date") ?: ev.str("date")) ?: return null
        return Game(
            id = "${league.name}:${ev.str("id")}", league = league, startUtc = start,
            state = when (status?.str("state")) { "in" -> GameState.LIVE; "post" -> GameState.FINAL; else -> GameState.PRE },
            detail = status?.str("shortDetail") ?: status?.str("detail").orEmpty(),
            timeTbd = comp.str("timeValid") == "false",
            neutralSite = comp.str("neutralSite") == "true",
            home = side(home), away = side(away), broadcasts = broadcasts(comp),
        )
    }

    fun events(body: String, league: League): List<Game> =
        (Json.parseToJsonElement(body).obj()?.get("events").arr()).orEmpty().mapNotNull { e -> e.obj()?.let { event(it, league) } }

    fun teams(body: String, league: League): List<Team> {
        val list = Json.parseToJsonElement(body).obj()?.get("sports").arr()?.firstOrNull().obj()
            ?.get("leagues").arr()?.firstOrNull().obj()?.get("teams").arr().orEmpty()
        return list.mapNotNull { it.obj()?.get("team").obj() }.map { t ->
            Team(league, t.str("id").orEmpty(), t.str("displayName").orEmpty(), t.str("shortDisplayName") ?: t.str("name").orEmpty(),
                t.str("abbreviation").orEmpty(), t["logos"].arr()?.firstOrNull().obj()?.str("href"))
        }.filter { it.id.isNotEmpty() }.sortedBy { it.name }
    }
}

/**
 * Talks to ESPN. Answers are kept in memory: team lists for a day, schedules for three hours,
 * today's scores for 90 seconds; when ESPN can't be reached, the last answer is used.
 * [fetch]: GETs a URL and returns the body (or throws); replaceable for tests.
 */
class SportsClient(
    private val api: String = "https://site.api.espn.com/apis/site/v2/sports",
    private val fetch: suspend (String) -> String = ::defaultFetch,
) {
    class SportsException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class Hit(val at: Long, val body: String)
    private val cache = HashMap<String, Hit>()
    private val lock = Mutex()

    private suspend fun get(url: String, keepMs: Long, now: Long): String {
        lock.withLock { cache[url] }?.let { if (now - it.at < keepMs) return it.body }
        return try {
            val body = fetch(url)
            lock.withLock { cache[url] = Hit(now, body) }
            body
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            lock.withLock { cache[url] }?.body ?: throw SportsException("Couldn't get sports schedules (${t.message})", t)
        }
    }

    suspend fun teams(league: League, now: Long): List<Team> =
        EspnParse.teams(get("$api/${league.path}/teams?limit=1000${league.groups?.let { "&groups=$it" } ?: ""}", 86_400_000, now), league)

    data class Schedule(val games: List<Game>, val errors: List<String>)

    /** The next games for [teams] (up to [perTeam] each, two weeks ahead), plus any on now or just finished. */
    suspend fun games(teams: List<Team>, now: Long, perTeam: Int = 4): Schedule = coroutineScope {
        val errors = ArrayList<String>()
        val found = LinkedHashMap<String, Game>()
        val schedules = teams.map { t ->
            async {
                t to runCatching { EspnParse.events(get("$api/${t.league.path}/teams/${t.id}/schedule", 3 * 3_600_000, now), t.league) }
            }
        }.awaitAll()
        for ((t, r) in schedules) {
            r.onSuccess { list -> list.filter { it.startUtc > now - 6 * 3_600_000 && it.startUtc < now + 14 * 86_400_000L }.forEach { found[it.id] = it } }
                .onFailure { errors.add("${t.name}: ${it.message}") }
        }
        // Today's games: fresh scores and status, and the TV networks once they're announced.
        val today = found.values.filter { it.startUtc < now + 12 * 3_600_000 }.map { it.league }.distinct()
        val boards = today.map { league ->
            async {
                league to runCatching {
                    val from = day(now - 12 * 3_600_000)
                    val to = day(now + 12 * 3_600_000)
                    EspnParse.events(get("$api/${league.path}/scoreboard?dates=$from-$to&limit=500${league.groups?.let { "&groups=$it" } ?: ""}", 90_000, now), league)
                }
            }
        }.awaitAll()
        for ((league, r) in boards) {
            r.onSuccess { list ->
                for (g in list) {
                    val had = found[g.id] ?: continue
                    found[g.id] = g.copy(broadcasts = g.broadcasts.ifEmpty { had.broadcasts })
                }
            }.onFailure { errors.add("${league.label} scores: ${it.message}") }
        }
        val count = HashMap<String, Int>()
        val shown = found.values
            .filter { it.state != GameState.FINAL || it.startUtc > now - 4 * 3_600_000 } // a final score stays up for a while
            .sortedBy { it.startUtc }
            .filter { g ->
                teams.filter { g.involves(it) }.any { t ->
                    val k = "${t.league}:${t.id}"
                    val n = count[k] ?: 0
                    count[k] = n + 1
                    n < perTeam
                }
            }
        Schedule(shown, errors)
    }

    private fun day(t: Long): String {
        // Civil date (UTC) from epoch millis, as yyyyMMdd.
        val z = t / 86_400_000 + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "$y${m.toString().padStart(2, '0')}${d.toString().padStart(2, '0')}"
    }

    private companion object {
        val http = tapperHttpClient().config {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }

        suspend fun defaultFetch(url: String): String {
            val r = http.get(url) {
                header("Accept", "application/json")
                header("User-Agent", "TapperIPTV")
            }
            if (!r.status.isSuccess()) throw SportsException("ESPN answered ${r.status.value}")
            return r.bodyAsText()
        }
    }
}
