# Spraak (unofficial)

An **unofficial** iOS port of [Praat](https://praat.org) — *doing phonetics by computer*,
by Paul Boersma & David Weenink (University of Amsterdam). This derivative is **not produced
or endorsed by the original Praat authors**. It is free software under
**GPL-3.0-or-later**, exactly like upstream Praat (see [LICENSING.md](LICENSING.md)).

It brings Praat's analysis + scripting engine — pitch, formants, spectrum, intensity,
the full Praat scripting language, etc. — to iOS (arm64), with a small SwiftUI front end.

## What works today

- The complete Praat **compute + scripting engine** is cross-compiled for iOS (all 14 Praat
  libraries + all 12 bundled `external/` libraries), verified running on the iOS 18.1 Simulator.
- A SwiftUI app (`ios/app/`) embeds the engine: type a Praat script, tap **Run**, see the
  Info-window output. Pitch/spectrum/intensity analysis all run natively on device.
- A headless CLI (`ios/praat_barren_ios`) runs `--run script.praat` under `simctl spawn`.

## Not yet ported (stubbed, documented in PORTING_NOTES.md)

- **Audio** playback/recording — Praat's PortAudio/CoreAudio path is macOS-only; iOS should use
  AVAudioEngine from the app shell (the engine links a no-backend PortAudio so it builds & runs).
- **Graphics rendering** — the Picture window / spectrogram drawing (the macOS path uses
  CoreGraphics, which *is* available on iOS, so this is a tractable next step).
- The interactive Cocoa editors (SoundEditor, etc.) — to be replaced by native SwiftUI editors.

## Build & run

Requires Xcode with the iOS SDK. From this repo root (`upstream-praat`):

```sh
# 1. Build the engine (static libs) for the iOS Simulator (arm64):
source ios/iosenv.sh
for d in kar melder sys dwsys stat fon foned LPC dwtools gram FFNet EEG artsynth sensors; do make -C $d; done
for d in num clapack gsl glpk lame mp3 flac vorbis opusfile espeak portaudio whispercpp; do make -C external/$d; done

# 2. Build, install and launch the SwiftUI app on a booted simulator:
bash ios/app/build-app.sh "iPhone 16"
```

For a **real device**, set `PRAAT_IOS_SDK=iphoneos PRAAT_IOS_TARGET=arm64-apple-ios15.0`
before building, and code-sign the `.app` with your provisioning profile.

`ios/iosenv.sh` assumes Xcode at `~/Downloads/Xcode-beta.app` (via `DEVELOPER_DIR`); edit it
for your install.

## Distribution & the App Store

**This app cannot be distributed through the Apple App Store.** The GPL is incompatible with
the App Store Terms of Service (DRM / device limits / installation restrictions), and Praat has
multiple copyright holders, so no third party can grant an App-Store exception. Distribute it as
**source**, and run it by **building it yourself** or **sideloading** (e.g. a free-account 7-day
signed build, or AltStore/SideStore). See [LICENSING.md](LICENSING.md) §4.

## Your rights

This is free software: you may use, study, share and modify it under GPL-3.0-or-later. When you
convey a binary, you must provide (or offer) the **complete corresponding source** — this entire
repository at the commit you built, including `ios/`. The full license text is in
[`main/gpl-3.0.txt`](../main/gpl-3.0.txt).

## Files in `ios/`

| File | Purpose |
|------|---------|
| `iosenv.sh` | cross-compile toolchain/flags (simulator arm64 by default) |
| `link-barren.sh` | link the headless `praat_barren_ios` engine |
| `pa_ios_hostapis.c` | empty PortAudio host-API table for iOS |
| `selftest.praat` | phonetics self-test script |
| `app/` | the SwiftUI app + C bridge + `build-app.sh` |
| `LICENSING.md` | license audit + GPL compliance plan |
| `PORTING_NOTES.md` | every source change made for the port |
