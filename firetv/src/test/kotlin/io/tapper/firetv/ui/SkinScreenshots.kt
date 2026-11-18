package io.tapper.firetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import io.tapper.core.model.StreamRef
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Skin
import io.tapper.firetv.ui.theme.TapperTheme
import io.tapper.firetv.ui.theme.UiSkin
import io.tapper.firetv.ui.theme.skinBackground
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the browse screen's real building blocks (source header, nav rail,
 * channel rows, guide panel, chips) in each skin, so a skin change can be
 * looked at without a Fire TV. Not part of the normal build: run by the
 * "Fire TV skin screenshots" workflow, which uploads the PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A 1080p Fire TV: 1920x1080 px at xhdpi. Plain Application so TapperApp's
// keystore/WorkManager start-up doesn't run under Robolectric.
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class SkinScreenshots {
    @get:Rule val compose = createComposeRule()

    @Test fun tapperBrowse() = shot(Skin.TAPPER, "browse-tapper")
    @Test fun garysHome() = garys(GView.HOME, "garys-home")
    @Test fun garysGuide() = garys(GView.GUIDE, "garys-guide")
    @Test fun garysShows() = garys(GView.SHOWS, "garys-shows")

    private fun garys(view: GView, name: String) {
        UiSkin.choose(RuntimeEnvironment.getApplication(), Skin.GARYSTV)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        GarysNav.view = view
        compose.setContent { TapperTheme { SampleGarys() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "build/skin-shots/$name.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }

    private fun shot(skin: Skin, name: String) {
        UiSkin.choose(RuntimeEnvironment.getApplication(), skin)
        // D-pad mode, as on a Fire TV, so the first channel row can take focus.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent { TapperTheme { SampleBrowse() } }
        compose.waitForIdle()
        compose.runOnIdle { runCatching { sampleFocus?.requestFocus() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "build/skin-shots/$name.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }
}

private var sampleFocus: FocusRequester? = null

private val source = TvSource(id = "s1", name = "Free TV", kind = TvSource.Kind.FREE, location = "")

private fun ch(i: Int, name: String) = Channel(
    id = "c$i", sourceId = "s1", name = name, number = i, logoUrl = null,
    group = "News", countryCode = "US", epgChannelId = null,
    streams = listOf(StreamRef("http://example.invalid/$i.m3u8", 0)),
)

private val channels = listOf(
    ch(2, "WCBS 2 New York"), ch(4, "WNBC 4 New York"), ch(5, "WNYW FOX 5"),
    ch(7, "WABC 7 New York"), ch(11, "WPIX 11"), ch(13, "WNET PBS 13"),
)

private val titles = listOf("CBS Evening News", "NBC Nightly News", "The Simpsons",
    "Jeopardy!", "Seinfeld", "PBS NewsHour")

@Composable
private fun SampleBrowse() {
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    val slot = now - now % (hour / 2)
    val schedule = listOf(
        EpgDatabase.Programme("c2", slot, slot + hour / 2, "CBS Evening News", "The day's top stories."),
        EpgDatabase.Programme("c2", slot + hour / 2, slot + hour, "Entertainment Tonight", null),
        EpgDatabase.Programme("c2", slot + hour, slot + 2 * hour, "FBI", "A bombing in Queens sends the team racing."),
        EpgDatabase.Programme("c2", slot + 2 * hour, slot + 3 * hour, "Tracker", null),
    )
    val focus = remember { FocusRequester() }
    sampleFocus = focus
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(Modifier.fillMaxSize().skinBackground().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            SourceHeader(source, "Guide: 140 channels") {}
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RailRow("Search", "", false, onFocused = {}, onClick = {}, onLongPress = {})
                RailRow("Live TV", "140", true, onFocused = {}, onClick = {}, onLongPress = {})
                RailRow("Movies", "0", false, onFocused = {}, onClick = {}, onLongPress = {})
                RailRow("Recordings", "", false, onFocused = {}, onClick = {}, onLongPress = {})
                RailRow("My Teams", "", false, onFocused = {}, onClick = {}, onLongPress = {})
                RailRow("Settings", "", false, onFocused = {}, onClick = {}, onLongPress = {})
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LIVE TV  ·  NEWS", style = MaterialTheme.typography.bodyMedium, color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All", true) {}
                Chip("Favourites", false) {}
            }
            channels.forEachIndexed { i, c ->
                ChannelRow(
                    channel = c, favorite = i == 1,
                    programme = EpgDatabase.Programme(c.id, slot, slot + hour, titles[i], null),
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                    onFocused = {}, onClick = {}, onLongPress = {},
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        ProgrammePanel(
            channel = channels[0], schedule = schedule, expanded = false, onFocused = {},
            hasReminder = { false }, onSetReminder = {}, onCancelReminder = {}, onRecord = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

private val gridTitles = listOf(
    listOf("CBS Evening News" to 30, "Entertainment Tonight" to 30, "FBI" to 60, "Tracker" to 60),
    listOf("NBC Nightly News" to 30, "Access Hollywood" to 30, "Chicago Fire" to 60, "Law & Order" to 60),
    listOf("The Simpsons" to 30, "Bob's Burgers" to 30, "NFL Thursday Night Football" to 180),
    listOf("Jeopardy!" to 30, "Wheel of Fortune" to 30, "Abbott Elementary" to 30, "Shifting Gears" to 30, "20/20" to 60),
    listOf("Seinfeld" to 60, "Family Feud" to 30, "Family Feud" to 30, "PIX11 News at 10" to 60),
    listOf("PBS NewsHour" to 60, "Antiques Roadshow" to 60, "Frontline" to 60),
)

private fun show(i: Int, name: String) = Channel(
    id = "s$i", sourceId = "s1", name = name, number = null, logoUrl = null,
    group = "Drama", countryCode = null, epgChannelId = null, kind = ContentKind.SERIES,
)

@Composable
private fun SampleGarys() {
    val now = System.currentTimeMillis()
    val slot = now - now % 1_800_000L - 15 * 60_000L
    val schedules = channels.mapIndexed { i, c ->
        var t = slot
        c.id to gridTitles[i].map { (title, mins) ->
            val p = EpgDatabase.Programme(c.id, t, t + mins * 60_000L, title,
                if (i == 0 && t == slot) "Norah O'Donnell with the day's top stories from across the country." else null)
            t += mins * 60_000L
            p
        }
    }.toMap()
    val shows = listOf("Yellowstone", "The Bear", "Severance", "Abbott Elementary", "Slow Horses", "Shrinking")
        .mapIndexed { i, n -> show(i, n) }
    val live = PlaylistRepository.Group(null, "News", channels)
    val catalogue = PlaylistRepository.Catalogue(
        sourceId = "s1",
        channels = channels + shows,
        sections = mapOf(
            ContentKind.LIVE to PlaylistRepository.Section(ContentKind.LIVE, channels, listOf(live), listOf(live)),
            ContentKind.SERIES to PlaylistRepository.Section(ContentKind.SERIES, shows,
                listOf(PlaylistRepository.Group(null, "Drama", shows)), listOf(PlaylistRepository.Group(null, "Drama", shows))),
        ),
        declaredEpgUrls = emptyList(),
        fromCache = false,
    )
    val favorites = remember { FavoritesStore(RuntimeEnvironment.getApplication(), "shots_fav") }
    GarysBrowseScreen(
        catalogue = catalogue,
        favorites = favorites,
        sourceName = "Free TV",
        nowPlaying = schedules.mapValues { (_, l) -> l.first() }.mapKeys { (k, _) -> k },
        epgStatus = null,
        epgIdFor = { it.id },
        guideFor = { chans, _, _ -> chans.associate { it.id to schedules[it.id].orEmpty() } },
        epgRevision = 0,
        lastChannelId = null,
        recordings = listOf(
            Recording("r1", "s1", "c2", "WCBS 2 New York", "", now - 3_600_000L, now + 1_800_000L, status = RecordingStatus.RECORDING),
            Recording("r2", "s1", "c13", "WNET PBS 13", "", now - 86_400_000L, now - 82_800_000L, status = RecordingStatus.DONE),
        ),
        onPlay = { _, _ -> }, onOpenSeries = {}, onSearch = {}, onSettings = {}, onOpenRecordings = {},
        onRecord = { _, _ -> }, onRecordChannel = {},
        hasReminder = { _, _ -> false }, onSetReminder = { _, _ -> }, onCancelReminder = { _, _ -> },
        onSelectionChanged = {},
    )
}
