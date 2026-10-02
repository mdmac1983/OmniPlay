package app.orionmd.omniplay.cloud

import org.json.JSONObject

/**
 * Dropbox (OAuth 2.0, PKCE — no client secret needed). You enter your own App key in Settings
 * (dropbox.com/developers → Create app → Scoped access → Full Dropbox or App folder). Offline access gives a refresh
 * token so you stay signed in; the short-lived access token is refreshed automatically.
 */
object Dropbox : CloudProvider {
    override val id = "dropbox"
    override val name = "Dropbox"
    override val auth = "oauth"
    override val needsClientId = true
    override val signupUrl = "https://www.dropbox.com/developers/apps"
    override val help = "Create a Dropbox app: dropbox.com/developers → Create app → Scoped access → choose Full Dropbox. Under Permissions enable files.metadata.read and files.content.read. Add the loopback redirect URI shown below, then paste the App key in Settings → API keys."

    override fun authUrl(clientId: String, redirect: String, verifier: String): String =
        "https://www.dropbox.com/oauth2/authorize?client_id=${Http.enc(clientId)}&response_type=code&redirect_uri=${Http.enc(redirect)}" +
            "&token_access_type=offline&code_challenge=${AuthFlow.challenge(verifier)}&code_challenge_method=S256"

    override fun finishAuth(clientId: String, clientSecret: String, redirect: String, verifier: String, redirectUri: String): JSONObject {
        val code = AuthFlow.queryOf(redirectUri)["code"] ?: throw Exception("No authorization code returned")
        val r = Http.postForm("https://api.dropboxapi.com/oauth2/token", mapOf(
            "code" to code, "grant_type" to "authorization_code", "redirect_uri" to redirect, "client_id" to clientId, "code_verifier" to verifier))
        return JSONObject().put("token", r.optString("access_token")).put("refresh", r.optString("refresh_token"))
            .put("expires", System.currentTimeMillis() + r.optLong("expires_in", 14000) * 1000).put("client", clientId)
    }

    /** A valid access token, refreshing it first when it's close to expiry. */
    private fun token(acct: CloudAccount): String {
        val d = acct.data
        if (System.currentTimeMillis() < d.optLong("expires") - 120_000 && d.optString("token").isNotEmpty()) return d.optString("token")
        val refresh = d.optString("refresh"); if (refresh.isEmpty()) return d.optString("token")
        val r = Http.postForm("https://api.dropboxapi.com/oauth2/token", mapOf(
            "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to d.optString("client")))
        d.put("token", r.optString("access_token")).put("expires", System.currentTimeMillis() + r.optLong("expires_in", 14000) * 1000)
        Cloud.update(acct.id, d)
        return d.optString("token")
    }
    private fun auth(acct: CloudAccount) = mapOf("Authorization" to "Bearer ${token(acct)}")

    override fun list(acct: CloudAccount, folderId: String): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        var res = Http.postJson("https://api.dropboxapi.com/2/files/list_folder", JSONObject().put("path", folderId).put("limit", 2000), auth(acct))
        while (true) {
            res.optJSONArray("entries")?.objs()?.forEach { e ->
                val dir = e.optString(".tag") == "folder"
                out.add(CloudFile(e.optString("path_lower"), e.optString("name"), dir, e.optLong("size"), parseTime(e.optString("server_modified"))))
            }
            if (!res.optBoolean("has_more")) break
            res = Http.postJson("https://api.dropboxapi.com/2/files/list_folder/continue", JSONObject().put("cursor", res.optString("cursor")), auth(acct))
        }
        return out
    }

    override fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream {
        val r = Http.postJson("https://api.dropboxapi.com/2/files/get_temporary_link", JSONObject().put("path", fileId), auth(acct))
        return Http.openRange(r.optString("link"), offset)
    }

    private fun parseTime(s: String): Long = try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: 0 } catch (_: Exception) { 0 }
}
