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
        /** The signed-in Google or Microsoft account, for GOOGLE / MICROSOFT. */
        val account: String? = null,
    ) {
        enum class Kind { NONE, FOLDER, WEBDAV, GOOGLE, MICROSOFT }
    }

    fun config(): Config = Config(
        kind = runCatching { Config.Kind.valueOf(prefs.getString("kind", "NONE")!!) }
            .getOrDefault(Config.Kind.NONE),
        folderUri = prefs.getString("folder", null),
        webdavUrl = prefs.getString("dav", null),
        username = prefs.getString("user", null),
        account = prefs.getString("cloud_account", null),
    )

    // Refresh tokens are as good as a password to the account, so they go to
    // the keystore-backed vault rather than these plain preferences.
    private val vault by lazy { CredentialVault(context) }
    private fun vaultKey(p: CloudProvider) = "sync-cloud-" + p.name.lowercase()

    fun saveFolder(uri: Uri) {
        prefs.edit().putString("kind", Config.Kind.FOLDER.name)
            .putString("folder", uri.toString()).apply()
    }

    fun saveWebDav(url: String, user: String?, pass: String?) {
        prefs.edit().putString("kind", Config.Kind.WEBDAV.name)
            .putString("dav", url).putString("user", user).putString("pass", pass).apply()
    }

    /** Turning sync off also signs out of any cloud account. */
    fun disable() {
        CloudProvider.entries.forEach { vault.delete(vaultKey(it)) }
        prefs.edit().putString("kind", Config.Kind.NONE.name).remove("cloud_account").apply()
    }

    /** Step one of cloud sign-in: the code to show. Blocking network call. */
    fun startCloudSignIn(p: CloudProvider): CloudAuth.DeviceCode = CloudAuth.start(p)

    /**
     * Step two: waits for the user to finish on their other device, then
     * creates the sync folder in their Drive / OneDrive and switches sync over
     * to it. Returns the account name.
     */
    suspend fun finishCloudSignIn(code: CloudAuth.DeviceCode): String = withContext(Dispatchers.IO) {
        val tokens = CloudAuth.await(code)
        val refresh = tokens.refreshToken
            ?: throw CloudAuthException("${code.provider.company} didn't allow offline access. Try signing in again.")
        val p = code.provider
        val account = CloudAuth.accountName(p, tokens.accessToken)
        vault.put(vaultKey(p), account, refresh)
        val session = CloudSession(p, account, refresh) { vault.put(vaultKey(p), account, it) }
        when (p) {
            CloudProvider.GOOGLE -> GoogleDriveBackend(session).ensureFolder()
            CloudProvider.MICROSOFT -> OneDriveBackend(session).ensureFolder()
        }
        prefs.edit()
            .putString("kind", if (p == CloudProvider.GOOGLE) Config.Kind.GOOGLE.name else Config.Kind.MICROSOFT.name)
            .putString("cloud_account", account)
            .apply()
        account
    }

    private fun cloudSession(p: CloudProvider): CloudSession? {
        val (account, refresh) = vault.get(vaultKey(p)) ?: return null
        return CloudSession(p, account, refresh) { vault.put(vaultKey(p), account, it) }
    }

    private fun backend(): SyncBackend? {
        val c = config()
        return when (c.kind) {
            Config.Kind.FOLDER -> c.folderUri?.let { SafBackend(context, Uri.parse(it)) }
            Config.Kind.WEBDAV -> c.webdavUrl?.let {
                WebDavBackend(it, c.username, prefs.getString("pass", null))
            }
            Config.Kind.GOOGLE -> cloudSession(CloudProvider.GOOGLE)?.let { GoogleDriveBackend(it) }
            Config.Kind.MICROSOFT -> cloudSession(CloudProvider.MICROSOFT)?.let { OneDriveBackend(it) }
            Config.Kind.NONE -> null
        }
    }

    fun describe(): String = backend()?.label ?: "Not set up"

    data class Result(val pulledFrom: Int, val changed: Int, val pushed: Boolean, val label: String)

    suspend fun sync(): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val b = backend() ?: error(
                if (config().kind == Config.Kind.GOOGLE || config().kind == Config.Kind.MICROSOFT)
                    "The cloud sign-in is missing. Sign in again in Settings."
                else "Sync isn't set up yet."
            )
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
