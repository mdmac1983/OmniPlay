package app.orionmd.omniplay

import app.orionmd.omniplay.cloud.AuthFlow
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Connecting cloud accounts (OAuth in the browser, or email/password / WebDAV). */
fun AppState.connectOAuth(providerId: String) {
    val p = Cloud.provider(providerId) ?: return
    if (p.needsClientId && Cloud.clientId(providerId).isEmpty()) { dialog = Dlg.Message("Add your ${p.name} keys first", "Open Settings → API keys and enter your ${p.name} Client ID" + (if (p.needsClientSecret) " and secret" else "") + ".\n\n${p.help}"); return }
    val verifier = AuthFlow.newVerifier()
    val url = AuthFlow.start(p, verifier) ?: run { say("Add your ${p.name} Client ID first"); return }
    say("Opening ${p.name} sign-in…"); openUrl(url)
    scope.launch {
        try {
            val data = withContext(Dispatchers.IO) { AuthFlow.await(p, verifier) }
            val acct = Cloud.add(providerId, data.optString("label").ifEmpty { data.optString("email").ifEmpty { p.name } }, data)
            say("Connected ${p.name}"); prefGen++
            open(Page.CloudBrowse(providerId, acct.id, p.rootId, ""))
        } catch (e: Exception) { dialog = Dlg.Message("Couldn't connect ${p.name}", e.message ?: "Sign-in failed. Check your Client ID/secret and the redirect URI, then try again.") }
    }
}

fun AppState.connectWithFields(providerId: String, fields: Map<String, String>) {
    val p = Cloud.provider(providerId) ?: return
    say("Connecting ${p.name}…")
    scope.launch {
        try {
            val data = withContext(Dispatchers.IO) { p.signIn(fields) }
            val acct = Cloud.add(providerId, data.optString("label").ifEmpty { fields["user"] ?: p.name }, data)
            say("Connected ${p.name}"); prefGen++
            open(Page.CloudBrowse(providerId, acct.id, p.rootId, ""))
        } catch (e: Exception) { dialog = Dlg.Message("Couldn't connect ${p.name}", e.message ?: "Sign-in failed.") }
    }
}

fun AppState.oauthRedirect(): String = app.orionmd.omniplay.cloud.CloudProxy.oauthRedirect()
