package io.tapper.firetv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.tapper.firetv.BuildConfig
import io.tapper.firetv.TapperApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app up to date from its GitHub releases.
 *
 * Every successful build is published as a release tagged "build-N" with the
 * APK attached, and N is also the app's version code (see build.gradle.kts),
 * so "is there a newer build" is a plain number comparison. When Auto-update
 * is on (the default) the app checks when it opens - at most every
 * [CHECK_EVERY_MS] - downloads a newer APK in the background and then asks
 * before installing it.
 *
 * Android never lets an ordinary app install silently, so the last step is
 * always the system's own "Do you want to update this app?" screen, and the
 * first time it needs the "Install unknown apps" permission for this app.
 */
class AppUpdater(private val context: Context) {

    private val prefs = context.getSharedPreferences("tapper_updates", Context.MODE_PRIVATE)

    /** Auto-update on/off. On unless the viewer turned it off. */
    var autoUpdate by mutableStateOf(prefs.getBoolean("auto", true))
        private set

    /** One line for Settings: what the last check found or what is going on. */
    var status by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** Build number of an update that is downloaded and ready to install. */
    var readyBuild by mutableStateOf<Int?>(null)
        private set

    private var readyFile: File? = null

    fun setAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean("auto", on).apply()
    }

    /** True when the automatic check has not run for a while. */
    fun dueForCheck(): Boolean =
        System.currentTimeMillis() - prefs.getLong("last_check", 0L) > CHECK_EVERY_MS

    /** Looks for a newer build and, if there is one, downloads it. Safe to
     *  call from the main thread; the network work is moved off it. */
    suspend fun check() {
        if (busy) return
        busy = true
        try {
            status = "Checking for updates..."
            val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() }
            if (release == null) {
                status = "Couldn't check for updates - no connection, or GitHub didn't answer."
                return
            }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (release.build <= BuildConfig.VERSION_CODE) {
                readyBuild = null; readyFile = null
                status = "You're up to date (build ${BuildConfig.VERSION_CODE})."
                return
            }
            val file = File(updateDir(), "tapper-iptv-${release.build}.apk")
            if (!(file.exists() && file.length() == release.sizeBytes)) {
                status = "Downloading build ${release.build}..."
                val ok = withContext(Dispatchers.IO) { runCatching { download(release, file) }.getOrDefault(false) }
                if (!ok) {
                    status = "Build ${release.build} is available, but the download didn't finish."
                    return
                }
            }
            readyFile = file
            readyBuild = release.build
            status = "Build ${release.build} is downloaded and ready to install."
        } finally {
            busy = false
        }
    }

    /** Hands the downloaded APK to the system installer. */
    suspend fun install() {
        val file = readyFile?.takeIf { it.exists() } ?: run {
            status = "No update is downloaded yet."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            status = "Allow Tapper IPTV to install apps (Settings > My Fire TV > Developer options > " +
                "Install unknown apps), then choose Install again."
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        status = "Installing build ${readyBuild ?: ""}..."
        val started = withContext(Dispatchers.IO) { runCatching { commitInstall(file) }.isSuccess }
        if (!started) status = "Couldn't start the install."
    }

    /** Called by [UpdateInstallReceiver] as the system reports progress. */
    internal fun onInstallStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            // The system wants the viewer's OK: show its confirmation screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> status = "Updated."
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                status = "The install didn't complete. $why".trim()
            }
        }
    }

    private fun commitInstall(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("tapper.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Explicit receiver, and mutable because the system adds the
            // status extras to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java), flags,
            )
            session.commit(pending.intentSender)
        }
    }

    private class Release(val build: Int, val apkUrl: String, val sizeBytes: Long)

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TapperIPTV-updater")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(build, a.getString("browser_download_url"), a.optLong("size", -1L))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release, target: File): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TapperIPTV-updater")
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
        if (release.sizeBytes > 0 && part.length() != release.sizeBytes) { part.delete(); return false }
        // Must really be this app, not something else that was served.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
        if (info?.packageName != context.packageName) { part.delete(); return false }
        target.delete()
        if (!part.renameTo(target)) return false
        // Older downloads are no use any more.
        target.parentFile?.listFiles()?.forEach { if (it != target) it.delete() }
        return true
    }

    private fun updateDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private companion object {
        const val REPO = "userxx09-afk/iptv"
        const val CHECK_EVERY_MS = 6L * 60L * 60L * 1000L
    }
}

/** Receives the system installer's progress reports for [AppUpdater]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? TapperApp)?.updater?.onInstallStatus(context, intent)
    }
}
