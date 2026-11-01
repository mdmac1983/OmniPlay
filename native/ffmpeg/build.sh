#!/bin/bash
# Builds an LGPL FFmpeg (decoders only) + the Media Hub glue for armeabi-v7a and arm64-v8a.
set -e
S=$(cd "$(dirname "$0")" && pwd)
# Needs the Android NDK r26d at $NDK and the FFmpeg 7.0.2 source unpacked next to this script
# (https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz). Output: ./out/<abi>/lib -> copy into app/src/main/jniLibs/<abi>/
NDK=/opt/android-sdk/ndk/26.3.11579264; TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
OUT=$S/out
build() {
  ABI=$1; ARCH=$2; CPU=$3; TRIPLE=$4; shift 4
  cd $S/ffmpeg-7.0.2
  make distclean >/dev/null 2>&1 || true
  ./configure --prefix=$OUT/$ABI --target-os=android --arch=$ARCH --cpu=$CPU --enable-cross-compile \
    --cc=$TC/bin/${TRIPLE}26-clang --cxx=$TC/bin/${TRIPLE}26-clang++ --nm=$TC/bin/llvm-nm --ar=$TC/bin/llvm-ar \
    --ranlib=$TC/bin/llvm-ranlib --strip=$TC/bin/llvm-strip --sysroot=$TC/sysroot \
    --enable-shared --disable-static --enable-pic --disable-programs --disable-doc --disable-debug \
    --disable-everything --disable-avdevice --disable-avformat --disable-avfilter --disable-postproc --disable-network --disable-autodetect \
    --enable-swscale --enable-swresample \
    --enable-decoder=h264,hevc,mpeg2video,mpeg4,ac3,eac3,dca,truehd,mlp,alac \
    --enable-neon "$@" --extra-cflags="-O3 -fPIC" --extra-ldflags="-Wl,-z,max-page-size=16384"
  # Android loads "libavcodec.so", so no version numbers in the library names
  sed -i 's/^SLIBNAME_WITH_MAJOR=.*/SLIBNAME_WITH_MAJOR=$(SLIBNAME)/; s/^SLIBNAME_WITH_VERSION=.*/SLIBNAME_WITH_VERSION=$(SLIBNAME)/; s/^SLIB_INSTALL_NAME=.*/SLIB_INSTALL_NAME=$(SLIBNAME)/; s/^SLIB_INSTALL_LINKS=.*/SLIB_INSTALL_LINKS=/' ffbuild/config.mak
  make -j$(nproc) >/dev/null && make install >/dev/null
  $TC/bin/${TRIPLE}26-clang -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -I$OUT/$ABI/include $S/mhffmpeg.c \
    -L$OUT/$ABI/lib -lavcodec -lswscale -lswresample -lavutil -landroid -llog -o $OUT/$ABI/lib/libmhffmpeg.so
  $TC/bin/llvm-strip --strip-unneeded $OUT/$ABI/lib/*.so
  echo "built $ABI"
}
build armeabi-v7a arm armv7-a armv7a-linux-androideabi
build arm64-v8a aarch64 armv8-a aarch64-linux-android
ls -la $OUT/*/lib/*.so
