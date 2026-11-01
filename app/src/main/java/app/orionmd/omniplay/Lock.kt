package app.orionmd.omniplay

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN lock: the whole app (asked when Media Hub opens, and again after it was in the background a while) and/or
 * chosen folders — a library source, a music / video folder or a gallery folder — that stay hidden everywhere until
 * "Show locked folders" is unlocked with the PIN. The PIN itself is never stored: only a salted PBKDF2 hash, encrypted.
 */
object Lock {
    /** The lock screen is up. */
    var locked by mutableStateOf(false)
    /** Locked folders are visible (unlocked with the PIN until the app locks again). */
    var privateOpen by mutableStateOf(false)
    /** Bumped when the locked-folder list changes. */
    var gen by mutableStateOf(0)

    private const val ITER = 20_000
    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    fun hasPin(store: Store) = store.secret("pinHash").isNotEmpty()
    fun setPin(store: Store, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.setSecret("pinSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
        store.setSecret("pinHash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
    }
    fun clearPin(store: Store) { store.setSecret("pinHash", ""); store.setSecret("pinSalt", ""); store.setPref("lockApp", "0") }

    private var fails = 0; private var waitUntil = 0L
    /** Seconds to wait after too many wrong PINs (0 = may try). */
    fun waitSeconds() = ((waitUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    fun check(store: Store, pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val salt = try { Base64.decode(store.secret("pinSalt"), Base64.NO_WRAP) } catch (_: Exception) { return false }
        val want = try { Base64.decode(store.secret("pinHash"), Base64.NO_WRAP) } catch (_: Exception) { return false }
        val ok = java.security.MessageDigest.isEqual(hash(pin, salt), want)
        if (ok) fails = 0 else if (++fails >= 5) { waitUntil = System.currentTimeMillis() + 30_000L * (fails - 4); }
        return ok
    }

    // ---------- the app lock ----------
    fun appLockOn(store: Store) = hasPin(store) && store.getPref("lockApp", "0") == "1"
    /** Seconds in the background before the PIN is asked again. */
    fun after(store: Store) = store.getPref("lockAfter", "60").toLongOrNull() ?: 60
    private var leftAt = 0L
    /** Media Hub itself opened something (folder picker, a system confirmation, the phone's lock): not "leaving". */
    @Volatile var ownTrip = false
    fun onLeave() { if (!ownTrip) leftAt = System.currentTimeMillis() }
    /** Coming back (or starting): lock again when away long enough; locked folders hide again too. */
    fun onReturn(store: Store, starting: Boolean) {
        ownTrip = false
        val away = leftAt > 0 && System.currentTimeMillis() - leftAt >= after(store) * 1000
        leftAt = 0
        if (starting || away) { if (appLockOn(store)) locked = true; privateOpen = false }
    }

    // ---------- locked folders ----------
    /** "src:<source id>", "dir:<folder path>" (music / video folder) or "bucket:<gallery folder id>". */
    fun folders(store: Store): Set<String> = try { JSONArray(store.getPref("lockedFolders", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (_: Exception) { emptySet() }
    fun setLocked(store: Store, key: String, on: Boolean) {
        val s = folders(store).toMutableSet(); if (on) s += key else s -= key
        store.setPref("lockedFolders", JSONArray(s.toList()).toString()); gen++
    }
    fun isLocked(store: Store, key: String) = key in folders(store)

    /** Whether a library item is in a locked source or folder (and they aren't shown right now). */
    fun hides(locked: Set<String>, x: Item): Boolean {
        if (locked.isEmpty()) return false
        if ("src:${x.src}" in locked) return true
        return locked.any { k -> k.startsWith("dir:") && k.substring(4).let { p -> x.folder == p || x.folder.startsWith("$p/") } }
    }
}
