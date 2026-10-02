package app.orionmd.omniplay

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Activation keys (licensing). OmniPlay only runs once it's been activated with a key signed by the owner. A key is
 *   <payload-base64url>.<signature-base64url>
 * where the payload is small JSON (who it's for, an optional expiry) and the signature is ECDSA P-256 / SHA-256 made with
 * the owner's private key. The app verifies it against the public key baked in below — so keys can be minted offline and
 * can't be forged. The owner password unlocks the app without a key. Keys are minted with scripts/mint_key.py.
 */
object Activation {
    // Owner's activation public key (EC P-256, X.509 SubjectPublicKeyInfo, base64).
    private const val PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5+fDwVP7Y+ZrGSVPRuxd3ARfwy+T6qQEnUTyi7N5/LVNBJLkvfYi3WAKkbLU8nKJ22F92iX+pQt0+1qfiP7RqQ=="

    fun isActivated(store: Store): Boolean {
        if (store.secret("ownerUnlocked") == "1") return true
        val key = store.secret("activationKey"); if (key.isEmpty()) return false
        return check(key).first
    }
    fun holder(store: Store): String = try { store.secret("activationKey").takeIf { it.isNotEmpty() }?.let { JSONObject(String(b64u(it.substringBefore(".")))).optString("sub") } ?: (if (store.secret("ownerUnlocked") == "1") "Owner" else "") } catch (_: Exception) { "" }

    /** Returns (valid, message). Checks the signature and the expiry. */
    fun check(key: String): Pair<Boolean, String> {
        return try {
            val parts = key.trim().split("."); if (parts.size != 2) return false to "That doesn't look like an activation key."
            val payload = b64u(parts[0]); val sig = b64u(parts[1])
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUB, Base64.DEFAULT)))
            val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(payload) }
            if (!v.verify(sig)) return false to "Invalid activation key."
            val o = JSONObject(String(payload))
            val exp = o.optLong("exp", 0)
            if (exp in 1..(System.currentTimeMillis() / 1000)) return false to "This activation key expired."
            true to ("Activated" + o.optString("sub").let { if (it.isNotEmpty()) " for $it" else "" } + ".")
        } catch (_: Exception) { false to "Couldn't read that activation key." }
    }

    // ---------- 14-day free trial (no key) ----------
    const val TRIAL_DAYS = 14
    const val BUY_URL = "https://buymeacoffee.com/orionmd"
    private const val DAY = 86_400_000L

    /** When the trial started (ms), 0 = never. Kept encrypted, with a second copy so one wiped setting doesn't reset it. */
    fun trialStart(store: Store): Long {
        val a = store.secret("trialStart").toLongOrNull() ?: 0L
        val b = try { Vault.readText(java.io.File(store.filesDir, ".ts"))?.toLongOrNull() } catch (_: Exception) { null } ?: 0L
        return listOf(a, b).filter { it > 0 }.minOrNull() ?: 0L
    }
    fun startTrial(store: Store) {
        if (trialStart(store) > 0) return
        val now = System.currentTimeMillis()
        store.setSecret("trialStart", now.toString())
        try { Vault.writeText(java.io.File(store.filesDir, ".ts"), now.toString()) } catch (_: Exception) {}
        noteClock(store)
    }
    /** Remembers the latest time seen, so setting the phone's clock back doesn't extend the trial. */
    fun noteClock(store: Store) {
        val now = System.currentTimeMillis()
        if (now > (store.secret("clockMax").toLongOrNull() ?: 0L)) store.setSecret("clockMax", now.toString())
    }
    private fun clockRolledBack(store: Store): Boolean = System.currentTimeMillis() < (store.secret("clockMax").toLongOrNull() ?: 0L) - DAY
    /** Whole days left (0 when expired or never started). */
    fun trialDaysLeft(store: Store): Int {
        val s = trialStart(store); if (s <= 0) return 0
        if (clockRolledBack(store)) return 0
        val left = s + TRIAL_DAYS * DAY - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
    }
    fun trialActive(store: Store): Boolean { if (trialDaysLeft(store) > 0) { noteClock(store); return true }; return false }
    /** Licensed with a key or the owner password (not just on trial). */
    fun isLicensed(store: Store) = isActivated(store)

    fun activate(store: Store, key: String): Pair<Boolean, String> {
        val r = check(key); if (r.first) store.setSecret("activationKey", key.trim()); return r
    }
    fun unlockWithOwner(store: Store) { store.setSecret("ownerUnlocked", "1") }

    private fun b64u(s: String): ByteArray { var t = s.replace('-', '+').replace('_', '/'); while (t.length % 4 != 0) t += "="; return Base64.decode(t, Base64.NO_WRAP) }
}
