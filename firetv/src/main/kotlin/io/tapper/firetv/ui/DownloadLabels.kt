package io.tapper.firetv.ui

import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import io.tapper.firetv.recordings.Downloader

/** One line of status for a row on the Recordings screen, both skins. A
 *  download reads differently from a live recording: "Downloading 42%"
 *  rather than "Recording now", and a failure says why. */
internal fun recordingStatusText(r: Recording): String {
    if (!r.download) return when (r.status) {
        RecordingStatus.SCHEDULED -> "Scheduled"
        RecordingStatus.RECORDING -> "Recording now"
        RecordingStatus.DONE -> "Ready to play"
        RecordingStatus.FAILED -> "Failed"
    }
    return when (r.status) {
        RecordingStatus.SCHEDULED -> when {
            r.paused && r.bytesTotal > 0 && r.bytesDone > 0 ->
                "Paused at ${(r.bytesDone * 100 / r.bytesTotal).coerceIn(0, 100)}%  ·  " +
                    "${Downloader.size(r.bytesDone)} of ${Downloader.size(r.bytesTotal)}"
            r.paused && r.bytesDone > 0 -> "Paused  ·  ${Downloader.size(r.bytesDone)}"
            r.paused -> "Paused"
            r.bytesDone > 0 -> "Waiting to carry on downloading"
            else -> "Waiting to download"
        }
        RecordingStatus.RECORDING -> when {
            r.bytesTotal > 0 && r.bytesDone > 0 ->
                "Downloading ${(r.bytesDone * 100 / r.bytesTotal).coerceIn(0, 100)}%  ·  " +
                    "${Downloader.size(r.bytesDone)} of ${Downloader.size(r.bytesTotal)}"
            r.bytesDone > 0 -> "Downloading  ·  ${Downloader.size(r.bytesDone)}"
            else -> "Starting download"
        }
        RecordingStatus.DONE ->
            "Downloaded" + if (r.bytesDone > 0) "  ·  ${Downloader.size(r.bytesDone)}" else ""
        RecordingStatus.FAILED -> "Download failed"
    }
}

/** The longer explanation under a selected row. */
internal fun recordingDetailText(r: Recording): String = when {
    !r.download -> "Recording works best with plain stream sources (Xtream \"ts\" links and most M3U providers)."
    r.status == RecordingStatus.FAILED -> r.error ?: "The download didn't finish."
    r.status == RecordingStatus.DONE -> "Saved for offline viewing. It plays without the internet."
    r.paused -> "Paused. Resume carries on from where it stopped (a download to a network share starts that file again). Cancel deletes what's been saved."
    else -> "Downloads run one at a time and keep going while you watch something else. " +
        "If your plan allows only one connection, watching live TV may have to wait until it finishes."
}
