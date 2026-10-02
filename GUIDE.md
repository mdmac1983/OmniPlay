# OmniPlay — User Guide

**OmniPlay** by OrionMD is a standalone, **offline-first hybrid music and video player** for Android. It plays everything on
your phone, in folders you choose, and in your cloud storage — and can download album art, lyrics, movie info and
subtitles so your library looks complete even with no internet.

- **App ID:** `app.orionmd.omniplay` · Android 8.0+ (API 26)
- **Get it:** GitHub → *Releases* → `OmniPlay-vX.Y.apk` (each update installs over the last one and keeps your data)

---

## Contents
1. [Getting started](#1-getting-started)
2. [Trial & activation](#2-trial--activation)
3. [Screens at a glance](#3-screens-at-a-glance)
4. [Music player](#4-music-player)
5. [Video player](#5-video-player)
6. [Podcasts](#6-podcasts)
7. [Offline: Sync all & downloads](#7-offline-sync-all--downloads)
8. [Cloud storage](#8-cloud-storage)
9. [What works with / without internet and API keys](#9-what-works-with--without-internet-and-api-keys)
10. [Getting the free API keys](#10-getting-the-free-api-keys)
11. [Privacy & security](#11-privacy--security)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Getting started
1. Install the APK. On first launch you'll see the OmniPlay splash, then the welcome screen.
2. Tap **Start 14-day free trial** (or enter an activation key).
3. Allow access to your music and videos when asked. **This device** is added automatically — your songs and videos
   appear in the **Music** and **Video** tabs.
4. Optional: add more sources in **Settings → Your library sources** (a folder, SD card, or cloud account).
5. Optional: add free API keys in **Settings → API keys** for movie/TV info, podcasts and online subtitles.

---

## 2. Trial & activation
| State | What you get |
|---|---|
| **Free trial** | Everything, free for **14 days** — starts automatically the first time you open the app. Days left show on the Dashboard and in Settings. |
| **Pending** | You entered a key with no internet. The app works fully for up to **14 days** and quietly confirms the key next time you're online. |
| **Activated** | Full version, no time limit. Checked online once, never again. |
| **Locked** | The trial ended (or a pending key turned out invalid / already used). Your library, playlists and downloads are **kept** and return as soon as you activate. |

- **Buy a key:** [buymeacoffee.com/orionmd](https://buymeacoffee.com/orionmd).
- **Enter a key:** the Dashboard card, **Settings → Activation & security**, or the Locked screen (**Verify**).
- **One key = one app on one device.** The first app + phone to use a key claims it permanently; using it on another
  phone, or in another OrionMD app, answers "already used".
- Setting the phone's clock back doesn't extend the trial.

---

## 3. Screens at a glance
| Tab | What's there |
|---|---|
| **Home** (Dashboard) | Library counts, trial status, **Sync all** for music & videos (with progress), Continue watching, Most played, Recently added, podcast episodes saved offline, shortcuts. |
| **Music** | Songs, Albums, Artists, Genres, Playlists, Folders · search · Discover (previews) · Podcasts · Sync all · rescan. |
| **Video** | All, Movies, TV (grouped by show), Music videos · search · Sync all · rescan. |
| **Gallery** | Every photo and video on the phone: timeline, albums, groups, viewer, editor, collages, GIFs, long exposure, stickers, trash. |
| **Settings** | Sources, cloud accounts, API keys, library tools, sync, playback & subtitles, appearance, activation. |

The OrionMD artwork appears as the launch splash and as a subtle background watermark (strength adjustable or off).

---

## 4. Music player
- **Playback:** queue, shuffle, repeat (off / all / one), mini player, full Now Playing screen, lock-screen and
  notification controls, keeps playing in the background, pauses when headphones are unplugged.
- **Sleep timer:** 5 min – 2 h, or "end of this song"; fades out gently.
- **Sound engine:** 10-band equaliser with 18 presets, headphone correction for specific models, bass, treble, vocal
  boost, stereo width, virtual surround, crossfeed, reverb (room / hall / stadium), volume leveller, volume boost and a
  soft limiter. **Sound profiles** (Speaker, Headphones, Earbuds, Bluetooth, Custom) can switch automatically when you
  plug in or connect Bluetooth.
- **Visualizers:** dozens of styles (bars, waves, circular, digital gauges, dashboards) driven by the real audio; full
  screen, behind lyrics, or mini bars.
- **Skins:** several looks for the Now Playing screen.
- **Lyrics:** time-synced (follows the song, tap a line to jump) or plain. Uses `.lrc` files next to your songs first,
  then LRCLIB online. Saved for offline.
- **Song info & album art:** genre, year, length and covers from Deezer, with iTunes as backup. Songs without an artist
  tag are read as **"Artist - Title"** from the title or file name.
- **Albums & artists:** album pages show the full official track list with the songs you own ticked (preview the rest
  when online); artist pages list their albums.
- **Playlists:** your own, plus automatic *Most played* and *Recently added*.

---

## 5. Video player
- Plays MP4, MKV, AVI, MOV, WebM and more (Media3 / ExoPlayer).
- **Gestures:** swipe left side for brightness, right side for volume, sideways to scrub, double-tap to skip.
- **Controls:** speed (0.5×–2×), picture size (fit / fill / zoom / stretch), rotation lock, **picture-in-picture**,
  screen lock, sleep timer, A-B repeat, repeat one, volume boost, screenshot, skip intro.
- **Resume** where you left off, **autoplay** the next episode/video, **Continue watching** on the Dashboard.
- **Subtitles:** files next to the video (`.srt`, `.vtt`, `.ass`, `.ssa`, e.g. `Movie.en.srt`) plus subtitles
  downloaded by Sync all. Choose size, colour, background and position.
- **TMDB:** posters, backdrops, rating, genres, runtime, overview, cast with photos, trailers and clips, TV seasons and
  episodes. **Fix match** if a title is wrong.

---

## 6. Podcasts
- Search podcasts (or music podcasts), see what's trending, subscribe.
- Play episodes in the music player (with visualizer and sleep timer).
- **Download** any episode, or **Download latest 5** for a show. Saved episodes appear under **Saved for offline**
  (Podcasts page and Dashboard) and play with no connection. Remove them any time.

---

## 6b. Gallery
- **Timeline:** every photo and video, grouped by **day, month or year**.
- **Albums:** your phone's folders plus **your own albums** (select → Add to album). Long-press an album for **Set cover
  image**, **Add to a group**, Share, Rename / Delete (own albums).
- **Groups:** combine several albums into one, e.g. "Holidays" = Beach + Camera + WhatsApp Images.
- **Viewer:** swipe, pinch / double-tap to zoom, **Edit, Rotate, Share, Set as wallpaper** (home, lock or both),
  add to album, set as album cover, info, trash. Videos play in the video player.
- **Editor** (always saves a copy): crop with aspect presets, rotate, flip, brightness / contrast / saturation / warmth,
  10 filters, undo, and **AI Eraser** — paint over people, objects or text and tap **Erase**; an AI model (MI-GAN) rebuilds the background. The first time, tap **Get AI Eraser** to download the model once (~28 MB, internet needed); after that it runs fully offline on the phone. **Basic** is a quick non-AI fill for small spots.
- **Stickers:** press and hold on a photo — the main subject is cut out on your phone (Google ML Kit) and saved as a
  transparent sticker in Pictures/OmniPlay/Stickers.
- **Create:** **Collage** (2–9 photos, layouts, shapes, spacing, rounded corners, background), **Animated GIF** (from
  photos or part of a video; frames, speed, size), **Long exposure** (blend a video or burst into one shot).
- **Trash:** deleted items wait 30 days in Android's trash (Android 11+) — restore or delete for good any time.
- Coming next: Movies & Reels editor, and AI object moving, colorization and reflection removal.

---

## 7. Offline: Sync all & downloads
OmniPlay is built to work without internet. Two kinds of "offline":

**Your media** — songs and videos on the phone or in a picked folder always play offline. Cloud songs/videos and podcast
episodes play offline once **downloaded** (Download button on songs, albums, videos and episodes; manage them under
*Offline* on the Dashboard or Settings → Library tools).

**Your library info** — use **Sync all** (Dashboard, the download icon on the Music/Video tabs, or Settings → Library
tools) to save everything the app normally fetches online:

| Sync all — Music | Sync all — Videos |
|---|---|
| Song info (genre, year, length) | TMDB match + full details |
| Album-art images + covers inside your files | Posters, backdrops, cast photos |
| Lyrics (time-synced when available) | Trailers / media info, TV season & episode info |
| Album details & full track lists | Subtitles (needs a Wyzie key; pick the language) |

Saved items are skipped next time, so re-running only picks up new files. **Automatic look-ups** (on by default) also
fetch song info and TMDB matches for new items as soon as they're added — turn off in Settings → Library tools.

---

## 8. Cloud storage
Connect in **Settings → Cloud accounts**, then pick a folder to add. Cloud files stream with seeking, can be downloaded
for offline, and folders can be kept in **background sync** (schedule and Wi-Fi-only in Settings → Sync).

| Service | How it connects | What you need |
|---|---|---|
| **pCloud** | Sign in through the browser | Your own free pCloud app's Client ID + secret |
| **Dropbox** | Sign in through the browser | Your own free Dropbox app's App key |
| **Google Drive** | Sign in through the browser | Your own Google Cloud OAuth "Desktop app" Client ID + secret |
| **MEGA** | Email + password | Nothing else (files are decrypted on your phone; accounts with 2FA aren't supported) |
| **Filen** | WebDAV | Turn on WebDAV in the Filen desktop app or run the Filen CLI |
| **Proton Drive** | WebDAV bridge | Run `rclone serve webdav` with rclone's Proton Drive remote on a computer |
| **WebDAV** (Nextcloud, ownCloud, NAS, Koofr, Box, Yandex…) | URL + user + password | Your server details (an app password if offered) |

Each provider shows step-by-step instructions and the redirect address to register. Tip: you can also add a cloud app's
**sync folder** on your phone as a normal folder (Add folder) for pure offline use.

---

## 9. What works with / without internet and API keys

### Without internet
| Works | Needs internet |
|---|---|
| Playing music and videos on the device or in added folders | Streaming from the cloud (unless downloaded) |
| Downloaded cloud files and podcast episodes | Podcast search, trending and new episodes |
| Sound engine, visualizers, skins, playlists, sleep timer | Discover previews |
| Everything saved by **Sync all**: album art, song info, lyrics, album details, TMDB details, posters, cast, episode info, subtitles | First-time look-ups for new songs/videos (they fill in next time you're online) |
| Subtitle and `.lrc` files next to your media | Running Sync all |
| Entering a key (held as *Pending* for 14 days until you're online) | Confirming a key, buying a key |

### Without any API keys
Everything above **plus**, when online: song info and album art (Deezer/iTunes), lyrics (LRCLIB), album pages and track
lists, Discover previews, music-video info, MEGA and WebDAV clouds. **No key is needed for any music feature.**

### What each optional key adds
| Key | Unlocks | Without it |
|---|---|---|
| **TMDB** (token or v3 key) | Movie & TV posters, details, cast, trailers, episode info, Fix match, video Sync all | Videos play normally with a frame thumbnail and file name |
| **Podcast Index** (key + secret) | Podcasts tab: search, trending, episodes | Saved (downloaded) episodes still play; no new search |
| **Wyzie Subs** | Download subtitles by TMDB match (needs TMDB too) | Use subtitle files next to your videos |
| **pCloud / Dropbox / Google Drive** app keys | Connecting those clouds | Use MEGA, WebDAV, or a sync folder |

---

## 10. Getting the free API keys
All free; paste them in **Settings → API keys** and tap **Save** (they're stored encrypted). Saving a TMDB key tests it
and starts matching your videos straight away.

- **TMDB** — sign up at themoviedb.org → profile → **Settings → API** → request a key (choose *Developer*, personal use)
  → copy the **API Read Access Token** (or the v3 API key).
- **Podcast Index** — api.podcastindex.org → sign up with your email → the **API key** and **secret** are emailed /
  shown on the dashboard. Enter both.
- **Wyzie Subs** — sub.wyzie.io → get a free key.
- **pCloud** — pcloud.com → Developers → My applications → new app → add the redirect shown in the app → copy
  Client ID and Client secret.
- **Dropbox** — dropbox.com/developers → Create app → Scoped access, Full Dropbox → Permissions: `files.metadata.read`,
  `files.content.read` → add the redirect shown in the app → copy the App key.
- **Google Drive** — console.cloud.google.com → new project → enable **Google Drive API** → OAuth consent screen (add
  yourself as a test user) → Credentials → OAuth client ID → **Desktop app** → copy Client ID and secret.

---

## 11. Privacy & security
- No account, ads or tracking. Your library stays on your phone.
- Caches, API keys, cloud sign-ins and the activation key are encrypted on the device (AES-256-GCM, key held by the
  Android Keystore).
- Activation sends only the key, this app's device ID and the app name to the OrionMD activation server.
- Online look-ups send only what's needed to find a match (artist/title or movie name) to Deezer, iTunes, LRCLIB,
  TMDB, Podcast Index or Wyzie.
- OmniPlay is proprietary and release-signed by OrionMD. It checks its own signature at launch; a modified or re-signed
  copy locks itself behind the owner password.

---

## 12. Troubleshooting
| Problem | Fix |
|---|---|
| "Unknown artist" / no album art | Be online; info fills in automatically, or run **Sync all** for Music. Files named `Artist - Title` work best. |
| No movie posters/info | Add a TMDB key and tap **Save** — it says "TMDB key works ✓", then matches your videos. Use **Fix match** for wrong titles. |
| Podcasts asks for keys | Enter **both** Podcast Index key and secret and tap **Save** on each. |
| No subtitles downloaded | Needs both a TMDB key and a Wyzie key; set the language in Settings → Library tools, then run video **Sync all**. |
| Update says to uninstall first | Install releases from GitHub *Releases* in order; don't mix in older test builds (uninstalling erases your data). |
| Cloud won't connect | Check the Client ID/secret and that the redirect address shown in the app is registered with the provider. |
| Trial ended | Buy a key at buymeacoffee.com/orionmd and enter it — nothing is lost. |
| Key says "already used" | Each key works for one app on one device. Use a new key, or contact OrionMD. |
| "Pending verification" | Connect to the internet and reopen the app — the key confirms automatically. |
