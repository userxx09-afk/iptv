package io.tapper.firetv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A configured content source.
 *
 * Credentials live in [CredentialVault], never here — this record is written to
 * ordinary preferences and would otherwise put a paid subscription in plaintext.
 */
data class TvSource(
    val id: String,
    val name: String,
    val kind: Kind,
    /** M3U playlist URL, or Xtream host. */
    val location: String,
    /** Overrides whatever the playlist header declares. Often necessary: the
     *  default playlist names two guides and the first one 404s. */
    val epgUrlOverride: String? = null,
    val builtIn: Boolean = false,
    /** A user-granted SAF tree URI. Channel logos are matched against this
     *  folder's contents by name before falling back to the playlist's own
     *  logoUrl - see [io.tapper.firetv.data.LogoFolderResolver]. */
    val logoFolderUri: String? = null,
) {
    /** FREE is the Free TV lineup GarysTV keeps (see GarysTvFeed);
     *  [location] is unused for it. */
    enum class Kind { M3U, XTREAM, FREE }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name)
        .put("location", location).put("epg", epgUrlOverride ?: JSONObject.NULL)
        .put("builtIn", builtIn).put("logoFolder", logoFolderUri ?: JSONObject.NULL)

    companion object {
        val BUILTIN = TvSource(
            id = "iptv-org",
            name = "iptv-org (free)",
            kind = Kind.M3U,
            location = "https://iptv-org.github.io/iptv/index.m3u",
            builtIn = true,
        )

        /** The GarysTV Free TV lineup: about 140 US channels streamed openly by
         *  their owners or licensed free services, with guide listings. */
        val FREE_TV = TvSource(
            id = "free-tv",
            name = "Free TV",
            kind = Kind.FREE,
            location = "github.com/userxx09-afk/Garystv",
            builtIn = true,
        )

        val BUILT_INS = listOf(BUILTIN, FREE_TV)

        fun fromJson(o: JSONObject) = TvSource(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = Kind.valueOf(o.optString("kind", "M3U")),
            location = o.getString("location"),
            epgUrlOverride = o.optString("epg").takeIf { it.isNotBlank() && it != "null" },
            builtIn = o.optBoolean("builtIn", false),
            logoFolderUri = o.optString("logoFolder").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

class SourceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tapper_sources", Context.MODE_PRIVATE)

    fun all(): List<TvSource> {
        val raw = prefs.getString("sources", null) ?: return TvSource.BUILT_INS
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return TvSource.BUILT_INS
        val list = ArrayList<TvSource>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { runCatching { TvSource.fromJson(it) }.getOrNull()?.let(list::add) }
        }
        // The built-in source is never removable — deleting it would leave a new
        // user in an empty app with no obvious way back.
        if (list.none { it.id == TvSource.BUILTIN.id }) list.add(0, TvSource.BUILTIN)
        // Free TV arrived later than the rest: added for everyone, right
        // after the iptv-org one.
        if (list.none { it.id == TvSource.FREE_TV.id }) {
            list.add(list.indexOfFirst { it.id == TvSource.BUILTIN.id } + 1, TvSource.FREE_TV)
        }
        return list
    }

    fun save(sources: List<TvSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    fun add(source: TvSource) = save(all().filterNot { it.id == source.id } + source)

    fun remove(id: String) = save(all().filterNot { it.id == id && !it.builtIn })

    /** Replaces one source, used when editing its guide URL. */
    fun update(source: TvSource) = save(all().map { if (it.id == source.id) source else it })

    var activeId: String
        get() = prefs.getString("active", TvSource.BUILTIN.id) ?: TvSource.BUILTIN.id
        set(v) = prefs.edit().putString("active", v).apply()

    fun active(): TvSource = all().firstOrNull { it.id == activeId } ?: TvSource.BUILTIN

    /**
     * Sources whose live channels are shown alongside the active one's, in
     * the same lineup and guide (Free TV next to a paid plan, say). Free TV
     * is on by default; never set means that default still applies.
     */
    var alongsideIds: Set<String>
        get() = prefs.getStringSet("alongside", null)?.toSet() ?: setOf(TvSource.FREE_TV.id)
        set(v) = prefs.edit().putStringSet("alongside", v.toSet()).apply()

    fun setAlongside(id: String, on: Boolean) {
        alongsideIds = if (on) alongsideIds + id else alongsideIds - id
    }

    /** The sources to show alongside [primary]: never itself, and only ones
     *  that still exist. */
    fun alongside(primary: TvSource): List<TvSource> {
        val ids = alongsideIds
        return all().filter { it.id in ids && it.id != primary.id }
    }

    /** Startup behaviour: whether to auto-play [lastChannelId] on launch. */
    var resumeLastChannel: Boolean
        get() = prefs.getBoolean("resume_last", false)
        set(v) = prefs.edit().putBoolean("resume_last", v).apply()

    /** The last channel actually watched (not merely focused while browsing)
     *  - see MainActivity's onChannelChanged. Feeds both resumeLastChannel
     *  above and the channel list's scroll-back-to-it-on-return behaviour in
     *  BrowseScreen. Deliberately the only piece of "where was I" state left:
     *  see NavHomeStore for why the country/category column no longer has an
     *  equivalent. */
    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        set(v) = prefs.edit().putString("last_channel", v).apply()
}

/**
 * Credentials go to the Android keystore, not to preferences or the database.
 * An Xtream username and password are embedded in every stream URL, so a
 * plaintext copy on disk is a resellable subscription for anyone with adb.
 */
class CredentialVault(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tapper_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(sourceId: String, username: String, password: String) {
        prefs.edit().putString("$sourceId.u", username).putString("$sourceId.p", password).apply()
    }

    fun get(sourceId: String): Pair<String, String>? {
        val u = prefs.getString("$sourceId.u", null) ?: return null
        val p = prefs.getString("$sourceId.p", null) ?: return null
        return u to p
    }

    fun delete(sourceId: String) {
        prefs.edit().remove("$sourceId.u").remove("$sourceId.p").apply()
    }
}
