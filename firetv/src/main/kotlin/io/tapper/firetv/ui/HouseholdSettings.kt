package io.tapper.firetv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.ChannelEditStore
import io.tapper.firetv.data.ParentalStore
import io.tapper.firetv.data.ProfileStore
import io.tapper.firetv.data.SetupSync
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings for the people using this TV: profiles, parental controls, the
 * channel list edits, and copying the whole setup to another TV.
 *
 * [onChanged] re-applies edits and parental filters to the channel list;
 * [onRestart] reloads the app after a profile switch or a setup import, since
 * both change files every screen reads.
 */
@Composable
fun HouseholdSettings(
    profiles: ProfileStore,
    edits: ChannelEditStore,
    parental: ParentalStore,
    setup: SetupSync,
    syncConfigured: Boolean,
    onSwitchProfile: (String) -> Unit,
    onChanged: () -> Unit,
    onRestart: () -> Unit,
) {
    var prompt by remember { mutableStateOf<PromptRequest?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var setupBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun changed() { rev++; onChanged() }

    /** Runs [action] only after the right PIN, when one is set. */
    fun withPin(title: String, action: () -> Unit) {
        if (!parental.hasPin) { action(); return }
        fun ask(message: String?) {
            prompt = PromptRequest(title, hint = "PIN", numeric = true, secret = true, message = message) { pin ->
                if (parental.check(pin)) action() else ask("Wrong PIN, try again.")
            }
        }
        ask(null)
    }

    // Read every pass, keyed on rev so a change shows at once.
    val profileList = remember(rev) { profiles.all() }
    val active = remember(rev) { profiles.active() }
    val locked = remember(rev) { parental.lockedCategories().sorted() }
    val hiddenCats = remember(rev) { edits.hiddenCategories().sorted() }
    val editSummary = remember(rev) { edits.summary() }

    Spacer(Modifier.height(28.dp))
    Text("PROFILES", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text("Watching as ${active.name}" + if (active.kids) " (kids)" else "", style = MaterialTheme.typography.bodyLarge, color = Ink)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profileList.forEach { p ->
            Chip(p.name + if (p.kids) " (kids)" else "", p.id == active.id) {
                if (p.id == active.id) return@Chip
                val go = { onSwitchProfile(p.id) }
                // Leaving a kids profile is what the PIN guards.
                if (active.kids) withPin("Enter the PIN to leave ${active.name}", go) else go()
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Add profile", false) {
            prompt = PromptRequest("New profile name", hint = "e.g. Dad") { name ->
                if (name.isNotBlank()) { profiles.add(name, kids = false); changed() }
            }
        }
        Chip("Add kids profile", false) {
            withPin("Enter the PIN to add a kids profile") {
                prompt = PromptRequest("Kids profile name", hint = "e.g. Kids") { name ->
                    if (name.isNotBlank()) { profiles.add(name, kids = true); changed() }
                }
            }
        }
        if (active.id != ProfileStore.MAIN.id && !active.kids) {
            Chip("Remove ${active.name}", false) {
                profiles.remove(active.id); onSwitchProfile(ProfileStore.MAIN.id)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Each profile keeps its own My List and pins. A kids profile always hides locked " +
            "and adult categories, and needs the PIN to leave.",
        style = MaterialTheme.typography.bodyMedium, color = Dim,
    )

    Spacer(Modifier.height(28.dp))
    Text("PARENTAL CONTROLS", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            !parental.hasPin -> "No PIN set."
            parental.unlocked -> "Unlocked until the app closes."
            else -> "Locked."
        } + if (locked.isNotEmpty()) "  ${locked.size} locked categories." else "",
        style = MaterialTheme.typography.bodyLarge, color = Ink,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (parental.hasPin) "Change PIN" else "Set PIN", false) {
            withPin("Enter the current PIN") {
                prompt = PromptRequest("New PIN (4 to 8 digits)", numeric = true, secret = true) { pin ->
                    if (pin.length in 4..8) { parental.setPin(pin); changed() }
                }
            }
        }
        if (parental.hasPin) {
            if (parental.unlocked) {
                Chip("Lock now", false) { parental.unlocked = false; changed() }
            } else {
                Chip("Unlock", false) { withPin("Enter the PIN to unlock") { parental.unlocked = true; changed() } }
            }
            Chip("Remove PIN", false) { withPin("Enter the PIN to remove it") { parental.setPin(null); changed() } }
        }
    }
    if (parental.hasPin) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Show adult categories", !parental.hideAdult) {
                withPin("Enter the PIN") { parental.hideAdult = false; changed() }
            }
            Chip("Hide adult categories", parental.hideAdult) { parental.hideAdult = true; changed() }
        }
        if (locked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Locked: tap one to unlock it for good.", style = MaterialTheme.typography.bodyMedium, color = Dim)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                locked.take(8).forEach { c ->
                    Chip(c, false) { withPin("Enter the PIN to unlock $c") { parental.setLocked(c, false); changed() } }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Lock a category from its long-press menu in the channel list. Locked categories " +
            "disappear from every list and search until the PIN is entered.",
        style = MaterialTheme.typography.bodyMedium, color = Dim,
    )

    Spacer(Modifier.height(28.dp))
    Text("CHANNEL LIST EDITS", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text(editSummary, style = MaterialTheme.typography.bodyLarge, color = Ink)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (edits.showHidden) "Showing hidden items" else "Show hidden items", edits.showHidden) {
            edits.showHidden = !edits.showHidden; changed()
        }
        Chip("Undo all edits", false) {
            withPin("Enter the PIN to undo all edits") { edits.clear(); changed() }
        }
    }
    if (hiddenCats.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Hidden categories: tap one to bring it back.", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hiddenCats.take(8).forEach { c -> Chip(c, false) { edits.setCategoryHidden(c, false); changed() } }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Long-press a channel to rename it, change its number or hide it. Long-press a " +
            "category to hide it. To unhide a channel, turn on Show hidden items and long-press it.",
        style = MaterialTheme.typography.bodyMedium, color = Dim,
    )

    Spacer(Modifier.height(28.dp))
    Text("COPY SETUP TO ANOTHER TV", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text(
        if (syncConfigured) setupStatus ?: "Uses the same sync folder as watch history."
        else "Set up Shared watch history below first; the setup travels through the same folder.",
        style = MaterialTheme.typography.bodyLarge, color = Ink,
    )
    if (syncConfigured) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun run(title: String, block: (String) -> String, restart: Boolean) {
                prompt = PromptRequest(
                    title, hint = "Leave empty to skip logins", secret = true,
                    message = "With a passphrase, service logins are copied too, encrypted with it.",
                ) { pass ->
                    setupBusy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { block(pass) } }
                        setupBusy = false
                        setupStatus = r.fold({ it }, { it.message ?: "That didn't work." })
                        if (r.isSuccess && restart) onRestart()
                    }
                }
            }
            Chip(if (setupBusy) "Working..." else "Save this TV's setup", false) {
                if (!setupBusy) run("Passphrase for logins", setup::upload, restart = false)
            }
            Chip("Load saved setup", false) {
                if (!setupBusy) withPin("Enter the PIN to replace this TV's setup") {
                    run("Passphrase the setup was saved with", setup::download, restart = true)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Copies services, guides, My List, My Teams, starting points, player settings, " +
                "channel edits, parental controls and profiles. Loading replaces this TV's own.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
    }

    prompt?.let { req -> TextPrompt(req, onDismiss = { if (prompt === req) prompt = null }) }
}
