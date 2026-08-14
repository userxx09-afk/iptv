package io.tapper.firetv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Buffer depth for playback, configurable from Settings.
 *
 * Medium is what shipped before this was configurable - tuned for fast
 * channel changes on a decent connection, since live streams can't seek
 * backwards and a deep buffer only delays the first frame. Small trims that
 * further for the fastest possible zap on a strong connection; Large and
 * Very Large trade zap speed and a little memory for more cushion against a
 * weak or congested one, for anyone whose stream keeps stalling or
 * rebuffering rather than failing outright.
 */
enum class BufferSize(
    val label: String,
    val description: String,
    /** min buffer, max buffer, buffer-for-playback, buffer-for-playback-
     *  after-rebuffer - all ms, the same order and units
     *  DefaultLoadControl.Builder#setBufferDurationsMs takes. */
    val minMs: Int,
    val maxMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
) {
    SMALL(
        "Small",
        "Fastest channel changes. Best on a strong, stable connection.",
        2_500, 8_000, 800, 1_500,
    ),
    MEDIUM(
        "Medium (default)",
        "A balance of quick zapping and some cushion against brief hiccups.",
        5_000, 15_000, 1_500, 3_000,
    ),
    LARGE(
        "Large",
        "More cushion for a slower or shared connection. Channel changes take a bit longer.",
        10_000, 30_000, 3_000, 5_000,
    ),
    VERY_LARGE(
        "Very Large",
        "Maximum cushion for a weak or congested connection. Channel changes are noticeably slower.",
        20_000, 60_000, 6_000, 8_000,
    ),
}

class PlayerSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tapper_player", Context.MODE_PRIVATE)

    var bufferSize: BufferSize
        get() = runCatching { BufferSize.valueOf(prefs.getString("buffer", null) ?: "") }
            .getOrDefault(BufferSize.MEDIUM)
        set(v) = prefs.edit().putString("buffer", v.name).apply()
}
