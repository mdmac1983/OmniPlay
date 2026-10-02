package app.orionmd.omniplay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.orionmd.omniplay.ui.AppTheme
import app.orionmd.omniplay.ui.GateScreen
import app.orionmd.omniplay.ui.OmniApp

class MainActivity : ComponentActivity(), Host {
    private lateinit var st: AppState
    private var treeCb: ((Uri?) -> Unit)? = null
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        treeCb?.invoke(uri); treeCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = AppState(this, this)
        Activation.ensureTrialStarted(st.store)
        gate = gateState()
        recheckPending()
        setContent {
            AppTheme {
                var splash by remember { mutableStateOf(savedInstanceState == null && st.store.getPref("splash", "1") == "1") }
                if (splash) app.orionmd.omniplay.ui.SplashScreen { splash = false }
                else if (gate == "ok") { Started(); OmniApp(st) }
                else GateScreen(st, gate) { gate = gateState() }
            }
        }
    }

    private var gate by mutableStateOf("")

    @Composable private fun Started() { LaunchedEffect(Unit) { st.start() } }

    /** "tamper" (re-signed copy → owner lock), "locked" (trial over / key not confirmed), or "ok" (trial, pending, activated). */
    private fun gateState(): String = when {
        Guard.tampered(this) && st.store.secret("ownerUnlocked") != "1" -> "tamper"
        Activation.state(st.store) == Activation.State.LOCKED -> "locked"
        else -> "ok"
    }

    /** While a key is pending (entered offline), quietly re-check it on every start / return to the app. */
    private fun recheckPending() {
        if (Activation.state(st.store) != Activation.State.PENDING) return
        Thread { Activation.retryPendingIfAny(this, st.store); runOnUiThread { gate = gateState(); st.prefGen++
            Activation.takeNotice(st.store)?.let { st.say(it) } } }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::st.isInitialized) { gate = gateState(); if (gate != "ok") Player.stop(); recheckPending(); st.onResume() }
    }
    override fun openTree(cb: (Uri?) -> Unit) { treeCb = cb; try { treePicker.launch(null) } catch (_: Exception) { cb(null) } }
    override fun launch(intent: Intent) { startActivity(intent) }
}
