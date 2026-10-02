package io.tapper.core.playback

/**
 * iOS-only wrapper around Diagnose.from, built the same way XtreamBridge
 * wraps XtreamClient: Swift gets plain primitives in and a plain message
 * string out, rather than constructing FailureEvidence/AccountProbe (full
 * data-class constructors - default parameter values don't survive the
 * Kotlin/Native Objective-C export, so every field would need to be spelled
 * out from Swift anyway) or pattern-matching the Diagnosis sealed interface
 * itself from Swift - both are much less proven parts of this interop
 * boundary than a flat function call returning a String is. Matches the
 * same caution XtreamBridge.kt already documents for suspend functions.
 *
 * Account/other-device probing (AccountProbe, OtherDevice,
 * matchesLearnedLimitSignature) isn't wired up on iOS yet - there's no sync
 * service and no re-auth-on-failure probe here - so those FailureEvidence
 * fields are always absent below. Diagnose.from already treats every
 * evidence field as optional, so this still returns the best answer the
 * remaining, real evidence supports; it just can't reach the
 * account-derived CONFIRMED/LIKELY tiers Fire TV sometimes can.
 */
object PlaybackDiagnosisBridge {
    fun diagnose(
        httpStatus: Int,
        hasHttpStatus: Boolean,
        socketError: Boolean,
        elapsedMs: Long,
        renderedFrames: Boolean,
        networkReachable: Boolean,
    ): String {
        val evidence = FailureEvidence(
            httpStatus = if (hasHttpStatus) httpStatus else null,
            socketError = socketError,
            elapsedMs = elapsedMs,
            renderedFrames = renderedFrames,
            account = null,
            otherDeviceStreaming = null,
            matchesLearnedLimitSignature = false,
            networkReachable = networkReachable,
        )
        return Diagnose.from(evidence).message()
    }
}
