package io.tapper.firetv.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copy one TV's whole setup to the others through the sync folder already
 * set up for watch history (Google Drive, OneDrive, WebDAV/NAS or a folder):
 * sources, My List and pins, guide overrides, extra guides, My Teams, the
 * starting points and the player settings. A new Fire TV is then ready in
 * one step instead of being set up by hand.
 *
 * Logins are included only when a passphrase is given, and then encrypted
 * with it (PBKDF2 + AES-GCM), so the file in the cloud never holds a
 * password in the clear. The other TV needs the same passphrase.
 */
class SetupSync(
    private val context: Context,
    private val vault: CredentialVault,
    private val sources: SourceStore,
    private val sync: WatchSync,
) {

    companion object {
        const val FILE = "tapper-setup.json"
        /** Settings files copied as they are. Device identity, recordings,
         *  reminders, caches and the updater stay per TV. */
        private val PREFS = listOf(
            "tapper_sources", "tapper_favorites", "tapper_epg_overrides", "tapper_supplemental_epg",
            "tapper_teams", "tapper_nav_home", "tapper_player", "tapper_channel_edits", "tapper_parental", "tapper_profiles",
        )
        /** Per-profile My List files, named after the profile. */
        private fun portable(name: String) = name in PREFS || name.startsWith("tapper_favorites_")
        private const val ITERATIONS = 120_000
    }

    /** The setup as text; logins encrypted with [passphrase] when it isn't blank. */
    fun export(passphrase: String): String {
        val root = JSONObject().put("version", 1).put("exportedAt", System.currentTimeMillis())
        val prefs = JSONObject()
        val names = PREFS + ProfileStore(context).all().map { ProfileStore(context).favoritesPrefs(it) }
        for (name in names.distinct()) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val o = JSONObject()
            for ((k, v) in all) {
                o.put(k, when (v) {
                    is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                    is Boolean -> JSONObject().put("bool", v)
                    is Int -> JSONObject().put("int", v)
                    is Long -> JSONObject().put("long", v)
                    is Float -> JSONObject().put("float", v.toDouble())
                    else -> JSONObject().put("str", v?.toString())
                })
            }
            prefs.put(name, o)
        }
        root.put("prefs", prefs)
        if (passphrase.isNotBlank()) {
            val logins = JSONObject()
            for (s in sources.all()) {
                val (u, p) = vault.get(s.id) ?: continue
                logins.put(s.id, JSONObject().put("u", u).put("p", p))
            }
            root.put("logins", encrypt(logins.toString(), passphrase))
        }
        return root.toString()
    }

    /**
     * Applies a setup written by [export]. Returns a one-line summary; throws
     * when the file isn't a Tapper setup or the passphrase is wrong.
     */
    /** Writes this TV's setup to the sync folder. */
    fun upload(passphrase: String): String {
        val b = sync.backendOrNull() ?: error("Set up a sync folder first (Settings > Shared watch history).")
        b.write(FILE, export(passphrase))
        return "Saved this TV's setup to ${b.label}."
    }

    /** Reads the setup another TV saved and applies it. */
    fun download(passphrase: String): String {
        val b = sync.backendOrNull() ?: error("Set up a sync folder first (Settings > Shared watch history).")
        val text = b.read(FILE) ?: error("No saved setup in ${b.label} yet. Save one from another TV first.")
        return import(text, passphrase)
    }

    fun import(text: String, passphrase: String): String {
        val root = JSONObject(text)
        require(root.optInt("version") == 1) { "That isn't a Tapper setup file." }
        val prefs = root.getJSONObject("prefs")
        var count = 0
        for (name in prefs.keys()) {
            if (!portable(name)) continue
            val o = prefs.getJSONObject(name)
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                when {
                    v.has("set") -> edit.putStringSet(k, v.getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    v.has("bool") -> edit.putBoolean(k, v.getBoolean("bool"))
                    v.has("int") -> edit.putInt(k, v.getInt("int"))
                    v.has("long") -> edit.putLong(k, v.getLong("long"))
                    v.has("float") -> edit.putFloat(k, v.getDouble("float").toFloat())
                    v.has("str") && !v.isNull("str") -> edit.putString(k, v.getString("str"))
                }
            }
            edit.commit()
            count++
        }
        var logins = 0
        root.optJSONObject("logins")?.let { box ->
            require(passphrase.isNotBlank()) { "This setup includes logins: enter its passphrase." }
            val clear = runCatching { decrypt(box, passphrase) }.getOrElse { throw IllegalArgumentException("Wrong passphrase.") }
            val o = JSONObject(clear)
            for (id in o.keys()) {
                val l = o.getJSONObject(id)
                vault.put(id, l.getString("u"), l.getString("p"))
                logins++
            }
        }
        return "Loaded $count settings groups" + if (logins > 0) " and $logins logins." else "."
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(text: String, passphrase: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        val data = c.doFinal(text.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(data)).put("kdf", "pbkdf2-sha256-$ITERATIONS")
    }

    private fun decrypt(box: JSONObject, passphrase: String): String {
        fun b(k: String) = Base64.decode(box.getString(k), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(passphrase, b("salt")), GCMParameterSpec(128, b("iv")))
        return String(c.doFinal(b("data")), Charsets.UTF_8)
    }
}
