package io.tapper.firetv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import io.tapper.core.auth.Redact
import io.tapper.core.model.Channel
import io.tapper.core.model.StreamRef
import io.tapper.core.playback.*
import io.tapper.core.playlist.M3uParser
import io.tapper.firetv.data.BufferSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // Self-healing for a stream that stops without raising an error: a live
    // feed that freezes, sits on "buffering" forever, or ends because the
    // provider dropped the connection. The remedy that used to work was the
    // viewer changing channel up and back down, i.e. a fresh connection - so
    // that is now done automatically.
    private var monitorJob: Job? = null
    private var stallRestarts = 0

    // Connection awareness. A Wi-Fi drop-out, a router restart or a switch
    // between Wi-Fi and Ethernet leaves the open stream connection dead; the
    // callback below notices the connection coming back and reopens the
    // stream straight away instead of waiting for the stall timer, and time
    // spent offline no longer counts against the reconnect allowance.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private var networkLost = false
    private var networkJob: Job? = null
    /** The failure screen is showing (set by explain, cleared on a new start). */
    private var failed = false
    private var offlineSinceMs = 0L

    companion object {
        /** Frozen / buffering this long after picture has started => reconnect. */
        private const val STALL_AFTER_PLAY_MS = 10_000L
        /** No picture at all this long after starting => reconnect. */
        private const val STALL_BEFORE_PLAY_MS = 20_000L
        /** Reconnects of the same feed before moving to an alternate/giving up. */
        private const val MAX_STALL_RESTARTS = 3
        /** Playing smoothly this long forgives earlier reconnects. */
        private const val HEALTHY_RESET_MS = 30_000L
        /** Offline this long while playing => say so instead of spinning. */
        private const val OFFLINE_NOTICE_MS = 20_000L
        /** Pause after the connection returns, so it has settled (and the
         *  provider has noticed the old connection closed) before reopening. */
        private const val RECONNECT_DELAY_MS = 1_200L
    }

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
        stallRestarts = 0
        pendingResumeMs = resumeMs
        networkJob?.cancel()
        registerNetworkCallback()

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
        failed = false
        offlineSinceMs = 0L
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
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http)
                    // More patience for a segment/manifest request that fails
                    // transiently before it is treated as a hard error.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            )
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
        startMonitor(channel)
    }

    private fun startMonitor(channel: Channel) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastPos = -1L
            var lastMove = SystemClock.elapsedRealtime()
            var movingSince = 0L
            while (isActive) {
                delay(1_000)
                val p = player ?: continue
                val now = SystemClock.elapsedRealtime()
                if (!p.playWhenReady) {      // paused on purpose
                    lastMove = now; movingSince = 0L; lastPos = p.currentPosition
                    continue
                }
                val pos = p.currentPosition
                val state = p.playbackState
                val live = durationMs() == 0L
                val moving = state == Player.STATE_READY && pos != lastPos
                lastPos = pos
                if (moving) {
                    lastMove = now
                    offlineSinceMs = 0L
                    if (movingSince == 0L) movingSince = now
                    if (now - movingSince > HEALTHY_RESET_MS) stallRestarts = 0
                    continue
                }
                movingSince = 0L
                // The failure screen is already up; the network callback
                // (or the viewer) starts the next attempt.
                if (failed) { lastMove = now; continue }
                // No connection: nothing a reconnect could fix, and the
                // time must not use up the allowance. After a while, say so.
                if (!isOnline()) {
                    lastMove = now
                    if (offlineSinceMs == 0L) offlineSinceMs = now
                    if (now - offlineSinceMs > OFFLINE_NOTICE_MS) {
                        explain(channel, null)
                    }
                    continue
                }
                offlineSinceMs = 0L
                // A live feed that reports "ended" has been cut off.
                val dead = state == Player.STATE_ENDED && live
                if (state == Player.STATE_ENDED && !live) continue   // finished normally
                val limit = if (renderedFrames) STALL_AFTER_PLAY_MS else STALL_BEFORE_PLAY_MS
                if (dead || now - lastMove > limit) {
                    recover(channel)
                    return@launch      // recover() starts a fresh monitor if it restarts
                }
            }
        }
    }

    /** Stalled or dropped mid-stream: reconnect the same feed a few times,
     *  then try an alternate, then explain. Always runs on the main loop and
     *  tears the old player down first, exactly as a manual channel change. */
    private fun recover(channel: Channel) {
        val stream = channel.streams.getOrNull(attempt) ?: return
        if (stallRestarts < MAX_STALL_RESTARTS) {
            stallRestarts++
            android.util.Log.w("TapperPlayer", "stalled - reconnecting ($stallRestarts/$MAX_STALL_RESTARTS)")
            if (durationMs() > 0) pendingResumeMs = positionMs()   // on-demand: keep our place
            player?.release(); player = null
            startStream(channel, stream)
            return
        }
        val next = channel.streams.getOrNull(attempt + 1)
        if (next != null && M3uParser.isPlayable(next.url)) {
            attempt++; stallRestarts = 0
            player?.release(); player = null
            startStream(channel, next)
            return
        }
        explain(channel, null)
    }

    /** Watches the default network. Registered once per playback session and
     *  removed in [release]. Callbacks arrive on a connectivity thread, so
     *  the work is handed to [scope]. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val previous = lastNetwork
                    lastNetwork = network
                    // The first report just says where things stand.
                    if (previous == null) { networkLost = false; return@launch }
                    val changed = previous != network || networkLost
                    networkLost = false
                    if (changed) onNetworkBack()
                }
            }

            override fun onLost(network: Network) {
                scope.launch { if (network == lastNetwork) networkLost = true }
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        if (registered) networkCallback = callback
    }

    /** The connection came back, or moved to a different network. */
    private fun onNetworkBack() {
        val channel = current ?: return
        val p = player
        stallRestarts = 0
        // Still playing from what was buffered: leave it. If the old
        // connection is dead it runs dry shortly and the stall timer
        // reconnects it; restarting a healthy picture only adds a blip.
        if (p != null && !failed && p.isPlaying && p.playbackState == Player.STATE_READY) return

        networkJob?.cancel()
        networkJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            val stream = channel.streams.getOrNull(attempt) ?: return@launch
            android.util.Log.w("TapperPlayer", "network back - reconnecting")
            if (durationMs() > 0) pendingResumeMs = positionMs()   // on-demand: keep our place
            player?.release(); player = null
            startStream(channel, stream)
        }
    }

    private val listener = object : Player.Listener {

        override fun onRenderedFirstFrame() {
            renderedFrames = true
            onPlaying()
        }

        override fun onPlayerError(error: PlaybackException) {
            val channel = current ?: return

            // Fell off the back of the live window (a long stall): jump to the
            // live edge on the same connection - no failure, no restart.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.let { it.seekToDefaultPosition(); it.prepare() }
                return
            }

            // No connection: show it (it clears itself when the picture comes
            // back) and let the network callback reopen the stream, rather
            // than spending the reconnect allowance on a dead link.
            if (!isOnline()) {
                explain(channel, error)
                return
            }

            // Dropped after it had been working: reconnect the same feed first
            // (a mid-stream network blip), before touching alternates.
            if (renderedFrames && stallRestarts < MAX_STALL_RESTARTS) {
                scope.launch { recover(channel) }
                return
            }

            // Try the next alternate feed before surfacing anything. On a free
            // playlist this recovers a large share of failures invisibly.
            val next = channel.streams.getOrNull(attempt + 1)
            if (next != null && M3uParser.isPlayable(next.url)) {
                attempt++
                stallRestarts = 0
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

    /** [error] is null when the stream never raised one but stalled for good
     *  - treated as a connection problem for the diagnosis. */
    private fun explain(channel: Channel, error: PlaybackException?) {
        failed = true
        val status = (error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val socket = error == null || error.errorCode in setOf(
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
                "http=$status code=${error?.errorCode ?: -1} -> ${diagnosis.confidence}"
        )
        onDiagnosis(diagnosis)
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun release() {
        monitorJob?.cancel()
        networkJob?.cancel()
        networkCallback?.let { cb ->
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        lastNetwork = null
        networkLost = false
        probeJob?.cancel()
        view?.player = null
        player?.release()
        player = null
    }
}
