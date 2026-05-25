#!/bin/bash
# link-barren.sh — link the iOS (simulator, arm64) headless Praat engine.
# Part of the Spraak derivative. GPL-3.0-or-later.
set -e
cd "$(dirname "$0")/.."
source ios/iosenv.sh >/dev/null

OUT="ios/praat_barren_ios"

# Link order matters (mirrors the upstream Makefile): fon appears twice on purpose.
$CXX $CFLAGS_TARGET -target "$PRAAT_IOS_TARGET" -isysroot "$(xcrun --sdk $PRAAT_IOS_SDK --show-sdk-path)" \
    -o "$OUT" \
    main/main_Praat.o \
    fon/libfon.a artsynth/libartsynth.a FFNet/libFFNet.a gram/libgram.a EEG/libEEG.a \
    LPC/libLPC.a dwtools/libdwtools.a sensors/libsensors.a foned/libfoned.a fon/libfon.a \
    stat/libstat.a dwsys/libdwsys.a sys/libsys.a melder/libmelder.a kar/libkar.a \
    external/espeak/libespeak.a external/portaudio/libportaudio.a \
    external/flac/libflac.a external/lame/liblame.a external/mp3/libmp3.a \
    external/glpk/libglpk.a external/clapack/libclapack.a external/gsl/libgsl.a \
    external/num/libnum.a external/vorbis/libvorbis.a external/opusfile/libopusfile.a \
    external/whispercpp/libwhisper.a \
    -framework CoreFoundation -framework Accelerate -framework Metal -framework Foundation \
    -lc++ -lm

echo "Linked: $OUT"
xcrun --sdk "$PRAAT_IOS_SDK" lipo -info "$OUT"
