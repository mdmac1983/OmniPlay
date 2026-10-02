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
        setContent {
            AppTheme {
                var gate by remember { mutableStateOf(gateState()) }
                var splash by remember { mutableStateOf(savedInstanceState == null && st.store.getPref("splash", "1") == "1") }
                if (splash) app.orionmd.omniplay.ui.SplashScreen { splash = false }
                else if (gate == "ok") { Started(); OmniApp(st) }
                else GateScreen(st, gate) { gate = gateState() }
            }
        }
    }

    @Composable private fun Started() { LaunchedEffect(Unit) { st.start() } }

    private fun gateState(): String = when {
        Guard.tampered(this) && st.store.secret("ownerUnlocked") != "1" -> "tamper"
        !Activation.isActivated(st.store) -> "activate"
        else -> "ok"
    }

    override fun onResume() { super.onResume(); if (::st.isInitialized) st.onResume() }
    override fun openTree(cb: (Uri?) -> Unit) { treeCb = cb; try { treePicker.launch(null) } catch (_: Exception) { cb(null) } }
    override fun launch(intent: Intent) { startActivity(intent) }
}
