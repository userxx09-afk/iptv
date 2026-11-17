package io.tapper.firetv.ui

import io.tapper.firetv.ui.theme.panelTint
import io.tapper.firetv.ui.theme.skinBackground
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.core.sports.ChannelMatch
import io.tapper.core.sports.ChannelRef
import io.tapper.core.sports.Game
import io.tapper.core.sports.GameState
import io.tapper.core.sports.League
import io.tapper.core.sports.Listing
import io.tapper.core.sports.SportsClient
import io.tapper.core.sports.SportsMatching
import io.tapper.core.sports.Team
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.TeamsStore
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A game with the channels here that may carry it, best first. */
class MatchedGame(val game: Game, val channels: List<Pair<Channel, ChannelMatch>>)

/**
 * My Teams: the next games for the teams picked here, and which channels have each one
 * (io.tapper.core.sports does the schedules and the matching). Choosing a game offers to watch
 * it, set a reminder, or record it on each likely channel.
 */
@Composable
fun TeamsScreen(
    store: TeamsStore,
    sports: SportsClient,
    channels: List<Channel>,
    epgIdFor: (Channel) -> String,
    /** Guide listings starting in a window, by guide channel id. */
    listingsBetween: (Long, Long) -> Map<String, List<EpgDatabase.Programme>>,
    onWatch: (Channel) -> Unit,
    onRemind: (Channel, Game, ChannelMatch) -> Unit,
    onRecord: (Channel, Game, ChannelMatch) -> Unit,
    /** Sets reminders for every upcoming game that has a channel, quietly. */
    onAutoRemind: (List<MatchedGame>) -> Unit = {},
    onExit: () -> Unit,
) {
    var autoRemind by remember { mutableStateOf(store.autoRemind) }
    var picking by remember { mutableStateOf(store.all().isEmpty()) }
    var teams by remember { mutableStateOf(store.all()) }
    var games by remember { mutableStateOf<List<MatchedGame>?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuFor by remember { mutableStateOf<MatchedGame?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    BackHandler { if (picking && teams.isNotEmpty()) picking = false else onExit() }

    LaunchedEffect(teams, reload, picking) {
        if (picking || teams.isEmpty()) return@LaunchedEffect
        games = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val schedule = sports.games(teams, System.currentTimeMillis())
                errors = schedule.errors
                schedule.games.map { g -> MatchedGame(g, matchChannels(g, channels, epgIdFor, listingsBetween)) }
            }
        }
        result.onFailure { errors = listOf(it.message ?: "Couldn't get the schedules") }
        games = result.getOrDefault(emptyList())
        if (store.autoRemind) games?.let(onAutoRemind)
    }

    if (picking) {
        TeamPicker(store, sports, onChanged = { teams = store.all() }, onDone = { picking = false })
        return
    }

    val fmt = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().skinBackground().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("My Teams", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your teams' next games, and which channels have them: from the guide, and from the TV " +
                "network each game is on. Schedules from ESPN.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "edit") {
                Row2(
                    title = "Edit my teams",
                    lines = listOf(teams.joinToString(", ") { it.name }),
                    modifier = Modifier.focusRequester(first),
                    onClick = { picking = true },
                )
            }
            item(key = "auto") {
                Row2(
                    title = if (autoRemind) "Remind me of every game: on" else "Remind me of every game: off",
                    lines = listOf("A reminder pops up on the TV as each game starts, on the channel that has it."),
                    selected = autoRemind,
                    onClick = {
                        autoRemind = !autoRemind
                        store.autoRemind = autoRemind
                        if (autoRemind) games?.let(onAutoRemind)
                    },
                )
            }
            val list = games
            when {
                list == null -> item(key = "loading") { Text("Getting the schedules...", color = Dim, modifier = Modifier.padding(16.dp)) }
                list.isEmpty() -> item(key = "none") {
                    Text(
                        "No games in the next two weeks." + (errors.firstOrNull()?.let { " ($it)" } ?: ""),
                        color = Dim, modifier = Modifier.padding(16.dp),
                    )
                }
                else -> items(list, key = { it.game.id }) { m ->
                    Row2(
                        title = m.game.title,
                        lines = listOf(whenText(m.game, fmt), whereText(m)),
                        live = m.game.state == GameState.LIVE,
                        logos = listOfNotNull(m.game.away.logoUrl, m.game.home.logoUrl),
                        onClick = { menuFor = m },
                    )
                }
            }
            if (errors.isNotEmpty() && !games.isNullOrEmpty()) {
                item(key = "errors") { Text(errors.joinToString("\n"), color = Dim, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    menuFor?.let { m ->
        val g = m.game
        val soon = g.state == GameState.LIVE || g.startUtc - System.currentTimeMillis() < 15 * 60_000
        val tv = g.broadcasts.filter { !it.streaming }.joinToString(", ") { it.name }
        val web = g.broadcasts.filter { it.streaming }.joinToString(", ") { it.name }
        ItemMenu(
            title = g.title,
            subtitle = listOfNotNull(
                "${g.league.label} · ${whenText(g, fmt)}",
                if (tv.isNotEmpty()) "On TV: $tv" else "TV network not announced yet",
                web.ifEmpty { null }?.let { "Streaming: $it (not a channel here)" },
                if (m.channels.isEmpty()) "None of your channels look like they have this game." else null,
            ).joinToString("\n"),
            actions = buildList {
                for ((ch, match) in m.channels) {
                    val where = "${ch.name}  -  ${match.why}"
                    if (soon) add(MenuAction("Watch on $where") { onWatch(ch) })
                    else {
                        add(MenuAction("Remind me on $where") { onRemind(ch, g, match) })
                        add(MenuAction("Record on ${ch.name}") { onRecord(ch, g, match) })
                    }
                }
                if (soon) m.channels.firstOrNull()?.let { (ch, match) -> add(MenuAction("Record on ${ch.name}") { onRecord(ch, g, match) }) }
                add(MenuAction("Refresh") { reload++ })
            },
            onDismiss = { menuFor = null },
        )
    }
}

private fun whenText(g: Game, fmt: SimpleDateFormat): String {
    val score = if (g.home.score != null && g.away.score != null) "${g.away.abbr} ${g.away.score}, ${g.home.abbr} ${g.home.score}" else ""
    return when (g.state) {
        GameState.LIVE -> "Live · ${g.detail}" + if (score.isNotEmpty()) " · $score" else ""
        GameState.FINAL -> g.detail.ifEmpty { "Final" } + if (score.isNotEmpty()) " · $score" else ""
        GameState.PRE -> if (g.timeTbd) SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startUtc)) + ", time to be announced"
            else fmt.format(Date(g.startUtc))
    }
}

private fun whereText(m: MatchedGame): String {
    val tv = m.game.broadcasts.joinToString(", ") { it.name }
    val best = m.channels.firstOrNull()
    return when {
        best != null && best.second.score >= 70 -> best.first.name + if (tv.isNotEmpty()) " · $tv" else ""
        tv.isNotEmpty() -> tv
        else -> "TV network not announced yet"
    }
}

/**
 * Runs the shared matcher over this provider's live channels. Only US/Canadian channels (or ones
 * with no country) are offered by network name - an iptv-org list has a "FOX" in every country -
 * but any channel whose guide shows the game counts.
 */
internal fun matchChannels(
    g: Game,
    channels: List<Channel>,
    epgIdFor: (Channel) -> String,
    listingsBetween: (Long, Long) -> Map<String, List<EpgDatabase.Programme>>,
): List<Pair<Channel, ChannelMatch>> {
    val listings = runCatching { listingsBetween(g.startUtc - 2 * 3_600_000, g.startUtc + 75 * 60_000) }.getOrDefault(emptyMap())
    val home = setOf(null, "us", "ca", "pr")
    val byId = HashMap<String, Channel>()
    val refs = ArrayList<ChannelRef>()
    for (ch in channels) {
        if (ch.kind != io.tapper.core.model.ContentKind.LIVE) continue
        val inGuide = listings.containsKey(epgIdFor(ch))
        if (!inGuide && ch.countryCode?.lowercase() !in home) continue
        byId[ch.id] = ch
        refs.add(ChannelRef(ch.id, ch.name))
    }
    return SportsMatching.match(g, refs, { ref ->
        listings[epgIdFor(byId.getValue(ref.id))].orEmpty().map { Listing(it.startUtc, it.endUtc, it.title, it.description) }
    }).mapNotNull { m -> byId[m.channelId]?.let { it to m } }
}

@Composable
private fun TeamPicker(store: TeamsStore, sports: SportsClient, onChanged: () -> Unit, onDone: () -> Unit) {
    var league by remember { mutableStateOf(League.NFL) }
    var teams by remember { mutableStateOf<List<Team>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }

    LaunchedEffect(league) {
        teams = null
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { sports.teams(league, System.currentTimeMillis()) } }
        teams = r.getOrDefault(emptyList())
        error = r.exceptionOrNull()?.message
    }

    Column(Modifier.fillMaxSize().skinBackground().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("Pick your teams", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        val mine = remember(revision) { store.all() }
        Text(
            if (mine.isEmpty()) "Choose a league, then each team you follow (up to ${TeamsStore.MAX}). Back when you're done."
            else "Following: " + mine.joinToString(", ") { it.name } + ". Back when you're done.",
            style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LazyColumn(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(League.entries.toList(), key = { it.name }) { l ->
                    Row2(
                        title = l.label, lines = emptyList(), selected = l == league,
                        modifier = if (l == League.NFL) Modifier.focusRequester(first) else Modifier,
                        onClick = { league = l },
                    )
                }
                item(key = "done") { Row2(title = "Done", lines = emptyList(), onClick = onDone) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val list = teams
                when {
                    list == null -> item { Text("Loading teams...", color = Dim) }
                    list.isEmpty() -> item { Text(error ?: "No teams found.", color = Dim) }
                    else -> items(list, key = { "${it.league}:${it.id}" }) { t ->
                        val have = remember(revision) { store.has(t) }
                        Row2(
                            title = (if (have) "✓  " else "") + t.name, lines = emptyList(), selected = have,
                            logos = listOfNotNull(t.logoUrl),
                            onClick = {
                                if (!store.toggle(t)) error = "That's ${TeamsStore.MAX} teams already. Remove one first."
                                revision++
                                onChanged()
                            },
                        )
                    }
                }
                error?.takeIf { teams?.isNotEmpty() == true }?.let { e -> item { Text(e, color = Color(0xFFE08A7A)) } }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Row2(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    live: Boolean = false,
    logos: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Focus.copy(alpha = 0.18f)
                    selected -> panelTint(0.10f)
                    else -> panelTint(0.04f)
                }
            )
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE5484D)))
            Spacer(Modifier.width(10.dp))
        }
        logos.forEach { url ->
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            lines.filter { it.isNotEmpty() }.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
