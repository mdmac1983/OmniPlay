# Media Hub — Owner's Guide

**Media Hub** by OrionMD is an **offline-first music, video, podcast and photo app** for Android. It plays everything
on your phone, in folders you choose and in your cloud storage. It can also save album art, lyrics, movie info and
subtitles, so your library looks complete even with no internet.

- **App ID:** `app.orionmd.omniplay` · Android 8.0+ (API 26)
- **Version:** shown under the logo on the splash screen and at the bottom of **Settings**
- **Updates:** GitHub → *Releases* → `MediaHub-vX.Y.apk`. Each release installs over the last one and keeps your data.

---

## Contents
1. [Getting started](#1-getting-started)
2. [Trial, activation & owner unlock](#2-trial-activation--owner-unlock)
3. [Screens at a glance](#3-screens-at-a-glance)
4. [Your library](#4-your-library)
5. [Music player](#5-music-player)
6. [Sound engine](#6-sound-engine)
7. [Visualizers](#7-visualizers)
8. [Skins](#8-skins)
9. [Video player](#9-video-player)
10. [Podcasts](#10-podcasts)
11. [Gallery](#11-gallery)
12. [Offline: downloads & Sync all](#12-offline-downloads--sync-all)
13. [Cloud storage](#13-cloud-storage)
14. [API keys (optional, free)](#14-api-keys-optional-free)
15. [Online vs offline](#15-online-vs-offline)
16. [Settings map](#16-settings-map)
17. [Privacy & security](#17-privacy--security)
18. [Troubleshooting](#18-troubleshooting)

---

## 1. Getting started
1. Install the APK from GitHub *Releases*. On first launch you'll see the splash screen, then the welcome screen.
2. Tap **Start 14-day free trial**, or enter an activation key.
3. Allow access to your music, videos and photos when asked. **This device** is added automatically, so your songs
   and videos appear in the **Music** and **Video** tabs.
4. Optional: add more sources in **Settings → Your library sources** (a folder, an SD card or a cloud account).
5. Optional: add free API keys in **Settings → API keys** for movie and TV info, podcasts and online subtitles.

> Always install the release builds from GitHub. A test build signed differently makes Android ask you to uninstall
> first, and uninstalling erases your data.

---

## 2. Trial, activation & owner unlock
| State | What you get |
|---|---|
| **Free trial** | Everything, free for **14 days**, starting the first time you open the app. Days left show on the Dashboard and in Settings. |
| **Pending** | You entered a key with no internet. The app works fully for up to **14 days** and confirms the key the next time you're online. |
| **Activated** | Full version with no time limit. The key is checked online once and never again. |
| **Owner** | Unlocked with the owner password. Full version, no key needed. |
| **Locked** | The trial ended, or a pending key turned out invalid or already used. Your library, playlists and downloads are **kept** and come back as soon as you activate. |

- **Buy a key:** [buymeacoffee.com/orionmd](https://buymeacoffee.com/orionmd), or **Purchase Key** in the app.
- **Enter a key:** on the Dashboard card, in **Settings → Activation & security**, or on the Locked screen (**Verify**).
- **One key = one app on one device.** The first app and phone to use a key claim it permanently.
- Setting the phone's clock back doesn't extend the trial.

**Owner unlock.** On the Locked screen, tap the small **Owner unlock** link at the bottom and enter the owner
password. Media Hub stores only a one-way check of the password (PBKDF2), never the password itself.

**Tamper lock.** Media Hub checks at launch that it is the genuine OrionMD release build. A modified or re-signed copy
opens only with the owner password. After **3 wrong attempts**, that copy erases its own data. Your media files are
never touched.

---

## 3. Screens at a glance
| Tab | What's there |
|---|---|
| **Home** (Dashboard) | Library counts, trial status, **Sync all** with progress, Continue watching, Most played, **Recently added music / videos**, podcast episodes saved offline and shortcuts. |
| **Music** | Songs, Albums, Artists, Genres, Playlists and Folders, plus search, Discover (Deezer previews, buy links, free downloads), Podcasts, Sync all and rescan. |
| **Video** | All, Movies, TV (grouped by show) and Music videos, plus search, network streams, Sync all and rescan. |
| **Gallery** | Every photo and video on the phone: timeline, albums, groups, viewer, editor, creative tools and trash. |
| **Settings** | Sources, cloud accounts, API keys, library tools, sync, playback & subtitles, sound, appearance and activation. The **Guide** and **Features** links are at the bottom. |

**Tapping the playback notification** (or the lock-screen player) always opens the Now Playing screen.

---

## 4. Your library
- **Sources:** this device, any folder or SD card (**Add folder**), and cloud folders (**Add cloud**). Remove a source
  in Settings. Only Media Hub's index is removed; your files aren't touched.
- **Sorting:** the chips above the song list sort **A–Z, Recently added, Artist or Album**. Videos sort **A–Z,
  Recently added or Year**. The choice is remembered.
- **Recently added** uses the date each file first appeared in your library. On the Dashboard and in the automatic
  playlist, the newest additions come first.
- **Song info & album art:** genre, year, length and covers come from Deezer, with iTunes as a backup. Songs without an
  artist tag are read as **"Artist - Title"** from the title or file name.
- **Albums & artists:** album pages show the full official track list, with the songs you own ticked. You can preview
  the rest when online. Artist pages list their albums.
- **Playlists:** your own, plus the automatic *Most played* and *Recently added*.

### Discover: find new music
Open **Discover** from the search icon on the Music tab. It has two halves:
- **Deezer:** search **Songs** or **Albums** and play 30-second previews (an album opens its track list). Tap **⋯**
  (or **Get the album**) to get the full song or album:
  - **Open in Deezer**, if you have a subscription;
  - or buy it as a DRM-free download from **Qobuz** (hi-res FLAC), **Bandcamp**, **7digital**, **iTunes** or **Amazon**.
  Save the bought file on your phone; when you come back to Media Hub it rescans the phone, and the song appears in
  your library.
- **Free downloads:** full songs that artists share for free:
  - **Internet Archive:** concert recordings from bands that allow taping and sharing (Live Music Archive) and
    Creative Commons releases from netlabels. No key needed. Open a release to play it or **Download all**.
  - **Jamendo:** thousands of Creative Commons songs. Needs a free client id: devportal.jamendo.com → sign up →
    My applications → create one → paste the Client ID in **Settings → API keys**.
  Play any song in full, or download it. Downloads show progress, can be paused and resumed, and are saved in
  **Music → Media Hub → Free → Artist → Album**, where your library picks them up.
- **Look up info automatically** (Settings → Library tools, on by default) fetches song info and TMDB matches for new
  items as soon as they're added.

---

## 5. Music player
- **Playback:** queue, shuffle, repeat (off / all / one), a mini player and a full Now Playing screen. Controls also
  appear on the lock screen and in the notification. Music keeps playing in the background and pauses when
  headphones are unplugged.
- **Options (⋮):** saved sound presets, effects on/off, sleep timer, skin, visualizer and more.
- **Sleep timer:** 5 min to 2 h, or "end of this song". The sound fades out gently.
- **Lyrics:** time-synced (the lyrics follow the song; tap a line to jump there) or plain text. Media Hub uses `.lrc`
  files next to your songs first, then LRCLIB online, and saves them for offline. Lyrics can also appear over the
  full-screen visualizer.

---

## 6. Sound engine
Open it from **Settings → Sound**, the tune icon in the player, or **Options**. One engine processes **both music and
videos**. The big switch at the top turns all effects on or off. Press and hold **compare** to hear the original sound.

### Output
- **Hi-res output** (on by default): the decoder's full 24-bit / float audio goes straight into the effects. Everything
  is processed in 64-bit and sent to the speaker or DAC as 32-bit float. If a device refuses float output, Media Hub
  switches by itself to 16-bit with dither and remembers that. If hi-res output fails completely, it falls back to
  Android's standard output, and the effects still work. The **Output** card shows what's in use (rate, channels
  and bit depth).
- **Own resampler:** converts songs to the phone's native rate (usually 48 kHz) with a high-quality filter, so Android
  doesn't resample them again.
- **Direct volume control (DVC):** Android's media volume is set to maximum and Media Hub controls the volume inside
  its 64-bit engine, so quiet listening keeps full detail. The volume keys control Media Hub while it plays. When
  nothing is playing, the system volume goes back to a normal level so other apps aren't loud. DVC needs the hi-res
  output; after switching it on, restart Media Hub once.
- 5.1 / 7.1 film sound is folded down to stereo so every effect works on it. Gaps at the start of MP3/AAC tracks
  are trimmed, so albums play gaplessly.

### Equaliser
- **10 bands** (with presets), **31 bands** (third-octave) or **Parametric**: up to 12 filters (peak, low/high shelf,
  low/high cut), each with its own frequency, gain and Q.
- **Headphone correction** for specific headphone models. You can then fine-tune the correction in the parametric EQ.
- **Preamp + limiter:** raises the level by up to +15 dB, and a look-ahead limiter stops clipping. This is the quickest
  way to make everything louder. **Auto headroom** (off by default) turns EQ boosts down instead, which sounds cleaner
  but quieter.

### Effects
| Effect | What it does |
|---|---|
| **Bass boost / Treble** | Tone controls. |
| **Dialogue / vocals** | Brings voices forward. Great for films. |
| **Stereo width** | Narrows or widens the stereo image. The bass stays centred, so it never thins out. |
| **Surround (3D)** | Adds spaciousness around your head. |
| **Crossfeed** | Blends a little of each channel into the other, so headphones sound more like speakers. Less fatigue. |
| **Balance** | Shifts the sound left or right. |
| **Reverb** | Room, Hall or Stadium, with a strength control. |
| **Loudness (leveller)** | Evens out quiet and loud passages. |
| **Volume boost** | Extra gain for quiet files. |
| **ReplayGain** (music) | Off, Track or Album: plays tagged songs at the same loudness. |

Width, surround and crossfeed act on the difference between left and right, so they're clearest on headphones. On a
single phone speaker there's little to hear.

### Presets & profiles
- **Saved sound presets:** save every sound setting under your own name (for example "Car" or "Movie dialogue") and
  switch with one tap from the Sound screen or the music and video **Options**. Press and hold a preset to update,
  rename or delete it. Output settings (hi-res, DVC) aren't stored in presets.
- **Sound profiles** (Speaker, Headphones, Earbuds, Bluetooth, Custom) each keep their own settings. With **Switch
  automatically** on, Media Hub changes profile when you plug in headphones or connect Bluetooth.
- **Use these effects for videos** can be turned off if you want videos to play untouched.

---

## 7. Visualizers
Visualizers are driven by the real audio. Show them full screen, behind the lyrics, or as mini bars in the mini player.
Pick a style in the player (**Options → Visualizer → Choose**) or in **Settings → Appearance → Visualizer style**.
In full screen, tap the visualizer to cycle through the styles.

The picker is split into **collapsible categories**. Tap a category to open or close it. The category of your current
style opens automatically, and the others remember whether you left them open.

| Category | Styles |
|---|---|
| **Bars** (13) | Bars, Mirror, Blocks, Needles, Bouncing dots, Center out, Flames, LED matrix, Skyline, Pyramids… |
| **Waves** (10) | Wave, Filled wave, Lissajous, Mountain, Aurora, DNA… |
| **Circles** (13) | Circle, Rings, Blob, Sunburst, Flower, Kaleidoscope, Spiral… |
| **Space & particles** (9) | Starfield, Digital rain, Bubbles, Tunnel, Lava lamp, Heartbeat… |
| **Retro & grids** (5) | Spectrogram, Ridgelines, Honeycomb… |
| **Instrument cluster** (16) | Analogue dials and needles. |
| **Digital gauges** (20) | Digital readouts and meters. |
| **Digital dashboards** (21) | Full dashboard layouts. |
| **Hi-fi faceplates** (12) | Two each in the style of McIntosh, Marantz, Onkyo, Sony ES, Kenwood and Pioneer Elite: blue watt meters, VFD displays, peak meters and spectrum analysers, with the OrionMD wordmark on the faceplate. |
| **Supercar clusters** (18) | Two each in the style of Ferrari, LaFerrari, Lexus LFA, Porsche 911 GT3, Nissan GT-R, Mercedes G-Class, Audi, Acura NSX, Honda Civic Type R and BMW M3 Competition. Rev counters, shift lights, gears and speed all move with the music. |

The hi-fi and car styles are inspired by those designs and carry no brand logos.

---

## 8. Skins
Open the skin picker from Now Playing (palette button) or **Options → Skin**. There are **41 looks**:

- **Classics:** Midnight, Neon, Vinyl, Retro '70s, Sunset, Ocean, Forest, Minimal light, Frosted art, Gold luxe,
  Cyberpunk, Pastel, Noir, Aurora and Polaroid.
- **Themed packs:** colours, prints and textures inspired by streetwear, gaming and fashion houses (no logos).

Each skin also styles the **video player**: its buttons, seek bar, menus, and the pattern around the picture (that
pattern can be switched off in Settings).

**Your own logo:** in the skin picker, **Add logo…** puts any picture from your phone on that skin (a transparent PNG
looks best). It appears above the song title, in the video player's top bar, and optionally large and faint in the
background. It stays on your device.

---

## 9. Video player
A VLC-style player with Media Hub's sound engine and its own subtitle layer.

- **Plays** MP4, MKV, AVI, MOV, WebM, TS and more, including **10-bit video** (HEVC Main 10, H.264 Hi10P) and AC-3 /
  E-AC-3 / DTS / TrueHD sound. On phones whose hardware can't decode these, the built-in FFmpeg decoder takes over
  automatically. On slower phones, 1080p 10-bit may stutter; 720p is fine.
- **Network streams** (Video tab → link icon): http(s) files, live **HLS** (`.m3u8`) and **DASH** (`.mpd`). Recent
  links are remembered. Other apps can also **Open with → Media Hub**.
- **Gestures:**
  - Swipe up or down on the left side for brightness, or on the right side for volume.
  - Swipe sideways to scrub.
  - Double-tap the sides to skip, or the middle to pause.
  - **Pinch** to resize the picture (25 %–400 %), and drag with two fingers to move it.
- **Subtitles:** pick a track (files next to the video, downloaded ones or ones inside the video), or use **Load
  subtitle file…**. You can also set the **delay** (0.1 s / 1 s steps), **text encoding** (UTF-8, Windows-125x,
  Cyrillic, Arabic, Chinese, Japanese, Korean…) and size. Set the look (size, colour, outline, background) in
  Settings → Playback & subtitles.
- **Audio:** language track, **audio delay** (50 ms / 500 ms steps), effects on/off, EQ presets, dialogue boost and
  night mode.
- **More controls:**
  - **Playback:** speed from 0.25× to 4×, A-B repeat, repeat one / all, sleep timer, skip intro.
  - **Picture:** picture size (best fit, fill, fit width, stretch, 16:9, 4:3, 21:9, 1.85:1, 16:10, 5:4, 1:1),
    rotation lock, picture-in-picture, screenshot.
  - **Navigation:** jump to time, per-video **bookmarks**, playlist, frame by frame.
  - **Info and options:** **media information** (codecs, resolution, HDR, bitrate, decoder), decoder choice
    (automatic, hardware first, software first), background sound and **lock**.
- **Choose which top buttons show** in Settings → Playback & subtitles → Player buttons.
- **Resume** where you left off, **autoplay** the next episode, and **Continue watching** on the Dashboard.
- **TMDB info** (with a key): posters, backdrops, rating, genres, overview, cast with photos, trailers, and TV seasons
  and episodes. Use **Fix match** if a title is wrong.

---

## 10. Podcasts
- Search podcasts (or music podcasts), see what's trending, and subscribe.
- Episodes play in the music player, with the visualizer and sleep timer.
- **Download** any episode, or tap **Download latest 5** for a show. Saved episodes appear under **Saved for offline**
  and play with no connection.

---

## 11. Gallery
- **Timeline:** every photo and video, grouped by day, month or year.
- **Albums:** the phone's folders plus **your own albums**. Long-press an album to set its cover, add it to a group,
  share it, or rename / delete it.
- **Groups:** combine several albums into one, for example "Holidays" = Beach + Camera + WhatsApp Images.
- **Viewer:** **swipe left or right** for the next or previous item. Pinch or double-tap to zoom (swiping pages again
  once you're zoomed back out). Edit, Rotate, Share, **Set as wallpaper**, add to album, info, trash.
- **Editor** (always saves a copy):
  - Crop with aspect presets, rotate and flip.
  - Brightness, contrast, saturation and warmth, plus 10 filters, with undo.
  - **Eraser:** paint over people, objects or text and tap **Erase**. The first time, download the eraser model once
    (~28 MB); after that it works fully offline. It needs 2 GB+ memory. On smaller phones, the quick **Basic** fill
    is offered instead.
- **Stickers:** press and hold on a photo, and the main subject is cut out as a transparent sticker. Stickers are saved
  in Pictures/Media Hub/Stickers.
- **Create:**
  - **Collage:** 2–9 photos, with layouts, shapes and spacing.
  - **Animated GIF:** made from photos or from part of a video.
  - **Long exposure:** blends a video or burst into one shot.
  - **Movie & Reel:** clips and photos with trims, fades, titles, captions and background music from your library.
    Formats are 9:16, 16:9, square and 4:5, and the result is saved to Movies/Media Hub.
- **Folders:** choose which phone folders appear (for example, hide Screenshots). Nothing is moved or deleted.
- **Trash:** deleted items wait 30 days (Android 11+), so you can restore them or delete them for good.

---

## 12. Offline: downloads & Sync all
**Downloads.** The Download button on songs, albums, videos and podcast episodes saves them for offline playback.
- The button shows a live **percentage**. Tap it to pause and again to resume.
- The **Offline downloads** page shows each file's %, MB done and speed. You can pause, resume or cancel each file, or
  use **Pause all / Resume all**.
- Downloads continue in the background, and a notification shows the overall progress.
- A download that is interrupted (closed app, lost connection) **continues from where it stopped**.

**Save downloads to** (Settings → Library tools):
| Option | Where | Who sees them |
|---|---|---|
| **App storage (private)** (default) | Inside Media Hub | Only Media Hub. They're removed if you uninstall. |
| **Phone storage** | Music, Movies and Pictures → *Media Hub* | Your gallery, file manager and other players. They stay after uninstalling. |

The choice applies to new downloads.

**Sync all** saves everything Media Hub normally looks up online. Run it from the Dashboard, the download icon on the
Music and Video tabs, or Settings → Library tools.

| Music | Videos |
|---|---|
| Song info (genre, year, length) | TMDB match and full details |
| Album art and covers inside your files | Posters, backdrops, cast photos |
| Lyrics (time-synced when available) | Trailers, TV season and episode info |
| Album details and full track lists | Subtitles (needs a Wyzie key; pick the language) |

Items that are already saved are skipped, so re-running only picks up new files.

---

## 13. Cloud storage
Connect in **Settings → Cloud accounts**, then pick a folder to add. Cloud files stream with seeking, can be downloaded,
and can be kept up to date with **background sync**. Set the schedule (every 6 h to weekly) and **Wi-Fi only** in
Settings → Sync.

| Service | How it connects | What you need |
|---|---|---|
| **pCloud** | Browser sign-in | Your own free pCloud app's Client ID + secret |
| **Dropbox** | Browser sign-in | Your own free Dropbox app's App key |
| **Google Drive** | Browser sign-in | A Google Cloud OAuth "Desktop app" Client ID + secret |
| **MEGA** | Email + password | Nothing else (accounts with 2FA aren't supported) |
| **Filen** | WebDAV | WebDAV turned on in the Filen desktop app or Filen CLI |
| **Proton Drive** | WebDAV bridge | `rclone serve webdav` with rclone's Proton Drive remote on a computer |
| **WebDAV** (Nextcloud, ownCloud, NAS, Koofr…) | URL + user + password | Your server details (use an app password if offered) |

Each provider shows step-by-step instructions and the redirect address to register. Tip: a cloud app's sync folder on
your phone can also be added as a normal folder for pure offline use.

---

## 14. API keys (optional, free)
Paste the keys in **Settings → API keys** and tap **Save**. They're stored encrypted. **No key is needed for any music
feature.**

| Key | Unlocks | Where to get it |
|---|---|---|
| **TMDB** (Read Access Token or v3 key) | Movie and TV posters, details, cast, trailers, episodes, Fix match, video Sync all | themoviedb.org → Settings → API → request a key (Developer, personal use) |
| **Podcast Index** (key + secret) | Podcast search, trending, new episodes | api.podcastindex.org → sign up; enter **both** values |
| **Wyzie Subs** | Subtitle downloads (needs TMDB too) | sub.wyzie.io |
| **pCloud / Dropbox / Google Drive** | Connecting those clouds | Their developer consoles; register the redirect address shown in the app |

**Test TMDB key** checks the key and starts matching your videos straight away.

---

## 15. Online vs offline
| Works offline | Needs internet |
|---|---|
| Music and videos on the phone or in added folders | Streaming from the cloud (unless downloaded) |
| Downloaded cloud files and podcast episodes | Podcast search and new episodes |
| Sound engine, visualizers, skins, playlists, sleep timer | Discover (previews, store links, free downloads) |
| Everything saved by **Sync all** | First look-ups for new songs and videos (they fill in later) |
| Subtitle and `.lrc` files next to your media | Running Sync all |
| The whole Gallery, including the eraser after its one-time download | Downloading the eraser model (once) |
| Entering a key (held as *Pending* for 14 days) | Confirming or buying a key |

---

## 16. Settings map
| Section | What's inside |
|---|---|
| **Your library sources** | This device, Add folder, Add cloud, remove a source |
| **Cloud accounts** | Connect / disconnect clouds and their OAuth keys |
| **API keys** | TMDB, Podcast Index, Wyzie |
| **Library tools** | Look up info automatically, Music / Videos Sync all, lyrics for all songs, subtitle language, **Save downloads to**, Offline downloads |
| **Sync** | Background sync schedule, Wi-Fi only |
| **Playback & subtitles** | Video defaults (resume, autoplay, gestures, picture size, decoder, skip amount, PiP, background sound), player buttons, subtitle look and encoding |
| **Sound** | Opens the sound engine and equaliser |
| **Appearance** | Visualizer style, player skin, skin pattern around videos, background watermark, splash screen |
| **Lock & privacy** | Set a PIN, lock Media Hub when it opens, lock folders, show / hide locked folders, change or remove the PIN |
| **Saved on this device** | See everything Media Hub has saved (pictures, info, lyrics, subtitles, downloads…) and clear it |
| **Activation & security** | Trial / key status, enter or purchase a key |
| **Footer** | App version, **Guide**, **Features**, open-source notices (FFmpeg, LGPL) |

---

## 17. Privacy & security

### PIN lock
In **Settings → Lock & privacy**, set a PIN (4–8 digits). Then:
- **Lock Media Hub:** the PIN is asked when the app opens, and again after it has been in the background for the time
  you choose (immediately, 1, 5 or 30 minutes). Music keeps playing while it's locked.
- **Lock folders:** tap the 🔒 next to a library source (Your library sources), a folder on the Music → Folders tab, a
  gallery folder's menu (**Lock with PIN**), or use **Lock a folder…**. Locked folders disappear from Home, Music,
  Video, Gallery and search. Tap the 🔒 at the top of Music, Video or Gallery (or **Show locked folders**) and enter
  the PIN to see them; they hide again when Media Hub locks.
- After 5 wrong PINs you have to wait before trying again. **Forgot PIN?** On the lock screen, unlock with your
  phone's own screen lock (PIN, pattern or fingerprint), then set a new PIN.
- The PIN itself is never stored, only a salted one-way check value, encrypted.

### Saved on this device
**Settings → Saved on this device → See & clear saved data** lists everything Media Hub keeps for offline use, with
its size: song and video pictures, album covers, movie & TV pictures, song info, movie & TV info, lyrics, downloaded
subtitles, offline downloads, the photo eraser model, your skin logos, library lists and temporary files.
**View** shows the pictures (with what each belongs to), reads lyrics and subtitles, or lists the saved titles.
**Clear** removes one category, and **Clear all saved pictures & info** removes everything that can be fetched
again. Your own Fix match choices, downloads, logos and library lists are kept.

### Data protection
- There's no account, no ads and no tracking. Your library stays on your phone.
- Caches, API keys, cloud sign-ins and the activation key are encrypted on the device (AES-256-GCM, with the key held
  by the Android Keystore).
- Activation sends only the key, this app's device ID and the app name to the OrionMD activation server.
- Online look-ups send only what's needed to find a match (artist and title, or a movie name).
- The owner password is never stored. The app keeps only a salted PBKDF2 check value.

---

## 18. Troubleshooting
| Problem | Fix |
|---|---|
| **No sound from music** | Open **Sound**: check the **Output** card says *Running*, and that the **Volume** slider (with DVC on) isn't at the bottom. Try DVC off, restart Media Hub, and play again. |
| Quieter than other players | Sound → **Preamp** (+3 to +9 dB) and/or **Volume boost**; make sure **Auto headroom** is off. With DVC on, turn up Media Hub's own volume. |
| Sliders seem to do nothing | Check **Sound effects ON** at the top. Width, surround and crossfeed are clearest on headphones. |
| Visualizer doesn't move | Make sure something is playing; the Sound screen should say *Engine running*. Update to the latest release. |
| Notification doesn't open the player | Update to the latest release: tapping the notification opens Now Playing. |
| "Unknown artist" / no album art | Go online, or run **Sync all** for Music. Files named `Artist - Title` work best. |
| No movie posters or info | Add a TMDB key and tap **Save** / **Test**. Use **Fix match** for wrong titles. |
| Video won't play or stutters | Video → More → **Decoder → Software first** (or Hardware first). |
| Subtitles out of sync / odd characters | **Subtitles → Delay**, or **Subtitles → Text encoding**. For sound out of sync, use **Audio → Audio delay**. |
| No subtitles downloaded | Needs both TMDB and Wyzie keys. Set the language in Library tools, then run video Sync all. |
| Downloads don't show in other apps | Set **Save downloads to → Phone storage** (applies to new downloads). |
| Update asks to uninstall first | Install only GitHub release builds, in order. Uninstalling erases your data. |
| Cloud won't connect | Check the Client ID / secret, and that the redirect address shown in the app is registered with the provider. |
| Trial ended / key "already used" / "Pending" | Buy a key at buymeacoffee.com/orionmd · each key works for one app on one device · go online and reopen to confirm a pending key. |

When you report a problem, include the **version number** (splash screen or Settings footer) and your Sound settings
(Hi-res and DVC on or off).
