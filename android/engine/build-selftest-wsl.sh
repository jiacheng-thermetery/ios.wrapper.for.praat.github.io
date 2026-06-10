#!/bin/bash
# build-selftest-wsl.sh — build the barren engine + shared bridge natively on
# Linux (WSL1) and run bridge_selftest under Android-like conditions. Debugging
# aid for the Android port. Part of the Spraak derivative. GPL-3.0-or-later.
set -euo pipefail

SRC=/mnt/c/praat-ios-wrapper
WORK="$HOME/praat"

echo "==> sync sources to $WORK (WSL fs is much faster than DrvFs)"
mkdir -p "$WORK"
cd "$SRC"
tar cf - --exclude=.git --exclude='*.o' --exclude='*.a' --exclude=android/build-libs \
        --exclude=ios/build-libs --exclude=android/app/build --exclude='android/.gradle' \
        melder kar sys fon foned stat dwsys LPC gram EEG dwtools sensors FFNet artsynth \
        external main ios/app android/engine Makefile \
  | (cd "$WORK" && tar xf -)

cd "$WORK"
DIRS="melder kar sys fon foned stat dwsys LPC gram EEG dwtools sensors FFNet artsynth \
external/espeak external/portaudio external/flac external/lame external/mp3 external/glpk \
external/clapack external/gsl external/num external/vorbis external/opusfile external/whispercpp"

# Same defines as android/androidenv.sh, native gcc; -O1 -g for speed + debuggability.
SHARED="-DUNIX -Dlinux -DNO_GRAPHICS -DNO_AUDIO -D_FILE_OFFSET_BITS=64 -O1 -g -fPIC -pthread -Wno-deprecated-declarations"
export CC=gcc CXX=g++ AR=ar RANLIB=ranlib RM="rm -f"
export CFLAGS="-std=gnu99 $SHARED"
export CXXFLAGS="-std=gnu++17 $SHARED -Wno-shadow"
export CPPFLAGS=""

JOBS="$(nproc)"
for d in $DIRS; do
  echo "    [wsl] make -C $d"
  make -j"$JOBS" -C "$d" > /dev/null
done

echo "==> bridge + selftest"
INCLUDES="-Isys -Imelder -Ikar -Ifon -Idwsys -Istat -ILPC -Igram -Idwtools -Iexternal/gsl"
g++ $CXXFLAGS $INCLUDES -x c++ -c ios/app/PraatBridge.mm -o PraatBridge.o
g++ $CXXFLAGS $INCLUDES -c android/engine/PraatBridgeAndroid.cpp -o PraatBridgeAndroid.o
gcc -std=gnu99 -O1 -g -c android/engine/bridge_selftest.c -o bridge_selftest.o

RELLIBS="fon/libfon.a artsynth/libartsynth.a FFNet/libFFNet.a gram/libgram.a EEG/libEEG.a \
 LPC/libLPC.a dwtools/libdwtools.a sensors/libsensors.a foned/libfoned.a \
 stat/libstat.a dwsys/libdwsys.a sys/libsys.a melder/libmelder.a kar/libkar.a \
 external/espeak/libespeak.a external/portaudio/libportaudio.a external/flac/libflac.a \
 external/lame/liblame.a external/mp3/libmp3.a external/glpk/libglpk.a \
 external/clapack/libclapack.a external/gsl/libgsl.a external/num/libnum.a \
 external/vorbis/libvorbis.a external/opusfile/libopusfile.a external/whispercpp/libwhisper.a"

g++ -o bridge_selftest bridge_selftest.o PraatBridge.o PraatBridgeAndroid.o \
  -Wl,--start-group $RELLIBS -Wl,--end-group -lm -lpthread

echo "==> run WITHOUT HOME (bare Android process conditions)"
gdb -batch -ex run -ex bt -ex 'info registers rip' --args ./bridge_selftest || true
echo "==> run WITH HOME (MainActivity setEnv fix)"
mkdir -p /tmp/spraakhome
gdb -batch -ex run -ex bt --args ./bridge_selftest /tmp/spraakhome || true
