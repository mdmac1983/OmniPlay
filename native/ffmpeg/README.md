# FFmpeg (software video / audio decoding)

Media Hub plays files the phone's hardware can't decode — 10-bit H.264 ("Hi10P") and 10-bit HEVC (common in MKV),
MPEG-2 / MPEG-4 video, and AC-3, E-AC-3, DTS, TrueHD and ALAC audio — with **FFmpeg 7.0.2**, built from the unmodified
upstream source as an **LGPL v2.1+** build (no `--enable-gpl`, no `--enable-nonfree`), decoders only.

- `build.sh` — the exact configure / build commands (Android NDK r26d, armeabi-v7a and arm64-v8a).
- `mhffmpeg.c` — Media Hub's JNI glue (decoding, 10-bit to 8-bit conversion, drawing to the screen, audio to float).
- The resulting shared libraries are in `app/src/main/jniLibs/<abi>/` (`libavutil`, `libswresample`, `libswscale`,
  `libavcodec`, `libmhffmpeg`). They're loaded dynamically, so they can be replaced with another build of FFmpeg.

FFmpeg is (c) the FFmpeg developers, licensed under the GNU LGPL v2.1 or later. Source: https://ffmpeg.org/
