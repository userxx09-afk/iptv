package io.tapper.core.playback

/**
 * Why did this channel not play?
 *
 * There is no universal "connection limit" signal. Providers behave differently
 * and some give nothing usable at all. So this classifies by *confidence* and
 * only says "too many streams" when something actually supports that claim —
 * a confidently wrong message ("you've hit your limit" when the channel is
 * simply dead) is worse than an honest vague one.
 */

/** Raw evidence gathered when playback fails. Any field may be absent. */
data class FailureEvidence(
    /** HTTP status from the stream request, if the player surfaced one. */
    val httpStatus: Int? = null,
    /** Transport-level failure with no status: RST, EOF, timeout. */
    val socketError: Boolean = false,
    /** Milliseconds between play() and failure. */
    val elapsedMs: Long = 0,
    /** Frames were rendered before it died — the stream opened, then stopped. */
    val renderedFrames: Boolean = false,
    /** Re-auth performed at failure time. Null when the source has no API. */
    val account: AccountProbe? = null,
    /** From the sync service: another of the user's own devices playing this source. */
    val otherDeviceStreaming: OtherDevice? = null,
    /** Signature previously observed on this source at a confirmed limit hit. */
    val matchesLearnedLimitSignature: Boolean = false,
    /** False when the device itself has no working network. */
    val networkReachable: Boolean = true,
)

data class AccountProbe(
    val authOk: Boolean,
    val status: String?,            // Active / Expired / Banned
    val maxConnections: Int?,
    val activeConnections: Int?,
)

data class OtherDevice(val label: String, val startedMsAgo: Long)

enum class Confidence { CONFIRMED, LIKELY, POSSIBLE, UNKNOWN }

sealed interface Diagnosis {
    val confidence: Confidence
    /** Shown on the TV. Written to be true at the stated confidence, not louder. */
    fun message(): String

    data class ConnectionLimit(
        override val confidence: Confidence,
        val otherDevice: String?,
        val max: Int?,
    ) : Diagnosis {
        override fun message(): String = when {
            otherDevice != null && max != null ->
                "$otherDevice is watching on this account. Your plan allows $max at a time."
            otherDevice != null ->
                "$otherDevice is watching on this account. Stop it there to watch here."
            max != null ->
                "All $max of your account's streams are in use."
            confidence == Confidence.POSSIBLE ->
                "Couldn't start this channel. Your account may already be streaming elsewhere."
            else -> "Your account is already streaming the maximum number of channels."
        }
    }

    data class SubscriptionProblem(val status: String) : Diagnosis {
        override val confidence = Confidence.CONFIRMED
        override fun message() = when (status.lowercase()) {
            "expired" -> "This subscription has expired. Renew with your provider to continue."
            "banned", "disabled" -> "Your provider has disabled this account."
            else -> "Your provider rejected this account ($status)."
        }
    }

    data object NoNetwork : Diagnosis {
        override val confidence = Confidence.CONFIRMED
        override fun message() = "No network connection. Check your Wi-Fi or router."
    }

    data class UnsupportedFormat(val scheme: String) : Diagnosis {
        override val confidence = Confidence.CONFIRMED
        override fun message() =
            "This channel uses a format Fire TV can't play ($scheme)."
    }

    data object ChannelDown : Diagnosis {
        override val confidence = Confidence.LIKELY
        override fun message() = "This channel isn't responding. It may be temporarily offline."
    }

    data class Unclear(val hint: String?) : Diagnosis {
        override val confidence = Confidence.UNKNOWN
        override fun message() =
            "Couldn't play this channel." + (hint?.let { " $it" } ?: "")
    }
}

object Diagnose {

    fun from(e: FailureEvidence): Diagnosis {

        // 0. Our own connectivity first. Without this, a dead router reads as
        //    "this channel isn't responding" for every channel the user tries.
        if (!e.networkReachable) return Diagnosis.NoNetwork

        // 1. CONFIRMED — our own sync service saw another device start streaming
        //    this source. Provider-independent: works for plain M3U too, where
        //    no API exists to ask. This is the only tier that is genuinely certain.
        e.otherDeviceStreaming?.let { other ->
            return Diagnosis.ConnectionLimit(
                confidence = Confidence.CONFIRMED,
                otherDevice = other.label,
                max = e.account?.maxConnections,
            )
        }

        // 2. Account is dead. Check before the limit path — an expired account
        //    also fails to stream, and telling the user to turn off another TV
        //    when the real problem is an unpaid bill wastes their evening.
        e.account?.let { a ->
            if (a.authOk && a.status != null && !a.status.equals("Active", true)) {
                return Diagnosis.SubscriptionProblem(a.status)
            }
        }

        // 3. LIKELY — the panel's own counters say we're at the ceiling.
        //    Advisory only: many panels report active_cons as 0 permanently or
        //    lag by up to a minute, so this never reaches CONFIRMED alone.
        e.account?.let { a ->
            val max = a.maxConnections
            val active = a.activeConnections
            if (max != null && active != null && max > 0 && active >= max) {
                return Diagnosis.ConnectionLimit(Confidence.LIKELY, null, max)
            }
        }

        // 4. LIKELY — this exact failure shape was recorded on this source on a
        //    previously CONFIRMED limit hit. Learned per source, because the
        //    signature differs by panel software.
        if (e.matchesLearnedLimitSignature) {
            return Diagnosis.ConnectionLimit(Confidence.LIKELY, null, e.account?.maxConnections)
        }

        // 5. POSSIBLE — 403 while the account authenticates fine. Ambiguous by
        //    nature: panels reuse 403 for limit, geo-block, and token expiry.
        //
        //    But if the panel gave us usable counters showing spare capacity,
        //    a limit is ruled out — say nothing about streams, or we send the
        //    user to unplug a TV that isn't the problem.
        val hasSpareCapacity = e.account?.let { a ->
            val max = a.maxConnections; val active = a.activeConnections
            max != null && active != null && max > 0 && active < max
        } ?: false

        if (e.httpStatus == 403 && e.account?.authOk == true && !hasSpareCapacity) {
            return Diagnosis.ConnectionLimit(Confidence.POSSIBLE, null, e.account.maxConnections)
        }
        if (e.httpStatus == 403 && hasSpareCapacity) {
            return Diagnosis.Unclear("The provider refused this stream — it may be region-locked.")
        }
        if (e.httpStatus == 403) {
            return Diagnosis.Unclear("The provider refused this stream.")
        }

        // 6. Ordinary dead channel. On a free playlist this is the common case
        //    by a wide margin, so it must not be mislabelled as a limit.
        if (e.httpStatus in 500..599 || e.httpStatus == 404 || e.socketError) {
            return Diagnosis.ChannelDown
        }

        // 7. Opened, rendered, then died almost immediately. Some panels answer
        //    an over-limit request with HTTP 200 and a short pre-encoded slate
        //    reading "MAX CONNECTIONS REACHED" — successful playback of a
        //    failure notice. Nothing in the transport distinguishes it, so this
        //    stays a hint rather than a claim.
        if (e.renderedFrames && e.elapsedMs in 1..12_000) {
            return Diagnosis.Unclear(
                "The stream stopped right after starting — the provider may be showing a limit notice."
            )
        }

        return Diagnosis.Unclear(null)
    }
}

/**
 * Per-source learning. When a CONFIRMED limit hit happens (our sync service saw
 * another device), record the failure shape. Next time the same shape appears
 * without corroboration, it can be promoted to LIKELY.
 *
 * This is what makes the feature work for *whatever* provider the user brings,
 * rather than only the panels we anticipated.
 */
data class LimitSignature(
    val httpStatus: Int?,
    val socketError: Boolean,
    val diedWithinMs: Boolean,
) {
    companion object {
        fun of(e: FailureEvidence) = LimitSignature(
            httpStatus = e.httpStatus,
            socketError = e.socketError,
            diedWithinMs = e.renderedFrames && e.elapsedMs in 1..12_000,
        )
    }
}

interface LimitSignatureStore {
    suspend fun record(sourceId: String, sig: LimitSignature)
    suspend fun matches(sourceId: String, sig: LimitSignature): Boolean
}
