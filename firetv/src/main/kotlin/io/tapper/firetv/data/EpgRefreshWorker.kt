package io.tapper.firetv.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import io.tapper.firetv.TapperApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the active source's guide fresh independently of whether the app is
 * open.
 *
 * The foreground path (MainActivity.refreshEpg) only ever runs when the app
 * happens to be opened after the guide has already gone stale - on a device
 * that stays parked on one live channel for days, that means a guide that's
 * a day or more out of date before anyone notices, and a refresh that then
 * competes with whatever the person actually opened the app to do. This runs
 * on its own schedule, retried and constrained by the platform (network
 * required, exponential backoff on failure) rather than by a coroutine tied
 * to an Activity's lifecycle.
 */
class EpgRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        // The schedule changed from every 6 hours to every 12. KEEP leaves an
        // already-scheduled job exactly as it was, so the new schedule needs a
        // new name; the old one is cancelled below.
        private const val OLD_WORK_NAME = "epg-refresh"
        private const val UNIQUE_WORK_NAME = "epg-refresh-12h"
        private const val REPEAT_INTERVAL_HOURS = 12L
        /** A guide fetched more recently than this is not fetched again. */
        private const val SKIP_IF_NEWER_MS = 10 * 60 * 60 * 1000L
        /** How long a playlist's declared guide addresses are reused rather
         *  than loading the whole playlist again to read them. */
        private const val DECLARED_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val DECLARED_PREFS = "epg_declared"

        /** Call once, from TapperApp.onCreate. Idempotent: KEEP means a second
         *  call (e.g. after a process restart) does not reset the schedule. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(OLD_WORK_NAME)
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TapperApp
        val source = app.sourceStore.active()
        runCatching {
            // Fetched recently already (the app was opened, or the last run
            // was a while ago but the guide is still current): nothing to do.
            val age = System.currentTimeMillis() - app.epgDb.fetchedAt(source.id)
            if (age < SKIP_IF_NEWER_MS && app.epgDb.countFor(source.id) > 0) return@runCatching
            app.epg.refresh(source, declaredUrls(app, source)).getOrThrow()
            Unit
        }.fold(
            onSuccess = { Result.success() },
            // Retry rather than failure: a dead guide URL is routine (the
            // foreground path treats it as informational, not an error) and a
            // transient network blip on a schedule this infrequent is not
            // worth waiting 12 more hours to recover from.
            onFailure = { Result.retry() },
        )
    }

    /**
     * The guide addresses a playlist declares in its header. An Xtream source
     * declares none (its guide comes from the provider; see
     * EpgRepository.guideUrls), so nothing needs loading for it. A plain M3U
     * playlist is loaded once to read them and they are remembered for a
     * week - before, every run parsed and indexed the whole catalogue (and
     * re-downloaded it once its cache was a day old) just to learn them.
     */
    private suspend fun declaredUrls(app: TapperApp, source: TvSource): List<String> {
        if (source.kind == TvSource.Kind.XTREAM) return emptyList()
        val prefs = applicationContext.getSharedPreferences(DECLARED_PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(source.id + ".at", 0L)
        val saved = prefs.getString(source.id, null)
        if (saved != null && System.currentTimeMillis() - savedAt < DECLARED_MAX_AGE_MS) {
            return saved.split('\n').filter { it.isNotBlank() }
        }
        val urls = app.repository.load(source).getOrNull()?.declaredEpgUrls.orEmpty()
        prefs.edit()
            .putString(source.id, urls.joinToString("\n"))
            .putLong(source.id + ".at", System.currentTimeMillis())
            .apply()
        return urls
    }
}
