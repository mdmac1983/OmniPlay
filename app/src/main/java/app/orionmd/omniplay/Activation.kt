package app.orionmd.omniplay

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OrionMD activation gate — standard design v4 (ACTIVATION_KEY_GATE.md): exclusive claim + offline grace.
 *
 *  - Keys live in the one shared Google Sheet, checked by the shared Apps Script ([BACKEND_URL]).
 *    GET ?key=…&device=<ANDROID_ID>&app=<package> → OK / USED / INVALID / ERROR (plain text).
 *  - A key is claimed by the first app+device that uses it, permanently. ANDROID_ID differs per app, so a key used
 *    by any other OrionMD app (even on this phone) answers USED. That's intended.
 *  - TRIAL: starts automatically on first launch, [TRIAL_DAYS] days (OmniPlay uses 14 instead of the standard 72 h).
 *  - PENDING: a key entered while the backend can't be reached unlocks the app for up to [GRACE_DAYS] days while it's
 *    retried on every start; OK → ACTIVATED, INVALID/USED → back to TRIAL/LOCKED, never confirmed → LOCKED.
 *  - ACTIVATED: verified online once; no further checks. The owner password also activates (Guard).
 *  - LOCKED: trial over (or pending failed/expired) — key entry + Purchase Key only.
 * State is kept in the app's encrypted settings ([Store.secret]). The clock checks are a soft deterrent, not DRM.
 */
object Activation {
    /** The shared OrionMD activation backend (same deployment as Launcher and Digital Wallet). */
    const val BACKEND_URL = "https://script.google.com/macros/s/AKfycbyUNYqPBX7XBHBu19aHw25sumXoNA1kT9ohDdOKXjFcM77LauF3TrugqajntUddRC68/exec"
    const val APP_ID = "app.orionmd.omniplay"
    const val BUY_URL = "https://buymeacoffee.com/orionmd"
    const val TRIAL_DAYS = 14
    const val GRACE_DAYS = 14
    private const val DAY = 86_400_000L

    enum class State { TRIAL, PENDING, ACTIVATED, LOCKED }
    enum class Result { OK, INVALID, USED, OFFLINE, ERROR }

    // ---------- state ----------
    fun ensureTrialStarted(store: Store) {
        if (isActivated(store) || trialStart(store) > 0) return
        val now = System.currentTimeMillis()
        store.setSecret("trialStart", now.toString())
        try { Vault.writeText(java.io.File(store.filesDir, ".ts"), now.toString()) } catch (_: Exception) {}
    }

    /** First trial start (also kept in a second encrypted file, so one wiped setting doesn't restart it). */
    fun trialStart(store: Store): Long {
        val a = store.secret("trialStart").toLongOrNull() ?: 0L
        val b = try { Vault.readText(java.io.File(store.filesDir, ".ts"))?.toLongOrNull() } catch (_: Exception) { null } ?: 0L
        return listOf(a, b).filter { it > 0 }.minOrNull() ?: 0L
    }

    fun isActivated(store: Store) = store.secret("activated") == "1" || store.secret("ownerUnlocked") == "1"

    fun state(store: Store): State {
        if (isActivated(store)) return State.ACTIVATED
        val now = System.currentTimeMillis()
        if (store.secret("pendingKey").isNotEmpty()) {
            val since = store.secret("pendingSince").toLongOrNull() ?: 0L
            if (since in 1..now && now - since <= GRACE_DAYS * DAY) return State.PENDING
            clearPending(store)   // grace ran out (or clock rolled back) without ever confirming
        }
        val ts = trialStart(store)
        return if (ts in 1..now && now - ts < TRIAL_DAYS * DAY) State.TRIAL else State.LOCKED
    }

    fun trialDaysLeft(store: Store): Long {
        val ts = trialStart(store); val now = System.currentTimeMillis()
        if (ts <= 0 || now < ts) return 0
        return ((ts + TRIAL_DAYS * DAY - now + DAY - 1) / DAY).coerceAtLeast(0)
    }
    fun pendingDaysLeft(store: Store): Long {
        val s = store.secret("pendingSince").toLongOrNull() ?: return 0; val now = System.currentTimeMillis()
        if (now < s) return 0
        return ((s + GRACE_DAYS * DAY - now + DAY - 1) / DAY).coerceAtLeast(0)
    }
    fun holder(store: Store): String = when {
        store.secret("ownerUnlocked") == "1" -> "Owner"
        else -> store.secret("activationKey").let { if (it.length > 4) "key …" + it.takeLast(4) else it }
    }

    /** A message for the user about the last pending check that failed (shown once). */
    fun takeNotice(store: Store): String? = store.secret("notice").takeIf { it.isNotEmpty() }?.also { store.setSecret("notice", "") }

    private fun clearPending(store: Store) { store.setSecret("pendingKey", ""); store.setSecret("pendingSince", "") }
    private fun markActivated(store: Store, key: String) {
        store.setSecret("activated", "1"); store.setSecret("activationKey", key); clearPending(store)
    }
    fun unlockWithOwner(store: Store) { store.setSecret("ownerUnlocked", "1"); clearPending(store) }

    // ---------- backend ----------
    @SuppressLint("HardwareIds")
    private fun deviceId(ctx: Context) = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    /** Blocking network call — call from Dispatchers.IO. */
    fun verifyKey(ctx: Context, key: String): Result {
        if (BACKEND_URL.isBlank()) return Result.OFFLINE
        return try {
            val e = { s: String -> URLEncoder.encode(s, "UTF-8") }
            val c = URL("$BACKEND_URL?key=${e(key.trim().uppercase())}&device=${e(deviceId(ctx))}&app=${e(APP_ID)}").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true   // Apps Script answers via a redirect
            try {
                if (c.responseCode !in 200..299) return Result.ERROR
                when (c.inputStream.bufferedReader().use { it.readText() }.trim().uppercase()) {
                    "OK" -> Result.OK; "USED" -> Result.USED; "INVALID" -> Result.INVALID; else -> Result.ERROR
                }
            } finally { c.disconnect() }
        } catch (_: java.io.IOException) { Result.OFFLINE } catch (_: Exception) { Result.ERROR }
    }

    /**
     * A key typed by the user (blocking — call from Dispatchers.IO). Returns (unlocks the app?, message).
     * Unreachable backend → PENDING with the 14-day grace (a new key replaces any earlier pending one).
     */
    fun enterKey(ctx: Context, store: Store, key: String): Pair<Boolean, String> {
        val k = key.trim().uppercase(); if (k.isEmpty()) return false to "Enter your activation key."
        return when (verifyKey(ctx, k)) {
            Result.OK -> { markActivated(store, k); true to "Activated — thank you!" }
            Result.USED -> false to "That key has already been used on another device or app."
            Result.INVALID -> false to "That key isn't valid. Check it and try again."
            Result.OFFLINE, Result.ERROR -> {
                store.setSecret("pendingKey", k); store.setSecret("pendingSince", System.currentTimeMillis().toString())
                true to "No internet connection. You're provisionally activated for $GRACE_DAYS days — connect any time in that window to confirm your key permanently."
            }
        }
    }

    /** On every start while PENDING: quietly re-check the stored key (blocking — call from Dispatchers.IO). */
    fun retryPendingIfAny(ctx: Context, store: Store) {
        val k = store.secret("pendingKey"); if (k.isEmpty() || isActivated(store)) return
        when (verifyKey(ctx, k)) {
            Result.OK -> markActivated(store, k)
            Result.USED -> { clearPending(store); store.setSecret("notice", "Your activation key turned out to be already used on another device or app, so it couldn't be confirmed.") }
            Result.INVALID -> { clearPending(store); store.setSecret("notice", "Your activation key couldn't be confirmed — it isn't valid.") }
            else -> {}   // still offline: stay pending
        }
    }
}
