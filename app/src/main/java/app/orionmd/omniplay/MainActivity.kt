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
        Lock.ownTrip = false
        treeCb?.invoke(uri); treeCb = null
    }

    private var mediaCb: ((Boolean) -> Unit)? = null
    private val mediaConfirm = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Lock.ownTrip = false
        mediaCb?.invoke(r.resultCode == RESULT_OK); mediaCb = null
    }
    /** Music, video and photo access (+ notifications for playback / downloads). Asked once; a rescan follows. */
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it } && ::st.isInitialized) { st.sync("device"); st.loadGallery() }
    }
    private fun askPermissions() {
        val want = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.POST_NOTIFICATIONS)
            else if (android.os.Build.VERSION.SDK_INT >= 29) arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            // Android 9 and older: writing too, so downloads can go to the phone's Music / Movies / Pictures
            else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = want.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) try { perms.launch(missing.toTypedArray()) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = AppState(this, this)
        if (intent?.getStringExtra("open") == "player") openPlayer()
        Activation.ensureTrialStarted(st.store)
        gate = gateState()
        Lock.onReturn(st.store, starting = savedInstanceState == null)
        recheckPending()
        setContent {
            AppTheme {
                var splash by remember { mutableStateOf(savedInstanceState == null && st.store.getPref("splash", "1") == "1") }
                if (splash) app.orionmd.omniplay.ui.SplashScreen { splash = false }
                else if (gate == "ok") { Started(); if (Lock.locked) app.orionmd.omniplay.ui.PinScreen(st) else OmniApp(st) }
                else GateScreen(st, gate) { gate = gateState() }
            }
        }
    }

    private var gate by mutableStateOf("")

    @Composable private fun Started() { LaunchedEffect(Unit) { st.start(); askPermissions() } }

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
    /** Direct Volume Control: while Media Hub is playing, the volume keys change its own volume. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (Dvc.engaged && (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) { Dvc.step(k == android.view.KeyEvent.KEYCODE_VOLUME_UP); if (::st.isInitialized) st.say("Volume ${Dvc.level}") }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun openTree(cb: (Uri?) -> Unit) { Lock.ownTrip = true; treeCb = cb; try { treePicker.launch(null) } catch (_: Exception) { cb(null) } }
    override fun launch(intent: Intent) { startActivity(intent) }
    // the downloads notification opens the Offline page
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::st.isInitialized) return
        when (intent.getStringExtra("open")) { "downloads" -> st.open(Page.Downloads); "player" -> openPlayer() }
    }
    /** From the playback notification: show the full player. */
    private fun openPlayer() { st.soundOpen = false; st.visFull = false; st.sheetOpen = true }
    private var deviceCb: ((Boolean) -> Unit)? = null
    private val deviceConfirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Lock.ownTrip = false
        deviceCb?.invoke(r.resultCode == RESULT_OK); deviceCb = null
    }
    override fun confirmDevice(title: String, cb: (Boolean) -> Unit) { Lock.ownTrip = true;
        val km = getSystemService(android.app.KeyguardManager::class.java)
        @Suppress("DEPRECATION") val i = if (km?.isDeviceSecure == true) km.createConfirmDeviceCredentialIntent(title, "Confirm it's you") else null
        if (i == null) { st.say("This phone has no screen lock set up"); cb(false); return }
        deviceCb = cb; try { deviceConfirm.launch(i) } catch (_: Exception) { cb(false) }
    }
    override fun leave() { moveTaskToBack(true) }
    override fun onStop() { super.onStop(); Lock.onLeave() }
    override fun onRestart() {
        super.onRestart()
        if (::st.isInitialized) { val was = Lock.privateOpen; Lock.onReturn(st.store, starting = false); if (was && !Lock.privateOpen) st.refreshLocks() }
    }
    override fun confirmMedia(sender: android.content.IntentSender, cb: (Boolean) -> Unit) { Lock.ownTrip = true;
        mediaCb = cb; try { mediaConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) } catch (_: Exception) { cb(false) }
    }
}
