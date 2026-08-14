package io.tapper.firetv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes this device's watch state and merges everyone else's.
 *
 * The whole design rests on one rule: a device writes only its own file. Two
 * devices can sync at the same instant without a lock, because they never touch
 * the same object. Merging happens locally on read, using rules that are
 * order-independent - so it does not matter who syncs first, or twice.
 */
class WatchSync(
    private val context: Context,
    private val store: WatchStore,
) {
    companion object {
        private const val PREFIX = "tapper-watch-"
        private const val PREFS = "tapper_sync"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Config(
        val kind: Kind = Kind.NONE,
        val folderUri: String? = null,
        val webdavUrl: String? = null,
        val username: String? = null,
    ) {
        enum class Kind { NONE, FOLDER, WEBDAV }
    }

    fun config(): Config = Config(
        kind = runCatching { Config.Kind.valueOf(prefs.getString("kind", "NONE")!!) }
            .getOrDefault(Config.Kind.NONE),
        folderUri = prefs.getString("folder", null),
        webdavUrl = prefs.getString("dav", null),
        username = prefs.getString("user", null),
    )

    fun saveFolder(uri: Uri) {
        prefs.edit().putString("kind", Config.Kind.FOLDER.name)
            .putString("folder", uri.toString()).apply()
    }

    fun saveWebDav(url: String, user: String?, pass: String?) {
        prefs.edit().putString("kind", Config.Kind.WEBDAV.name)
            .putString("dav", url).putString("user", user).putString("pass", pass).apply()
    }

    fun disable() = prefs.edit().putString("kind", Config.Kind.NONE.name).apply()

    private fun backend(): SyncBackend? {
        val c = config()
        return when (c.kind) {
            Config.Kind.FOLDER -> c.folderUri?.let { SafBackend(context, Uri.parse(it)) }
            Config.Kind.WEBDAV -> c.webdavUrl?.let {
                WebDavBackend(it, c.username, prefs.getString("pass", null))
            }
            Config.Kind.NONE -> null
        }
    }

    fun describe(): String = backend()?.label ?: "Not set up"

    data class Result(val pulledFrom: Int, val changed: Int, val pushed: Boolean, val label: String)

    suspend fun sync(): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val b = backend() ?: error("Sync isn't set up yet.")
            val me = WatchStore.deviceId(context)
            val mine = PREFIX + me + ".json"

            // Pull first, so anything this device then publishes already
            // reflects what the others knew.
            var changed = 0
            var pulled = 0
            for (name in b.list(PREFIX)) {
                if (name == mine) continue
                val body = b.read(name) ?: continue
                pulled++
                changed += store.importShard(body)
            }

            b.write(mine, store.exportOwn(me, WatchStore.deviceLabel(context)))
            Result(pulled, changed, true, b.label)
        }
    }

    /** Records progress locally. Pushed on the next sync, not per update. */
    fun record(
        itemId: String,
        sourceId: String,
        seriesId: String?,
        season: Int,
        number: Int,
        title: String,
        positionMs: Long,
        durationMs: Long,
        finished: Boolean,
    ) {
        val entry = WatchStore.Entry(
            itemId = itemId, sourceId = sourceId, seriesId = seriesId,
            season = season, number = number, title = title,
            positionMs = positionMs, durationMs = durationMs,
            watched = finished, clock = store.nextClock(),
            deviceId = WatchStore.deviceId(context),
        )
        store.put(if (entry.isComplete()) entry.copy(watched = true, positionMs = 0) else entry)
    }

    /**
     * The episode to offer next: the first in the series that has not been
     * completed, after the highest one that has. Falls back to the first
     * episode when nothing has been watched.
     */
    fun nextEpisode(seriesId: String, episodes: List<EpisodeRef>): EpisodeRef? {
        val watchedIds = store.all().filter { it.seriesId == seriesId && it.watched }
            .map { it.itemId }.toSet()
        val ordered = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        return ordered.firstOrNull { it.itemId !in watchedIds }
    }

    /** Resume point for a specific item, or null if unwatched or finished. */
    fun resumeAt(itemId: String): Long? =
        store.get(itemId)?.takeIf { !it.watched && it.positionMs > 5_000 }?.positionMs

    data class EpisodeRef(val itemId: String, val season: Int, val number: Int)
}
