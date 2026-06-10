# Praat → Android porting notes

Human-readable summary of how the Android port of Spraak is built. Companion to
`ios/PORTING_NOTES.md` (read that first — the Android port reuses its architecture).
This is a modified version of Praat under GPL-3.0-or-later; see `ios/LICENSING.md`.

## Strategy

Android **is** Linux (bionic libc), so unlike iOS — which had to masquerade as
`macintosh` with a `PRAAT_IOS` marker — the Android engine build simply uses Praat's
existing **Linux barren** configuration (upstream: `make PRAAT_GRAPHICS=barren
PRAAT_AUDIO=none`), cross-compiled with the NDK's clang:

- `-DUNIX -Dlinux` — Praat's Linux platform identity.
- `-DNO_GRAPHICS` — the barren switch (entails `NO_GUI`): no GTK, no X11, no drawing
  backend. On-screen rendering lives in the Compose app shell; the Objects-window
  "Draw" goes through the Graphics *recording* mechanism (below).
- `-DNO_AUDIO` — upstream switch that drops the OSS (`<sys/soundcard.h>`) code paths;
  bionic does not ship that header. With no `ALSA`/`JACK` defines either, PortAudio
  builds its UNIX host-API table empty (backend-less, the same effect as
  `ios/pa_ios_hostapis.c`). Live audio is provided by the app shell via
  AudioRecord/AudioTrack.
- `-fPIC` — the static libs are linked into `libpraat.so` (a JNI library), unlike the
  iOS build which linked them into the app executable.

**Zero Praat source files needed modification for the Android engine** — the
`NO_AUDIO` define was the only adjustment beyond the iOS port's existing edits
(which are all inert here: they live under `macintosh`/`PRAAT_IOS` guards).

## Layout

```
android/androidenv.sh         NDK toolchain env (mirrors ios/iosenv.sh)
android/build-engine-libs.sh  builds the 26 static libs per ABI (mirrors iOS script)
android/build-bridge.sh       compiles the bridge + JNI, links libpraat.so into app/src/main/jniLibs/
android/engine/               PraatBridgeAndroid.{h,cpp} + praat_jni.cpp
android/app/                  Gradle/Kotlin/Compose application
```

The C bridge is **shared with iOS**: `ios/app/PraatBridge.mm` is plain C++ (despite
the `.mm` name) and is compiled for Android unchanged (`-x c++`). Its ~30-function
API (`ios/app/PraatBridge.h`) is wrapped 1:1 by `android/engine/praat_jni.cpp` for
Kotlin (`PraatEngine.kt`). JNI strings are converted manually in both directions
because JNI's `NewStringUTF` expects *modified* UTF-8 and Praat emits real UTF-8.

## Graphics ("Draw") — the Android rewrite

iOS renders Draw to PNG through Praat's Quartz backend (`PRAAT_IOS_GRAPHICS`).
Android has no Quartz/Cairo/GDI, so the port uses Praat's backend-independent
**Graphics recording** (`sys/Graphics_record.cpp`): `PraatBridgeAndroid.cpp` draws
the selected object into a recording Graphics and exports the raw opcode stream
(`[opcode, nArgs, args...]*` as doubles); `PraatPicture.kt` replays it onto an
`android.graphics.Canvas` (the Kotlin equivalent of `Graphics_play`), with text via
`Paint`/`Typeface` and the spectrogram cell-array as a greyscale `Bitmap`.

## Engine build

```sh
# NDK r27c; see android/androidenv.sh for ANDROID_NDK_ROOT probing
android/build-engine-libs.sh arm64-v8a     # 26 static libs -> android/build-libs/arm64-v8a/
android/build-bridge.sh arm64-v8a          # libpraat.so -> android/app/src/main/jniLibs/arm64-v8a/
```

`libpraat.so` links with `-Wl,--no-undefined` (everything resolves), static libc++,
and `-Wl,-z,max-page-size=16384` for Android 15+ 16-KB-page devices. The app builds
with Gradle/AGP (`android/`), minSdk 26, and is signed with the debug key for local
sideloading only — **the APK is not distributed** (GPL source-offer obligations are
moot for private use, but if it is ever distributed, distribute it under GPL-3 with
this source tree, which is App-Store-incompatible but F-Droid-friendly).

## Threading

The engine's object table is global and unsynchronised. All `PraatEngine` calls are
funnelled through a single-thread dispatcher in `PraatViewModel` (the Kotlin
counterpart of the iOS app calling the bridge only from the main actor).
