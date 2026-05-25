# Spraak — *unofficial* derivative

**This is a modified version of Praat, not the original.** It is an **unofficial** iOS port and is
**not produced, reviewed, or endorsed by Paul Boersma, David Weenink, or the University of Amsterdam.**

It cross-compiles Praat's analysis + scripting engine for iOS (arm64) and adds a SwiftUI front end:
record/analyze with a live spectrogram and pitch/formant/intensity overlays, a native **Objects**
window over the live interpreter, eSpeak text-to-speech, and TextGrid / Manipulation (PSOLA) / Vowel /
ExperimentMFC editors plus a script console. Almost everything new lives in the [`ios/`](ios/)
directory; a small number of core source files carry tagged `// [iOS port]` edits (e.g. an iOS Quartz
Graphics drawing path). For details, build instructions, and the change record see:

- [`ios/README.md`](ios/README.md) — what the port is and how to build & run it
- [`ios/PORTING_NOTES.md`](ios/PORTING_NOTES.md) — every change made to upstream source (GPLv3 §5 record)
- [`ios/LICENSING.md`](ios/LICENSING.md) — license audit & GPL-compliance plan
- [`ios/FEATURES_ROADMAP.md`](ios/FEATURES_ROADMAP.md) — feature status

**License:** free software under **GPL-3.0-or-later**, exactly like upstream Praat, and distributed
WITHOUT ANY WARRANTY. Modifications for the iOS port were made in 2024–2026 by the port's contributors.
Because the GPL is incompatible with the Apple App Store Terms of Service, **this build cannot be
distributed via the App Store** — use it by building from source or sideloading.

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
