package io.tapper.core.auth

/**
 * Xtream credentials ride in every stream URL:
 *   http://host/live/USERNAME/PASSWORD/12345.ts
 * That string reaches logcat, crash reports, and error dialogs. Applied at the
 * sink so one forgotten call site can't leak a paid subscription.
 */
object Redact {
    private const val MASK = "***"

    fun url(raw: String): String {
        var s = Regex("""/(live|movie|series)/([^/]+)/([^/]+)/""")
            .replace(raw) { m -> "/${m.groupValues[1]}/$MASK/$MASK/" }
        s = Regex("""([?&](?:username|password|pass|user))=([^&]*)""", RegexOption.IGNORE_CASE)
            .replace(s) { m -> "${m.groupValues[1]}=$MASK" }
        return s
    }
}
