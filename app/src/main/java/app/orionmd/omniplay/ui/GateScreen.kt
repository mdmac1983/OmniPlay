package app.orionmd.omniplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.Activation
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The non-dismissible gate: "locked" (trial over, or a pending key that couldn't be confirmed) — enter a key or buy
 * one; "tamper" (a re-signed copy) — owner password only. Back does nothing here.
 */
@Composable
fun GateScreen(st: AppState, mode: String, onPass: () -> Unit) {
    BackHandler {}   // can't back out of the gate into the app
    val ctx = st.ctx
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var ownerMode by remember { mutableStateOf(mode == "tamper") }
    var msg by remember { mutableStateOf(Activation.takeNotice(st.store)) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        SplashArt(Modifier.fillMaxWidth(.75f).heightIn(max = 280.dp))
        Spacer(Modifier.height(16.dp))

        if (ownerMode) {
            if (mode == "tamper") {
                Text("This copy has been modified", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.warn, textAlign = TextAlign.Center)
                Muted("This build wasn't signed with the OrionMD key. Enter the owner password to continue. After 3 wrong attempts the app erases its own data.", Modifier.padding(vertical = 10.dp), 13.sp)
            } else {
                Text("Owner unlock", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Muted("Enter the owner password to unlock without a key.", Modifier.padding(vertical = 10.dp), 13.sp)
            }
            Field(owner, { owner = it }, "Owner password", Modifier.fillMaxWidth(), password = true)
            msg?.let { Text(it, color = C.dangerTx, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Btn("Unlock", {
                if (Guard.verifyOwner(owner)) { Guard.clearFails(ctx); Activation.unlockWithOwner(st.store); onPass() }
                else if (mode == "tamper") { val n = Guard.noteFail(ctx); if (n >= 3) Guard.wipe(ctx) else { msg = "Wrong password. ${3 - n} attempt${if (3 - n == 1) "" else "s"} left."; owner = "" } }
                else { msg = "Wrong password."; owner = "" }
            }, Modifier.fillMaxWidth().padding(top = 12.dp))
            if (mode != "tamper") Btn("Back to activation", { ownerMode = false; msg = null }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost)
            return@Column
        }

        Text("Your free trial has ended", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Muted("Thanks for trying Media Hub! Enter an activation key to keep using it, or buy one. Your library, playlists and downloads are kept and come back as soon as you activate.",
            Modifier.padding(vertical = 10.dp), 13.sp)
        Field(key, { key = it; msg = null }, "Activation key", Modifier.fillMaxWidth())
        msg?.let { Text(it, color = if (it.startsWith("Activated") || it.startsWith("No internet")) C.ac2 else C.dangerTx, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (busy) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = C.ac, strokeWidth = 2.dp); Muted("Checking your key…", Modifier.padding(start = 10.dp)) }
        else Btn("Verify", {
            busy = true
            scope.launch {
                val (ok, m) = withContext(Dispatchers.IO) { Activation.enterKey(ctx, st.store, key) }
                busy = false; msg = m
                if (ok) { st.say(m); onPass() }
            }
        }, Modifier.fillMaxWidth().padding(top = 12.dp))
        Btn("Purchase Key", { st.openUrl(Activation.BUY_URL) }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost, icon = Ic.key)
        Muted("Keys are sold at buymeacoffee.com/orionmd. Each key activates one app on one device.", Modifier.padding(top = 6.dp), 12.sp)
        Text("Owner unlock", color = C.mu, fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp).clickableNoRipple { ownerMode = true; msg = null })
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit) =
    clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
