# Spraak — an *unofficial* iOS & Android app built on Praat

**This is a modified version of Praat, not the original.** "Spraak" is this port's own name (Dutch
for "speech"); it is an **unofficial** iOS & Android derivative and is **not produced, reviewed, or endorsed by
Paul Boersma, David Weenink, or the University of Amsterdam.** "Praat" is used here only descriptively,
to say what engine Spraak runs.

**Spraak** cross-compiles Praat's analysis + scripting engine for iOS (arm64) and adds a SwiftUI front end:
record/analyze with a live spectrogram and pitch/formant/intensity overlays, a native **Objects**
window over the live interpreter, eSpeak text-to-speech, and TextGrid / Manipulation (PSOLA) / Vowel /
ExperimentMFC editors plus a script console. Almost everything new lives in the [`ios/`](ios/)
directory; a small number of core source files carry tagged `// [iOS port]` edits (e.g. an iOS Quartz
Graphics drawing path). For details, build instructions, and the change record see:

- [`ios/README.md`](ios/README.md) — deep dive: what works today, the file map, and design notes
- [`ios/PORTING_NOTES.md`](ios/PORTING_NOTES.md) — every change made to upstream source (GPLv3 §5 record)
- [`ios/LICENSING.md`](ios/LICENSING.md) — license audit & GPL-compliance plan
- [`ios/FEATURES_ROADMAP.md`](ios/FEATURES_ROADMAP.md) — feature status

There is also an **Android port** — same engine, same C bridge, same six tabs, as a Kotlin /
Jetpack Compose app — living in
[`android/`](https://github.com/jiacheng-thermetery/ios.wrapper.for.praat.github.io/tree/android-port/android)
on the **`android-port`** branch. See [the Android section](#android) below.

## iOS ONLY, IGNORE IF YOU ARE ON ANDROID ## #Why you build it yourself — not the app stores#

**This app can't be shipped on the Apple App Store**, and that shapes everything below. The GPL is
incompatible with the App Store Terms of Service (the DRM, device limits, and installation
restrictions the GPL forbids), and Praat has multiple copyright holders, so no one can grant an
App-Store exception. That isn't a shortcoming of this port — it's the flip side of Praat being free
software: instead of tapping *Install*, you get the **source** and run it by **building it yourself**
or **sideloading** onto your own device. So the rest of this README is the build: a Simulator path
that needs no signing, and a real-device path that you sign with your own Apple ID. (Full reasoning:
[`ios/LICENSING.md`](ios/LICENSING.md) §4.)

The Android story is the same in spirit and easier in practice: **we don't distribute binaries
through any app store on Android either** — there is no Play Store listing, and the APKs are
**local sideload builds** (GPL-3.0-or-later, debug-signed, built by you from this source tree).
Sideloading is a first-class, supported path on Android, so no signing accounts or fees are
involved at all. See [Android](#android) below.

## Installation (iOS)

> **New to building iOS apps?** This setup — Xcode, the cross-compile toolchain, and (for a real
> device) code-signing — has a moderately difficult learning curve for your first time and is **not trivial for beginners.** If the steps below
> are over your head, paste this README into your LLM of choice (Claude, ChatGPT, …) and ask it to
> set up the environment with you, step by step, for your machine. For example, try the prompt below:

```
I'd like to install `github.com/jiacheng-thermetery/ios.wrapper.for.praat.github.io` onto my iPhone. It is basically an iOS wrapper for the phonetic analysis software Praat. I am new to compiling, sideloading, signing apps, and this whole installation process. Would you please walk me through step by step for this installation? 
```

Requires [Xcode](https://developer.apple.com/xcode/) with the iOS SDK. All commands run from the
repository root.

### On the iOS Simulator (no signing needed)

```sh
# 1. Build Praat's engine (static libs) for the iOS Simulator (arm64):
source ios/iosenv.sh
for d in kar melder sys dwsys stat fon foned LPC dwtools gram FFNet EEG artsynth sensors; do make -C $d; done
for d in num clapack gsl glpk lame mp3 flac vorbis opusfile espeak portaudio whispercpp; do make -C external/$d; done

# 2. Build, install and launch the SwiftUI app on a booted simulator:
bash ios/app/build-app.sh "iPhone 16"
```

`ios/iosenv.sh` and `ios/app/build-app.sh` default to the system Xcode at `/Applications/Xcode.app`;
set `DEVELOPER_DIR` to override (e.g. `export DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer`).

### Running on a real iPhone (signing & sideloading)

iOS will not launch an app on a physical device unless it is **code-signed** — only the Simulator
runs unsigned builds. Because the GPL keeps this off the App Store (see above), the way onto a
device is to **sign it yourself**, which is free and fully supported.

**For your own phone, just sign it — there are no GPL strings attached** (personal use isn't
"distribution"). Use whatever signing identity you have:

- **Free Apple ID** — works in Xcode (choose your personal team); the build runs on your own
  devices but **expires after 7 days** (re-install to refresh).
- **Paid Apple Developer Program** ($99/yr) — your *development* certificate signs builds that last
  **one year** on your registered devices. If you already have it, use it: it's the least hassle
  (no weekly re-signing). You do **not** need a paid account just to run it on your own phone.

> ⚠️ **The $99/year is Apple's fee, paid in full to Apple — not a subscription from us.** Neither
> this project's contributors nor the original Praat authors provide it, receive any part of it, or
> have any financial relationship with you. This software is **free** in both senses (freedom *and*
> price): we charge nothing and never will. The only thing money buys here is Apple's optional
> convenience — a developer account whose signing certificate lasts a year instead of the free
> account's 7 days. The **free Apple ID path costs $0** and is enough to run the app.

**Steps.** The repo's `build-app.sh` targets the **Simulator** and doesn't sign; for a real device
use the generated Xcode project (`project.yml`, [XcodeGen](https://github.com/yonaskolb/XcodeGen)):

```sh
# 1. Build the engine static libs for the device slice (arm64, iphoneos):
export PRAAT_IOS_SDK=iphoneos PRAAT_IOS_TARGET=arm64-apple-ios15.0
source ios/iosenv.sh
for d in kar melder sys dwsys stat fon foned LPC dwtools gram FFNet EEG artsynth sensors; do make -B -C $d; done
for d in num clapack gsl glpk lame mp3 flac vorbis opusfile espeak portaudio whispercpp; do make -B -C external/$d; done

# 2. Generate Spraak.xcodeproj (compiles the SwiftUI app + C++ bridge, links the libs):
brew install xcodegen
export DEVELOPMENT_TEAM=XXXXXXXXXX        # your 10-char Apple Developer Team ID (or set it in Xcode)
xcodegen generate
```

Then **enable Developer Mode on the iPhone** (*Settings → Privacy & Security → Developer Mode* → on
→ restart), `open Spraak.xcodeproj`, select your connected iPhone as the run destination, and
**Run** (⌘R). With automatic signing, Xcode registers the device with your team, provisions, signs,
installs, and launches. The first launch is blocked as an untrusted developer until you trust it on
the phone: *Settings → General → VPN & Device Management → your profile → Trust*.

> **Note (`-iquote`):** Praat ships `melder/complex.h`, which shadows the C library's `<complex.h>`
> if Praat's dirs are on a plain `-I` path that the Swift Clang importer also sees — that breaks
> every system module. `project.yml` therefore passes the Praat dirs via `-iquote` (quoted-include
> only) on the C++ compile, never via the target-wide header search paths. Don't "simplify" that.

(A free Apple ID works too — leave `DEVELOPMENT_TEAM` unset and pick your personal team in Xcode ▸
target ▸ *Signing & Capabilities*; signatures then expire after 7 days. Or `codesign` the built
`.app` manually and install via `xcrun devicectl device install app` — Xcode is just far less fiddly.)

**Sharing with other people — share the source, not your signed binary.** Free-account signatures
expire in 7 days, ad-hoc distribution caps at 100 registered devices/year, and **Enterprise**
certificates *may not* be used for public distribution (Apple revokes that). Point people at this
repository so each person builds and signs with **their own** Apple ID; **AltStore / SideStore**
automate exactly that (on-device re-signing with the user's Apple ID, and auto-refresh of the
7-day signature). This is also what keeps the project **GPL-clean**: anyone can install their own
**modified** build on their device (which the App Store forbids), and you never have to share your
private signing key — GPLv3's "Installation Information" here is simply *the source plus these
instructions*.

## Android

The Android port lives in
[`android/`](https://github.com/jiacheng-thermetery/ios.wrapper.for.praat.github.io/tree/android-port/android)
on the **`android-port`** branch and mirrors the iOS app. What exists today:

- **The full Praat engine** as `libpraat.so` under the NDK, built with Praat's existing upstream
  **Linux barren** configuration (Android *is* Linux) — **zero engine-source changes** beyond that
  config; the iOS port's tagged edits are all inert on Android.
- **The same C bridge as iOS**: `ios/app/PraatBridge.mm` is plain C++ and compiles for Android
  unchanged, wrapped 1:1 by a thin JNI layer for Kotlin.
- A **Kotlin / Jetpack Compose** app with the same six tabs as the iOS app (Analyze, Objects,
  Vowel, Manipulation, Experiment, Script), including the TextGrid editor.
- **Draw** via Praat's backend-independent **Graphics recording**: the engine records the drawing
  as an opcode stream, and the app replays it onto an Android `Canvas`.
- Live audio through **AudioRecord / AudioTrack** in the app shell (the engine itself builds
  audio-less, exactly like the iOS build).

Build (Android NDK + Gradle), from the repository root on the `android-port` branch:

```sh
android/build-engine-libs.sh arm64-v8a   # 1. Praat's static libs, cross-compiled with the NDK
android/build-bridge.sh arm64-v8a        # 2. bridge + JNI -> libpraat.so in the app's jniLibs
cd android && gradle :app:assembleDebug  # 3. the Compose app, with libpraat.so inside
```

For the change record and design notes see
[`android/PORTING_NOTES.md`](https://github.com/jiacheng-thermetery/ios.wrapper.for.praat.github.io/blob/android-port/android/PORTING_NOTES.md)
(companion to `ios/PORTING_NOTES.md`). As everywhere in this project, the result is a **local
sideload build**: the debug-signed APK goes onto your own device via `adb install` (or any
sideloading route), and is **not distributed through any app store** — GPL-3.0-or-later, source
first, no store listings.

## License

**Free software under GPL-3.0-or-later**, exactly like upstream Praat, and distributed WITHOUT ANY
WARRANTY. Modifications for the iOS and Android ports were made in 2024–2026 by the ports'
contributors. As explained at the top, the GPL is precisely what keeps this off the App Store — so
distribute it as **source** (or a self-signed sideload onto your own device), never as a signed
binary; on Android likewise, APKs stay local sideload builds and off the app stores. See
[`ios/LICENSING.md`](ios/LICENSING.md) §4.

Upstream Praat: <https://github.com/praat/praat> · <https://praat.org>

---

*The original Praat README follows.*

# Praat: doing phonetics by computer

Welcome to Praat! Praat is a speech analysis tool used for doing phonetics by computer.
Praat can analyse, synthesize, and manipulate speech, and create high-quality pictures for your publications.
Praat was created by Paul Boersma and David Weenink of the Institute of Phonetics Sciences of the University of Amsterdam.

Some of Praat’s most prominent features are:

#### Speech analysis

Praat allows you to analyze different aspects of speech including pitch, formant, intensity, and voice quality.
You have access to spectrograms (a visual representation of sound changing over time)
and cochleagrams (a specific type of spectrogram more closely resembling how the inner ear receives sound).

#### Speech synthesis

Praat allows you to generate speech from a pitch curve and filters that you create (acoustic synthesis),
or from muscle activities (articulatory synthesis).

#### Speech manipulation

Praat gives you the ability to modify existing speech utterances. You can alter pitch, intensity, and duration of speech.

#### Speech labelling

Praat allows you to custom-label your samples using the IPA (International Phonetics Alphabet),
and annotate your sound segments based on the particular variables you are seeking to analyze.
Multi-language text-to-speech facilities allow you to segment the sound into words and phonemes.

#### Grammar models

With Praat, you can try out Optimality-Theoretic and Harmonic-Grammar learning,
as well as several kinds of neural-network models.

#### Statistical analysis

Praat allows you to perform several statistical techniques, among which
multidimensional scaling, principal component analysis, and discriminant analysis.

For more information, consult the extensive manual in Praat (under Help),
and the website [praat.org](https://praat.org), which has Praat tutorials in several languages.

## 1. Binary executables

While the [Praat website](https://praat.org) contains the latest executable for all platforms that we support
(or used to support), the [releases on GitHub](https://github.com/praat/praat.github.io/releases) contain many older executables as well.

The meaning of the names of binary files available on GitHub is as follows (editions that currently receive updates are in bold):

### 1.1. Windows binaries
- **`praatXXXX_win-x64v3.zip`: zipped executable for Intel64/AMD64(v3) Windows (10 and higher)**
- **`praatXXXX_win-x64v1.zip`: zipped executable for Intel64/AMD64(v1) Windows (10 and higher)**
- **`praatXXXX_win-arm64.zip`: zipped executable for ARM64 Windows (11 and higher)**
- **`praatXXXX_win-intel32.zip`: zipped executable for Intel32 Windows (7 [until 6.4.52] and higher)**
- `praatXXXX_win-intel64.zip`: zipped executable for Intel64/AMD64(v1) Windows (7 [until 6.4.52] and higher)
- `praatXXXX_win64.zip`: zipped executable for Intel64/AMD64 Windows (XP and higher, or 7 and higher)
- `praatXXXX_win32.zip`: zipped executable for Intel32 Windows (XP and higher, or 7 and higher)
- `praatconXXXX_win64.zip`: zipped executable for Intel64/AMD64 Windows, console edition
- `praatconXXXX_win32.zip`: zipped executable for Intel32 Windows, console edition
- `praatconXXXX_win32sit.exe`: self-extracting StuffIt archive with executable for Intel32 Windows, console edition
- `praatXXXX_win98.zip`: zipped executable for Windows 98
- `praatXXXX_win98sit.exe`: self-extracting StuffIt archive with executable for Windows 98

### 1.2. Mac binaries
- **`praatXXXX_mac.dmg`: disk image with universal executable for (64-bit) Intel and Apple Silicon Macs (Cocoa)**
- **`praatXXXX_xcodeproj.zip`: zipped Xcode project file for the universal (64-bit) edition (Cocoa)**
- `praatXXXX_mac64.dmg`: disk image with executable for 64-bit Intel Macs (Cocoa)
- `praatXXXX_xcodeproj64.zip`: zipped Xcode project file for the 64-bit edition (Cocoa)
- `praatXXXX_mac32.dmg`: disk image with executable for 32-bit Intel Macs (Carbon)
- `praatXXXX_xcodeproj32.zip`: zipped Xcode project file for the 32-bit edition (Carbon)
- `praatXXXX_macU.dmg`: disk image with universal executable for (32-bit) PPC and Intel Macs (Carbon)
- `praatXXXX_macU.sit`: StuffIt archive with universal executable for (32-bit) PPC and Intel Macs (Carbon)
- `praatXXXX_macU.zip`: zipped universal executable for (32-bit) PPC and Intel Macs (Carbon)
- `praatXXXX_macX.zip`: zipped executable for MacOS X (PPC)
- `praatXXXX_mac9.sit`: StuffIt archive with executable for MacOS 9
- `praatXXXX_mac9.zip`: zipped executable for MacOS 9
- `praatXXXX_mac7.sit`: StuffIt archive with executable for MacOS 7

### 1.3. Linux binaries
- **`praatXXXX_linux-x64v3-barren.tar.gz`: gzipped tarred executable for Intel64/AMD64(v3) Linux (Ubuntu, Debian...), without GUI, sound and graphics**
- **`praatXXXX_linux-x64v3.tar.gz`: gzipped tarred executable for Intel64/AMD64(v3) Linux (Ubuntu, Debian...) (GTK 3)
- **`praatXXXX_linux-s390x-barren.tar.gz`: gzipped tarred executable for s390x Linux, without GUI, sound and graphics**
- **`praatXXXX_linux-s390x.tar.gz`: gzipped tarred executable for s390x Linux (GTK 3)**
- **`praatXXXX_linux-arm64-barren.tar.gz`: gzipped tarred executable for ARM64 Linux (Ubuntu, Debian...), without GUI, sound and graphics**
- **`praatXXXX_linux-arm64.tar.gz`: gzipped tarred executable for ARM64 Linux (Ubuntu, Debian...) (GTK 3)**
- `praatXXXX_linux-intel64-barren.tar.gz`: gzipped tarred executable for Intel64/AMD64(v1) Linux (Ubuntu, Debian...), without GUI, sound and graphics**
- `praatXXXX_linux-intel64.tar.gz`: gzipped tarred executable for Intel64/AMD64(v1) Linux (Ubuntu, Debian...) (GTK 3)
- `praatXXXX_linux-arm64-nogui.tar.gz`: gzipped tarred executable for ARM64 Linux, without GUI and sound but with graphics (Cairo and Pango)
- `praatXXXX_linux-intel64-nogui.tar.gz`: gzipped tarred executable for Intel64/AMD64 Linux, without GUI and sound but with graphics (Cairo and Pango)
- `praatXXXX_linux64barren.tar.gz`: gzipped tarred executable for Intel64/AMD64 Linux, without GUI, sound and graphics
- `praatXXXX_linux64nogui.tar.gz`: gzipped tarred executable for Intel64/AMD64 Linux, without GUI and sound but with graphics (Cairo and Pango)
- `praatXXXX_linux64.tar.gz`: gzipped tarred executable for Intel64/AMD64 Linux (GTK 2 or 3)
- `praatXXXX_linux32.tar.gz`: gzipped tarred executable for Intel32 Linux (GTK 2)
- `praatXXXX_linux_motif64.tar.gz`: gzipped tarred executable for Intel64/AMD64 Linux (Motif)
- `praatXXXX_linux_motif32.tar.gz`: gzipped tarred executable for Intel32 Linux (Motif)

### 1.4. Chromebook binaries
- **`praatXXXX_chrome-x64v1.tar.gz`: gzipped tarred executable for Intel64/AMD64(v1) Linux on Intel64/AMD64 Chromebooks (GTK 3)**
- **`praatXXXX_chrome-arm64.tar.gz`: gzipped tarred executable for Linux on ARM64 Chromebooks (GTK 3)**
- `praatXXXX_chrome-intel64.tar.gz`: gzipped tarred executable for Intel64/AMD64(v1) Linux on Intel64/AMD64 Chromebooks (GTK 3)
- `praatXXXX_chrome64.tar.gz`: gzipped tarred executable for 64-bit Linux on Intel64/AMD64 Chromebooks (GTK 2 or 3)

### 1.5. Raspberry Pi binaries
- **`praatXXXX_rpi-armv7.tar.gz`: gzipped tarred executable for (32-bit) ARMv7 Linux on the Raspberry Pi 4B (GTK 3)**
- `praatXXXX_rpi_armv7.tar.gz`: gzipped tarred executable for (32-bit) ARMv7 Linux on the Raspberry Pi 4B (GTK 2 or 3)

### 1.6. Other Unix binaries (all obsolete)
- `praatXXXX_solaris.tar.gz`: gzipped tarred executable for Sun Solaris
- `praatXXXX_sgi.tar.gz`: gzipped tarred executable for Silicon Graphics Iris
- `praatXXXX_hpux.tar.gz`: gzipped tarred executable for HP-UX (Hewlett-Packard Unix)

## 2. Compiling the source code

You need the Praat source code only in the following cases:

1. you want to extend Praat’s functionality by adding C or C++ code to it; or
2. you want to understand or reuse Praat’s source code; or
3. you want to compile Praat for a computer for which we do not provide binary executables,
e.g. Linux for some non-Intel computers, FreeBSD, HP-UX, SGI, or SPARC Solaris.

Before trying to dive into Praat’s source code, you should be familiar with the working of the Praat program
and with writing Praat scripts. The Praat program can be downloaded from
https://praat.org or https://www.fon.hum.uva.nl/praat.

### 2.1. License

Most of the source code of Praat is distributed on GitHub under the General Public License,
[version 2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) or later,
or [version 3](https://praat.org/manual/General_Public_License__version_3.html) or later.
However, as Praat includes software written by others,
the whole of Praat is distributed under the General Public License,
[version 3](https://praat.org/manual/General_Public_License__version_3.html) or later.
See [Acknowledgments](https://praat.org/manual/Acknowledgments.html) for details on the licenses
of software libraries by others that are included in Praat.
Of course, any improvements in the Praat source code are welcomed by the authors.

### 2.2. Downloading the archive

To download the latest source code of Praat from GitHub,
click on the *zip* or *tar.gz* archive at the latest release,
or fork ("clone") the praat/praat repository at any later change.

### 2.3. Steps to take if you want to extend Praat

First make sure that the source code can be compiled as is.
Then add your own buttons by editing `main/main_Praat.cpp` or `fon/praat_Fon.cpp`.
Consult the manual page on [Programming](https://praat.org/manual/Programming_with_Praat.html).

### 2.4. The programming language

Most of the source code is written in C++, but some parts are written in C.
The code requires that your compiler supports C99 and C++17.

## 3. Developing Praat for one platform

See [HOW_TO_BUILD_ONE.md](HOW_TO_BUILD_ONE.md).

## 4. Developing Praat on all platforms simultaneously

See [HOW_TO_BUILD_ALL.md](HOW_TO_BUILD_ALL.md).
