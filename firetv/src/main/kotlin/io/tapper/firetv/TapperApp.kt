package io.tapper.firetv

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.tapper.firetv.data.CredentialVault
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.EpgOverrideStore
import io.tapper.firetv.data.EpgRefreshWorker
import io.tapper.firetv.data.EpgRepository
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.MovieMetadataStore
import io.tapper.firetv.data.NavHomeStore
import io.tapper.firetv.data.Notifications
import io.tapper.firetv.data.PlayerSettingsStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.RecordingStore
import io.tapper.firetv.data.ReminderStore
import io.tapper.firetv.data.SourceStore
import io.tapper.firetv.data.SupplementalEpgStore
import io.tapper.firetv.data.WatchStore
import io.tapper.firetv.data.WatchSync

class TapperApp : Application() {
    lateinit var vault: CredentialVault; private set
    lateinit var sourceStore: SourceStore; private set
    lateinit var favorites: FavoritesStore; private set
    lateinit var repository: PlaylistRepository; private set
    lateinit var epgDb: EpgDatabase; private set
    lateinit var epg: EpgRepository; private set
    lateinit var epgOverrides: EpgOverrideStore; private set
    lateinit var supplementalEpg: SupplementalEpgStore; private set
    lateinit var reminders: ReminderStore; private set
    lateinit var recordings: RecordingStore; private set
    lateinit var watch: WatchStore; private set
    lateinit var sync: WatchSync; private set
    lateinit var playerSettings: PlayerSettingsStore; private set
    lateinit var navHome: NavHomeStore; private set
    lateinit var movieMetadata: MovieMetadataStore; private set

    /** Last crash, if any, for Settings to display. */
    fun lastCrash(): String? =
        File(filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clearLastCrash() {
        runCatching { File(filesDir, "last-crash.txt").delete() }
    }

    private val eventLogFile: File get() = File(filesDir, "event-log.txt")
    private val maxEventLogLines = 200

    /**
     * A running log of load and guide failures, not just fatal crashes - a
     * "couldn't reach the panel" or "guide unavailable" that gets swallowed
     * into a quiet fallback is otherwise invisible outside of catching it on
     * screen at the moment it happens. Newest entry first, capped by line
     * count rather than size so it can't grow without bound on a source that
     * fails the same way repeatedly.
     */
    @Synchronized
    fun logEvent(tag: String, message: String) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$stamp  [$tag]  $message"
            val existing = if (eventLogFile.exists()) eventLogFile.readLines() else emptyList()
            eventLogFile.writeText((listOf(line) + existing).take(maxEventLogLines).joinToString("\n"))
        }
    }

    fun eventLog(): String? =
        eventLogFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clearEventLog() {
        runCatching { eventLogFile.delete() }
    }

    /**
     * Records the stack trace of a fatal exception before the process dies.
     *
     * Without a cable and adb there is otherwise no way to find out why the app
     * disappeared, which turns every crash report into guesswork. The default
     * handler still runs afterwards, so behaviour is unchanged.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(filesDir, "last-crash.txt").writeText(
                    stamp + "  (thread: " + thread.name + ")\n\n" + sw.toString()
                )
            }
            // Also folded into the event log, so a crash shows up in the same
            // timeline as whatever load/guide trouble led up to it, instead of
            // only existing in the separate one-shot crash slot.
            logEvent("CRASH", "${error.javaClass.simpleName}: ${error.message}")
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        vault = CredentialVault(this)
        sourceStore = SourceStore(this)
        favorites = FavoritesStore(this)
        repository = PlaylistRepository(cacheDir, vault, onWarning = { msg -> logEvent("CATALOGUE", msg) })
        epgDb = EpgDatabase(this)
        supplementalEpg = SupplementalEpgStore(this)
        epg = EpgRepository(epgDb, vault, supplementalEpg)
        epgOverrides = EpgOverrideStore(this)
        reminders = ReminderStore(this)
        recordings = RecordingStore(this)
        watch = WatchStore(this)
        sync = WatchSync(this, watch)
        playerSettings = PlayerSettingsStore(this)
        navHome = NavHomeStore(this)
        movieMetadata = MovieMetadataStore(this, onError = { msg -> logEvent("TMDB", msg) })

        Notifications.createChannels(this)
        EpgRefreshWorker.schedule(this)
        // A RECORDING row left behind by a crash, force-stop, or reboot will
        // never finish on its own - see RecordingStore for why this is safer
        // than leaving it stuck.
        recordings.markOrphanedRecordingsFailed()
    }
}
