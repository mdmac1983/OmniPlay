package app.orionmd.omniplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * App integrity + the owner lock. OmniPlay is proprietary and distributed only as the release build signed with the
 * OrionMD key. [tampered] is true when the running copy was re-signed (repackaged / modified). A tampered copy, or anyone
 * opening the hidden owner lock, must enter the owner password; after 3 wrong tries the app wipes its OWN data (its
 * encrypted caches, settings, offline files and the device data key) so nothing of yours can be recovered from a
 * modified copy. Nothing outside the app is touched.
 */
object Guard {
    // SHA-256 of the OrionMD release signing certificate (keystore/release.keystore).
    private const val EXPECTED = "BD3A5C3DE7060FBD9CFB3EB94E0CAE34EC79EE98EDA67D16B6717247B556B0FA"

    // Owner password verifier (PBKDF2-HMAC-SHA256) — the password itself is never stored.
    private const val VER_ITER = 310000
    private const val VER_SALT = "dEK+KntuMC6fU6ILoZd2Kg=="
    private const val VER_HASH = "qnceFmxaclzRsRF2+7p69bHOvghvr9c+GazwdmWeAM0="
    private const val FAIL_KEY = "ownerFails"

    fun currentSig(ctx: Context): String = try {
        val pm = ctx.packageManager
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = sigs?.firstOrNull()?.toByteArray() ?: return ""
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02X".format(it) }
    } catch (_: Exception) { "" }

    fun tampered(ctx: Context): Boolean { val s = currentSig(ctx); return s.isNotEmpty() && s != EXPECTED }

    fun verifyOwner(password: String): Boolean = try {
        val salt = Base64.decode(VER_SALT, Base64.DEFAULT)
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, VER_ITER, 256)).encoded
        constEq(h, Base64.decode(VER_HASH, Base64.DEFAULT))
    } catch (_: Exception) { false }

    private fun constEq(a: ByteArray, b: ByteArray): Boolean { if (a.size != b.size) return false; var r = 0; for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt()); return r == 0 }

    fun fails(ctx: Context): Int = ctx.getSharedPreferences("omniplay", Context.MODE_PRIVATE).getInt(FAIL_KEY, 0)
    fun noteFail(ctx: Context): Int { val n = fails(ctx) + 1; ctx.getSharedPreferences("omniplay", Context.MODE_PRIVATE).edit().putInt(FAIL_KEY, n).apply(); return n }
    fun clearFails(ctx: Context) { ctx.getSharedPreferences("omniplay", Context.MODE_PRIVATE).edit().remove(FAIL_KEY).apply() }

    /** Wipes the app's own data irrecoverably: all files, caches, offline copies, settings and the data-encryption key. */
    fun wipe(ctx: Context) {
        try { Vault.destroyKey(ctx) } catch (_: Exception) {}
        try { ctx.getSharedPreferences("omniplay", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
        try { ctx.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
        val dirs = listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null), ctx.externalCacheDir, File(ctx.applicationInfo.dataDir, "shared_prefs"))
        dirs.forEach { d -> try { d.deleteRecursively() } catch (_: Exception) {} }
        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData() } catch (_: Exception) {}
        Process.killProcess(Process.myPid())
    }
}
