package app.orionmd.omniplay.cloud

import android.content.Context
import app.orionmd.omniplay.Store
import app.orionmd.omniplay.Vault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** A file or folder in a cloud account. [id] is whatever the provider addresses it by (id or path). */
data class CloudFile(val id: String, val name: String, val isDir: Boolean, val size: Long = 0, val modified: Long = 0)

/** An open byte stream for playback/download: the bytes, the total length, and the MIME type when known. */
class CloudStream(val input: InputStream, val length: Long, val contentType: String?) : AutoCloseable {
    override fun close() { try { input.close() } catch (_: Exception) {} }
}

/** A connected cloud account. [data] holds provider-specific secrets (tokens, keys) — stored encrypted. */
data class CloudAccount(val id: String, val provider: String, val label: String, val data: JSONObject) {
    fun json(): JSONObject = JSONObject().put("id", id).put("provider", provider).put("label", label).put("data", data)
    companion object { fun of(o: JSONObject) = CloudAccount(o.optString("id"), o.optString("provider"), o.optString("label"), o.optJSONObject("data") ?: JSONObject()) }
}

/**
 * A cloud storage backend. Everything the rest of the app needs: list a folder, open a file for streaming (with a byte
 * offset, so the players can seek), and download a small sidecar (subtitle / .lrc). OAuth providers also build the sign-in
 * URL and finish sign-in from the redirect. End-to-end-encrypted providers (Mega, Filen) decrypt inside [open].
 */
interface CloudProvider {
    val id: String
    val name: String
    /** "oauth" (sign in through the browser), "password" (email + password), or "webdav" (server URL + user + password). */
    val auth: String
    val rootId: String get() = ""
    /** Human help shown on the connect screen, and the link to open. */
    val help: String
    val signupUrl: String get() = ""

    fun list(acct: CloudAccount, folderId: String): List<CloudFile>
    fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream

    fun download(acct: CloudAccount, fileId: String, dest: File): Boolean {
        return try { open(acct, fileId, 0).use { s -> Vault.writeBytes(dest, s.input.readBytes()) }; true } catch (_: Exception) { false }
    }

    // --- OAuth providers ---
    /** Needs the user's own OAuth client id (and sometimes secret), entered in Settings. */
    val needsClientId: Boolean get() = false
    val needsClientSecret: Boolean get() = false
    fun authUrl(clientId: String, redirect: String, verifier: String): String = ""
    /** Finishes sign-in from the redirect URI; returns the account data (tokens) or throws. */
    fun finishAuth(clientId: String, clientSecret: String, redirect: String, verifier: String, redirectUri: String): JSONObject = JSONObject()
    /** Password / WebDAV sign-in; returns account data or throws. */
    fun signIn(fields: Map<String, String>): JSONObject = JSONObject()
}

/**
 * The cloud layer: the provider registry, the connected accounts (saved encrypted), and the download helpers the
 * players and sync use. Cloud items carry a URI "omni://<accountId>/<fileId>"; [CloudProxy] turns that into a local
 * HTTP URL ExoPlayer can stream and seek.
 */
object Cloud {
    const val REDIRECT = "omniplay://oauth"

    val PROVIDERS: List<CloudProvider> by lazy { listOf(PCloud, Dropbox, GDrive, Mega, Filen, ProtonDrive, WebDav) }
    fun provider(id: String): CloudProvider? = PROVIDERS.firstOrNull { it.id == id }

    private lateinit var store: Store
    @Volatile private var accounts: MutableList<CloudAccount> = mutableListOf()

    fun init(store: Store) {
        this.store = store
        accounts = try {
            JSONArray(store.secret("cloudAccounts", "[]")).let { a -> (0 until a.length()).map { CloudAccount.of(a.getJSONObject(it)) }.toMutableList() }
        } catch (_: Exception) { mutableListOf() }
    }

    fun accounts(): List<CloudAccount> = accounts.toList()
    fun account(id: String): CloudAccount? = accounts.firstOrNull { it.id == id }
    private fun persist() { store.setSecret("cloudAccounts", JSONArray(accounts.map { it.json() }).toString()) }

    fun add(provider: String, label: String, data: JSONObject): CloudAccount {
        val acct = CloudAccount("c" + System.currentTimeMillis().toString(36), provider, label, data)
        accounts.add(acct); persist(); return acct
    }
    fun update(id: String, data: JSONObject) { accounts.replaceAll { if (it.id == id) it.copy(data = data) else it }; persist() }
    fun remove(id: String) { accounts.removeAll { it.id == id }; persist() }

    // ---------- OAuth client ids / secrets (the user's own, entered in Settings) ----------
    fun clientId(provider: String) = store.secret("cid_$provider").trim()
    fun setClientId(provider: String, v: String) = store.setSecret("cid_$provider", v.trim())
    fun clientSecret(provider: String) = store.secret("csec_$provider").trim()
    fun setClientSecret(provider: String, v: String) = store.setSecret("csec_$provider", v.trim())

    fun list(acct: CloudAccount, folderId: String): List<CloudFile> =
        provider(acct.provider)?.list(acct, folderId.ifEmpty { provider(acct.provider)!!.rootId }) ?: emptyList()

    fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream =
        provider(acct.provider)!!.open(acct, fileId, offset)

    /** Downloads a cloud file (or sidecar) to [dest], encrypted. */
    fun download(acct: CloudAccount, fileId: String, dest: File): Boolean =
        provider(acct.provider)?.download(acct, fileId, dest) ?: false

    /** Resolve an "omni://acct/file" URI into (account, fileId). */
    fun resolve(uri: String): Pair<CloudAccount, String>? {
        if (!uri.startsWith("omni://")) return null
        val rest = uri.removePrefix("omni://")
        val acctId = rest.substringBefore('/'); val fileId = rest.substringAfter('/')
        val acct = account(acctId) ?: return null
        return acct to java.net.URLDecoder.decode(fileId, "UTF-8")
    }
    fun uriFor(acctId: String, fileId: String) = "omni://$acctId/" + java.net.URLEncoder.encode(fileId, "UTF-8")
}
