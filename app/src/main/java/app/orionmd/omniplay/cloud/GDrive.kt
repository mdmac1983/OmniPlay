package app.orionmd.omniplay.cloud

import org.json.JSONObject

/**
 * Google Drive (OAuth 2.0 code flow + PKCE). You create an OAuth "Desktop app" client in the Google Cloud console and
 * enter its Client ID and Client secret in Settings. Read-only Drive scope; offline access gives a refresh token.
 */
object GDrive : CloudProvider {
    override val id = "gdrive"
    override val name = "Google Drive"
    override val auth = "oauth"
    override val needsClientId = true
    override val needsClientSecret = true
    override val signupUrl = "https://console.cloud.google.com/apis/credentials"
    override val help = "In Google Cloud console: create a project, enable the Google Drive API, configure the OAuth consent screen (add yourself as a test user), then create an OAuth client ID of type Desktop app. Paste its Client ID and Client secret in Settings → API keys. (The loopback redirect is handled automatically.)"

    override fun authUrl(clientId: String, redirect: String, verifier: String): String =
        "https://accounts.google.com/o/oauth2/v2/auth?client_id=${Http.enc(clientId)}&redirect_uri=${Http.enc(redirect)}&response_type=code" +
            "&scope=${Http.enc("https://www.googleapis.com/auth/drive.readonly")}&access_type=offline&prompt=consent" +
            "&code_challenge=${AuthFlow.challenge(verifier)}&code_challenge_method=S256"

    override fun finishAuth(clientId: String, clientSecret: String, redirect: String, verifier: String, redirectUri: String): JSONObject {
        val code = AuthFlow.queryOf(redirectUri)["code"] ?: throw Exception("No authorization code returned")
        val r = Http.postForm("https://oauth2.googleapis.com/token", mapOf(
            "code" to code, "client_id" to clientId, "client_secret" to clientSecret, "redirect_uri" to redirect,
            "grant_type" to "authorization_code", "code_verifier" to verifier))
        var email = ""
        try { email = Http.getJson("https://www.googleapis.com/oauth2/v2/userinfo", mapOf("Authorization" to "Bearer ${r.optString("access_token")}")).optString("email") } catch (_: Exception) {}
        return JSONObject().put("token", r.optString("access_token")).put("refresh", r.optString("refresh_token"))
            .put("expires", System.currentTimeMillis() + r.optLong("expires_in", 3500) * 1000)
            .put("client", clientId).put("secret", clientSecret).put("email", email)
    }

    private fun token(acct: CloudAccount): String {
        val d = acct.data
        if (System.currentTimeMillis() < d.optLong("expires") - 120_000 && d.optString("token").isNotEmpty()) return d.optString("token")
        val refresh = d.optString("refresh"); if (refresh.isEmpty()) return d.optString("token")
        val r = Http.postForm("https://oauth2.googleapis.com/token", mapOf(
            "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to d.optString("client"), "client_secret" to d.optString("secret")))
        d.put("token", r.optString("access_token")).put("expires", System.currentTimeMillis() + r.optLong("expires_in", 3500) * 1000)
        Cloud.update(acct.id, d)
        return d.optString("token")
    }
    private fun auth(acct: CloudAccount) = mapOf("Authorization" to "Bearer ${token(acct)}")

    override fun list(acct: CloudAccount, folderId: String): List<CloudFile> {
        val parent = folderId.ifEmpty { "root" }
        val out = ArrayList<CloudFile>()
        var pageToken: String? = null
        do {
            val q = Http.enc("'$parent' in parents and trashed=false")
            val fields = Http.enc("nextPageToken,files(id,name,mimeType,size,modifiedTime)")
            var url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=$fields&pageSize=1000&orderBy=folder,name&supportsAllDrives=true&includeItemsFromAllDrives=true"
            if (pageToken != null) url += "&pageToken=${Http.enc(pageToken!!)}"
            val r = Http.getJson(url, auth(acct))
            r.optJSONArray("files")?.objs()?.forEach { f ->
                val dir = f.optString("mimeType") == "application/vnd.google-apps.folder"
                out.add(CloudFile(f.optString("id"), f.optString("name"), dir, f.optLong("size"), parseTime(f.optString("modifiedTime"))))
            }
            pageToken = r.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    override fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream =
        Http.openRange("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true", offset, auth(acct))

    private fun parseTime(s: String): Long = try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time ?: 0 } catch (_: Exception) { 0 }
}
