# Sourced by build scripts. Sets up iOS (simulator, arm64) cross-compile toolchain.
# This file is part of the Spraak derivative. GPL-3.0-or-later.
# [iOS port] Default to the system-installed Xcode; honour an explicit DEVELOPER_DIR override.
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

# Target: iOS Simulator on Apple Silicon (arm64). For a real device, use:
#   PRAAT_IOS_SDK=iphoneos  PRAAT_IOS_TARGET=arm64-apple-ios15.0
: "${PRAAT_IOS_SDK:=iphonesimulator}"
: "${PRAAT_IOS_TARGET:=arm64-apple-ios15.0-simulator}"

SYSROOT="$(xcrun --sdk "$PRAAT_IOS_SDK" --show-sdk-path)"
TARGETFLAGS="-target ${PRAAT_IOS_TARGET} -isysroot ${SYSROOT}"

# Praat platform identity for the iOS barren core:
#   macintosh   -> iOS is the Apple/Darwin family; selects the correct platform
#                  dispatch (MelderThread, byte order, CoreFoundation path
#                  normalisation, ...). NB: do NOT also define UNIX, because Praat
#                  uses `#if defined(UNIX) ... #elif defined(macintosh)`, so UNIX
#                  would wrongly win and pull in the GTK path.
#   PRAAT_IOS   -> our marker: inside `macintosh` blocks, skip macOS-desktop-only
#                  frameworks (Carbon, AppKit/Cocoa, OpenCL, CoreAudio HAL,
#                  AudioToolbox alert sounds) and take the portable/CPU fallback.
#                  Every guarded site is listed in ios/PORTING_NOTES.md.
#   NO_GRAPHICS -> no window system / drawing backend (entails NO_GUI => cocoa==0).
PRAATDEFS="-Dmacintosh -DPRAAT_IOS -DNO_GRAPHICS -DPRAAT_IOS_GRAPHICS"

SHARED="${TARGETFLAGS} ${PRAATDEFS} -O2 -g1 -fno-common -Wno-deprecated-declarations"

export CC="xcrun --sdk ${PRAAT_IOS_SDK} clang"
export CXX="xcrun --sdk ${PRAAT_IOS_SDK} clang++"
export CFLAGS="-std=gnu99 ${SHARED}"
export CXXFLAGS="-std=gnu++17 ${SHARED} -Wno-shadow"
export CPPFLAGS=""
export AR="xcrun --sdk ${PRAAT_IOS_SDK} ar"
export RANLIB="xcrun --sdk ${PRAAT_IOS_SDK} ranlib"
export RM="rm -f"

echo "[iosenv] SDK=${PRAAT_IOS_SDK} TARGET=${PRAAT_IOS_TARGET}"
echo "[iosenv] SYSROOT=${SYSROOT}"
