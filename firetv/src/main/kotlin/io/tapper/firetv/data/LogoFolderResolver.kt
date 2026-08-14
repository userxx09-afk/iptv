package io.tapper.firetv.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.tapper.core.model.Channel

/**
 * Matches channels to locally-supplied logo images.
 *
 * Playlist-declared logos are frequently missing, low-resolution, or simply
 * wrong for a given provider - this is the same escape hatch TiviMate ships
 * (a per-source folder of images that overrides whatever the playlist
 * declares). A local folder has no id scheme to match against, only
 * filenames, so matching is by channel name, normalised identically on both
 * sides so "ESPN HD" and "espn-hd.png" still line up.
 */
object LogoFolderResolver {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Reads a granted folder's contents once into a name -> content-uri map.
     *  Does real I/O (a SAF directory listing) - call off the main thread. */
    fun index(context: Context, folderUri: String): Map<String, String> {
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull()
            ?: return emptyMap()
        if (!tree.isDirectory) return emptyMap()
        val out = HashMap<String, String>()
        for (file in tree.listFiles()) {
            val name = file.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTENSIONS) continue
            val base = name.substringBeforeLast('.')
            out[normalize(base)] = file.uri.toString()
        }
        return out
    }

    fun match(index: Map<String, String>, channelName: String): String? = index[normalize(channelName)]
}

/**
 * Rewrites every channel's logoUrl to a local match where one exists,
 * leaving the playlist's own URL as the fallback everywhere else.
 *
 * A no-op (returns `this`) when no folder is configured for the source, so
 * this costs nothing for the common case of not using the feature. Applied
 * once, high up the screen stack (see MainActivity), rather than threaded as
 * a resolver function through every screen that renders a logo - the whole
 * point is that BrowseScreen, PlayerScreen, SearchScreen and ProgrammePanel
 * all keep reading `channel.logoUrl` exactly as before and need no changes.
 */
fun PlaylistRepository.Catalogue.withResolvedLogos(logoIndex: Map<String, String>): PlaylistRepository.Catalogue {
    if (logoIndex.isEmpty()) return this

    fun fix(ch: Channel): Channel {
        val local = LogoFolderResolver.match(logoIndex, ch.name)
        return if (local != null) ch.copy(logoUrl = local) else ch
    }
    fun fixGroup(g: PlaylistRepository.Group) = g.copy(channels = g.channels.map(::fix))

    val newSections = sections.mapValues { (_, section) ->
        section.copy(
            items = section.items.map(::fix),
            byCountry = section.byCountry.map(::fixGroup),
            byCategory = section.byCategory.map(::fixGroup),
        )
    }
    return copy(channels = channels.map(::fix), sections = newSections)
}
