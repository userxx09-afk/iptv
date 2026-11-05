package io.tapper.firetv.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.firetv.R
import io.tapper.firetv.data.EpgDatabase.Programme
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import kotlinx.coroutines.delay
import java.util.Calendar

/*
 * The GarysTV interface (Settings > Interface > GarysTV): the GarysTV DVR's
 * own screens rebuilt in Compose - its top bar, Home tiles, DISH-style guide
 * grid and key-hint footer. Sizes follow GarysTV's styles.css, where 1rem is
 * 1.08% of the screen width; see Rem below.
 */

// ---------- palette (GarysTV styles.css) ----------
internal val GBg = Color(0xFF04070D)
internal val GBg2 = Color(0xFF0A1424)
internal val GGlow = Color(0xFF15284A)
internal val GPanel = Color(0xFF0E1A2E)
internal val GPanel2 = Color(0xFF13233D)
internal val GCell = Color(0xFF12213A)
internal val GCellAlt = Color(0xFF0F1C32)
internal val GLine = Color(0xFF22385C)
internal val GText = Color(0xFFEEF3FB)
internal val GMuted = Color(0xFF93A6C4)
internal val GDim = Color(0xFF5D7194)
internal val GSub = Color(0xFFCFE0FF)
internal val GDesc = Color(0xFFC4D1E6)
internal val GFocusBg = Color(0xFFE9F0FB)
internal val GFocusText = Color(0xFF08111F)
internal val GFocusSub = Color(0xFF3B4A63)
internal val GRed = Color(0xFFE4002B)
internal val GRed2 = Color(0xFFFF3355)
internal val GBlue = Color(0xFF2F8CFF)
internal val GYellow = Color(0xFFF5C518)

private const val SLOT = 30 * 60_000L
private const val SPAN = 3 * SLOT
private fun align(t: Long) = t / SLOT * SLOT

/** GarysTV's rem: clamp(14px, 1.08vw, 24px), expressed in dp. */
internal class Rem(val px: Float) {
    fun dp(x: Float): Dp = (px * x).dp
    fun sp(x: Float) = (px * x).sp
}

internal val LocalRem = staticCompositionLocalOf { Rem(10.4f) }

@Composable
internal fun rememberRem(): Rem {
    val w = LocalConfiguration.current.screenWidthDp
    return remember(w) { Rem((w * 0.0108f).coerceIn(9.5f, 16f)) }
}

@Composable
internal fun gStyle(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = GText, spacing: Float = 0f): TextStyle {
    val r = LocalRem.current
    return TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = r.sp(size), color = color,
        letterSpacing = if (spacing != 0f) spacing.em else TextStyle.Default.letterSpacing,
    )
}

internal enum class GView { HOME, GUIDE, MOVIES, SHOWS }

/** Which GarysTV main screen is showing - kept here so the top bar on every other screen can jump to Home or the Guide. */
object GarysNav {
    internal var view by mutableStateOf(GView.HOME)
}

/** Guide filter: everything, favorites only, or one provider category. */
private sealed interface GFilter {
    data object All : GFilter
    data object Favorites : GFilter
    data class Category(val name: String) : GFilter
}

@Composable
fun GarysBrowseScreen(
    catalogue: PlaylistRepository.Catalogue,
    favorites: FavoritesStore,
    sourceName: String,
    nowPlaying: Map<String, Programme>,
    epgStatus: String?,
    epgIdFor: (Channel) -> String,
    /** Guide rows for these channels between two times, keyed by epgIdFor. */
    guideFor: suspend (List<Channel>, Long, Long) -> Map<String, List<Programme>>,
    epgRevision: Int,
    lastChannelId: String?,
    recordings: List<Recording>,
    onPlay: (List<Channel>, Int) -> Unit,
    onOpenSeries: (Channel) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpenRecordings: () -> Unit,
    onRecord: (Channel, Programme) -> Unit,
    onRecordChannel: (Channel) -> Unit,
    hasReminder: (Channel, Programme) -> Boolean,
    onSetReminder: (Channel, Programme) -> Unit,
    onCancelReminder: (Channel, Programme) -> Unit,
    onSelectionChanged: (String) -> Unit,
) {
    val rem = rememberRem()
    var view by GarysNav::view
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    var favRevision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }

    BackHandler(enabled = view != GView.HOME) { view = GView.HOME }

    val live = remember(catalogue) { catalogue.section(ContentKind.LIVE)?.items.orEmpty() }
    val hasMovies = catalogue.section(ContentKind.MOVIE)?.items?.isNotEmpty() == true
    val hasShows = catalogue.section(ContentKind.SERIES)?.items?.isNotEmpty() == true

    fun toggleFavorite(ch: Channel) { favorites.toggle(ch.sourceId, ch.id); favRevision++ }
    fun isFav(ch: Channel) = favorites.isFavorite(ch.sourceId, ch.id)

    CompositionLocalProvider(LocalRem provides rem) {
        Column(
            Modifier.fillMaxSize().drawBehind {
                drawRect(GBg)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(GGlow, GBg2, GBg),
                        center = Offset(size.width * 0.2f, -size.height * 0.1f),
                        radius = size.width * 0.9f,
                    )
                )
            }
        ) {
            GTopBar(
                showBack = view != GView.HOME,
                crumb = when (view) {
                    GView.HOME -> ""
                    GView.GUIDE -> "Guide"
                    GView.MOVIES -> "Movies"
                    GView.SHOWS -> "Shows"
                },
                recording = recordings.any { it.status == RecordingStatus.RECORDING },
                now = now,
                onBack = { view = GView.HOME },
                onHome = { view = GView.HOME },
                onGuide = { view = GView.GUIDE },
                onSearch = onSearch,
                onMenu = onSettings,
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = rem.dp(1.6f))) {
                when (view) {
                    GView.HOME -> GHome(
                        live = live, now = now, nowPlaying = nowPlaying, epgIdFor = epgIdFor,
                        favorites = remember(live, favRevision) { live.filter { isFav(it) } },
                        recordings = recordings, hasMovies = hasMovies, hasShows = hasShows,
                        sourceName = sourceName,
                        onGo = { view = it },
                        onLive = {
                            val i = live.indexOfFirst { it.id == lastChannelId }.coerceAtLeast(0)
                            if (live.isNotEmpty()) onPlay(live, i)
                        },
                        onPlay = onPlay, onSearch = onSearch, onSettings = onSettings,
                        onOpenRecordings = onOpenRecordings,
                    )
                    GView.GUIDE -> GGuide(
                        catalogue = catalogue, live = live, now = now, epgStatus = epgStatus,
                        epgIdFor = epgIdFor, guideFor = guideFor, epgRevision = epgRevision,
                        favRevision = favRevision, isFav = ::isFav,
                        lastChannelId = lastChannelId,
                        onSelectionChanged = onSelectionChanged,
                        onPlay = onPlay,
                        onOptions = { rows, i, p ->
                            val ch = rows[i]
                            val acts = ArrayList<MenuAction>()
                            acts += MenuAction("▶  Watch ${ch.name}") { onPlay(rows, i) }
                            if (p != null && p.endUtc > System.currentTimeMillis()) {
                                acts += MenuAction("●  Record ${p.title}") { onRecord(ch, p) }
                                if (p.startUtc > System.currentTimeMillis()) {
                                    acts += if (hasReminder(ch, p)) MenuAction("Cancel the reminder") { onCancelReminder(ch, p) }
                                    else MenuAction("Remind me when it starts") { onSetReminder(ch, p) }
                                }
                            } else {
                                acts += MenuAction("●  Record this channel now") { onRecordChannel(ch) }
                            }
                            acts += if (isFav(ch)) MenuAction("☆  Remove from Favorites") { toggleFavorite(ch) }
                            else MenuAction("★  Add to Favorites") { toggleFavorite(ch) }
                            menu = (p?.title ?: ch.name) to acts
                        },
                        onCategories = { cats, pick ->
                            menu = "Categories" to (listOf(MenuAction("All channels") { pick(null) }) +
                                cats.map { (name, n) -> MenuAction("$name  ($n)") { pick(name) } })
                        },
                    )
                    GView.MOVIES, GView.SHOWS -> GLibrary(
                        kind = if (view == GView.MOVIES) ContentKind.MOVIE else ContentKind.SERIES,
                        section = catalogue.section(if (view == GView.MOVIES) ContentKind.MOVIE else ContentKind.SERIES),
                        onPlay = onPlay, onOpenSeries = onOpenSeries,
                    )
                }
            }
            GHints(
                when (view) {
                    GView.HOME -> listOf("←→↑↓" to "Move", "OK" to "Select", "≡" to "Options")
                    GView.GUIDE -> listOf("↑↓←→" to "Move", "OK" to "Watch", "Hold OK" to "Record · Favorite", "Back" to "Home")
                    else -> listOf("↑↓" to "Move", "OK" to "Play", "Back" to "Home")
                }
            )
        }
        menu?.let { (title, acts) -> ItemMenu(title = title, actions = acts, onDismiss = { menu = null }) }
    }
}

// ---------------- frame ----------------

@Composable
internal fun GTopBar(
    showBack: Boolean, crumb: String, recording: Boolean, now: Long,
    onBack: () -> Unit, onHome: () -> Unit, onGuide: () -> Unit, onSearch: () -> Unit, onMenu: () -> Unit,
) {
    val r = LocalRem.current
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(start = r.dp(1.6f), end = r.dp(1.6f), top = r.dp(0.7f), bottom = r.dp(0.4f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(r.dp(1.2f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(r.dp(0.55f))) {
            GLogo(r.dp(2.3f), 2.dp)
            Text(
                buildAnnotatedString {
                    append("Garys")
                    withStyle(SpanStyle(color = GRed2)) { append("TV") }
                },
                style = gStyle(1.35f, FontWeight.ExtraBold, spacing = 0.02f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(r.dp(0.4f))) {
            if (showBack) GPill("‹ Back", onBack)
            GPill("Home", onHome)
            GPill("Guide", onGuide)
            GPill("Search", onSearch)
            GPill("☰ Menu", onMenu)
        }
        if (crumb.isNotEmpty()) {
            Text(crumb.uppercase(), style = gStyle(1.1f, FontWeight.SemiBold, GMuted, spacing = 0.12f), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (recording) {
            Row(
                Modifier.clip(RoundedCornerShape(r.dp(1f))).background(Color(0xFF3A0712))
                    .border(1.dp, Color(0xFF7A1026), RoundedCornerShape(r.dp(1f)))
                    .padding(horizontal = r.dp(0.7f), vertical = r.dp(0.2f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(r.dp(0.45f)),
            ) {
                GDot()
                Text("Recording", style = gStyle(0.9f, FontWeight.SemiBold))
            }
        }
        val date = remember(now) { java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now) }
        val time = remember(now) { DateFormat.getTimeFormat(ctx).format(now) }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = GMuted, fontWeight = FontWeight.Normal, fontSize = r.sp(1f))) { append("$date   ") }
                append(time)
            },
            style = gStyle(1.25f, FontWeight.SemiBold),
        )
    }
}

@Composable
internal fun GLogo(size: Dp, ring: Dp) {
    Image(
        painter = painterResource(R.drawable.garys_logo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape).border(ring, GRed, CircleShape),
    )
}

@Composable
internal fun GDot(color: Color = GRed2) {
    val r = LocalRem.current
    Box(Modifier.size(r.dp(0.7f)).clip(CircleShape).background(color))
}

/** A top-bar button: GarysTV's `.nav` pill, white when focused. */
@Composable
internal fun GPill(label: String, onClick: () -> Unit) {
    val r = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(r.dp(2f)))
            .background(if (focused) GFocusBg else GPanel)
            .border(1.dp, if (focused) GFocusBg else GLine, RoundedCornerShape(r.dp(2f)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = r.dp(0.8f), vertical = r.dp(0.25f)),
    ) {
        Text(label, style = gStyle(0.95f, FontWeight.SemiBold, if (focused) GFocusText else GText), maxLines = 1)
    }
}

/** GarysTV's `.chip`: rounded, red when on, white when focused. */
@Composable
internal fun GChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val r = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when { focused -> GFocusBg; on -> GRed; else -> GPanel }
    val edge = when { focused -> GFocusBg; on -> GRed; else -> GLine }
    Box(
        modifier.clip(RoundedCornerShape(r.dp(2f))).background(bg)
            .border(2.dp, edge, RoundedCornerShape(r.dp(2f)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = r.dp(1f), vertical = r.dp(0.3f)),
    ) {
        Text(label, style = gStyle(0.95f, FontWeight.SemiBold, if (focused) GFocusText else GText), maxLines = 1)
    }
}

@Composable
internal fun GHints(hints: List<Pair<String, String>>) {
    val r = LocalRem.current
    Row(
        Modifier.fillMaxWidth().padding(start = r.dp(1.6f), end = r.dp(1.6f), top = r.dp(0.55f), bottom = r.dp(0.8f)),
        horizontalArrangement = Arrangement.spacedBy(r.dp(1.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.widthIn(min = r.dp(1.6f)).clip(RoundedCornerShape(r.dp(0.3f))).background(GPanel2)
                        .border(1.dp, GLine, RoundedCornerShape(r.dp(0.3f)))
                        .padding(horizontal = r.dp(0.4f), vertical = r.dp(0.05f)),
                    contentAlignment = Alignment.Center,
                ) { Text(key, style = gStyle(0.9f, FontWeight.SemiBold)) }
                Spacer(Modifier.width(r.dp(0.35f)))
                Text(label, style = gStyle(0.9f, color = GMuted))
            }
        }
    }
}

/** Keeps asking for focus until the target is attached (it may not be on the first frame). */
@Composable
internal fun FocusOnEnter(fr: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        repeat(20) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

// ---------------- Home ----------------

private enum class GIcon(val paths: List<String>) {
    GUIDE(listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 9h18M9 9v11M3 14.5h18")),
    LIVE(listOf("M4.5 5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8M10 9.5v5l4.5-2.5z")),
    MOVIES(listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16M17 4v16M3 8h4M3 12h4M3 16h4M17 8h4M17 12h4M17 16h4")),
    SHOWS(listOf("M4.5 7h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M8 3l4 4 4-4", "M10 11v5l4-2.5z")),
    DVR(listOf("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M13 11h5M13 13.5h3")),
    SEARCH(listOf("M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "M15.5 15.5 21 21")),
    SETTINGS(listOf("M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1")),
}

/** GarysTV's line icons (its SVGs, 24x24 viewBox, 1.6 stroke). */
@Composable
private fun GIconView(icon: GIcon, color: Color, size: Dp) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            if (icon == GIcon.DVR) drawCircle(GRed2, radius = 2.2f, center = Offset(8f, 12f))
        }
    }
}

@Composable
private fun GHome(
    live: List<Channel>, now: Long, nowPlaying: Map<String, Programme>, epgIdFor: (Channel) -> String,
    favorites: List<Channel>, recordings: List<Recording>, hasMovies: Boolean, hasShows: Boolean, sourceName: String,
    onGo: (GView) -> Unit, onLive: () -> Unit, onPlay: (List<Channel>, Int) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onOpenRecordings: () -> Unit,
) {
    val r = LocalRem.current
    val first = remember { FocusRequester() }
    FocusOnEnter(first, Unit)
    val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val greet = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    val done = recordings.count { it.status == RecordingStatus.DONE }

    data class Tile(val icon: GIcon, val label: String, val sub: String, val go: () -> Unit)
    val tiles = buildList {
        add(Tile(GIcon.GUIDE, "Guide", "See what's on") { onGo(GView.GUIDE) })
        add(Tile(GIcon.LIVE, "Live TV", "Last channel", onLive))
        if (hasMovies) add(Tile(GIcon.MOVIES, "Movies", "On demand") { onGo(GView.MOVIES) })
        if (hasShows) add(Tile(GIcon.SHOWS, "Shows", "Every episode") { onGo(GView.SHOWS) })
        add(Tile(GIcon.DVR, "My Recordings", "$done ${if (done == 1) "show" else "shows"}", onOpenRecordings))
        add(Tile(GIcon.SEARCH, "Search", "Find a show", onSearch))
        add(Tile(GIcon.SETTINGS, "Settings", "Sources, guide, playback", onSettings))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileH = (maxHeight * 0.30f).coerceIn(r.dp(8f), r.dp(17f))
        val logoSize = (maxHeight * 0.12f).coerceIn(r.dp(4.5f), r.dp(7f))
        Column(
            Modifier.fillMaxSize().padding(bottom = maxHeight * 0.04f),
            verticalArrangement = Arrangement.spacedBy(r.dp(2f), Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(r.dp(1.6f))) {
                GLogo(logoSize, 3.dp)
                Text(
                    buildAnnotatedString {
                        append("$greet. ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("What would you like to watch?") }
                    },
                    style = gStyle(2.4f, FontWeight.Light),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text(sourceName, style = gStyle(1f, color = GMuted), maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(r.dp(1f))) {
                tiles.forEachIndexed { i, t ->
                    GTile(t.icon, t.label, t.sub, tileH, Modifier.weight(1f).let { if (i == 0) it.focusRequester(first) else it }, t.go)
                }
            }
            GStrip("Favorites") {
                if (favorites.isEmpty()) {
                    item { GCard("☆ No favorites yet", "In the Guide, hold OK on a channel and choose Add to Favorites", onClick = { onGo(GView.GUIDE) }) }
                }
                itemsIndexed(favorites) { i, ch ->
                    GCard(ch.name, nowPlaying[epgIdFor(ch)]?.title ?: (ch.group ?: "Live"), star = true) { onPlay(favorites, i) }
                }
            }
            val upcoming = recordings.filter { it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED }
                .sortedBy { it.startUtc }.take(6)
            GStrip("Recording now & up next") {
                if (upcoming.isEmpty()) item { GEmpty("Nothing scheduled. Open the Guide and hold OK on a show to record it.") }
                items(upcoming) { rec ->
                    GCard(
                        rec.channelName,
                        if (rec.status == RecordingStatus.RECORDING) "Recording now" else whenText(rec.startUtc),
                        dot = if (rec.status == RecordingStatus.RECORDING) GRed2 else GRed2.copy(alpha = 0.6f),
                        onClick = onOpenRecordings,
                    )
                }
            }
            val recent = recordings.filter { it.status == RecordingStatus.DONE }.sortedByDescending { it.startUtc }.take(6)
            GStrip("Recently recorded") {
                if (recent.isEmpty()) item { GEmpty("Your recordings will show up here.") }
                items(recent) { rec -> GCard(rec.channelName, whenText(rec.startUtc), onClick = onOpenRecordings) }
            }
        }
    }
}

@Composable
private fun whenText(t: Long): String {
    val ctx = LocalContext.current
    val cal = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    val day = when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) + 1 -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(t)
    }
    return "$day ${DateFormat.getTimeFormat(ctx).format(t)}"
}

@Composable
private fun GTile(icon: GIcon, label: String, sub: String, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    val r = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(r.dp(1f))
    Column(
        modifier.height(height).scale(if (focused) 1.03f else 1f).clip(shape)
            .background(if (focused) Brush.verticalGradient(listOf(GFocusBg, GFocusBg)) else Brush.verticalGradient(listOf(GPanel2, GPanel)))
            .border(2.dp, if (focused) GFocusBg else GLine, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(r.dp(0.5f)),
        verticalArrangement = Arrangement.spacedBy(r.dp(0.8f), Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GIconView(icon, if (focused) GFocusText else GText, r.dp(3.6f))
        Text(label, style = gStyle(1.3f, FontWeight.SemiBold, if (focused) GFocusText else GText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = gStyle(0.85f, color = if (focused) GFocusSub else GMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GStrip(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val r = LocalRem.current
    Column {
        Text(title.uppercase(), style = gStyle(1.15f, FontWeight.SemiBold, GMuted, spacing = 0.1f))
        Spacer(Modifier.height(r.dp(0.7f)))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(r.dp(1f)), content = content)
    }
}

@Composable
private fun GEmpty(text: String) {
    val r = LocalRem.current
    Text(text, style = gStyle(1f, color = GMuted), modifier = Modifier.padding(vertical = r.dp(1f)))
}

@Composable
private fun GCard(title: String, sub: String, star: Boolean = false, dot: Color? = null, onClick: () -> Unit) {
    val r = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(r.dp(0.7f))
    Column(
        Modifier.width(r.dp(17f)).scale(if (focused) 1.03f else 1f).clip(shape)
            .background(if (focused) GFocusBg else GPanel)
            .border(2.dp, if (focused) GFocusBg else GLine, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = r.dp(1.1f), vertical = r.dp(1f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(r.dp(0.5f))) {
            if (star) Text("★", style = gStyle(1.1f, FontWeight.Bold, GYellow))
            if (dot != null) GDot(dot)
            Text(title, style = gStyle(1.1f, FontWeight.Bold, if (focused) GFocusText else GText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(r.dp(0.3f)))
        Text(sub, style = gStyle(0.9f, color = if (focused) GFocusSub else GMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Guide ----------------

@Composable
private fun GGuide(
    catalogue: PlaylistRepository.Catalogue,
    live: List<Channel>,
    now: Long,
    epgStatus: String?,
    epgIdFor: (Channel) -> String,
    guideFor: suspend (List<Channel>, Long, Long) -> Map<String, List<Programme>>,
    epgRevision: Int,
    favRevision: Int,
    isFav: (Channel) -> Boolean,
    lastChannelId: String?,
    onSelectionChanged: (String) -> Unit,
    onPlay: (List<Channel>, Int) -> Unit,
    onOptions: (List<Channel>, Int, Programme?) -> Unit,
    onCategories: (List<Pair<String, Int>>, (String?) -> Unit) -> Unit,
) {
    val r = LocalRem.current
    val ctx = LocalContext.current
    val timeFmt = remember(ctx) { DateFormat.getTimeFormat(ctx) }
    var filter by remember { mutableStateOf<GFilter>(GFilter.All) }
    val categories = remember(catalogue) {
        catalogue.section(ContentKind.LIVE)?.byCategory.orEmpty()
            .filter { it.channels.isNotEmpty() }
            .map { it.label to it.channels.size }
    }
    val rows = remember(live, filter, favRevision) {
        when (val f = filter) {
            GFilter.All -> live
            GFilter.Favorites -> live.filter(isFav)
            is GFilter.Category -> {
                val ids = catalogue.section(ContentKind.LIVE)?.byCategory.orEmpty()
                    .firstOrNull { it.label == f.name }?.channels.orEmpty().map { it.id }.toHashSet()
                live.filter { it.id in ids }
            }
        }
    }
    var selRow by remember(rows) {
        mutableIntStateOf(if (filter == GFilter.All) rows.indexOfFirst { it.id == lastChannelId }.coerceAtLeast(0) else 0)
    }
    var top by remember(rows) { mutableIntStateOf((selRow - 2).coerceAtLeast(0)) }
    var ws by remember { mutableLongStateOf(align(System.currentTimeMillis())) }
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableIntStateOf(7) }
    var progs by remember { mutableStateOf<Map<String, List<Programme>>>(emptyMap()) }
    var gridFocused by remember { mutableStateOf(false) }
    val grid = remember { FocusRequester() }
    FocusOnEnter(grid, Unit)

    val shown = if (rows.isEmpty()) emptyList() else rows.subList(top.coerceIn(0, rows.size), (top + visible + 1).coerceAtMost(rows.size))
    var progsWs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shown.map { it.id }, ws, epgRevision) {
        if (shown.isEmpty()) return@LaunchedEffect
        val got = guideFor(shown, ws, ws + SPAN)
        // A new time window starts a fresh map, so it never grows unbounded.
        progs = if (progsWs == ws) progs + got else got
        progsWs = ws
    }

    val selCh = rows.getOrNull(selRow)
    LaunchedEffect(selCh?.id) { selCh?.let { onSelectionChanged(it.id) } }
    fun progsFor(ch: Channel) = progs[epgIdFor(ch)].orEmpty()
    fun progAt(ch: Channel, at: Long) = progsFor(ch).firstOrNull { at >= it.startUtc && at < it.endUtc }
    val selProg = selCh?.let { progAt(it, t) }

    fun keepVisible() {
        if (selRow < top) top = selRow
        if (selRow >= top + visible) top = selRow - visible + 1
    }
    fun moveRow(d: Int) {
        if (rows.isEmpty()) return
        selRow = (selRow + d).coerceIn(0, rows.size - 1); keepVisible()
    }
    fun moveTime(forward: Boolean) {
        val ch = selCh ?: return
        val p = progAt(ch, t)
        if (forward) {
            val next = p?.endUtc ?: (align(t) + SLOT)
            if (next >= ws + SPAN) ws += SLOT
            t = next
        } else {
            val start = p?.startUtc ?: align(t)
            val target = start - 1
            val floor = align(System.currentTimeMillis())
            if (target < floor) { t = maxOf(t, System.currentTimeMillis()); return }
            if (target < ws) ws = maxOf(floor, align(target))
            t = target
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ----- info pane -----
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.31f).padding(top = r.dp(0.6f), bottom = r.dp(1f)),
            verticalArrangement = Arrangement.spacedBy(r.dp(0.35f)),
        ) {
            Text(
                selProg?.title ?: selCh?.name ?: "No channels",
                style = gStyle(2.1f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (selCh != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(r.dp(0.8f))) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = GText)) { append(numberOf(selCh, rows.indexOf(selCh))) }
                            append("  ${selCh.name}")
                        },
                        style = gStyle(1f, color = GMuted), maxLines = 1,
                    )
                    if (selProg != null) {
                        Text(
                            "${dayWord(selProg.startUtc)} ${timeFmt.format(selProg.startUtc)} – ${timeFmt.format(selProg.endUtc)}",
                            style = gStyle(1f, color = GMuted),
                        )
                        Text("${(selProg.endUtc - selProg.startUtc) / 60_000} min", style = gStyle(1f, color = GMuted))
                    }
                    selCh.group?.let { GTag(it) }
                    if (isFav(selCh)) Text("★ Favorite", style = gStyle(0.9f, FontWeight.Bold, GYellow))
                }
                if (selProg != null && now >= selProg.startUtc && now < selProg.endUtc) {
                    Box(Modifier.width(r.dp(24f)).height(r.dp(0.3f)).clip(RoundedCornerShape(r.dp(0.2f))).background(Color.White.copy(alpha = 0.11f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(selProg.progressAt(now)).background(GRed2))
                    }
                }
                Text(
                    selProg?.description ?: if (selProg == null) (epgStatus ?: "No guide information for this channel.") else "",
                    style = gStyle(1.05f, color = GDesc), maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ----- bar above the guide -----
        Row(
            Modifier.fillMaxWidth().padding(bottom = r.dp(0.6f)),
            horizontalArrangement = Arrangement.spacedBy(r.dp(0.5f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GChip("All channels", filter == GFilter.All) { filter = GFilter.All }
            GChip("★ Favorites", filter == GFilter.Favorites) { filter = GFilter.Favorites }
            val cat = (filter as? GFilter.Category)?.name
            GChip("▦ ${cat ?: "Categories"}", cat != null) {
                onCategories(categories) { picked -> filter = if (picked == null) GFilter.All else GFilter.Category(picked) }
            }
            if (cat != null) GChip("✕", false) { filter = GFilter.All }
            Spacer(Modifier.weight(1f))
            Text("${rows.size} channels", style = gStyle(0.95f, color = GMuted))
        }

        // ----- the grid -----
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val chanW = r.dp(15f)
            val rowH = r.dp(4.4f)
            val progW = maxWidth - chanW
            val density = LocalDensity.current
            val rowPx = with(density) { rowH.toPx() }
            val fits = ((maxHeight - r.dp(2.6f)) / rowH).toInt().coerceAtLeast(1)
            LaunchedEffect(fits) { visible = fits; keepVisible() }

            Column(Modifier.fillMaxSize().drawBehind { drawLine(GLine, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }) {
                // header: day + times
                Row(Modifier.fillMaxWidth().height(r.dp(2.6f))) {
                    Box(Modifier.width(chanW).fillMaxHeight().padding(start = r.dp(0.8f)), contentAlignment = Alignment.CenterStart) {
                        Text(dayWord(ws).uppercase(), style = gStyle(0.95f, FontWeight.Bold, GYellow, spacing = 0.06f))
                    }
                    Box(Modifier.width(progW).fillMaxHeight()) {
                        for (k in 0 until 3) {
                            val x = progW * (k / 3f)
                            Box(
                                Modifier.offset(x = x).fillMaxHeight().width(progW / 3)
                                    .drawBehind { drawLine(GLine, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                                    .padding(start = r.dp(0.6f)),
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(timeFmt.format(ws + k * SLOT), style = gStyle(1f, FontWeight.SemiBold, GMuted)) }
                        }
                        NowLine(now, ws, progW)
                    }
                }
                // body
                Box(
                    Modifier.fillMaxSize()
                        .onFocusChanged { gridFocused = it.isFocused }
                        .focusRequester(grid)
                        .onKeyEvent { e -> guideKey(e, rows.isNotEmpty(), selRow,
                            up = { moveRow(-1) }, down = { moveRow(1) },
                            left = { moveTime(false) }, right = { moveTime(true) },
                            ok = { if (rows.isNotEmpty()) onPlay(rows, selRow) },
                            options = { if (rows.isNotEmpty()) onOptions(rows, selRow, selProg) },
                            page = { d -> moveRow(d * visible) }) }
                        .focusable()
                        .pointerInput(rows, visible) {
                            var acc = 0f
                            detectVerticalDragGestures(onDragEnd = { acc = 0f }) { _, dy ->
                                acc += dy
                                while (acc > rowPx) { acc -= rowPx; if (top > 0) { top--; if (selRow >= top + visible) selRow = top + visible - 1 } }
                                while (acc < -rowPx) { acc += rowPx; if (top + visible < rows.size) { top++; if (selRow < top) selRow = top } }
                            }
                        },
                ) {
                    if (rows.isEmpty()) {
                        Text(
                            if (filter == GFilter.Favorites) "No favorites yet. Hold OK on a channel and choose Add to Favorites."
                            else "No channels here.",
                            style = gStyle(1.2f, color = GMuted),
                            modifier = Modifier.align(Alignment.Center).padding(r.dp(3f)),
                        )
                    }
                    Column {
                        shown.forEachIndexed { k, ch ->
                            val index = top + k
                            val sel = index == selRow
                            Row(Modifier.fillMaxWidth().height(rowH).drawBehind {
                                drawLine(GBg, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                            }) {
                                GChannelCell(ch, numberOf(ch, index), sel, isFav(ch), Modifier.width(chanW).fillMaxHeight()
                                    .pointerInput(ch.id, index, rows) { detectTapGestures(onTap = { onPlay(rows, index) }) })
                                Box(Modifier.width(progW).fillMaxHeight()) {
                                    val list = progsFor(ch)
                                    if (list.isEmpty()) {
                                        GProgCell(ch.name, "", Dp(0f), progW, alt = false, filler = true,
                                            focused = sel && gridFocused, cutLeft = false, cutRight = false,
                                            onTap = { if (sel) onPlay(rows, index) else { selRow = index; t = maxOf(ws, System.currentTimeMillis()) } },
                                            onLong = { selRow = index; onOptions(rows, index, null) })
                                    }
                                    list.forEachIndexed { j, p ->
                                        val s = maxOf(p.startUtc, ws)
                                        val e = minOf(p.endUtc, ws + SPAN)
                                        if (e > s) {
                                            val x = progW * ((s - ws).toFloat() / SPAN)
                                            val w = progW * ((e - s).toFloat() / SPAN)
                                            val isFocus = sel && t >= p.startUtc && t < p.endUtc
                                            GProgCell(
                                                p.title, "${timeFmt.format(p.startUtc)} – ${timeFmt.format(p.endUtc)}", x, w,
                                                alt = j % 2 == 1, filler = false, focused = isFocus && gridFocused,
                                                cutLeft = p.startUtc < ws, cutRight = p.endUtc > ws + SPAN,
                                                onTap = {
                                                    if (isFocus) {
                                                        if (now >= p.startUtc) onPlay(rows, index) else onOptions(rows, index, p)
                                                    } else { selRow = index; t = maxOf(p.startUtc, System.currentTimeMillis()) }
                                                },
                                                onLong = { selRow = index; t = maxOf(p.startUtc, System.currentTimeMillis()); onOptions(rows, index, p) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(start = chanW).width(progW).fillMaxHeight()) { NowLine(now, ws, progW) }
                }
            }
        }
    }
}

/** D-pad handling for the guide grid. Returns false for Up on the first row so focus can leave for the bar above. */
private var heldOk = false
private fun guideKey(
    e: androidx.compose.ui.input.key.KeyEvent, any: Boolean, selRow: Int,
    up: () -> Unit, down: () -> Unit, left: () -> Unit, right: () -> Unit,
    ok: () -> Unit, options: () -> Unit, page: (Int) -> Unit,
): Boolean {
    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    if (isOk) {
        if (e.type == KeyEventType.KeyDown) {
            if (e.nativeKeyEvent.isLongPress && !heldOk) { heldOk = true; options() }
        } else if (e.type == KeyEventType.KeyUp) {
            if (!heldOk) ok()
            heldOk = false
        }
        return true
    }
    if (e.key == Key.Menu) { if (e.type == KeyEventType.KeyUp) options(); return true }
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.DirectionUp -> if (!any || selRow == 0) false else { up(); true }
        Key.DirectionDown -> { down(); true }
        Key.DirectionLeft -> { left(); true }
        Key.DirectionRight -> { right(); true }
        Key.PageUp, Key.ChannelUp -> { page(-1); true }
        Key.PageDown, Key.ChannelDown -> { page(1); true }
        else -> false
    }
}

private fun numberOf(ch: Channel, index: Int) = (ch.number ?: (index + 1)).toString()

private fun dayWord(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    val diff = (c.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 366 + c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
    return when (diff) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(t)
    }
}

@Composable
private fun NowLine(now: Long, ws: Long, width: Dp) {
    if (now < ws || now >= ws + SPAN) return
    Box(
        Modifier.offset(x = width * ((now - ws).toFloat() / SPAN)).width(2.dp).fillMaxHeight().background(GRed2)
    )
}

@Composable
private fun GTag(text: String) {
    val r = LocalRem.current
    Box(
        Modifier.border(1.dp, GLine, RoundedCornerShape(r.dp(0.25f))).padding(horizontal = r.dp(0.4f))
    ) { Text(text, style = gStyle(0.8f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = r.dp(14f))) }
}

@Composable
private fun GChannelCell(ch: Channel, number: String, selected: Boolean, fav: Boolean, modifier: Modifier) {
    val r = LocalRem.current
    Row(
        modifier.background(if (selected) GPanel2 else GPanel)
            .drawBehind {
                drawLine(GBg, Offset(size.width, 0f), Offset(size.width, size.height), 2.dp.toPx())
                if (selected) drawRect(GRed2, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
            }
            .padding(horizontal = r.dp(0.8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(r.dp(0.6f)),
    ) {
        Text(number, style = gStyle(1.35f, FontWeight.Bold), maxLines = 1, modifier = Modifier.widthIn(min = r.dp(3.2f)))
        if (!ch.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ch.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = r.dp(3.2f), height = r.dp(2.2f)),
            )
        }
        Text(ch.name, style = gStyle(0.95f, color = GMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (fav) Text("★", style = gStyle(1f, color = GYellow))
    }
}

@Composable
private fun GProgCell(
    title: String, sub: String, x: Dp, w: Dp, alt: Boolean, filler: Boolean, focused: Boolean,
    cutLeft: Boolean, cutRight: Boolean, onTap: () -> Unit, onLong: () -> Unit,
) {
    val r = LocalRem.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    Column(
        Modifier.offset(x = x).width(w).fillMaxHeight()
            .background(if (focused) GFocusBg else if (alt) GCellAlt else GCell)
            .drawBehind {
                drawLine(GBg, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx())
                if (focused) drawRect(GRed2, topLeft = Offset(0f, size.height - 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 4.dp.toPx()))
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { long() }) }
            .padding(horizontal = r.dp(0.7f), vertical = r.dp(0.45f)),
        verticalArrangement = Arrangement.Center,
    ) {
        val label = (if (cutLeft) "‹ " else "") + title + (if (cutRight) " ›" else "")
        Text(
            label,
            style = gStyle(1.1f, if (filler) FontWeight.Normal else FontWeight.SemiBold,
                if (focused) GFocusText else if (filler) GDim else GText),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (sub.isNotEmpty() && w > r.dp(9f)) {
            Text(sub, style = gStyle(0.85f, color = if (focused) GFocusSub else GMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- Movies / Shows ----------------

@Composable
private fun GLibrary(
    kind: ContentKind,
    section: PlaylistRepository.Section?,
    onPlay: (List<Channel>, Int) -> Unit,
    onOpenSeries: (Channel) -> Unit,
) {
    val r = LocalRem.current
    val groups = remember(section) { section?.byCategory.orEmpty().filter { it.channels.isNotEmpty() } }
    var group by remember(section) { mutableStateOf<String?>(null) }
    val items = remember(section, group) {
        if (group == null) section?.items.orEmpty()
        else groups.firstOrNull { it.label == group }?.channels.orEmpty()
    }
    var sel by remember(items) { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    FocusOnEnter(first, kind)
    val open: (Int) -> Unit = { i ->
        val it = items[i]
        if (kind == ContentKind.SERIES) onOpenSeries(it) else onPlay(items, i)
    }

    Row(Modifier.fillMaxSize().padding(bottom = r.dp(0.4f)), horizontalArrangement = Arrangement.spacedBy(r.dp(1.6f))) {
        Column(Modifier.weight(1.25f).fillMaxHeight()) {
            Row(Modifier.padding(top = r.dp(0.2f), bottom = r.dp(0.8f)), verticalAlignment = Alignment.CenterVertically) {
                Text((if (kind == ContentKind.SERIES) "Shows" else "Movies").uppercase(),
                    style = gStyle(1.15f, FontWeight.SemiBold, GMuted, spacing = 0.1f))
                Spacer(Modifier.weight(1f))
                Text("${items.size}", style = gStyle(0.95f, color = GMuted))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(r.dp(0.5f)), modifier = Modifier.padding(bottom = r.dp(0.8f))) {
                item { GChip("All", group == null) { group = null } }
                items(groups) { g -> GChip(g.label, group == g.label) { group = g.label } }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(r.dp(0.35f)), modifier = Modifier.weight(1f)) {
                itemsIndexed(items, key = { _, c -> c.id }) { i, c ->
                    GItemRow(c, Modifier.let { if (i == 0) it.focusRequester(first) else it }, onFocus = { sel = i }) { open(i) }
                }
            }
        }
        val c = items.getOrNull(sel)
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(r.dp(0.8f))).background(GPanel)
                .border(1.dp, GLine, RoundedCornerShape(r.dp(0.8f)))
                .padding(horizontal = r.dp(1.6f), vertical = r.dp(1.4f)),
            verticalArrangement = Arrangement.spacedBy(r.dp(0.7f)),
        ) {
            if (c == null) {
                Text("Nothing here yet.", style = gStyle(1.1f, color = GMuted))
            } else {
                if (!c.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = c.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = r.dp(22f)).clip(RoundedCornerShape(r.dp(0.6f))),
                    )
                }
                Text(c.name, style = gStyle(1.8f, FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                c.group?.let { Text(it, style = gStyle(1.15f, color = GSub), maxLines = 1) }
                Text(
                    if (kind == ContentKind.SERIES) "Press OK to see the seasons and episodes." else "Press OK to play.",
                    style = gStyle(1f, color = GMuted),
                )
            }
        }
    }
}

@Composable
private fun GItemRow(c: Channel, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val r = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val shape = RoundedCornerShape(r.dp(0.5f))
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (focused) GFocusBg else GPanel)
            .drawBehind { if (focused) drawRect(GRed2, size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)) }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = r.dp(1f), vertical = r.dp(0.75f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(r.dp(0.9f)),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = gStyle(1.15f, FontWeight.SemiBold, if (focused) GFocusText else GText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.group?.let { Text(it, style = gStyle(0.92f, color = if (focused) GFocusSub else GMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
