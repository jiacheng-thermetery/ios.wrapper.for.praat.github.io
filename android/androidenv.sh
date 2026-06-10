# Sourced by build scripts. Sets up the Android NDK cross-compile toolchain.
# This file is part of the Spraak derivative. GPL-3.0-or-later.
# [Android port] Mirrors ios/iosenv.sh: exports CC/CXX/CFLAGS/AR/... so the stock
# per-directory Praat Makefiles cross-compile unchanged under the NDK.
#
#   PRAAT_ANDROID_ABI=arm64-v8a | x86_64      (default arm64-v8a)
#   PRAAT_ANDROID_API=<minSdk>                (default 26 = Android 8.0, AAudio era)
#   ANDROID_NDK_ROOT=<path to NDK>            (else common install spots are probed)

# Locate the NDK: honour ANDROID_NDK_ROOT/ANDROID_NDK_HOME, else probe usual places.
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
  for cand in "${ANDROID_NDK_HOME:-}" /c/Android/android-ndk-r2[0-9]* \
              "$HOME"/Android/Sdk/ndk/* /opt/android-ndk-r2[0-9]*; do
    [ -d "$cand" ] && ANDROID_NDK_ROOT="$cand"
  done
fi
if [ -z "${ANDROID_NDK_ROOT:-}" ] || [ ! -d "$ANDROID_NDK_ROOT" ]; then
  echo "[androidenv] Android NDK not found; set ANDROID_NDK_ROOT" >&2
  return 2 2>/dev/null || exit 2
fi
export ANDROID_NDK_ROOT

# Host tag of the prebuilt LLVM toolchain inside the NDK.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
  Darwin)               NDK_HOST=darwin-x86_64 ;;
  *)                    NDK_HOST=linux-x86_64 ;;
esac
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST"

: "${PRAAT_ANDROID_ABI:=arm64-v8a}"
: "${PRAAT_ANDROID_API:=26}"
case "$PRAAT_ANDROID_ABI" in
  arm64-v8a) NDK_TRIPLE=aarch64-linux-android ;;
  x86_64)    NDK_TRIPLE=x86_64-linux-android ;;
  *) echo "[androidenv] unsupported ABI '$PRAAT_ANDROID_ABI' (use arm64-v8a | x86_64)" >&2
     return 2 2>/dev/null || exit 2 ;;
esac
TARGETFLAGS="--target=${NDK_TRIPLE}${PRAAT_ANDROID_API}"

# Praat platform identity for the Android barren core:
#   UNIX + linux -> Android *is* Linux (bionic libc); selects Praat's existing
#                   Linux platform dispatch. Unlike iOS (which had to define
#                   `macintosh` + PRAAT_IOS), Android needs no marker macro of
#                   its own so far: the upstream Linux *barren* configuration
#                   (make PRAAT_GRAPHICS=barren PRAAT_AUDIO=none) already
#                   compiles with no GTK, no X11, no ALSA/JACK/Pulse.
#   NO_GRAPHICS  -> Praat's "barren" switch (entails NO_GUI). No drawing backend;
#                   on-screen rendering lives in the Compose app shell, and the
#                   object-window Draw goes through the JNI Graphics callback
#                   backend (see android/PORTING_NOTES.md).
#   NO_AUDIO     -> upstream switch that drops the OSS (<sys/soundcard.h>) code
#                   paths, which bionic does not provide. Together with the
#                   absence of ALSA/JACK defines, PortAudio compiles its UNIX
#                   host-API table empty (backend-less, like the iOS build);
#                   live audio is provided by the app shell via AudioRecord/
#                   AudioTrack.
PRAATDEFS="-DUNIX -Dlinux -DNO_GRAPHICS -DNO_AUDIO -D_FILE_OFFSET_BITS=64"

# -fPIC: the static libs are linked into libpraat.so (iOS linked into an app
# binary and did not need it). 16 KB page alignment is handled at link time.
SHARED="${TARGETFLAGS} ${PRAATDEFS} -O2 -g1 -fPIC -fno-common -pthread -Wno-deprecated-declarations"

export CC="$TOOLCHAIN/bin/clang ${TARGETFLAGS}"
export CXX="$TOOLCHAIN/bin/clang++ ${TARGETFLAGS}"
export CFLAGS="-std=gnu99 ${SHARED}"
export CXXFLAGS="-std=gnu++17 ${SHARED} -Wno-shadow"
export CPPFLAGS=""
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export RM="rm -f"

# GNU make for hosts that lack one (plain Git Bash on Windows): the NDK ships it.
export NDK_HOST_MAKE="$ANDROID_NDK_ROOT/prebuilt/$NDK_HOST/bin/make"

echo "[androidenv] NDK=$ANDROID_NDK_ROOT"
echo "[androidenv] ABI=$PRAAT_ANDROID_ABI API=$PRAAT_ANDROID_API TARGET=${NDK_TRIPLE}${PRAAT_ANDROID_API}"
