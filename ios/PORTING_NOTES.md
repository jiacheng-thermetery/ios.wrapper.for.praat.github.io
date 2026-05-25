# Praat → iOS porting notes

This is the human-readable summary of the modifications made to upstream Praat to build a
headless **compute + scripting core** for iOS (arm64). The authoritative, dated change set
is `git diff master..ios-port`. This is a modified version of Praat under GPL-3.0-or-later;
see `ios/LICENSING.md`.

## Strategy

iOS is the Apple/Darwin family, so the build defines Praat's `macintosh` platform macro (this
makes platform dispatch — threading, byte order, CoreFoundation path normalisation — correct).
It additionally defines:

- **`PRAAT_IOS`** — our marker. Inside `macintosh` code, anything that needs a **macOS-desktop-only**
  facility (Carbon, AppKit/Cocoa, OpenCL, the CoreAudio HAL, AppleEvents, Process Manager,
  `CGMainDisplayID`, `kSystemSoundID_*`) is guarded with `! defined (PRAAT_IOS)` so iOS takes the
  portable/CPU/`#else` fallback.
- **`NO_GRAPHICS`** — Praat's existing "barren" switch (entails `NO_GUI`, so `cocoa == 0`). No window
  system, no drawing backend. All Objective-C in `sys/Gui*.cpp` lives under `#if cocoa` and thus
  compiles away. The native iOS UI / rendering / audio will live in the app shell, not in this core.

The core therefore compiles as **pure C++** (no Objective-C, no AppKit/Carbon), linking only base
frameworks (CoreFoundation, plus libSystem). Build flags live in `ios/iosenv.sh`.

Every edited line is tagged `// [iOS port]` (except the repetitive `Photo.cpp` / `SoundRecorder.cpp`
guard edits, which were applied by anchored `sed` and are listed below).

## Edits by file

### melder/ (base library)
- `melder.cpp` — gate `<Carbon/Carbon.h>` and the `NSProcessInfo` OS-version probe. `Melder_systemVersion`
  stays 0 on iOS (cosmetic; keeps the core Objective-C-free).
- `melder_audio.cpp` — gate `<CoreAudio/CoreAudio.h>`, the CoreAudio-HAL device-change listener, and the
  Carbon Event-Manager Escape-key poll. (PortAudio is built backend-less; live audio comes from the app shell.)
- `melder_play.cpp` — gate `<AudioToolbox/AudioToolbox.h>` and `AudioServicesPlayAlertSound`
  (`kSystemSoundID_*` is macOS-only); iOS uses the `\a` fallback.
- `VEC.cpp` — gate the `<Accelerate/Accelerate.h>` include and `_add_macfast_VEC_out` (already
  call-disabled upstream via `macintoshXXX`). CPU path used. *(Re-enabling Accelerate on iOS is a safe future optimisation.)*
- `MAT.cpp` — gate `<Accelerate>`, `<MetalPerformanceShaders>` and `<OpenCL/opencl.h>` (OpenCL is absent
  on iOS) and the Metal/OpenCL matrix-multiply impls. The `#else mul_MAT_out()` CPU fallback is used.

### sys/ (system + scripting engine + GUI abstraction)
- `Gui.h` — gate the unconditional `<Cocoa/Cocoa.h>` include with `#if cocoa`; gate the legacy Carbon-MLTE
  `GuiText` fields (`TXNObject`/`TXNFrameID`) so iOS uses the generic `#else` fields.
- `GuiP.h`, `Gui.cpp` — gate the AppKit `NSFont` label-font declarations/definitions (used only by the
  cocoa backend).
- `Picture.cpp` — gate the Carbon `Pasteboard`/PDF "copy to clipboard" implementation; add an iOS stub
  `Picture_copyToClipboard` that throws (app shell offers a native share sheet instead).
- `DataEditor.cpp` — gate the Cocoa text-field visibility check; iOS uses the generic `#else`.
- `praat.cpp` — include `<unistd.h>` on the macintosh path (for `isatty`/`getpid`); gate the AppleEvent/
  `sendpraat` IPC, `NSRunningApplication` multiple-instance check, Process-Manager foreground activation,
  and the `NSApplication` bootstrap. iOS is a single-instance, UIApplication-hosted app.
- `praat_statistics.cpp` — gate the AppKit full-disk-access / sandbox probes and the `CGMainDisplayID`
  screen-size report (iOS uses UIScreen, reported from the app shell).

### fon/ (phonetics analysis)
- `Photo.cpp` — gate every `#if/#elif defined (macintosh)` image-I/O block (CoreGraphics + ImageIO) with
  `&& ! defined (NO_GRAPHICS)`, mirroring the existing `#elif defined (linux) && ! defined (NO_GRAPHICS)`.
  No image backend in the barren core; trivially re-enabled in a graphics-enabled iOS build (the code uses
  CoreGraphics/ImageIO, both present on iOS). *(applied by sed)*
- `Praat_tests.cpp` — gate the `NSDate` timing test (macOS-only).

### foned/ (interactive editors)
- `SoundRecorder.{h,cpp}` — gate the macOS live-audio implementation (PortAudio mac-core API, Carbon
  `Str255`) with `! defined (PRAAT_IOS)`. The recorder compiles as a no-backend stub; real recording will
  be provided by the app shell via AVAudioEngine. *(applied by sed)*

## Build

```sh
source ios/iosenv.sh                 # iphonesimulator arm64 by default
#   PRAAT_IOS_SDK=iphoneos PRAAT_IOS_TARGET=arm64-apple-ios15.0  for a real device
for d in kar melder sys dwsys stat fon foned LPC dwtools gram FFNet EEG artsynth sensors; do make -C $d; done
```

External C/C++ libraries (gsl, clapack, glpk, espeak, …) are built separately for the same target;
see `ios/build-core.sh`.
