package app.orionmd.omniplay.cloud

import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Generic WebDAV — works with Nextcloud, ownCloud, Koofr, Box, 4shared, Yandex, a NAS, or anything that speaks WebDAV.
 * You enter the server URL, your username and a password (an app password where the service offers one). File ids are
 * paths relative to the base URL.
 */
open class WebDavBase(
    override val id: String, override val name: String, override val help: String, override val signupUrl: String = "",
) : CloudProvider {
    override val auth = "webdav"

    override fun signIn(fields: Map<String, String>): JSONObject {
        var base = fields["url"]?.trim()?.trimEnd('/') ?: throw Exception("Enter the WebDAV URL")
        if (!base.startsWith("http")) base = "https://$base"
        val user = fields["user"]?.trim() ?: ""; val pass = fields["pass"] ?: ""
        val data = JSONObject().put("url", base).put("user", user).put("pass", pass)
        // verify by listing the root
        list(CloudAccount("test", id, "", data), "")
        return data.put("label", user.ifEmpty { base.substringAfter("://") })
    }

    protected fun header(a: CloudAccount): Map<String, String> {
        val u = a.data.optString("user"); val p = a.data.optString("pass")
        return if (u.isEmpty() && p.isEmpty()) emptyMap() else mapOf("Authorization" to "Basic " + Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP))
    }
    protected fun base(a: CloudAccount) = a.data.optString("url").trimEnd('/')
    private fun urlFor(a: CloudAccount, path: String): String {
        val rel = path.trim('/').split('/').joinToString("/") { Http.enc(it).replace("+", "%20") }
        return base(a) + "/" + rel
    }

    override fun list(acct: CloudAccount, folderId: String): List<CloudFile> {
        val url = urlFor(acct, folderId).let { if (it.endsWith("/")) it else "$it/" }
        val r = Http.req(url, "PROPFIND", header(acct) + mapOf("Depth" to "1"), "".toByteArray(), "application/xml")
        if (r.code !in 200..299) throw CloudError(r.code, r.body)
        return parse(r.body, base(acct), folderId.trim('/'))
    }

    /** Minimal PROPFIND parser: one <response> per entry with href, resourcetype, getcontentlength, getlastmodified. */
    private fun parse(xml: String, base: String, parent: String): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        val basePath = java.net.URL(base).path.trimEnd('/')
        val blocks = Regex("<(?:[a-zA-Z]+:)?response[ >][\\s\\S]*?</(?:[a-zA-Z]+:)?response>").findAll(xml)
        for (m in blocks) {
            val b = m.value
            val href = Regex("<(?:[a-zA-Z]+:)?href>([\\s\\S]*?)</(?:[a-zA-Z]+:)?href>").find(b)?.groupValues?.get(1)?.trim() ?: continue
            var path = try { java.net.URLDecoder.decode(java.net.URL(if (href.startsWith("http")) href else base + href).path, "UTF-8") } catch (_: Exception) { href }
            if (basePath.isNotEmpty() && path.startsWith(basePath)) path = path.removePrefix(basePath)
            path = path.trim('/')
            if (path == parent || path.isEmpty()) continue   // the folder itself
            val isDir = b.contains("<collection", true) || b.contains(":collection", true)
            val size = Regex("getcontentlength>([0-9]+)<").find(b)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val mod = Regex("getlastmodified>([\\s\\S]*?)<").find(b)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0
            out.add(CloudFile(path, path.substringAfterLast('/'), isDir, size, mod))
        }
        return out
    }

    override fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream = Http.openRange(urlFor(acct, fileId), offset, header(acct))
    override fun download(acct: CloudAccount, fileId: String, dest: File): Boolean = super.download(acct, fileId, dest)
    private fun parseTime(s: String): Long = try { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(s.trim())?.time ?: 0 } catch (_: Exception) { 0 }
}

object WebDav : WebDavBase("webdav", "WebDAV (Nextcloud / NAS / other)",
    "Works with Nextcloud, ownCloud, Koofr, Box, Yandex, a home NAS and more. Enter the full WebDAV URL (e.g. https://cloud.example.com/remote.php/dav/files/you/), your username, and a password — use an app password if the service offers one.",
    "https://docs.nextcloud.com/server/latest/user_manual/en/files/access_webdav.html")

/**
 * Proton Drive has no public mobile API, so OmniPlay reaches it over WebDAV. Two easy ways:
 *  1) Install Proton Drive on a computer, turn on its sync folder, and run `rclone serve webdav` pointing at that folder
 *     (rclone also has a native `protondrive` backend you can serve over WebDAV).
 *  2) Or add the Proton Drive sync folder on this device as a Local folder (Library → add folder) for pure offline use.
 * Enter the WebDAV URL / user / password from step 1 here.
 */
object ProtonDrive : WebDavBase("proton", "Proton Drive (via WebDAV bridge)",
    "Proton Drive has no public app API, so connect it over WebDAV. On a computer install rclone, configure its 'protondrive' remote with your Proton account, then run:  rclone serve webdav protondrive: --addr :8080 --user you --pass secret  — and enter that URL, user and password here. For offline-only use you can instead add your Proton Drive sync folder as a Local folder.",
    "https://rclone.org/protondrive/")
