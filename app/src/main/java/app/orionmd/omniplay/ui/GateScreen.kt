package app.orionmd.omniplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.orionmd.omniplay.Activation
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Guard

/** The start-up gate: enter an activation key (or the owner password), or — for a re-signed copy — the owner lock. */
@Composable
fun GateScreen(st: AppState, mode: String, onPass: () -> Unit) {
    val ctx = st.ctx
    var key by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var ownerMode by remember { mutableStateOf(mode == "tamper") }
    var msg by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            SplashArt(Modifier.fillMaxWidth(.8f).heightIn(max = 300.dp))
            Spacer(Modifier.height(8.dp))
            Text("OmniPlay", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Muted("Hybrid music & video player · OrionMD", size = 13.sp)
            Spacer(Modifier.height(24.dp))

            if (mode == "tamper" || ownerMode) {
                if (mode == "tamper") {
                    Text("This copy has been modified", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.warn, textAlign = TextAlign.Center)
                    Muted("This build wasn't signed with the OrionMD key. Enter the owner password to continue. After 3 wrong attempts the app erases its own data.", Modifier.padding(vertical = 10.dp), 13.sp)
                    val left = 3 - Guard.fails(ctx)
                    if (left in 1..2) Text("$left attempt${if (left == 1) "" else "s"} left", color = C.danger, fontSize = 13.sp)
                } else {
                    Text("Owner unlock", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Muted("Enter the owner password to unlock without an activation key.", Modifier.padding(vertical = 10.dp), 13.sp)
                }
                Field(owner, { owner = it }, "Owner password", Modifier.fillMaxWidth(), password = true)
                msg?.let { Text(it, color = C.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Btn("Unlock", {
                    if (Guard.verifyOwner(owner)) { Guard.clearFails(ctx); Activation.unlockWithOwner(st.store); onPass() }
                    else {
                        if (mode == "tamper") { val n = Guard.noteFail(ctx); if (n >= 3) Guard.wipe(ctx) else { msg = "Wrong password. ${3 - n} attempt${if (3 - n == 1) "" else "s"} left."; owner = "" } }
                        else { msg = "Wrong password."; owner = "" }
                    }
                }, Modifier.fillMaxWidth().padding(top = 12.dp))
                if (mode != "tamper") Btn("Use an activation key instead", { ownerMode = false; msg = null }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost)
            } else {
                Text("Activate OmniPlay", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Muted("Enter your activation key to unlock the app.", Modifier.padding(vertical = 10.dp), 13.sp)
                Field(key, { key = it; msg = null }, "Activation key", Modifier.fillMaxWidth())
                msg?.let { Text(it, color = if (it.startsWith("Activated")) C.ac2 else C.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Btn("Activate", {
                    val (ok, m) = Activation.activate(st.store, key); msg = m; if (ok) onPass()
                }, Modifier.fillMaxWidth().padding(top = 12.dp))
                Btn("Owner unlock", { ownerMode = true; msg = null }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost)
            }
        }
    }
}
