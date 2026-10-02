package app.orionmd.omniplay

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Everything OmniPlay keeps on the device. Settings live in SharedPreferences; API keys, cloud sign-ins and the
 * activation key are "secret" settings, encrypted with [Vault] (AES-256-GCM, key held by the Android Keystore).
 * Caches (covers, posters, lyrics, TMDB data, library index) are encrypted files in app-private storage.
 */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("omniplay", Context.MODE_PRIVATE)
    init { Vault.init(ctx) }

    val filesDir: File get() = ctx.filesDir
    val artDir: File = File(ctx.filesDir, "art").apply { mkdirs() }
    val mcoverDir: File = File(ctx.filesDir, "mcover").apply { mkdirs() }
    val tmdbImgDir: File = File(ctx.filesDir, "tmdbimg").apply { mkdirs() }
    val libDir: File = File(ctx.filesDir, "lib").apply { mkdirs() }

    fun getPref(k: String, d: String): String = prefs.getString("p_$k", null) ?: d
    fun setPref(k: String, v: String) { prefs.edit().putString("p_$k", v).apply() }
    fun removePref(k: String) { prefs.edit().remove("p_$k").apply() }

    /** Encrypted settings: API keys, cloud accounts, activation. */
    fun secret(k: String, d: String = ""): String = Vault.decString(prefs.getString("s_$k", null)) ?: d
    fun setSecret(k: String, v: String) {
        if (v.isEmpty()) prefs.edit().remove("s_$k").apply() else prefs.edit().putString("s_$k", Vault.encString(v)).apply()
    }

    // ---------- API keys (all optional; entered in Settings → API keys) ----------
    fun tmdbToken() = secret("tmdbToken").trim()
    fun tmdbKey() = secret("tmdbKey").trim()
    fun hasTmdb() = tmdbToken().isNotEmpty() || tmdbKey().isNotEmpty()

    // ---------- online music info (Deezer / iTunes), per song id ----------
    private val metaFile = File(ctx.filesDir, "musicmeta.json")
    @Synchronized fun musicMeta(): JSONObject = try { Vault.readText(metaFile)?.let { JSONObject(it) } ?: JSONObject() } catch (_: Exception) { JSONObject() }
    @Synchronized fun saveMusicMeta(o: JSONObject) = Vault.writeText(metaFile, o.toString())
    @Synchronized fun updateMusicMeta(f: (JSONObject) -> Unit) { val o = musicMeta(); f(o); saveMusicMeta(o) }
    fun clearMusicMeta() { metaFile.delete(); mcoverDir.listFiles()?.forEach { it.delete() } }

    // ---------- album / artist info (Deezer / iTunes), by "album|artist" ----------
    private val albumFile = File(ctx.filesDir, "albuminfo.json")
    @Synchronized fun albumInfo(): JSONObject = try { Vault.readText(albumFile)?.let { JSONObject(it) } ?: JSONObject() } catch (_: Exception) { JSONObject() }
    @Synchronized fun updateAlbumInfo(f: (JSONObject) -> Unit) { val o = albumInfo(); f(o); Vault.writeText(albumFile, o.toString()) }

    // ---------- TMDB matches ----------
    private val tmdbFile = File(ctx.filesDir, "tmdb.json")
    @Synchronized fun tmdbCache(): JSONObject = try { Vault.readText(tmdbFile)?.let { JSONObject(it) } ?: JSONObject() } catch (_: Exception) { JSONObject() }
    @Synchronized fun saveTmdb(o: JSONObject) = Vault.writeText(tmdbFile, o.toString())
    @Synchronized fun updateTmdb(f: (JSONObject) -> Unit) { val c = tmdbCache(); f(c); Vault.writeText(tmdbFile, c.toString()) }
    fun clearTmdb() { tmdbFile.delete(); tmdbImgDir.listFiles()?.forEach { it.delete() } }

    // ---------- library index (one file per source: device, a picked folder, a cloud folder) ----------
    fun libFile(id: String) = File(libDir, "$id.json")
    fun readLibrary(id: String): String = Vault.readText(libFile(id)) ?: ""
    fun writeLibrary(id: String, json: String) = Vault.writeText(libFile(id), json)

    fun artFile(id: Long) = File(artDir, "$id.jpg")
}
