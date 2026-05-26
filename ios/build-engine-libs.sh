#!/bin/bash
# build-engine-libs.sh — build the Praat engine static libs for iOS and stage them
# per-SDK, so the device and simulator app builds keep their own arm64 slices.
# Part of the Spraak derivative. GPL-3.0-or-later.
#
# Usage:
#   ios/build-engine-libs.sh [iphonesimulator | iphoneos | both]    (default: both)
#
# Why staging: device-arm64 and simulator-arm64 are the *same* CPU architecture, so a
# single .a cannot hold both (lipo refuses — it's why Apple invented .xcframeworks).
# We build each slice and copy its .a into ios/build-libs/<sdk>/<dir>/, which project.yml
# links per-SDK via ENGINE_LIBS_DIR (and build-app.sh links the simulator set). The Praat
# subdir Makefiles build in-tree, so each pass rebuilds in-tree then copies out; `both`
# builds the simulator last, leaving the in-tree .a as simulator slices.
set -euo pipefail
cd "$(dirname "$0")/.."                       # repo root (upstream-praat)

# 14 Praat libs + 12 external libs. Each <dir> yields <dir>/lib*.a after `make`.
DIRS="melder kar sys fon foned stat dwsys LPC gram EEG dwtools sensors FFNet artsynth \
external/espeak external/portaudio external/flac external/lame external/mp3 external/glpk \
external/clapack external/gsl external/num external/vorbis external/opusfile external/whispercpp"

JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"   # parallelise compiles across all cores

build_one_sdk() {
  local sdk="$1" target log
  case "$sdk" in
    iphonesimulator) target="arm64-apple-ios15.0-simulator" ;;
    iphoneos)        target="arm64-apple-ios15.0" ;;
    *) echo "unknown SDK '$sdk' (use iphonesimulator | iphoneos | both)" >&2; exit 2 ;;
  esac
  mkdir -p "ios/build-libs"
  log="ios/build-libs/$sdk-build.log"; : > "$log"
  echo "==> building engine libs for $sdk ($target)  [log: $log]"
  ( export PRAAT_IOS_SDK="$sdk" PRAAT_IOS_TARGET="$target"
    source ios/iosenv.sh >>"$log" 2>&1                              # exports toolchain; banner to log
    for d in $DIRS; do
      echo "    [$sdk] make -B -C $d"
      if ! make -j"$JOBS" -B -C "$d" >>"$log" 2>&1; then
        echo "BUILD FAILED in $d for $sdk — last 25 log lines:" >&2
        tail -25 "$log" >&2
        exit 1
      fi
    done )
  echo "==> staging $sdk -> ios/build-libs/$sdk/"
  for d in $DIRS; do
    mkdir -p "ios/build-libs/$sdk/$d"
    cp "$d"/lib*.a "ios/build-libs/$sdk/$d/"
  done
}

case "${1:-both}" in
  both) build_one_sdk iphoneos; build_one_sdk iphonesimulator ;;  # sim last: in-tree ends as sim slices
  iphoneos|iphonesimulator) build_one_sdk "$1" ;;
  *) echo "usage: $0 [iphonesimulator | iphoneos | both]" >&2; exit 2 ;;
esac
echo "Done. Staged: $(ls -d ios/build-libs/*/ 2>/dev/null | tr '\n' ' ')"
