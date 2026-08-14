package io.tapper.firetv.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where shared watch state lives.
 *
 * Two transports, because no single one covers both device types:
 *
 *  - [SafBackend] uses Android's Storage Access Framework. The user picks a
 *    folder with the system picker, and that folder can be backed by Google
 *    Drive, OneDrive, Dropbox, local storage or a file manager exposing a
 *    network share. No OAuth, no API keys, no cloud project - the provider's
 *    own app does the authentication. Ideal on phones and tablets.
 *
 *  - [WebDavBackend] talks to any WebDAV folder: Nextcloud, ownCloud, Synology,
 *    most NAS boxes. Needed because Fire TV frequently has no Drive or OneDrive
 *    document provider installed, which leaves the SAF picker with nothing
 *    useful to offer.
 *
 * Both write one file per device, never a shared one. Concurrent writes from
 * two devices therefore cannot clobber each other - the merge happens on read.
 */
interface SyncBackend {
    val label: String
    /** File names in the folder starting with [prefix]. */
    fun list(prefix: String): List<String>
    fun read(name: String): String?
    fun write(name: String, content: String)
}

class SafBackend(private val context: Context, private val treeUri: Uri) : SyncBackend {

    override val label get() = "Folder: " + (treeUri.lastPathSegment ?: treeUri.toString())

    private fun childrenUri() = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )

    private fun findChild(name: String): Pair<String, Uri>? {
        context.contentResolver.query(
            childrenUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    val id = c.getString(0)
                    return id to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        return null
    }

    override fun list(prefix: String): List<String> {
        val out = ArrayList<String>()
        context.contentResolver.query(
            childrenUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { if (it.startsWith(prefix)) out.add(it) } }
        return out
    }

    override fun read(name: String): String? {
        val (_, uri) = findChild(name) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    override fun write(name: String, content: String) {
        val existing = findChild(name)?.second
        val target = existing ?: DocumentsContract.createDocument(
            context.contentResolver, childrenUri().let {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
            },
            "application/json", name
        ) ?: error("Couldn't create $name in the chosen folder.")

        // "wt" truncates. Plain "w" leaves trailing bytes from a longer previous
        // version behind, which produces JSON that parses as garbage.
        context.contentResolver.openOutputStream(target, "wt")?.use {
            it.write(content.toByteArray())
        } ?: error("Couldn't write $name.")
    }
}

class WebDavBackend(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
) : SyncBackend {

    override val label get() = "WebDAV: " + runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)

    private val root = baseUrl.trim().trimEnd('/')

    private companion object { const val INDEX = "tapper-index.json" }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TapperIPTV/0.7")
            if (!username.isNullOrBlank()) {
                val token = Base64.encodeToString(
                    ("$username:" + password.orEmpty()).toByteArray(), Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $token")
            }
        }

    /**
     * Device discovery via a shared index rather than PROPFIND.
     *
     * HttpURLConnection rejects any method outside its built-in list, and
     * PROPFIND is not on it - setRequestMethod throws ProtocolException before
     * a request is ever made. Rather than reflect into platform internals, each
     * device registers its own file name in a small index.
     *
     * The index is the one file several devices may write, so a simultaneous
     * update can drop a registration. That is harmless and self-healing: the
     * affected device re-adds itself on its next sync, and its shard file was
     * never at risk because nothing else writes it.
     */
    override fun list(prefix: String): List<String> {
        val body = read(INDEX) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { n -> n.startsWith(prefix) } }
        }.getOrDefault(emptyList())
    }

    private fun register(name: String) {
        val known = runCatching {
            val arr = org.json.JSONArray(read(INDEX) ?: "[]")
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        if (name in known) return
        val arr = org.json.JSONArray()
        (known + name).distinct().forEach { arr.put(it) }
        runCatching { put(INDEX, arr.toString()) }
    }

    override fun read(name: String): String? {
        val conn = open("$root/$name", "GET")
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    override fun write(name: String, content: String) {
        put(name, content)
        // Registered after a successful write, so a failed upload never leaves
        // a name in the index pointing at nothing.
        if (name != INDEX) register(name)
    }

    private fun put(name: String, content: String) {
        val conn = open("$root/$name", "PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(content.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                error("Server returned HTTP ${conn.responseCode} writing $name.")
            }
        } finally {
            conn.disconnect()
        }
    }
}
