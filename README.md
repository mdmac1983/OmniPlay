# OmniPlay (app.orionmd.omniplay)

A standalone, offline-first **hybrid music & video player** by OrionMD — the music and video engines from the
Media Library app, rebuilt as their own app with first-class cloud support.

See **FEATURES.md** for the full list.

## Build & release
This is a **private** repo and every push builds a **signed release APK** (the signing keystore is committed in
`keystore/`). GitHub Actions bumps the version, tags `vX.Y`, and publishes `OmniPlay-vX.Y.apk` under **Releases** —
install it straight on your phone; it updates over the previous version.

## First run
1. Install the APK and enter your **activation key** (mint one with `scripts/mint_key.py`, or use the owner password).
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

## Activation keys
Keys are signed by the owner's private key and verified against the public key baked into the app.
Mint one on any machine with the owner password:
```
pip install cryptography
python3 scripts/mint_key.py --sub "My phone"          # never expires
python3 scripts/mint_key.py --sub "Trial" --days 30   # time-limited
```
Paste the printed key on the app's activation screen.

## Security notes
- The release is signed with `keystore/release.keystore` (baked in on purpose — private repo).
- The app checks its own signing certificate at launch; a re-signed/modified copy is locked behind the owner password
  and wipes its own app data after 3 wrong attempts. Nothing outside the app is touched.
- Owner password, activation public key and the integrity certificate hash live in `Guard.kt` / `Activation.kt`.
