package app.orionmd.omniplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.BuildConfig
import app.orionmd.omniplay.Updater
import app.orionmd.omniplay.Dlg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → App updates: this version, the latest one, what changed, and Update. */
@Composable
fun UpdateSettings(st: AppState) {
    val cur = BuildConfig.VERSION_NAME
    val r = Updater.result
    Column(Modifier.fillMaxWidth()) {
        Text("This version: v$cur", fontWeight = FontWeight.SemiBold)
        when {
            Updater.checking -> Muted("Checking for updates…", Modifier.padding(top = 4.dp), 13.sp)
            Updater.error != null -> Muted(Updater.error!!, Modifier.padding(top = 4.dp), 13.sp)
            r != null && !r.available -> Muted("You have the latest version" + (r.latest?.let { " (v${it.version})" } ?: "") + ".", Modifier.padding(top = 4.dp), 13.sp)
        }
        if (r != null && r.available) {
            val top = r.newer.first()
            Text("Media Hub v${top.version} is available", fontWeight = FontWeight.Bold, color = C.ac, modifier = Modifier.padding(top = 8.dp))
            Muted("${r.newer.size} update${if (r.newer.size == 1) "" else "s"} since your version · ${String.format(java.util.Locale.US, "%.1f MB", top.size / 1048576.0)}", size = 12.sp)
            r.newer.take(8).forEach { n ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text("v${n.version} · ${n.date}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Muted(n.notes.lines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("Install **") && !it.startsWith("The full feature guide") }.joinToString("\n").take(600), size = 12.sp)
                }
            }
            if (r.newer.size > 8) Muted("…and ${r.newer.size - 8} earlier updates", Modifier.padding(top = 4.dp), 12.sp)
            val p = Updater.progress
            if (p != null) { Progress(if (p < 0) null else p); Muted(if (p < 0) "Downloading…" else "Downloading… ${(p * 100).toInt()}%", size = 12.sp) }
            else Btn("Update to v${top.version}", { st.installUpdate(top) }, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Btn(if (Updater.checking) "Checking…" else "Check for updates", { st.checkUpdates(quiet = false) }, Modifier.padding(top = 10.dp), kind = BtnKind.Ghost, small = true)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Check automatically", fontWeight = FontWeight.SemiBold); Muted("Once a day, and tell me when an update is out", size = 12.sp) }
            Switch(st.pref("autoUpdate", "1") == "1", { st.setPref("autoUpdate", if (it) "1" else "0") })
        }
        Muted("Android shows its install screen to confirm; your data is kept. Only updates signed with the same key as this app are installed.",
            Modifier.padding(top = 8.dp), 11.sp)
    }
}

/** Looks for a newer version. Quiet = the daily check: only speaks up when there is one. */
fun AppState.checkUpdates(quiet: Boolean) {
    if (Updater.checking) return
    Updater.checking = true; if (!quiet) Updater.error = null
    scope.launch {
        val res = withContext(Dispatchers.IO) { try { Updater.fetch(BuildConfig.VERSION_NAME) } catch (e: Exception) { if (!quiet) Updater.error = e.message ?: "Couldn't check for updates"; null } }
        Updater.checking = false
        setPref("updateCheckedAt", System.currentTimeMillis().toString())
        if (res != null) { Updater.result = res; Updater.error = null }
        if (quiet && res != null && res.available && pref("updateSkipped", "") != res.newer.first().version) {
            val top = res.newer.first()
            dialog = Dlg.Confirm("Media Hub v${top.version} is available", top.notes.lines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("Install **") && !it.startsWith("The full feature guide") }.joinToString("\n").take(400) +
                "\n\nUpdate now? (Settings → App updates has the full list.)") { installUpdate(top) }
            setPref("updateSkipped", top.version)   // asked once per version
        }
    }
}

/** The daily check, when it's on and a day has passed. */
fun AppState.autoCheckUpdates() {
    if (pref("autoUpdate", "1") != "1") return
    val last = pref("updateCheckedAt", "0").toLongOrNull() ?: 0
    if (System.currentTimeMillis() - last > 20L * 3600_000) checkUpdates(quiet = true)
}

/** Downloads the update and opens Android's installer (after making sure it's signed like this app). */
fun AppState.installUpdate(r: Updater.Release) {
    if (Updater.progress != null) return
    if (!Updater.canInstall(ctx)) {
        say("Allow Media Hub to install updates, then tap Update again")
        try { host.launch(Updater.allowInstallIntent(ctx)) } catch (_: Exception) {}
        return
    }
    Updater.progress = -1f
    scope.launch {
        val f = withContext(Dispatchers.IO) {
            try { Updater.download(ctx, r) { p -> scope.launch { Updater.progress = p } } } catch (e: Exception) { withContext(Dispatchers.Main) { say(e.message ?: "Download failed") }; null }
        }
        Updater.progress = null
        if (f == null) return@launch
        if (!withContext(Dispatchers.IO) { try { Updater.sameSigner(ctx, f) } catch (_: Exception) { false } }) { f.delete(); say("That download isn't signed like this app — not installed"); return@launch }
        try { host.launch(Updater.installIntent(ctx, f)) } catch (e: Exception) { say("Couldn't open the installer") }
    }
}
