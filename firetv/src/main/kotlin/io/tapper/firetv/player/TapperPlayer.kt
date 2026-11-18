package io.tapper.firetv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import io.tapper.core.auth.Redact
import io.tapper.core.catchup.CatchupUrl
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
        // Keep the last minute played, so Rewind on a live channel has
        // somewhere to go even without a provider archive. Held in memory,
        // which is why it is a minute and not more on a 1.5GB stick.
        .setBackBuffer(BACK_BUFFER_MS, true)
        .build()

    /** When false this player stays silent - a multiview tile without the
     *  sound. A commercial mute still applies on top. */
    private var audible = true

    fun setAudible(on: Boolean) {
        audible = on
        player?.volume = if (on && muteRemainingMs() == 0L) 1f else 0f
    }

    // Pausing live TV. A paused live stream keeps its place for as long as
    // the buffer lasts; after that, a channel with a provider archive picks
    // up from the archive at the moment it was paused (true timeshift), and
    // one without goes back to live.
    private var pausedAtUtc = 0L
    /** Set while playing from the archive after a pause or rewind: where in
     *  real time the archive started, and the live channel to go back to. */
    private var timeshiftStartUtc = 0L
    private var timeshiftOf: Channel? = null

    /** Pauses or resumes a live channel; returns true when now paused. */
    fun toggleLivePause(): Boolean {
        val p = player ?: return false
        if (p.playWhenReady) {
            p.playWhenReady = false
            pausedAtUtc = System.currentTimeMillis()
            return true
        }
        val ch = timeshiftOf ?: current
        val pausedFor = System.currentTimeMillis() - pausedAtUtc
        if (timeshiftOf == null && ch != null && pausedAtUtc > 0 && pausedFor > PAUSE_KEEPS_PLACE_MS &&
            CatchupUrl.canPlay(ch, pausedAtUtc, System.currentTimeMillis())
        ) {
            playFromArchive(ch, pausedAtUtc)
        } else {
            p.playWhenReady = true
        }
        pausedAtUtc = 0L
        return false
    }

    /**
     * Rewind on a live channel: back [ms] within what the player still
     * holds, or - on a channel with an archive - from the archive.
     */
    fun rewindLive(ms: Long) {
        val p = player ?: return
        val ch = timeshiftOf ?: current ?: return
        if (timeshiftOf != null || durationMs() > 0) { seekBy(-ms); return }
        if (ch.catchup != null) {
            playFromArchive(ch, System.currentTimeMillis() - ms - (p.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: 0L))
            return
        }
        seekBy(-ms)
    }

    /** How far behind live a paused or archive-played channel is, or null. */
    fun timeshiftBehindMs(): Long? {
        if (timeshiftOf != null) {
            return (System.currentTimeMillis() - (timeshiftStartUtc + positionMs())).coerceAtLeast(0L)
        }
        if (pausedAtUtc > 0) return System.currentTimeMillis() - pausedAtUtc
        return null
    }

    private fun playFromArchive(ch: Channel, fromUtc: Long) {
        val now = System.currentTimeMillis()
        val url = CatchupUrl.url(ch, fromUtc, now + 4 * 60 * 60 * 1000L, now) ?: return
        timeshiftOf = ch
        timeshiftStartUtc = fromUtc
        val headers = ch.streams.firstOrNull()?.headers.orEmpty()
        player?.release(); player = null
        pendingResumeMs = null
        startStream(ch, StreamRef(url, 0, headers))
    }

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

    // Commercial-break mute. A fixed-length, viewer-triggered mute: sound
    // returns by itself when the time is up, or sooner on a second press, and
    // a channel change always ends it. The deadline lives here rather than in
    // the screen because the player is torn down and rebuilt by every
    // reconnect - the new one must come back up still muted.
    private var muteUntilMs = 0L          // SystemClock.elapsedRealtime() deadline; 0 = not muted
    private var muteJob: Job? = null

    // Ad-marker diagnostic: what the stream itself says about ad breaks, if
    // anything. Counted, never acted on - it exists so we can tell whether a
    // provider sends markers at all before building anything that relies on them.
    private val markerCounts = LinkedHashMap<String, Int>()
    private val seenPlaylistTags = HashSet<String>()
    private var otherMetadata = 0

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
        /** Rewind room kept in memory on a live channel. */
        private const val BACK_BUFFER_MS = 60_000
        /** A pause shorter than this resumes from the buffer; longer, from the archive. */
        private const val PAUSE_KEEPS_PLACE_MS = 20_000L
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

    private val settings get() = (context.applicationContext as? io.tapper.firetv.TapperApp)?.playerSettings

    /** Captions on or off, and the preferred audio/subtitle language, from
     *  Settings. CEA-608 captions inside a live stream show up as an
     *  undetermined-language text track, so those are allowed explicitly. */
    private fun applyTrackPrefs(exo: ExoPlayer) {
        val s = settings ?: return
        val lang = s.preferredLanguage.takeIf { it.isNotBlank() }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(lang)
            .setPreferredTextLanguage(lang)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.captions)
            .build()
    }

    /**
     * One line describing the stream: picture size and frame rate, video
     * codec, bitrate, audio, and how far behind live it is. Blank until the
     * first frame. Shown with the channel overlay.
     */
    fun streamInfo(): String {
        val p = player ?: return ""
        val parts = ArrayList<String>()
        p.videoFormat?.let { v ->
            if (v.height > 0) {
                parts += "${v.width}×${v.height}" +
                    (if (v.frameRate > 0f) " ${kotlin.math.round(v.frameRate).toInt()}fps" else "")
            }
            v.codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }?.let { parts += it }
            val bitrate = listOf(v.bitrate, v.averageBitrate, v.peakBitrate).firstOrNull { it > 0 }
            if (bitrate != null) parts += String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
        }
        p.audioFormat?.let { a ->
            val audio = listOfNotNull(
                a.sampleMimeType?.substringAfter('/')?.uppercase(),
                a.channelCount.takeIf { it > 0 }?.let { if (it == 6) "5.1" else "${it}ch" },
                a.language?.takeIf { it.isNotBlank() && it != "und" },
            ).joinToString(" ")
            if (audio.isNotEmpty()) parts += audio
        }
        (timeshiftBehindMs() ?: behindLiveMs())?.let { parts += behindText(it) }
        return parts.joinToString("  ·  ")
    }

    /** How far behind the live edge playback is, when that's known and
     *  noticeable (over 15 seconds); null otherwise. */
    fun behindLiveMs(): Long? {
        val p = player ?: return null
        if (!p.isCurrentMediaItemLive) return null
        val offset = p.currentLiveOffset
        return if (offset != C.TIME_UNSET && offset > 15_000L) offset else null
    }

    /** Back to the live edge, for a stream that has drifted behind or is
     *  playing from the archive after a pause. */
    fun jumpToLive() {
        val live = timeshiftOf
        pausedAtUtc = 0L
        if (live != null) {
            timeshiftOf = null
            player?.release(); player = null
            startStream(live, live.streams.getOrNull(attempt) ?: live.streams.first())
            return
        }
        player?.playWhenReady = true
        player?.seekToDefaultPosition()
    }

    private fun behindText(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "${s / 3600}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} behind live"
        else if (s >= 60) "${s / 60}:${(s % 60).toString().padStart(2, '0')} behind live"
        else "${s}s behind live"
    }

    fun captionsOn(): Boolean = settings?.captions == true

    /** Flips captions for this and every later stream; returns the new state. */
    fun toggleCaptions(): Boolean {
        val s = settings ?: return false
        s.captions = !s.captions
        player?.let { applyTrackPrefs(it) }
        return s.captions
    }

    /** True when the stream playing now has a caption/subtitle track at all. */
    fun hasCaptionTrack(): Boolean =
        player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT } == true

    /** Milliseconds of mute left; 0 when sound is on. */
    fun muteRemainingMs(): Long =
        if (muteUntilMs == 0L) 0L else (muteUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** Mutes for [ms], or - when already muted - brings the sound back. */
    fun toggleMute(ms: Long): Boolean {
        if (muteRemainingMs() > 0L) { clearMute(); return false }
        muteUntilMs = SystemClock.elapsedRealtime() + ms
        player?.volume = 0f
        muteJob?.cancel()
        muteJob = scope.launch {
            delay(ms)
            clearMute()
        }
        return true
    }

    private fun clearMute() {
        muteJob?.cancel(); muteJob = null
        muteUntilMs = 0L
        player?.volume = if (audible) 1f else 0f
    }

    /** One line for the screen: what ad markers this stream has shown. */
    fun markerSummary(): String {
        val parts = markerCounts.entries.map { "${it.key} x${it.value}" }
        return when {
            parts.isNotEmpty() -> "Ad markers seen: " + parts.joinToString(", ")
            otherMetadata > 0 -> "Ad markers: none seen (other timed data x$otherMetadata)"
            else -> "Ad markers: none seen"
        }
    }

    private fun noteMarker(label: String) {
        val first = !markerCounts.containsKey(label)
        markerCounts[label] = (markerCounts[label] ?: 0) + 1
        if (first) android.util.Log.i("TapperPlayer", "ad marker first seen: $label")
    }

    /** A subtitle file added for the current title (OpenSubtitles), kept across restarts of its stream. */
    private var extraSubtitle: Pair<String, java.io.File>? = null

    /**
     * Adds a downloaded .srt to what's playing and shows it: the stream is
     * reopened at the same position with the file attached.
     */
    fun addSubtitle(file: java.io.File, language: String) {
        val ch = current ?: return
        extraSubtitle = ch.id to file
        settings?.captions = true
        pendingResumeMs = positionMs()
        lastLanguage = language
        startStream(ch, ch.streams.first())
    }
    private var lastLanguage: String = "en"

    /** True once an on-demand title has played to its end (virtual channels move on). */
    fun hasEnded(): Boolean = player?.playbackState == Player.STATE_ENDED

    fun play(channel: Channel, resumeMs: Long? = null) {
        if (extraSubtitle?.first != channel.id) extraSubtitle = null
        current = channel
        timeshiftOf = null
        pausedAtUtc = 0L
        clearMute()
        markerCounts.clear(); seenPlaylistTags.clear(); otherMetadata = 0
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
                    // smb:// recordings on a network share, file:// ones on
                    // this Fire TV, and http(s) streams with the settings above.
                    .setDataSourceFactory(
                        RoutingDataSourceFactory(
                            context, http,
                            io.tapper.firetv.data.RecordingLocationStore(context.applicationContext),
                        )
                    )
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

        applyTrackPrefs(exo)
        // A rebuilt player starts at full volume; a mute in progress carries over.
        exo.volume = if (muteRemainingMs() > 0L || !audible) 0f else 1f
        val sub = extraSubtitle?.takeIf { it.first == channel.id }?.second
        exo.setMediaItem(
            if (sub == null) MediaItem.fromUri(stream.url)
            else MediaItem.Builder().setUri(stream.url).setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(sub))
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(lastLanguage)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            ).build()
        )
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

        // Timed metadata riding in the stream. SCTE-35 ad cues arrive here
        // (as SpliceInsertCommand, TimeSignalCommand and friends) when a
        // provider leaves them in; ID3 tags and the like are counted apart so
        // they don't pass for ad markers.
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val name = metadata.get(i).javaClass.simpleName
                if (name.contains("Splice") || name.contains("TimeSignal") || name.contains("Scte")) {
                    noteMarker("SCTE-35 $name")
                } else {
                    otherMetadata++
                }
            }
        }

        // HLS only: the playlist is refreshed every few seconds on a live
        // channel, and ad breaks show up in it as DATERANGE / CUE-OUT / SCTE35
        // tag lines. Reading the tags ExoPlayer has already parsed costs nothing.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val hls = player?.currentManifest as? HlsManifest ?: return
            for (tag in hls.mediaPlaylist.tags) {
                val label = when {
                    tag.startsWith("#EXT-X-DATERANGE") -> "HLS DATERANGE"
                    tag.startsWith("#EXT-X-CUE-OUT") -> "HLS CUE-OUT"
                    tag.startsWith("#EXT-OATCLS-SCTE35") || tag.startsWith("#EXT-X-SCTE35") -> "HLS SCTE35"
                    else -> continue
                }
                // The same tag stays in the playlist across many refreshes;
                // count each distinct line once.
                if (seenPlaylistTags.size < 500 && seenPlaylistTags.add(tag)) noteMarker(label)
            }
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
        muteJob?.cancel()
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
