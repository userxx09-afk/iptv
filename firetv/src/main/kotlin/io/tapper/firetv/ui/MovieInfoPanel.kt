package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.data.MovieMetadataStore
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import java.util.Locale

/**
 * The Movies/Shows counterpart to ProgrammePanel - occupies the same third
 * column of the browse layout, but a movie or series has one thing to show
 * (poster, synopsis, rating) rather than a schedule. See MovieMetadataStore
 * for where the data comes from, and BrowseScreen's GUIDE column for how
 * focus routes here instead of to ProgrammePanel based on the focused
 * channel's kind.
 */
@Composable
fun MovieInfoPanel(
    channel: Channel?,
    info: MovieMetadataStore.Metadata?,
    /** True while a lookup for [channel] is in flight - distinguishes
     *  "still loading" from "TMDb has nothing on this one", which would
     *  otherwise look identical (both show no info yet). */
    loading: Boolean,
    /** Set when the most recent lookup failed outright (bad key, no
     *  network, TMDb unreachable) rather than genuinely finding nothing -
     *  shown instead of the generic "no match" message so a config problem
     *  doesn't look identical to TMDb simply not having this title. */
    error: String?,
    apiKeyConfigured: Boolean,
    expanded: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Same "focus arriving here widens the column" behaviour as
    // ProgrammePanel - see its own comment for why.
    LaunchedEffect(focused) { if (focused) onFocused() }

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) Focus.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onFocused)
            .padding(10.dp)
    ) {
        if (channel == null) {
            Text("Select a title", style = MaterialTheme.typography.bodyMedium, color = Dim)
            return@Column
        }

        Text(
            channel.name, style = MaterialTheme.typography.titleMedium, color = Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))

        when {
            !apiKeyConfigured -> Text(
                "Add a free TMDb API key in Settings to see posters, synopses " +
                    "and ratings here.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
            loading -> Text("Looking it up...", style = MaterialTheme.typography.bodyMedium, color = Dim)
            error != null -> Text(
                "Couldn't look this up: $error",
                style = MaterialTheme.typography.bodyMedium, color = Focus,
            )
            info == null -> Text(
                "No match found on TMDb for this title.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
            else -> {
                if (info.posterUrl != null) {
                    AsyncImage(
                        model = info.posterUrl, contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (expanded) 360.dp else 220.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // The title/year TMDb actually matched, not necessarily
                // identical to the VOD listing's own title text above it -
                // worth showing both, since a wrong match is otherwise
                // silent and looks like a correct one.
                Text(
                    info.title + (info.year?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge, color = Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                info.rating?.let { rating ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "★ " + String.format(Locale.US, "%.1f", rating) + " / 10",
                        style = MaterialTheme.typography.bodyMedium, color = Focus,
                    )
                }
                info.overview?.let { overview ->
                    Spacer(Modifier.height(10.dp))
                    Text(overview, style = MaterialTheme.typography.bodyMedium, color = Dim)
                }
                Spacer(Modifier.height(16.dp))
                // Required by TMDb's API terms for non-commercial use of
                // their data - see Settings for the fuller credit.
                Text(
                    "Data from TMDb", style = MaterialTheme.typography.bodySmall,
                    color = Dim.copy(alpha = 0.6f),
                )
            }
        }
    }
}
