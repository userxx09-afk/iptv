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
import com.github.takahirom.roborazzi.captureRoboImage
import io.tapper.core.model.Channel
import io.tapper.core.model.StreamRef
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Skin
import io.tapper.firetv.ui.theme.TapperTheme
import io.tapper.firetv.ui.theme.UiSkin
import io.tapper.firetv.ui.theme.skinBackground
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
    @Test fun garysBrowse() = shot(Skin.GARYSTV, "browse-garystv")

    private fun shot(skin: Skin, name: String) {
        UiSkin.choose(RuntimeEnvironment.getApplication(), skin)
        compose.setContent { TapperTheme { SampleBrowse() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/skin-shots/$name.png")
    }
}

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
