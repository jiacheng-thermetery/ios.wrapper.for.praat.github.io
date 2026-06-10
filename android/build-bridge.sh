#!/bin/bash
# build-bridge.sh — compile the C bridge + JNI layer and link libpraat.so,
# staging it into the app's jniLibs. Part of the Spraak derivative.
# GPL-3.0-or-later.
#
# Usage:
#   android/build-bridge.sh [arm64-v8a | x86_64]    (default: arm64-v8a)
#
# Prerequisite: android/build-engine-libs.sh <abi> has staged the engine libs.
set -euo pipefail
cd "$(dirname "$0")/.."                       # repo root (upstream-praat)

export PRAAT_ANDROID_ABI="${1:-arm64-v8a}"
source android/androidenv.sh

ENGINE="android/build-libs/$PRAAT_ANDROID_ABI"
if [ ! -f "$ENGINE/fon/libfon.a" ]; then
  echo "Engine libs not staged. Run: android/build-engine-libs.sh $PRAAT_ANDROID_ABI" >&2
  exit 1
fi

OUT="android/app/src/main/jniLibs/$PRAAT_ANDROID_ABI"
mkdir -p "$OUT" android/build-libs/obj

INCLUDES="-Isys -Imelder -Ikar -Ifon -Idwsys -Istat -ILPC -Igram -Idwtools -Iexternal/gsl"

echo "[1/3] compiling shared C++ bridge (ios/app/PraatBridge.mm is plain C++)"
$CXX $CXXFLAGS $INCLUDES -x c++ \
  -c ios/app/PraatBridge.mm -o android/build-libs/obj/PraatBridge.o

echo "[2/3] compiling Android bridge extension + JNI layer"
$CXX $CXXFLAGS $INCLUDES \
  -c android/engine/PraatBridgeAndroid.cpp -o android/build-libs/obj/PraatBridgeAndroid.o
$CXX $CXXFLAGS \
  -c android/engine/praat_jni.cpp -o android/build-libs/obj/praat_jni.o

echo "[3/3] linking $OUT/libpraat.so"
RELLIBS="fon/libfon.a artsynth/libartsynth.a FFNet/libFFNet.a gram/libgram.a EEG/libEEG.a \
 LPC/libLPC.a dwtools/libdwtools.a sensors/libsensors.a foned/libfoned.a \
 stat/libstat.a dwsys/libdwsys.a sys/libsys.a melder/libmelder.a kar/libkar.a \
 external/espeak/libespeak.a external/portaudio/libportaudio.a external/flac/libflac.a \
 external/lame/liblame.a external/mp3/libmp3.a external/glpk/libglpk.a \
 external/clapack/libclapack.a external/gsl/libgsl.a external/num/libnum.a \
 external/vorbis/libvorbis.a external/opusfile/libopusfile.a external/whispercpp/libwhisper.a"
LIBS=""; for l in $RELLIBS; do LIBS="$LIBS $ENGINE/$l"; done

# --start/end-group: the Praat libs have circular references; 16 KB max-page-size
# satisfies Android 15+ page-alignment requirements; libc++ is linked statically
# so the .so has no STL runtime dependency.
$CXX $CXXFLAGS -shared -static-libstdc++ \
  -Wl,-z,max-page-size=16384 -Wl,--no-undefined \
  -o "$OUT/libpraat.so" \
  android/build-libs/obj/praat_jni.o \
  android/build-libs/obj/PraatBridge.o \
  android/build-libs/obj/PraatBridgeAndroid.o \
  -Wl,--start-group $LIBS -Wl,--end-group \
  -lm -llog -landroid

echo "Done: $OUT/libpraat.so"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUT/libpraat.so" | grep -E 'Machine|Type'
