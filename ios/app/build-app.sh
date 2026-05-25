#!/bin/bash
# build-app.sh — build & run the Spraak SwiftUI app on the simulator.
# Part of the Spraak derivative. GPL-3.0-or-later.
set -e
cd "$(dirname "$0")/../.."          # repo root (upstream-praat)
export DEVELOPER_DIR=/Users/jiachengliu/Downloads/Xcode-beta.app/Contents/Developer
SDK=iphonesimulator
SDKPATH="$(xcrun --sdk $SDK --show-sdk-path)"
TGT="arm64-apple-ios17.0-simulator"   # app uses modern SwiftUI; iOS-15 engine libs link forward-compatibly
APPDIR="ios/app/Spraak.app"
BID="com.thermetery.spraak"
DEVICE="${1:-iPhone 16}"

LIBS="fon/libfon.a artsynth/libartsynth.a FFNet/libFFNet.a gram/libgram.a EEG/libEEG.a \
 LPC/libLPC.a dwtools/libdwtools.a sensors/libsensors.a foned/libfoned.a fon/libfon.a \
 stat/libstat.a dwsys/libdwsys.a sys/libsys.a melder/libmelder.a kar/libkar.a \
 external/espeak/libespeak.a external/portaudio/libportaudio.a external/flac/libflac.a \
 external/lame/liblame.a external/mp3/libmp3.a external/glpk/libglpk.a \
 external/clapack/libclapack.a external/gsl/libgsl.a external/num/libnum.a \
 external/vorbis/libvorbis.a external/opusfile/libopusfile.a external/whispercpp/libwhisper.a"

echo "[1/4] compiling C++ bridge"
xcrun --sdk $SDK clang++ -target $TGT -isysroot "$SDKPATH" -std=gnu++17 \
  -Dmacintosh -DPRAAT_IOS -DNO_GRAPHICS -O2 -Wno-deprecated-declarations \
  -Isys -Imelder -Ikar -Ifon -Idwsys -Istat -ILPC -Igram -Idwtools -Iexternal/gsl \
  -c ios/app/PraatBridge.mm -o ios/app/PraatBridge.o

echo "[2/4] compiling + linking Swift app"
rm -rf "$APPDIR"; mkdir -p "$APPDIR"
xcrun --sdk $SDK swiftc -sdk "$SDKPATH" -target $TGT -O \
  -import-objc-header ios/app/Spraak-Bridging-Header.h -I ios/app \
  ios/app/PraatApp.swift ios/app/ContentView.swift ios/app/PraatModel.swift \
  ios/app/AudioEngine.swift ios/app/SpectrogramView.swift ios/app/SettingsView.swift \
  ios/app/ObjectsView.swift \
  -o "$APPDIR/Spraak" \
  ios/app/PraatBridge.o ios/pa_ios_hostapis.o $LIBS \
  -framework CoreFoundation -framework Accelerate -framework Metal -framework Foundation \
  -lc++ -lm

echo "[3/4] assembling bundle"
cp ios/app/Info.plist "$APPDIR/Info.plist"

echo "[4/4] install + launch on '$DEVICE'"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl install booted "$APPDIR"
xcrun simctl launch booted "$BID"
echo "Done."
