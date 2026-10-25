package app.orionmd.omniplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub: every build is also published to the public repo mdmac1983/releases (tag "mediahub-vX.Y",
 * file "MediaHub-vX.Y.apk"). Settings → App updates checks it, shows what changed, downloads the APK with progress
 * and opens Android's installer. The download is installed only when it's signed with the same key as this app.
 */
object Updater {
    private const val API = "https://api.github.com/repos/mdmac1983/releases/releases?per_page=60"
    private const val TAG = "mediahub-v"

    class Release(val version: String, val notes: String, val url: String, val size: Long, val date: String)

    /** Newest release, the notes of every release newer than this app, and whether it's newer. */
    class Check(val latest: Release?, val newer: List<Release>) { val available get() = newer.isNotEmpty() }

    var checking by mutableStateOf(false)
    var result by mutableStateOf<Check?>(null)
    var error by mutableStateOf<String?>(null)
    /** Download progress 0..1 (-1 = size unknown), null when not downloading. */
    var progress by mutableStateOf<Float?>(null)

    /** "4.10" > "4.9": compares the numbers, not the text. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    /** Reads the release list (no sign-in needed: the releases repo is public). */
    fun fetch(current: String): Check {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "OrionMD-Updater")
        val text = try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                403, 429 -> throw Exception("GitHub is busy — try again in a few minutes")
                404 -> throw Exception("The releases page isn't available")
                else -> throw Exception("GitHub answered ${c.responseCode}")
            }
        } finally { c.disconnect() }
        val a = JSONArray(text)
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("tag_name").startsWith(TAG) && !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
                Release(r.optString("tag_name").removePrefix(TAG), r.optString("body").trim(), apk.optString("browser_download_url"), apk.optLong("size"), r.optString("published_at").take(10))
            }
            .sortedWith { p, q -> if (newer(p.version, q.version)) -1 else if (newer(q.version, p.version)) 1 else 0 }
        return Check(all.firstOrNull(), all.filter { newer(it.version, current) })
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Downloads the APK (resuming a partial download) and returns the file. */
    fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File {
        val f = File(dir(ctx), "MediaHub-v${r.version}.apk")
        if (f.exists() && r.size > 0 && f.length() == r.size) return f
        val part = File(dir(ctx), f.name + ".part")
        var u = URL(r.url); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "OrionMD-Updater")
            if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); if (++hops > 5) throw Exception("Too many redirects"); continue }
            if (code != 200 && code != 206) { c.disconnect(); throw Exception("Download failed ($code)") }
            if (code == 200) part.delete()
            val total = if (code == 206) part.length() + c.contentLengthLong else c.contentLengthLong
            c.inputStream.use { ins -> java.io.FileOutputStream(part, true).use { out ->
                val buf = ByteArray(64 * 1024); var done = part.length(); var last = 0L
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis(); if (now - last > 200) { last = now; onProgress(if (total > 0) done.toFloat() / total else -1f) } }
            } }
            c.disconnect(); break
        }
        if (r.size > 0 && part.length() != r.size) throw Exception("The download was incomplete — try again")
        f.delete(); part.renameTo(f)
        dir(ctx).listFiles()?.forEach { if (it.name != f.name) it.delete() }   // older downloads
        return f
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { x -> "%02x".format(x) }
    /** Every certificate Android reports for a package, by both the new and the old method (some versions fill only one). */
    @Suppress("DEPRECATION")
    private fun certs(info: android.content.pm.PackageInfo?): Set<String> {
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.let { si ->
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.forEach { out += hex(it.toByteArray()) }
            si.apkContentsSigners?.forEach { out += hex(it.toByteArray()) }
        }
        info?.signatures?.forEach { out += hex(it.toByteArray()) }
        return out
    }

    /**
     * False only when the download is for another app, or is signed with a different key than the installed app.
     * Some Android versions don't report a downloaded APK's signature at all; then Android's installer, which refuses
     * an update signed with another key anyway, has the final say.
     */
    @Suppress("DEPRECATION")
    fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNATURES or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val other = pm.getPackageArchiveInfo(apk.path, flags)?.also { it.applicationInfo?.let { a -> a.sourceDir = apk.path; a.publicSourceDir = apk.path } }
            ?: return false   // not an APK Android can read
        if (other.packageName != ctx.packageName) return false
        val theirs = certs(other); if (theirs.isEmpty()) return true
        val mine = certs(pm.getPackageInfo(ctx.packageName, flags)); if (mine.isEmpty()) return true
        return theirs.any { it in mine }
    }

    /** Android must allow this app to install updates (asked once, in system settings). */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    fun allowInstallIntent(ctx: Context) = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Opens Android's installer for the downloaded APK. */
    fun installIntent(ctx: Context, apk: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
