package io.tapper.firetv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.tapper.core.auth.Redact
import io.tapper.core.model.Channel
import io.tapper.core.model.StreamRef
import io.tapper.core.playback.*
import io.tapper.core.playlist.M3uParser
import io.tapper.firetv.data.BufferSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live playback for Fire TV.
 *
 * Three things a generic ExoPlayer wrapper doesn't do, all needed here:
 *  1. Per-stream request headers — 831 channels in the default playlist return
 *     403 without a specific User-Agent or Referer.
 *  2. Failover through alternate feeds, silently, before bothering the user.
 *  3. Explaining failures instead of spinning forever.
 */
@OptIn(UnstableApi::class)
class TapperPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDiagnosis: (Diagnosis) -> Unit,
    private val onPlaying: () -> Unit,
    /** Configurable from Settings - see BufferSize. Read once, at whatever
     *  value was current when this player was created; a value changed
     *  mid-playback takes effect the next time something is played, not on
     *  the stream already running, same as most players' buffer settings. */
    private val bufferSize: BufferSize = BufferSize.MEDIUM,
    /** Wired for v0.2 when the sync service lands; harmless no-ops until then. */
    private val probeAccount: suspend (String) -> AccountProbe? = { null },
    private val otherDeviceStreaming: suspend (String) -> OtherDevice? = { null },
) {

    /**
     * Buffers well below ExoPlayer's 50s default at the Medium setting.
     *
     * Zap speed is what people judge a TV app on, and live streams can't seek
     * backwards, so a deep buffer buys nothing on a good connection — it only
     * delays first frame. Larger presets exist for weak/congested connections
     * that need the cushion more than they need a fast channel change.
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(bufferSize.minMs, bufferSize.maxMs, bufferSize.playbackMs, bufferSize.rebufferMs)
        .build()

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null

    private var current: Channel? = null
    private var attempt = 0
    private var startedAtMs = 0L
    private var renderedFrames = false
    private var probeJob: Job? = null
    private var pendingResumeMs: Long? = null
    private var probe: AccountProbe? = null
    private var other: OtherDevice? = null

    fun attach(playerView: PlayerView) {
        view = playerView
        player?.let { playerView.player = it }
    }

    /** Position and duration for on-demand items; 0 for live streams. */
    fun positionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun durationMs(): Long =
        player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L

    /** Transport controls for on-demand playback. Live channels don't call
     *  any of these - there is nothing to pause or seek on a live stream,
     *  and the screen never offers the controls that would call them. */
    fun isPlaying(): Boolean = player?.isPlaying == true

    fun setPlaying(playing: Boolean) {
        player?.playWhenReady = playing
    }

    /** Relative seek, clamped to the known duration where one is available.
     *  A negative delta with no known duration (metadata not loaded yet)
     *  still clamps at zero rather than seeking to a negative position,
     *  which some panels' progressive containers handle badly. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = durationMs()
        val target = p.currentPosition + deltaMs
        p.seekTo(if (dur > 0) target.coerceIn(0, dur) else target.coerceAtLeast(0))
    }

    fun play(channel: Channel, resumeMs: Long? = null) {
        current = channel
        attempt = 0
        pendingResumeMs = resumeMs

        val first = channel.streams.first()
        // Fail fast and honestly rather than loading something ExoPlayer will
        // never open. Two channels in the default playlist are mmsh://.
        if (!M3uParser.isPlayable(first.url)) {
            onDiagnosis(Diagnosis.UnsupportedFormat(first.url.substringBefore("://")))
            return
        }
        startStream(channel, first)
    }

    private fun startStream(channel: Channel, stream: StreamRef) {
        renderedFrames = false
        startedAtMs = System.currentTimeMillis()
        probe = null
        other = null

        // Probe concurrently, never before. Blocking every channel change on an
        // API call to check a limit we're usually under would tax every zap to
        // pay for the rare failure. Results are read only if playback dies.
        probeJob?.cancel()
        probeJob = scope.launch {
            probe = runCatching { probeAccount(channel.sourceId) }.getOrNull()
            other = runCatching { otherDeviceStreaming(channel.sourceId) }.getOrNull()
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)   // http -> https hops are common
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .apply {
                if (stream.headers.isNotEmpty()) setDefaultRequestProperties(stream.headers)
                stream.headers["User-Agent"]?.let { setUserAgent(it) }
            }

        val exo = player ?: ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
            .also {
                it.addListener(listener)
                player = it
                view?.player = it
            }

        exo.setMediaItem(MediaItem.fromUri(stream.url))
        exo.prepare()
        // Seek after prepare: seeking before the timeline is known is ignored
        // on progressive sources, which silently restarts the episode.
        pendingResumeMs?.takeIf { it > 5_000 }?.let { exo.seekTo(it) }
        exo.playWhenReady = true
    }

    private val listener = object : Player.Listener {

        override fun onRenderedFirstFrame() {
            renderedFrames = true
            onPlaying()
        }

        override fun onPlayerError(error: PlaybackException) {
            val channel = current ?: return

            // Try the next alternate feed before surfacing anything. On a free
            // playlist this recovers a large share of failures invisibly.
            val next = channel.streams.getOrNull(attempt + 1)
            if (next != null && M3uParser.isPlayable(next.url)) {
                attempt++
                // Releasing an ExoPlayer from inside its own listener callback
                // is unsafe — it tears down the object still on the stack.
                // Defer to the next main-loop pass. A new player is required
                // anyway because the data source headers differ per feed.
                scope.launch {
                    player?.release()
                    player = null
                    startStream(channel, next)
                }
                return
            }
            explain(channel, error)
        }
    }

    private fun explain(channel: Channel, error: PlaybackException) {
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val socket = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        val evidence = FailureEvidence(
            httpStatus = status,
            socketError = socket,
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            renderedFrames = renderedFrames,
            account = probe,
            otherDeviceStreaming = other,
            networkReachable = isOnline(),
        )

        val diagnosis = Diagnose.from(evidence)

        // Redacted: the URL carries the subscription username and password on
        // Xtream sources, and this line reaches logcat.
        android.util.Log.w(
            "TapperPlayer",
            "failed ${Redact.url(channel.streams.getOrNull(attempt)?.url.orEmpty())} " +
                "http=$status code=${error.errorCode} -> ${diagnosis.confidence}"
        )
        onDiagnosis(diagnosis)
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun release() {
        probeJob?.cancel()
        view?.player = null
        player?.release()
        player = null
    }
}
