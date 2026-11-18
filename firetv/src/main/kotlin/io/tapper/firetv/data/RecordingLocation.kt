package io.tapper.firetv.data

import android.content.Context
import android.os.StatFs
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Where new recordings are written: this Fire TV's own storage, or a network
 * share (a Windows shared folder or a NAS, over SMB).
 *
 * There is no Tapper server in between. The Fire TV talks SMB to the share
 * itself (smbj, pure Java), the same way File Explorer on a PC would.
 */
class RecordingLocationStore(private val context: Context) {

    enum class Kind { LOCAL, NETWORK }

    data class Share(
        val host: String,
        val share: String,
        /** Folder inside the share, "" for the top. Forward or back slashes. */
        val folder: String = "",
        val username: String = "",
        val password: String = "",
        /** Windows domain or workgroup; "" is fine for almost every NAS. */
        val domain: String = "",
    ) {
        val isComplete get() = host.isNotBlank() && share.isNotBlank()

        /** \\host\share\folder, the way Windows writes it. */
        val label get() = "\\\\" + listOf(host, share, folder.replace('/', '\\').trim('\\'))
            .filter { it.isNotBlank() }.joinToString("\\")

        /** Path of [name] inside the share, in SMB's backslash form. */
        fun pathOf(name: String): String =
            listOf(folder.replace('/', '\\').trim('\\'), name).filter { it.isNotBlank() }.joinToString("\\")

        fun uriOf(name: String): String =
            "smb://" + host + "/" + share + "/" + pathOf(name).replace('\\', '/')
    }

    private val prefs = context.getSharedPreferences("tapper_recording_location", Context.MODE_PRIVATE)
    // The share password goes to the keystore-backed vault, like source
    // credentials, never these plain preferences.
    private val vault by lazy { CredentialVault(context) }

    var kind: Kind
        get() = runCatching { Kind.valueOf(prefs.getString("kind", Kind.LOCAL.name)!!) }.getOrDefault(Kind.LOCAL)
        set(v) { prefs.edit().putString("kind", v.name).apply() }

    fun share(): Share = Share(
        host = prefs.getString("host", "").orEmpty(),
        share = prefs.getString("share", "").orEmpty(),
        folder = prefs.getString("folder", "").orEmpty(),
        username = prefs.getString("user", "").orEmpty(),
        password = vault.get(VAULT_KEY)?.second.orEmpty(),
        domain = prefs.getString("domain", "").orEmpty(),
    )

    fun saveShare(s: Share) {
        prefs.edit()
            .putString("host", s.host.trim()).putString("share", s.share.trim().trim('\\', '/'))
            .putString("folder", s.folder.trim()).putString("user", s.username.trim())
            .putString("domain", s.domain.trim())
            .apply()
        if (s.password.isEmpty()) vault.delete(VAULT_KEY) else vault.put(VAULT_KEY, s.username.trim(), s.password)
    }

    /** The share new recordings should go to, or null for this Fire TV. */
    fun activeShare(): Share? = share().takeIf { kind == Kind.NETWORK && it.isComplete }

    fun localDir(): File = context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")

    /** Free space on this Fire TV's recording folder, in bytes. */
    fun localFreeBytes(): Long = runCatching {
        val dir = localDir().apply { mkdirs() }
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /** Credentials for playing back or deleting an smb:// recording. A
     *  recording on a different share than the saved one tries a guest login. */
    fun shareFor(uri: String): Share? {
        val p = SmbUri.parse(uri) ?: return null
        val saved = share()
        return if (saved.host.equals(p.host, true) && saved.share.equals(p.share, true)) saved
        else Share(host = p.host, share = p.share)
    }

    companion object {
        private const val VAULT_KEY = "recording-smb"

        /**
         * Accepts what people actually type or paste: `\\nas\Media\TV`,
         * `smb://nas/Media/TV`, or `nas/Media/TV`. Returns host, share, folder.
         */
        fun parseLocation(text: String): Triple<String, String, String>? {
            val parts = text.trim().removePrefix("smb://").removePrefix("SMB://")
                .replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return Triple(parts[0], parts[1], parts.drop(2).joinToString("/"))
        }
    }
}

/** smb://host/share/path/inside -> parts. */
data class SmbUri(val host: String, val share: String, val path: String) {
    companion object {
        fun parse(uri: String): SmbUri? {
            if (!uri.startsWith("smb://", ignoreCase = true)) return null
            val parts = android.net.Uri.decode(uri.substring(6)).split('/').filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            return SmbUri(parts[0], parts[1], parts.drop(2).joinToString("\\"))
        }
    }
}

/**
 * Small blocking SMB helpers. Every call opens its own connection: recordings
 * and playback are long-lived single streams, so pooling buys little and a
 * fresh connection survives the NAS having gone to sleep in between.
 * Call off the main thread.
 */
object SmbStorage {

    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build()
        )
    }

    /** An open share plus everything that has to be closed with it. */
    class Opened(val share: DiskShare, private val connection: Connection, private val session: Session) : Closeable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    fun open(s: RecordingLocationStore.Share): Opened {
        val connection = client.connect(s.host)
        try {
            val auth = if (s.username.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(s.username, s.password.toCharArray(), s.domain.ifBlank { null })
            val session = connection.authenticate(auth)
            val share = session.connectShare(s.share) as? DiskShare
                ?: throw java.io.IOException("\"${s.share}\" on ${s.host} isn't a shared folder")
            return Opened(share, connection, session)
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    /** Creates each folder along [path] (backslash form) that is missing. */
    private fun mkdirs(share: DiskShare, path: String) {
        var at = ""
        path.split('\\').filter { it.isNotBlank() }.forEach { part ->
            at = if (at.isEmpty()) part else "$at\\$part"
            if (!share.folderExists(at)) share.mkdir(at)
        }
    }

    /** A stream that writes a new file [name] in the share's folder. Closing
     *  the stream closes the file and the connection. */
    fun create(s: RecordingLocationStore.Share, name: String): OutputStream {
        val opened = open(s)
        try {
            mkdirs(opened.share, s.pathOf(""))
            val file = opened.share.openFile(
                s.pathOf(name),
                setOf(AccessMask.GENERIC_WRITE),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            val out = file.outputStream
            return object : java.io.FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                override fun close() {
                    runCatching { out.close() }
                    runCatching { file.close() }
                    opened.close()
                }
            }
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
    }

    fun delete(uri: String, s: RecordingLocationStore.Share) {
        val p = SmbUri.parse(uri) ?: return
        open(s).use { if (it.share.fileExists(p.path)) it.share.rm(p.path) }
    }

    /**
     * Settings > Recording > Test: signs in, makes the folder, writes and
     * removes a small file. Returns a line for the screen either way.
     */
    fun test(s: RecordingLocationStore.Share): Result<String> = runCatching {
        val name = "tapper-test-" + System.currentTimeMillis() + ".txt"
        create(s, name).use { it.write("Tapper can save recordings here.\n".toByteArray()) }
        open(s).use { it.share.rm(s.pathOf(name)) }
        "Connected. Recordings will be saved to ${s.label}."
    }.recoverCatching { t -> throw IllegalStateException(friendly(t), t) }

    /** smbj's errors are NT status names; turn the usual ones into English. */
    fun friendly(t: Throwable): String {
        val msg = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            "LOGON_FAILURE" in msg -> "The user name or password was turned down."
            "ACCESS_DENIED" in msg -> "Signed in, but that user can't write to the folder."
            "BAD_NETWORK_NAME" in msg -> "The server has no share with that name."
            "UnknownHost" in t.javaClass.simpleName || "Unable to resolve" in msg ->
                "Couldn't find that server. Try its IP address, like 192.168.1.20."
            "timed out" in msg.lowercase() || "ConnectException" in t.javaClass.simpleName ||
                "Connection refused" in msg -> "The server didn't answer. Check it's on and file sharing (SMB) is turned on."
            else -> "Couldn't use the share: " + (t.message ?: t.javaClass.simpleName)
        }
    }
}
