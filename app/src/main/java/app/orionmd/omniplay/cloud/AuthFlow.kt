package app.orionmd.omniplay.cloud

import android.security.keystore.KeyProperties
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * The browser OAuth sign-in (pCloud, Dropbox, Google Drive). It uses the loopback redirect served by [CloudProxy]:
 * the app opens the provider's sign-in page, the browser comes back to http://127.0.0.1:<port>/oauth?code=…,
 * and [await] picks that up and exchanges the code for tokens. PKCE is used so no provider needs a confidential secret
 * baked into the app (Google's "Desktop" client still expects its id + secret, entered in Settings).
 */
object AuthFlow {
    private val rng = SecureRandom()

    fun newVerifier(): String {
        val b = ByteArray(48); rng.nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance(KeyProperties.DIGEST_SHA256).digest(verifier.toByteArray())
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Build the sign-in URL for [provider] (returns null if its client id isn't set yet). */
    fun start(provider: CloudProvider, verifier: String): String? {
        val cid = Cloud.clientId(provider.id)
        if (provider.needsClientId && cid.isEmpty()) return null
        return provider.authUrl(cid, CloudProxy.oauthRedirect(), verifier)
    }

    /** Waits (up to [timeoutMs]) for the browser redirect, then finishes sign-in. Returns the account data or throws. */
    suspend fun await(provider: CloudProvider, verifier: String, timeoutMs: Long = 300_000): org.json.JSONObject {
        CloudProxy.lastOAuth = null
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            CloudProxy.lastOAuth?.let { params ->
                CloudProxy.lastOAuth = null
                params["error"]?.let { throw Exception("Sign-in was declined: $it") }
                val redirectUri = CloudProxy.oauthRedirect() + "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
                return provider.finishAuth(Cloud.clientId(provider.id), Cloud.clientSecret(provider.id), CloudProxy.oauthRedirect(), verifier, redirectUri)
            }
            kotlinx.coroutines.delay(400)
        }
        throw Exception("Timed out waiting for sign-in. Try again and finish in the browser.")
    }

    fun queryOf(uri: String): Map<String, String> {
        val q = uri.substringAfter('?', "")
        return q.split('&').mapNotNull { val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8") }.toMap()
    }
}
