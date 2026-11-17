package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.model.Channel
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The household's own tidy-up of a provider's list: channels renamed,
 * renumbered or hidden, and whole categories hidden. Kept per source, by
 * channel id, so a playlist refresh keeps every change.
 */
class ChannelEditStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_channel_edits", Context.MODE_PRIVATE)

    private fun key(ch: Channel) = "${ch.sourceId}|${ch.id}"

    fun nameFor(ch: Channel): String? = prefs.getString("name:" + key(ch), null)
    fun numberFor(ch: Channel): Int? = prefs.getInt("num:" + key(ch), -1).takeIf { it >= 0 }
    fun isHidden(ch: Channel): Boolean = key(ch) in hiddenChannels()

    fun rename(ch: Channel, name: String?) {
        val e = prefs.edit()
        if (name.isNullOrBlank()) e.remove("name:" + key(ch)) else e.putString("name:" + key(ch), name.trim())
        e.apply()
    }

    fun renumber(ch: Channel, number: Int?) {
        val e = prefs.edit()
        if (number == null || number < 0) e.remove("num:" + key(ch)) else e.putInt("num:" + key(ch), number)
        e.apply()
    }

    fun hiddenChannels(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty()

    fun setHidden(ch: Channel, hidden: Boolean) {
        val now = hiddenChannels().toMutableSet()
        if (hidden) now += key(ch) else now -= key(ch)
        prefs.edit().putStringSet("hidden", now).apply()
    }

    /** Hidden categories, by name; a category hides for every kind and source. */
    fun hiddenCategories(): Set<String> = prefs.getStringSet("hiddenCats", emptySet()).orEmpty()

    fun setCategoryHidden(category: String, hidden: Boolean) {
        val now = hiddenCategories().toMutableSet()
        if (hidden) now += category else now -= category
        prefs.edit().putStringSet("hiddenCats", now).apply()
    }

    /** Lists hidden channels and categories again, so they can be unhidden. */
    var showHidden: Boolean
        get() = prefs.getBoolean("showHidden", false)
        set(v) { prefs.edit().putBoolean("showHidden", v).apply() }

    fun hasRenames(): Boolean = prefs.all.keys.any { it.startsWith("name:") || it.startsWith("num:") }

    fun summary(): String {
        val renamed = prefs.all.keys.count { it.startsWith("name:") }
        val renumbered = prefs.all.keys.count { it.startsWith("num:") }
        return "${hiddenChannels().size} channels hidden, ${hiddenCategories().size} categories hidden, " +
            "$renamed renamed, $renumbered renumbered."
    }

    fun clear() { prefs.edit().clear().apply() }
}

/**
 * Parental controls: a 4 to 8 digit PIN, categories that stay out of sight
 * until it is entered, and an optional filter for adult categories and
 * titles. Unlocking lasts until the app is closed or locked again.
 */
class ParentalStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_parental", Context.MODE_PRIVATE)

    /** In memory only, so a restart locks everything again. */
    var unlocked: Boolean = false

    val hasPin: Boolean get() = prefs.getString("pinHash", null) != null

    fun setPin(pin: String?) {
        if (pin.isNullOrBlank()) {
            prefs.edit().remove("pinHash").remove("salt").apply()
            unlocked = false
            return
        }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        prefs.edit().putString("salt", salt).putString("pinHash", hash(salt, pin)).apply()
    }

    fun check(pin: String): Boolean {
        val salt = prefs.getString("salt", null) ?: return false
        return prefs.getString("pinHash", null) == hash(salt, pin)
    }

    fun lockedCategories(): Set<String> = prefs.getStringSet("locked", emptySet()).orEmpty()

    fun setLocked(category: String, locked: Boolean) {
        val now = lockedCategories().toMutableSet()
        if (locked) now += category else now -= category
        prefs.edit().putStringSet("locked", now).apply()
    }

    /** Hides anything whose category or name looks adult (XXX, 18+, Adult...). */
    var hideAdult: Boolean
        get() = prefs.getBoolean("hideAdult", false)
        set(v) { prefs.edit().putBoolean("hideAdult", v).apply() }

    /** Whether anything is currently being kept out of sight. */
    val active: Boolean get() = hasPin && !unlocked && (hideAdult || lockedCategories().isNotEmpty())

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        val ADULT = Regex("""(?i)(\bxxx\b|\badult|\b18\s*\+|\bfor adults\b|\bporn|\berotic|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bred ?light\b)""")
    }
}

/**
 * Applies the household's edits and the parental filter to a catalogue:
 * hidden and locked items drop out of every list, renamed and renumbered
 * channels show their new name and number. Run after withResolvedLogos.
 */
fun PlaylistRepository.Catalogue.withEdits(
    edits: ChannelEditStore,
    parental: ParentalStore,
    /** A kids profile: locked and adult categories stay hidden even when unlocked. */
    kids: Boolean = false,
): PlaylistRepository.Catalogue {
    val hiddenCh = if (edits.showHidden) emptySet() else edits.hiddenChannels()
    val hiddenCats = if (edits.showHidden) emptySet() else edits.hiddenCategories()
    val enforced = kids || (parental.hasPin && !parental.unlocked)
    val locked = if (enforced) parental.lockedCategories() else emptySet()
    val adult = kids || (enforced && parental.hideAdult)
    val blockedCats = hiddenCats + locked
    val anyRenames = edits.hasRenames()
    if (hiddenCh.isEmpty() && blockedCats.isEmpty() && !adult && !anyRenames) return this

    fun catsOf(ch: Channel) = (listOfNotNull(ch.group) + ch.categories)
    fun keep(ch: Channel): Boolean {
        if ("${ch.sourceId}|${ch.id}" in hiddenCh) return false
        val cats = catsOf(ch)
        if (cats.any { it in blockedCats }) return false
        if (adult && (ParentalStore.ADULT.containsMatchIn(ch.name) || cats.any { ParentalStore.ADULT.containsMatchIn(it) })) return false
        return true
    }
    fun fix(ch: Channel): Channel {
        if (!anyRenames) return ch
        val n = edits.nameFor(ch)
        val num = edits.numberFor(ch)
        return if (n == null && num == null) ch else ch.copy(name = n ?: ch.name, number = num ?: ch.number)
    }
    fun groupBlocked(g: PlaylistRepository.Group) =
        g.label in blockedCats || (g.key != null && g.key in blockedCats) ||
            (adult && ParentalStore.ADULT.containsMatchIn(g.label))
    fun fixGroups(list: List<PlaylistRepository.Group>) = list
        .filterNot(::groupBlocked)
        .map { g -> g.copy(channels = g.channels.filter(::keep).map(::fix)) }
        .filter { it.channels.isNotEmpty() }

    val newSections = sections.mapValues { (_, s) ->
        s.copy(
            items = s.items.filter(::keep).map(::fix),
            byCountry = fixGroups(s.byCountry),
            byCategory = fixGroups(s.byCategory),
        )
    }
    return copy(channels = channels.filter(::keep).map(::fix), sections = newSections)
}

