package app.orionmd.omniplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for everything the app stores offline (library databases, music info, TMDB data,
 * album art, posters, thumbnails, folder links and API keys).
 *
 *  - AES-256-GCM (authenticated encryption, random 12-byte IV per file).
 *  - A random 256-bit data key is generated once and stored *wrapped* by a master key that lives in the
 *    Android Keystore (hardware-backed on most devices) and never leaves it.
 *  - File format: "OMD1" + IV(12) + ciphertext+tag. Files without the header are old plain files and are
 *    read as-is, then encrypted by migrate() — so upgrading from an older version is seamless.
 */
object Vault {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    private const val ALIAS = "orionmd_omniplay_master"
    private const val PREF_PREFIX = "v1:"
    @Volatile private var key: SecretKey? = null
    @Volatile var hardwareBacked = false; private set
    @Volatile var keystoreOk = false; private set
    private val rng = SecureRandom()

    fun init(ctx: Context) {
        if (key != null) return
        synchronized(this) { if (key == null) key = loadKey(ctx.applicationContext) }
    }

    /** Destroys the data-encryption key (Keystore master + wrapped/fallback copies), so encrypted files can't be read. */
    fun destroyKey(ctx: Context) {
        key = null
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        try { ctx.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
    }

    private fun loadKey(ctx: Context): SecretKey {
        val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                kg.generateKey()
            }
            val master = ks.getKey(ALIAS, null) as SecretKey
            try {
                val info = SecretKeyFactory.getInstance(master.algorithm, "AndroidKeyStore").getKeySpec(master, KeyInfo::class.java) as KeyInfo
                @Suppress("DEPRECATION")
                hardwareBacked = info.isInsideSecureHardware
            } catch (_: Exception) {}

            val wrapped = prefs.getString("dk", null)
            if (wrapped != null) {
                val b = Base64.decode(wrapped, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, b, 0, 12))
                keystoreOk = true
                return SecretKeySpec(c.doFinal(b, 12, b.size - 12), "AES")
            }
            val dk = ByteArray(32).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, master)
            val blob = c.iv + c.doFinal(dk)
            prefs.edit().putString("dk", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
            keystoreOk = true
            return SecretKeySpec(dk, "AES")
        } catch (e: Exception) {
            // Keystore broken on this device: fall back to an app-private key so the app still works.
            keystoreOk = false
            val raw = prefs.getString("dk_fallback", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: ByteArray(32).also { rng.nextBytes(it); prefs.edit().putString("dk_fallback", Base64.encodeToString(it, Base64.NO_WRAP)).commit() }
            return SecretKeySpec(raw, "AES")
        }
    }

    private fun k(): SecretKey = key ?: throw IllegalStateException("Vault not initialised")

    fun isEncrypted(b: ByteArray) = b.size >= 4 && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3]

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k(), GCMParameterSpec(128, iv))
        return MAGIC + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (!isEncrypted(data)) return data // old plain file (pre-encryption) — migrate() will encrypt it
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k(), GCMParameterSpec(128, data, 4, 12))
        return c.doFinal(data, 16, data.size - 16)
    }

    // ---------- files ----------
    fun writeBytes(f: File, plain: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(encrypt(plain))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
    fun readBytes(f: File): ByteArray? = if (f.exists()) try { decrypt(f.readBytes()) } catch (e: Exception) { null } else null
    fun writeText(f: File, s: String) = writeBytes(f, s.toByteArray(Charsets.UTF_8))
    fun readText(f: File): String? = readBytes(f)?.toString(Charsets.UTF_8)
    fun open(f: File): InputStream? = readBytes(f)?.let { ByteArrayInputStream(it) }

    fun decodeBitmap(f: File, opts: BitmapFactory.Options? = null): Bitmap? {
        val b = readBytes(f) ?: return null
        return BitmapFactory.decodeByteArray(b, 0, b.size, opts)
    }

    // ---------- preference strings ----------
    fun encString(s: String): String = PREF_PREFIX + Base64.encodeToString(encrypt(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    fun decString(s: String?): String? {
        if (s == null) return null
        if (!s.startsWith(PREF_PREFIX)) return s // written by an older version
        return try { decrypt(Base64.decode(s.removePrefix(PREF_PREFIX), Base64.NO_WRAP)).toString(Charsets.UTF_8) } catch (e: Exception) { null }
    }

    /** Encrypts every older plain file under dir. Safe to run repeatedly. Returns (encrypted now, total files). */
    fun migrate(dir: File): Pair<Int, Int> {
        var done = 0; var total = 0
        dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") }.forEach { f ->
            total++
            try {
                val head = ByteArray(4)
                val n = f.inputStream().use { it.read(head) }
                if (n < 4 || !isEncrypted(head)) { writeBytes(f, f.readBytes()); done++ }
            } catch (_: Exception) {}
        }
        return done to total
    }

    fun countEncrypted(dir: File): Pair<Int, Int> {
        var enc = 0; var total = 0
        dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") }.forEach { f ->
            total++
            try { val h = ByteArray(4); if (f.inputStream().use { it.read(h) } == 4 && isEncrypted(h)) enc++ } catch (_: Exception) {}
        }
        return enc to total
    }
}
