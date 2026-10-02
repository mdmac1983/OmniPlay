# OmniPlay (app.orionmd.omniplay)

A standalone, offline-first **hybrid music & video player** by OrionMD — the music and video engines from the
Media Library app, rebuilt as their own app with first-class cloud support.

See **GUIDE.md** for the full user guide and **FEATURES.md** for the feature list.

## Build & release
This is a **private** repo and every push builds a **signed release APK** (the signing keystore is committed in
`keystore/`). GitHub Actions bumps the version, tags `vX.Y`, and publishes `OmniPlay-vX.Y.apk` under **Releases** —
install it straight on your phone; it updates over the previous version.

## First run
1. Install the APK — a 14-day free trial starts automatically; enter an activation key any time (Settings → Activation & security).
2. Add sources in **Settings → Your library sources**: *This device*, *Add folder*, or a **cloud account**.
3. Add free **API keys** in Settings for the extras you want (TMDB for movies/TV, Podcast Index for podcasts). Each
   field has in-app instructions.

## Clouds
Connect in **Settings → Cloud accounts**:
- **pCloud, Dropbox, Google Drive** — sign in through the browser. You create your own free OAuth app once and paste
  its Client ID (and secret where needed); the redirect URI to register is shown in the app.
- **MEGA** — email + password; files are end-to-end decrypted on the device.
- **Filen, Proton Drive** — connected over WebDAV (the app shows how to run the bridge); any **WebDAV** server
  (Nextcloud, ownCloud, a NAS) works directly.

## Activation (OrionMD standard gate v4)
OmniPlay uses the shared OrionMD activation backend (Google Sheet + Apps Script, same deployment as Launcher and
Digital Wallet). See `Activation.kt`.
- **Trial:** 14 days, starts automatically on first launch.
- **Keys:** from the shared key pool, sold at buymeacoffee.com/orionmd. A key is claimed by the first app + device that
  uses it — permanently; any other device or OrionMD app gets `USED`.
- **Offline:** a key entered with no connection unlocks the app as *Pending* for up to 14 days while it's re-checked on
  every start; confirmed → Activated, invalid/used → back to Trial/Locked.
- **Locked:** after the trial (or a failed pending key) — enter a key or Purchase Key. The owner password also unlocks.

## Security notes
- The release is signed with `keystore/release.keystore` (baked in on purpose — private repo).
- The app checks its own signing certificate at launch; a re-signed/modified copy is locked behind the owner password
  and wipes its own app data after 3 wrong attempts. Nothing outside the app is touched.
- The owner-password verifier and the integrity certificate hash live in `Guard.kt`; the activation backend URL in `Activation.kt`.
