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
        private const val UNIQUE_WORK_NAME = "epg-refresh"
        private const val REPEAT_INTERVAL_HOURS = 6L

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
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TapperApp
        val source = app.sourceStore.active()
        runCatching {
            // Declared guide URLs live on the parsed catalogue, not the saved
            // source - there is no UI here to already have one loaded, so this
            // pays the same playlist-load cost the foreground path pays on
            // every cold start, just on a timer instead of on open.
            val catalogue = app.repository.load(source).getOrNull()
            app.epg.refresh(source, catalogue?.declaredEpgUrls.orEmpty()).getOrThrow()
        }.fold(
            onSuccess = { Result.success() },
            // Retry rather than failure: a dead guide URL is routine (the
            // foreground path treats it as informational, not an error) and a
            // transient network blip on a schedule this infrequent is not
            // worth waiting 6 more hours to recover from.
            onFailure = { Result.retry() },
        )
    }
}
