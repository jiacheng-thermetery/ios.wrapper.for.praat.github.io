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
with Gradle/AGP (`android/`), minSdk 26. Release builds sign with the Spraak release
key when the gitignored `android/keystore.properties` is present (debug-key fallback
otherwise); APKs are published only as sideload artifacts on this repository's GitHub
Releases, each tagged to its exact source commit (GPL §6 corresponding source) —
no app-store distribution (App-Store-incompatible but F-Droid-friendly).

## Threading

The engine's object table is global and unsynchronised. All `PraatEngine` calls are
funnelled through a single-thread dispatcher in `PraatViewModel` (the Kotlin
counterpart of the iOS app calling the bridge only from the main actor).

## Emulator (use `android/run-emulator.sh`)

Findings from a long debugging session, recorded so nobody repeats it:

- **Always run accelerated (`-accel on`).** Emulator 36.x with `-accel off` (TCG)
  crashes with an access violation during netsim WiFi/Bluetooth bring-up *before
  any guest vCPU starts*; when crashpad mishandles that crash the emulator turns
  into a zombie that sits forever at ~0 CPU with adb "offline". Software emulation
  is not a fallback here — it is broken.
- **Don't trust WMI for acceleration capability.** On a Windows guest where a
  hypervisor is already running (VBS / VirtualMachinePlatform),
  `Win32_Processor` reports `VirtualizationFirmwareEnabled=False` because the
  hypervisor owns VT-x — yet **WHPX works**. The authoritative probe is
  `emulator-check accel` ("WHPX … is installed and usable"). AEHD, by contrast,
  needs direct VT-x and can never load in that configuration.
- **Windows launcher gotchas:** the qemu child process needs `<sdk>/emulator`,
  `<sdk>/emulator/lib64`, `<sdk>/emulator/lib64/qt/lib` on `PATH` (else it dies
  instantly with `STATUS_DLL_NOT_FOUND`), and stale `*.lock` files in the AVD
  directory after a kill make the launcher abort with a bogus
  "multiple emulators" error.
- **Binary I/O from PowerShell:** never `adb exec-out screencap -p > file.png`
  (PowerShell redirection re-encodes the stream and corrupts it); use
  `adb shell screencap -p /sdcard/s.png` + `adb pull`.

With WHPX the API-35 x86_64 image boots headless in well under a minute, and the
x86_64 `libpraat.so` (same build scripts, ABI `x86_64`) runs the full analysis
pipeline there — so app changes can be verified on the emulator before they ever
touch a device.
