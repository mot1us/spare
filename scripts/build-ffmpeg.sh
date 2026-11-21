#!/usr/bin/env bash
# Builds the FFmpeg libraries for Spare's DTS and Dolby TrueHD decoder (the ffmpeg module, AndroidX Media3 1.11.1's
# decoder_ffmpeg). FFmpeg is LGPL-2.1-or-later; only these audio decoders are enabled, with no GPL or non-free parts.
# The checkout and libraries live in ffmpeg/src/main/jni/ffmpeg (ignored by git); a stamp skips work already done.
set -euo pipefail
FFMPEG_TAG=n6.0.1
FFMPEG_COMMIT=c41ff724ede7da657762d61097e26fac296c53bf
NDK_VERSION=27.2.12479018
API=26
DECODERS=(dca truehd mlp)
ABIS=(armeabi-v7a arm64-v8a x86_64)

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/ffmpeg/src/main/jni/ffmpeg"
stamp="$src/android-libs/.spare-build"
want="$FFMPEG_COMMIT ${DECODERS[*]} ${ABIS[*]} $API $NDK_VERSION $(shasum -a 256 "$0" | cut -c1-16)"
if [[ -f "$stamp" && "$(cat "$stamp")" == "$want" ]]; then echo "FFmpeg $FFMPEG_TAG already built"; exit 0; fi

ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/$NDK_VERSION}"
case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; Linux) host=linux-x86_64 ;; *) echo "Unsupported build host" >&2; exit 1 ;; esac
tools="$ndk/toolchains/llvm/prebuilt/$host/bin"
[[ -d "$tools" ]] || { echo "Android NDK $NDK_VERSION not found at $ndk" >&2; exit 1; }

if [[ ! -d "$src/.git" ]]; then
  rm -rf "$src"
  git clone --quiet --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$src"
fi
actual="$(git -C "$src" rev-parse HEAD)"
[[ "$actual" == "$FFMPEG_COMMIT" ]] || { echo "FFmpeg checkout $actual is not the pinned $FFMPEG_COMMIT" >&2; exit 1; }

jobs="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
common=(--target-os=android --enable-static --disable-shared --disable-doc --disable-programs --disable-everything
  --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver
  --enable-swresample --extra-ldexeflags=-pie --disable-v4l2-m2m --disable-vulkan --disable-network --disable-autodetect
  --enable-small --disable-debug)
for decoder in "${DECODERS[@]}"; do common+=("--enable-decoder=$decoder"); done

# One section per function and variable, so the linker can drop what the decoder never calls (see CMakeLists.txt).
sections="-ffunction-sections -fdata-sections"
cd "$src"
rm -rf android-libs
for abi in "${ABIS[@]}"; do
  case "$abi" in
    armeabi-v7a) arch=(--arch=arm --cpu=armv7-a --cross-prefix="$tools/armv7a-linux-androideabi$API-" --extra-cflags="-march=armv7-a -mfloat-abi=softfp $sections") ;;
    arm64-v8a) arch=(--arch=aarch64 --cpu=armv8-a --cross-prefix="$tools/aarch64-linux-android$API-" --extra-cflags="$sections") ;;
    x86_64) arch=(--arch=x86_64 --cpu=x86-64 --cross-prefix="$tools/x86_64-linux-android$API-" --disable-asm --extra-cflags="$sections") ;;
  esac
  echo "Building FFmpeg $FFMPEG_TAG for $abi"
  ./configure --libdir="android-libs/$abi" --nm="$tools/llvm-nm" --ar="$tools/llvm-ar" --ranlib="$tools/llvm-ranlib" \
    --strip="$tools/llvm-strip" "${arch[@]}" "${common[@]}" > /dev/null
  make -j"$jobs" > /dev/null
  make install-libs > /dev/null
  make clean > /dev/null
done
echo "$want" > "$stamp"
echo "FFmpeg $FFMPEG_TAG built for ${ABIS[*]} with decoders ${DECODERS[*]}"
