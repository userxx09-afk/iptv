package io.tapper.firetv.recordings

import io.tapper.firetv.TapperApp
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import io.tapper.firetv.data.SmbStorage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves one movie, episode or catch-up programme for offline viewing.
 *
 * On-demand titles from an IPTV provider are ordinary files on a web server
 * (Xtream's movie/ and series/ links are .mp4 or .mkv), so a download is a
 * plain HTTP fetch written to the same place recordings go (Settings >
 * Recording: this Fire TV or a network share). The player already opens
 * file:// and smb:// paths, so a finished download plays like a recording.
 *
 * A dropped connection is retried a few times, picking up where it stopped
 * with an HTTP Range request when the file is on this Fire TV. A download to
 * a network share starts that file again instead (smbj's writer here only
 * creates files, it doesn't append).
 *
 * What can't be downloaded: a title the provider only serves as HLS (a
 * .m3u8 playlist of small pieces). Saving that would mean fetching and
 * joining every piece, which this doesn't do; it fails with a clear reason.
 */
internal class Downloader(
    private val app: TapperApp,
    /** Asked between reads and before each retry; true ends the download. */
    private val stopRequested: () -> Boolean,
    /** The live connection, so a stop can disconnect it and unblock a read. */
    private val onConnection: (HttpURLConnection?) -> Unit,
    private val onProgress: (done: Long, total: Long) -> Unit,
) {
    sealed interface Outcome {
        data class Done(val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Stopped : Outcome
    }

    private class GiveUp(message: String) : Exception(message)

    /** The file the last attempt wrote to, for cleaning up after a delete. */
    @Volatile var path: String? = null
        private set

    fun run(rec: Recording): Outcome {
        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            if (stopRequested()) return Outcome.Stopped
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            if (stopRequested()) return Outcome.Stopped
            try {
                // Re-read each time: the path and byte count from the last
                // attempt are what a resume needs.
                val current = app.recordings.get(rec.id) ?: return Outcome.Stopped
                return Outcome.Done(fetchOnce(current))
            } catch (g: GiveUp) {
                return Outcome.Failed(g.message ?: "Download failed")
            } catch (t: Throwable) {
                if (stopRequested()) return Outcome.Stopped
                lastError = t
                app.logEvent("DOWNLOAD", "${rec.channelName}: attempt ${attempt + 1} failed (${t.javaClass.simpleName}: ${t.message})")
            } finally {
                onConnection(null)
            }
        }
        return Outcome.Failed(friendly(lastError))
    }

    private fun fetchOnce(rec: Recording): Long {
        val location = app.recordingLocation

        // Resume only a partial file on this Fire TV; anything else (first
        // try, or a share) starts from the beginning.
        val partial = rec.filePath
            ?.takeIf { !it.startsWith("smb://", ignoreCase = true) }
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0 }
        val share = if (partial != null) null else location.activeShare()
        val name = partial?.name ?: fileName(rec)
        val localFile = partial ?: File(location.localDir(), name)
        var from = partial?.length() ?: 0L

        val conn = open(rec.streamUrl, rec.headers, from)
        onConnection(conn)
        val code = conn.responseCode
        if (from > 0 && code == 416) {
            // Asked for bytes past the end: the file was already complete.
            return from
        }
        if (code !in 200..299) throw GiveUp(httpReason(code))
        if (code == 200) from = 0L  // the server ignored Range; start over

        val type = conn.contentType.orEmpty().lowercase()
        if ("mpegurl" in type) throw GiveUp(HLS_REASON)
        val length = conn.contentLengthLong
        val total = if (length >= 0) from + length else -1L

        if (share == null && length > 0) {
            val free = location.localFreeBytes()
            if (free in 1 until length + SPACE_MARGIN) {
                throw GiveUp(
                    "Not enough room on this Fire TV: it needs ${size(length)} and ${size(free)} is free. " +
                        "Set Recording settings to a network share, or delete something.",
                )
            }
        }

        val (out, written) = openOutput(rec, share, name, localFile, append = from > 0)
        path = written
        app.recordings.get(rec.id)?.let {
            app.recordings.upsert(it.copy(status = RecordingStatus.RECORDING, filePath = written, bytesDone = from, bytesTotal = total, error = null))
        }

        var done = from
        var lastReport = 0L
        conn.inputStream.use { input ->
            out.use { o ->
                val buffer = ByteArray(BUFFER)
                var first = from == 0L
                while (true) {
                    if (stopRequested()) throw IOException("stopped")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (first) {
                        first = false
                        // A playlist served as text/plain or octet-stream.
                        if (n >= 7 && String(buffer, 0, 7, Charsets.US_ASCII) == "#EXTM3U") throw GiveUp(HLS_REASON)
                    }
                    o.write(buffer, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= REPORT_EVERY_MS) {
                        lastReport = now
                        onProgress(done, total)
                    }
                }
            }
        }
        if (total > 0 && done < total) {
            throw IOException("The connection closed at ${size(done)} of ${size(total)}")
        }
        onProgress(done, if (total > 0) total else done)
        return done
    }

    /** The file and its stored path: the share when one is set and opens,
     *  this Fire TV otherwise (same fallback recordings use). */
    private fun openOutput(
        rec: Recording, share: io.tapper.firetv.data.RecordingLocationStore.Share?,
        name: String, localFile: File, append: Boolean,
    ): Pair<OutputStream, String> {
        if (share != null) {
            try {
                return SmbStorage.create(share, name) to share.uriOf(name)
            } catch (t: Throwable) {
                app.logEvent(
                    "DOWNLOAD",
                    "Couldn't save ${rec.channelName} to ${share.label} (${SmbStorage.friendly(t)}). Saving it on this Fire TV instead.",
                )
            }
        }
        localFile.parentFile?.mkdirs()
        return FileOutputStream(localFile, append) to localFile.absolutePath
    }

    /** HttpURLConnection won't follow an http -> https redirect on its own,
     *  and providers often bounce VOD links to a CDN that way. */
    private fun open(url: String, headers: Map<String, String>, from: Long): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "TapperIPTV/0.3")
                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) setRequestProperty(k, v) }
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = conn.responseCode
            val next = conn.getHeaderField("Location")
            if (code in 300..399 && !next.isNullOrBlank()) {
                conn.disconnect()
                target = URL(URL(target), next).toString()
            } else {
                return conn
            }
        }
        throw GiveUp("The provider kept redirecting the download.")
    }

    private fun httpReason(code: Int): String = when (code) {
        401, 403 -> "The provider turned the download down ($code). Your plan may allow only one " +
            "connection at a time, so stop watching on other devices and try again."
        404, 410 -> "The provider no longer has this title ($code)."
        429, 458, 509 -> "The provider says too many connections are open ($code). Try again when nothing else is playing."
        in 500..599 -> "The provider's server had a problem ($code). Try again later."
        else -> "The provider answered $code."
    }

    private fun friendly(t: Throwable?): String {
        val msg = t?.message.orEmpty()
        return when {
            t == null -> "Download failed"
            t is java.net.UnknownHostException -> "Couldn't reach the provider. Check the internet connection."
            t is java.net.SocketTimeoutException -> "The provider stopped sending. Try again later."
            "ENOSPC" in msg || "No space" in msg -> "This Fire TV ran out of space."
            else -> "Download stopped: " + (msg.ifBlank { t.javaClass.simpleName })
        }
    }

    companion object {
        private const val ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 5_000L
        private const val REPORT_EVERY_MS = 1_500L
        private const val BUFFER = 256 * 1024
        private const val MAX_REDIRECTS = 6
        /** Kept free so a full download doesn't leave the Fire TV unusable. */
        private const val SPACE_MARGIN = 200L * 1024 * 1024
        const val HLS_REASON = "This title streams in small pieces (HLS), which can't be saved for offline viewing. Watch it online instead."

        fun fileName(rec: Recording): String {
            val base = rec.channelName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
                .trim().take(80).ifBlank { "download" }
            return base + "-" + rec.id.removePrefix("rec-") + extensionOf(rec.streamUrl)
        }

        /** Keeps the provider's container (.mp4, .mkv...) so the file is
         *  recognisable on a NAS; the player sniffs the format either way. */
        fun extensionOf(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase()
            return if (ext in KNOWN) ".$ext" else ".ts"
        }

        private val KNOWN = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "mpg", "mpeg", "flv", "wmv")

        /** Whether a URL is plainly an HLS playlist, so the menu can say so
         *  up front instead of failing a moment later. */
        fun looksLikeHls(url: String): Boolean =
            url.substringBefore('?').lowercase().endsWith(".m3u8")

        fun size(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
            else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }
}
