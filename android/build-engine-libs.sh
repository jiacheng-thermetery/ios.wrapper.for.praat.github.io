#!/bin/bash
# build-engine-libs.sh — build the Praat engine static libs for Android and stage
# them per-ABI under android/build-libs/<abi>/. Part of the Spraak derivative.
# GPL-3.0-or-later.
#
# Usage:
#   android/build-engine-libs.sh [arm64-v8a | x86_64 | both]    (default: arm64-v8a)
#
# Mirrors ios/build-engine-libs.sh. The Praat subdir Makefiles build in-tree, so
# each ABI pass rebuilds in-tree (-B) and copies its .a files out.
set -euo pipefail
cd "$(dirname "$0")/.."                       # repo root (upstream-praat)

# 14 Praat libs + 12 external libs. Each <dir> yields <dir>/lib*.a after `make`.
DIRS="melder kar sys fon foned stat dwsys LPC gram EEG dwtools sensors FFNet artsynth \
external/espeak external/portaudio external/flac external/lame external/mp3 external/glpk \
external/clapack external/gsl external/num external/vorbis external/opusfile external/whispercpp"

JOBS="$(nproc 2>/dev/null || echo 4)"

build_one_abi() {
  local abi="$1" log
  mkdir -p "android/build-libs"
  log="android/build-libs/$abi-build.log"; : > "$log"
  echo "==> building engine libs for $abi  [log: $log]"
  ( export PRAAT_ANDROID_ABI="$abi"
    source android/androidenv.sh >>"$log" 2>&1
    MAKE="${MAKE:-make}"
    command -v "$MAKE" >/dev/null 2>&1 || MAKE="$NDK_HOST_MAKE"   # NDK's prebuilt make
    for d in $DIRS; do
      echo "    [$abi] make -B -C $d"
      if ! "$MAKE" -j"$JOBS" -B -C "$d" >>"$log" 2>&1; then
        echo "BUILD FAILED in $d for $abi — last 25 log lines:" >&2
        tail -25 "$log" >&2
        exit 1
      fi
    done )
  echo "==> staging $abi -> android/build-libs/$abi/"
  for d in $DIRS; do
    mkdir -p "android/build-libs/$abi/$d"
    cp "$d"/lib*.a "android/build-libs/$abi/$d/"
  done
}

case "${1:-arm64-v8a}" in
  both) build_one_abi x86_64; build_one_abi arm64-v8a ;;   # arm64 last: in-tree ends as arm64
  arm64-v8a|x86_64) build_one_abi "$1" ;;
  *) echo "usage: $0 [arm64-v8a | x86_64 | both]" >&2; exit 2 ;;
esac
echo "Done. Staged: $(ls -d android/build-libs/*/ 2>/dev/null | tr '\n' ' ')"
