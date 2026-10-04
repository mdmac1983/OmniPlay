# Media Hub features

A standalone, offline-first hybrid **music + video player** by OrionMD.

## Dashboard
- Library at a glance, **Sync all** for music and videos (with progress), continue watching, most played, recently added, and podcast episodes saved offline.

## Music player
- Plays everything on your device, in any folder you add, and from connected clouds.
- Gapless queue, shuffle, repeat (off/all/one), sleep timer (with fade-out), background playback with media-notification controls.
- Guide & Features readable in the app (Settings → footer).
- **VLC-style video player**: subtitle & audio track menus, subtitle delay (both ways) and text encodings, load subtitle files, audio delay, speed 0.25–4×, 11 picture sizes and aspect ratios, pinch-zoom, jump to time, bookmarks, playlist, shuffle / repeat all, media info, frame stepping, hardware/software decoder, background audio, network streams (HLS/DASH), "Open with", choose which buttons show.
- **Sound engine** (music and videos): hi-res output (24-bit/float in, 64-bit processing, 32-bit float out, own high-quality resampler to the native rate, 5.1→stereo downmix, gapless trimming); 10-band, 31-band or parametric equaliser (12 filters); preamp + peak limiter (Poweramp-style loudness); ReplayGain (track/album); direct volume control (DVC); saved sound presets (every EQ/effect setting, named, one-tap from the Sound screen and the Options button in the music and video players); balance; headphone correction, bass/treble, vocal boost, stereo width, virtual surround, crossfeed, reverb, volume leveller and a soft limiter. Sound profiles (speaker / headphones / earbuds / Bluetooth) that can switch automatically.
- **Visualizers**: dozens of styles (bars, waves, circular, digital gauges, dashboards) driven by a live FFT of the audio.
- **Skins** for Now Playing and the video player — 41 looks incl. packs inspired by Gucci, Louis Vuitton, Prada, Ethika, Under Armour, Nike, Jordan, Adidas, Fortnite, Marshmello, Supreme and Chrome Hearts (colours and patterns, no logos).
- **Lyrics**: time-synced from LRCLIB (and from `.lrc` files next to the song), saved offline, batch-downloadable.
- **Online info**: covers, genre, year from Deezer/iTunes.
- **Albums & artists** pages with online details and track lists.
- **Podcasts**: search, trending, subscribe and play (Podcast Index); **download episodes** (one at a time or the latest 5) to listen offline.
- **Music sync all**: album art, song info, album details (full track lists) and lyrics for every song, saved on the device for offline.
- **Discover**: search Deezer/iTunes and preview 30-second clips.
- **Playlists** (plus automatic Most-played / Recently-added).

## Video player
- Media3/ExoPlayer: plays MKV, MP4 and more; streams from cloud with seeking.
- Gestures (brightness/volume swipe, double-tap skip, scrub), speed, picture size, rotation lock, **picture-in-picture**, screen lock, A-B repeat, volume boost, screenshot, resume position, autoplay-next, sleep timer.
- **Subtitles**: sidecar files (.srt/.vtt/.ass/.ssa) and online subtitles; size/colour/background/position options.
- **Video sync all**: TMDB details, posters, backdrops, cast photos, TV episode info and subtitles (Wyzie Subs, choose the language) for every video, saved for offline.
- **TMDB**: posters, backdrops, cast with photos, trailers, ratings and descriptions for movies & TV; "Fix match".

## Gallery
- Timeline (day / month / year), device albums, your own albums, groups of albums, custom covers, 30-day trash.
- Choose which device folders appear in the Gallery; videos play inline in the viewer.
- Viewer with zoom, rotate, share, set as wallpaper; editor (crop, rotate, flip, light & colour, filters, on-device AI Eraser — MI-GAN, one-time 28 MB download, then offline), saved as copies.
- Movies & Reels editor: trim and reorder clips, fades, photo zoom, title and captions, background music with preview, 9:16 / 16:9 / 1:1 / 4:5 export (on the phone).
- Create collages, animated GIFs and long-exposure shots; press-and-hold to cut out stickers (on-device ML Kit).

## Offline & cloud
- **Offline first**: everything you've scanned browses and plays with no internet; downloaded items play fully offline.
- **Clouds**: pCloud, Dropbox, Google Drive, MEGA, Filen (via WebDAV), Proton Drive (via WebDAV) and generic WebDAV (Nextcloud/NAS/…).
- Cloud files stream through an on-device proxy that handles authentication, seeking and on-the-fly decryption; MEGA is end-to-end decrypted on the device.
- **Background sync** keeps chosen cloud folders up to date.

## Security
- Proprietary, release-signed build; verifies it hasn't been re-signed/modified.
- **Activation keys** (owner-signed) gate the app; **owner password** unlock; a tampered copy locks and erases its own data after repeated wrong attempts.
- All caches, keys and sign-ins are encrypted on the device (AES-256-GCM, Android Keystore).
