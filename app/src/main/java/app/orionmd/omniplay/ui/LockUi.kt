package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.orionmd.omniplay.*

/** Number pad with PIN dots. [onDone] gets the PIN when 4–8 digits are entered and ✓ is tapped. */
@Composable
private fun PinPad(title: String, sub: String, error: String?, onDone: (String) -> Unit, extra: @Composable ColumnScope.() -> Unit = {}) {
    var pin by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Ic.lock, null, tint = C.ac, modifier = Modifier.size(36.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        if (sub.isNotEmpty()) Muted(sub, Modifier.padding(top = 4.dp), 13.sp)
        Row(Modifier.padding(vertical = 18.dp).height(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(maxOf(4, pin.length)) { i -> Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) C.ac else C.s3)) }
        }
        error?.let { Text(it, color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                row.forEach { k ->
                    val enabled = k != "✓" || pin.length >= 4
                    Box(Modifier.size(68.dp).clip(CircleShape).background(if (k == "✓" && enabled) C.ac else C.s2)
                        .clickable(enabled = enabled) {
                            when (k) { "⌫" -> pin = pin.dropLast(1); "✓" -> { val p = pin; pin = ""; onDone(p) }; else -> if (pin.length < 8) pin += k }
                        }, contentAlignment = Alignment.Center) {
                        Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = if (k == "✓" && enabled) androidx.compose.ui.graphics.Color.Black else if (enabled) C.tx else C.mu)
                    }
                }
            }
        }
        extra()
    }
}

/** The app lock: shown instead of everything until the PIN (or the phone's own screen lock) is given. */
@Composable
fun PinScreen(st: AppState) {
    var err by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler { st.host.leave() }
    Box(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        PinPad("Media Hub is locked", "Enter your PIN", err, { p ->
            if (Lock.check(st.store, p)) { Lock.locked = false; err = null }
            else err = Lock.waitSeconds().let { w -> if (w > 0) "Too many tries — wait $w s" else "Wrong PIN" }
        }) {
            Text("Forgot PIN? Use this phone's screen lock", color = C.mu, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp).clickable {
                st.host.confirmDevice("Unlock Media Hub") { ok -> if (ok) { Lock.locked = false; st.say("Unlocked — you can set a new PIN in Settings → Lock & privacy") } }
            })
        }
    }
}

/** Asks for the PIN, then runs [then]. */
@Composable
fun PinAskDialog(st: AppState, d: Dlg.PinAsk) {
    var err by remember { mutableStateOf<String?>(null) }
    Dialog({ st.dialog = null }) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.s1).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PinPad(d.title, "Enter your PIN", err, { p ->
                if (Lock.check(st.store, p)) { st.dialog = null; d.then() }
                else err = Lock.waitSeconds().let { w -> if (w > 0) "Too many tries — wait $w s" else "Wrong PIN" }
            }) {
                Text("Cancel", color = C.mu, modifier = Modifier.padding(top = 12.dp).clickable { st.dialog = null })
            }
        }
    }
}

/** New PIN (asked twice). Changing it asks for the current one first (done by the caller). */
@Composable
fun PinSetDialog(st: AppState, d: Dlg.PinSet) {
    var first by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Dialog({ st.dialog = null }) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.s1).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            key(first) {
                PinPad(if (first == null) "Choose a PIN" else "Enter it again", if (first == null) "4 to 8 digits" else "", err, { p ->
                    val f = first
                    if (f == null) { first = p; err = null }
                    else if (f == p) { Lock.setPin(st.store, p); st.dialog = null; st.prefGen++; st.say("PIN saved"); d.then() }
                    else { first = null; err = "The PINs didn't match — try again" }
                }) {
                    Text("Cancel", color = C.mu, modifier = Modifier.padding(top = 12.dp).clickable { st.dialog = null })
                }
            }
        }
    }
}

/** Runs [then] once there is a PIN (asking to create one first). */
fun AppState.withPin(then: () -> Unit) { if (Lock.hasPin(store)) then() else dialog = Dlg.PinSet(then) }

/** Lock or unlock a folder (setting a PIN first if needed). Unlocking asks for the PIN. */
fun AppState.toggleLock(key: String, name: String) {
    if (Lock.isLocked(store, key)) dialog = Dlg.PinAsk("Unlock \"$name\"") { Lock.setLocked(store, key, false); refreshLocks(); say("\"$name\" is no longer locked") }
    else withPin { Lock.setLocked(store, key, true); refreshLocks(); say("\"$name\" locked — it's hidden until you show locked folders with your PIN") }
}

/** Show (with the PIN) or hide again the locked folders. */
fun AppState.togglePrivate() {
    if (Lock.privateOpen) { Lock.privateOpen = false; refreshLocks(); say("Locked folders hidden") }
    else dialog = Dlg.PinAsk("Show locked folders") { Lock.privateOpen = true; refreshLocks(); say("Showing locked folders until Media Hub locks again") }
}

/** Lock icon for top bars: shows / hides locked folders (only when there are any). */
@Composable
fun RowScope.PrivateToggle(st: AppState) {
    Lock.gen
    if (Lock.folders(st.store).isNotEmpty()) IconBtn(Ic.lock, { st.togglePrivate() }, tint = if (Lock.privateOpen) C.ac else C.mu)
}

/** Settings → Lock & privacy. */
@Composable
fun LockSettings(st: AppState) {
    st.prefGen; Lock.gen
    val has = Lock.hasPin(st.store)
    if (!has) {
        Muted("Set a PIN to lock Media Hub when it opens, or to hide chosen folders (library sources, music and video folders, gallery folders) until you unlock them.", Modifier.padding(bottom = 8.dp), 12.sp)
        Btn("Set a PIN", { st.dialog = Dlg.PinSet {} }, icon = Ic.lock, small = true)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Lock Media Hub", fontWeight = FontWeight.SemiBold); Muted("Ask for the PIN when the app opens", size = 12.sp) }
        androidx.compose.material3.Switch(Lock.appLockOn(st.store), { on -> st.setPref("lockApp", if (on) "1" else "0") })
    }
    if (Lock.appLockOn(st.store)) Pick(st, "lockAfter", "Lock again after leaving for", listOf("0" to "Immediately", "60" to "1 minute", "300" to "5 minutes", "1800" to "30 minutes"), "60")
    SectionHeader("Locked folders")
    val locked = Lock.folders(st.store)
    if (locked.isEmpty()) Muted("None yet. Lock a folder below, from a library source in “Your library sources”, or from a gallery folder's menu.", size = 12.sp)
    else {
        Muted(if (Lock.privateOpen) "Shown right now (until the app locks again)." else "Hidden everywhere — Home, Music, Video, Gallery, search.", size = 12.sp)
        Btn(if (Lock.privateOpen) "Hide locked folders" else "Show locked folders", { st.togglePrivate() }, icon = Ic.lock, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(vertical = 6.dp))
        locked.sorted().forEach { k -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.lock, null, tint = C.mu, modifier = Modifier.size(18.dp))
            Text(st.lockName(k), Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
            Text("Unlock", color = C.ac, fontSize = 13.sp, modifier = Modifier.clickable { st.toggleLock(k, st.lockName(k)) }.padding(6.dp))
        } }
    }
    Btn("Lock a folder…", { st.dialog = Dlg.PickLockFolder }, icon = Ic.folder, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(top = 6.dp))
    SectionHeader("PIN")
    BtnRow {
        Btn("Change PIN", { st.dialog = Dlg.PinAsk("Current PIN") { st.dialog = Dlg.PinSet {} } }, small = true, kind = BtnKind.Ghost)
        Btn("Remove PIN", { st.dialog = Dlg.PinAsk("Remove the PIN") {
            Lock.clearPin(st.store); st.store.setPref("lockedFolders", "[]"); Lock.gen++; Lock.privateOpen = false; st.prefGen++; st.refreshLocks()
            st.say("PIN removed — nothing is locked now") } }, small = true, kind = BtnKind.Danger)
    }
    Muted("Forgot it? On the lock screen, tap “Forgot PIN” to unlock with this phone's own screen lock, then set a new one.", Modifier.padding(top = 6.dp), 11.sp)
}

/** Every folder that can be locked: library sources, music / video folders and gallery folders. */
@Composable
fun PickLockFolderDialog(st: AppState) {
    val opts = remember(st.allItems, st.gallery.tick, Lock.gen) {
        val l = ArrayList<Pair<String, String>>()
        st.sources.forEach { l += "src:${it.id}" to "Source · ${it.name}" }
        st.allItems.filter { it.c == 2 || it.c == 3 }.map { it.folder }.distinct().sorted().forEach { l += "dir:$it" to "Folder · $it" }
        (st.gallery.all ?: emptyList()).distinctBy { it.bucketId }.sortedBy { it.bucket.lowercase() }.forEach { l += "bucket:${it.bucketId}" to "Gallery · ${it.bucket}" }
        l
    }
    val locked = Lock.folders(st.store)
    Dialog({ st.dialog = null }) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.s1).padding(18.dp).heightIn(max = 620.dp)) {
            Text("Lock a folder", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Muted("Locked folders are hidden until you show them with your PIN.", Modifier.padding(bottom = 8.dp), 12.sp)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                items(opts.size) { i -> val (k, name) = opts[i]
                    Row(Modifier.fillMaxWidth().clickable { st.dialog = null; st.toggleLock(k, name.substringAfter(" · ")) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (k in locked) Ic.lock else Ic.folder, null, tint = if (k in locked) C.ac else C.mu, modifier = Modifier.size(20.dp))
                        Text(name, Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp, maxLines = 2)
                    }
                }
            }
            Btn("Close", { st.dialog = null }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost)
        }
    }
}
