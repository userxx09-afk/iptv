package io.tapper.core.sports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsTest {
    private val t0 = EspnParse.parseTime("2026-11-15T18:00Z")!! // Sunday noon in Iowa

    private val bears = """{"id":"3","displayName":"Chicago Bears","shortDisplayName":"Bears","name":"Bears","location":"Chicago","abbreviation":"CHI","logos":[{"href":"http://l/chi.png"}]}"""
    private val packers = """{"id":"9","displayName":"Green Bay Packers","shortDisplayName":"Packers","name":"Packers","location":"Green Bay","abbreviation":"GB","logos":[]}"""

    // What a team's /schedule answers: broadcasts as objects, scores as objects.
    private fun scheduleEvent(id: String = "401", date: String = "2026-11-15T18:00Z", state: String = "pre") = """
        {"id":"$id","date":"$date","name":"Chicago Bears at Green Bay Packers","competitions":[{"date":"$date",
          "status":{"type":{"state":"$state","shortDetail":"11/15 - 12:00 PM CST"}},
          "competitors":[{"homeAway":"home","team":$packers},{"homeAway":"away","team":$bears}],
          "broadcasts":[{"type":{"shortName":"TV"},"market":{"type":"National"},"media":{"shortName":"FOX"}},
                        {"type":{"shortName":"Streaming"},"market":{"type":"National"},"media":{"shortName":"NFL+"}}]}]}"""

    // What the scoreboard answers: broadcast names as lists, scores as strings.
    private val scoreboardEvent = """
        {"id":"401","date":"2026-11-15T18:00Z","competitions":[{"date":"2026-11-15T18:00Z",
          "status":{"type":{"state":"in","shortDetail":"3rd 4:12"}},
          "competitors":[{"homeAway":"home","team":$packers,"score":"17"},{"homeAway":"away","team":$bears,"score":"24"}],
          "broadcasts":[{"market":"national","names":["FOX"]}]}]}"""

    @Test fun readsBothEspnShapes() {
        val g = EspnParse.events("""{"events":[${scheduleEvent()}]}""", League.NFL).single()
        assertEquals("NFL:401", g.id)
        assertEquals(listOf("Bears", "Packers", t0), listOf(g.away.shortName, g.home.shortName, g.startUtc))
        assertEquals(GameState.PRE, g.state)
        assertEquals("Bears at Packers", g.title)
        assertEquals(listOf("FOX" to false, "NFL+" to true), g.broadcasts.map { it.name to it.streaming })
        val live = EspnParse.events("""{"events":[$scoreboardEvent]}""", League.NFL).single()
        assertEquals(listOf<Any?>(GameState.LIVE, "3rd 4:12", "24", "17"), listOf(live.state, live.detail, live.away.score, live.home.score))
        assertEquals(listOf(Broadcast("FOX", "national", false)), live.broadcasts)
    }

    @Test fun parsesTimes() {
        assertEquals(1_794_765_600_000L, t0)
        assertEquals(t0 + 30_000, EspnParse.parseTime("2026-11-15T18:00:30Z"))
    }

    @Test fun networkNames() {
        assertEquals("ESPN2", SportsMatching.networkOf("ESPN2"))
        assertEquals("FS1", SportsMatching.networkOf("FS1"))
        assertEquals(null, SportsMatching.networkOf("Peacock"))
    }

    @Test fun matchesByGuideFirstThenNetwork() {
        val base = EspnParse.events("""{"events":[${scheduleEvent()}]}""", League.NFL).single()
        val g = base.copy(broadcasts = base.broadcasts + Broadcast("ESPN", "national", false))
        val channels = listOf(
            ChannelRef("kljb", "KLJB-DT"), // an antenna station named by call sign: only the guide knows
            ChannelRef("wfld", "WFLD FOX 32 Chicago"), // carries FOX, nothing in the guide
            ChannelRef("espn", "ESPN"),
            ChannelRef("espn2", "ESPN2"),
            ChannelRef("foxnews", "FOX News"),
            ChannelRef("livenow", "LiveNOW from FOX"),
            ChannelRef("kwqc", "KWQC-DT"),
        )
        val listings = mapOf(
            "kljb" to listOf(Listing(t0 - 1_800_000, t0, "FOX NFL Kickoff", null), Listing(t0, t0 + 12_600_000, "NFL Football: Chicago Bears at Green Bay Packers", null)),
            "kwqc" to listOf(Listing(t0, t0 + 3_600_000, "Packers Today", null)), // names a team, but isn't the game
        )
        val m = SportsMatching.match(g, channels, { listings[it.id].orEmpty() })
        assertEquals(listOf("kljb", "espn", "wfld"), m.map { it.channelId })
        assertEquals(100, m[0].score)
        assertTrue(m[2].why.contains("may show a different one"))
    }

    @Test fun myTeamsSchedulesScoresAndLimits() = runTest {
        val now = t0 - 3_600_000
        val urls = ArrayList<String>()
        val client = SportsClient("http://espn") { url ->
            urls.add(url)
            when {
                "/teams/3/schedule" in url -> """{"events":[${scheduleEvent("1", "2026-11-13T17:00Z", "post")},${scheduleEvent("2", "2026-11-15T18:00Z")},${scheduleEvent("3", "2026-11-22T17:00Z")},${scheduleEvent("4", "2026-12-15T17:00Z")}]}"""
                "/scoreboard" in url -> """{"events":[${scoreboardEvent.replace("\"401\"", "\"2\"")}]}"""
                else -> throw IllegalStateException("404")
            }
        }
        val team = Team(League.NFL, "3", "Chicago Bears", "Bears", "CHI", null)
        val r = client.games(listOf(team), now)
        assertEquals(listOf("NFL:2", "NFL:3"), r.games.map { it.id }) // an old final and a month out are left off
        assertEquals(GameState.LIVE, r.games[0].state) // from the scoreboard
        client.games(listOf(team), now)
        assertEquals(1, urls.count { "/schedule" in it }) // the schedule is kept for a while
    }
}
