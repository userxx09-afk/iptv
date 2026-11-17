package io.tapper.core.sports

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS-only callback bridge over SportsClient's suspend functions, for the
 * iPad's My Teams screen. Same contract as XtreamBridge: Swift never calls a
 * suspend function; each call fires its callback exactly once, on a
 * background thread.
 */
object SportsBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = SportsClient()

    /** Every league, in the order Fire TV lists them. */
    fun leagues(): List<League> = League.entries.toList()

    /** A guide listing, built here so Swift needn't name Listing's `description`
     *  (Kotlin/Native renames that parameter to avoid NSObject's). */
    fun listing(startUtc: Long, endUtc: Long, title: String): Listing = Listing(startUtc, endUtc, title, null)

    fun leagueNamed(name: String): League? = League.entries.firstOrNull { it.name == name }

    fun teams(league: League, nowUtcMs: Long, onResult: (List<Team>?, String?) -> Unit) {
        scope.launch {
            try {
                onResult(client.teams(league, nowUtcMs).sortedBy { it.name }, null)
            } catch (t: Throwable) {
                onResult(null, t.message ?: "Couldn't get the teams.")
            }
        }
    }

    fun games(teams: List<Team>, nowUtcMs: Long, onResult: (SportsClient.Schedule?, String?) -> Unit) {
        scope.launch {
            try {
                onResult(client.games(teams, nowUtcMs), null)
            } catch (t: Throwable) {
                onResult(null, t.message ?: "Couldn't get the schedules.")
            }
        }
    }
}
