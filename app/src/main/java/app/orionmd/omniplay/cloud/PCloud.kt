package app.orionmd.omniplay.cloud

import org.json.JSONObject
import java.io.File

/**
 * pCloud (OAuth 2.0 code flow). You enter your own app's Client ID and Client secret in Settings (pcloud.com → Developers
 * → My Applications → new app). pCloud tells us which data region to use (US api.pcloud.com or EU eapi.pcloud.com) in the
 * redirect, and we keep the access token (which does not expire).
 */
object PCloud : CloudProvider {
    override val id = "pcloud"
    override val name = "pCloud"
    override val auth = "oauth"
    override val needsClientId = true
    override val needsClientSecret = true
    override val signupUrl = "https://docs.pcloud.com/my_apps/"
    override val help = "Create a free pCloud app: pcloud.com → Developers → My Applications → New app. Set the redirect URI to the loopback address shown below, then paste the app's Client ID and Client secret in Settings → API keys."

    override fun authUrl(clientId: String, redirect: String, verifier: String): String =
        "https://my.pcloud.com/oauth2/authorize?client_id=${Http.enc(clientId)}&response_type=code&redirect_uri=${Http.enc(redirect)}"

    override fun finishAuth(clientId: String, clientSecret: String, redirect: String, verifier: String, redirectUri: String): JSONObject {
        val q = AuthFlow.queryOf(redirectUri)
        val code = q["code"] ?: throw Exception("No authorization code returned")
        val host = when (q["hostname"]) { "eapi.pcloud.com" -> "eapi.pcloud.com"; else -> "api.pcloud.com" }
        val r = Http.getJson("https://$host/oauth2_token?client_id=${Http.enc(clientId)}&client_secret=${Http.enc(clientSecret)}&code=${Http.enc(code)}")
        val token = r.optString("access_token"); if (token.isEmpty()) throw Exception("pCloud did not return a token")
        var email = ""
        try { email = Http.getJson("https://$host/userinfo?access_token=${Http.enc(token)}").optString("email") } catch (_: Exception) {}
        return JSONObject().put("token", token).put("host", host).put("email", email)
    }

    private fun host(a: CloudAccount) = a.data.optString("host", "api.pcloud.com")
    private fun token(a: CloudAccount) = a.data.optString("token")

    override fun list(acct: CloudAccount, folderId: String): List<CloudFile> {
        val fid = folderId.ifEmpty { "0" }
        val r = Http.getJson("https://${host(acct)}/listfolder?folderid=$fid&access_token=${Http.enc(token(acct))}")
        if (r.optInt("result") != 0) throw Exception(r.optString("error", "pCloud error"))
        val contents = r.optJSONObject("metadata")?.optJSONArray("contents") ?: return emptyList()
        return contents.objs().map { o ->
            val dir = o.optBoolean("isfolder")
            CloudFile(if (dir) o.optLong("folderid").toString() else "f" + o.optLong("fileid"), o.optString("name"), dir, o.optLong("size"), o.optLong("modified") * 1000)
        }
    }

    override fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream {
        val id = fileId.removePrefix("f")
        val link = Http.getJson("https://${host(acct)}/getfilelink?fileid=$id&forcedownload=0&skipfilename=1&access_token=${Http.enc(token(acct))}")
        if (link.optInt("result") != 0) throw Exception(link.optString("error", "pCloud error"))
        val hosts = link.optJSONArray("hosts"); val path = link.optString("path")
        val url = "https://" + (hosts?.optString(0) ?: throw Exception("no host")) + path
        return Http.openRange(url, offset)
    }

    override fun download(acct: CloudAccount, fileId: String, dest: File): Boolean = super.download(acct, fileId, dest)
}
