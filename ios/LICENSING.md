# Licensing & GPL compliance — Spraak derivative

This document records the licensing audit for this derivative work and the steps taken
to comply with the GNU General Public License. **This derivative remains licensed under
the GNU General Public License, version 3 or later (GPL-3.0-or-later)**, exactly like
upstream Praat. Nothing here relicenses any code.

## 1. Upstream license

Praat (© Paul Boersma & David Weenink, University of Amsterdam) is free software under the
GNU GPL. Individual source files carry one of two compatible notices:

- *"either version 3 of the License, or (at your option) any later version"* (GPL-3.0-or-later), e.g. `melder/melder.h`.
- *"either version 2 of the License, or (at your option) any later version"* (GPL-2.0-or-later), e.g. `melder/melder.cpp`.

Both are upward-compatible; combined, the work is distributable under **GPL-3.0-or-later**.
The full license text ships in the repository at `main/gpl-3.0.txt`.

### No GPLv2-*only* code
A full-tree scan for files claiming "version 2 of the License" but lacking the
"or (at your option) any later version" clause found **none**. The only file initially
flagged, `melder/regularExp.cpp` (Henry Spencer's regex as adapted by NEdit), is in fact
GPL-2.0-*or-later* (the words "any later version" are split across two lines) and additionally
carries the original permissive Spencer notice plus a Motif-linking exception — all GPL-compatible.

## 2. Bundled third-party code (`external/`)

| Component | Upstream license | GPLv3-compatible? |
|-----------|------------------|-------------------|
| `gsl` (GNU Scientific Library) | GPL-3.0-or-later | yes (is GPL) |
| `glpk` (GNU Linear Programming Kit) | GPL-3.0-or-later | yes (is GPL) |
| `espeak` (eSpeak NG TTS) | GPL-3.0-or-later | yes (is GPL) |
| `mp3` (libmad) | GPL-2.0-or-later | yes |
| `lame` (LAME) | LGPL-2.0-or-later | yes |
| `clapack` (CLAPACK) | BSD-3-Clause (LAPACK) | yes |
| `flac` (libFLAC) | BSD-3-Clause (Xiph) | yes |
| `vorbis` / `opusfile` | BSD-3-Clause (Xiph) | yes |
| `portaudio` | MIT-like | yes |
| `whispercpp` (+ Silero-VAD, Whisper) | MIT | yes |
| `num` (median-of-ninthers etc.) | permissive | yes |

Because gsl, glpk and espeak are themselves GPL-3.0-or-later, the **combined work is necessarily
GPL-3.0-or-later** — there is no way to make it more permissive, which matches the project goal
("the resulting software stays GNU"). All bundled components are GPLv3-compatible; there is no
GPLv2-only component that would conflict with the GPLv3 pieces.

## 3. Obligations we meet as distributor of a modified version

GPL-3.0 §5 ("Conveying Modified Source Versions") requires:

- **§5(a) — mark modifications with dates.** Every source change for this port is committed on
  the `ios-port` git branch (so `git diff master..ios-port` is the authoritative, dated change set)
  and each edited line is tagged with a `// [iOS port]` comment. A human-readable summary is in
  `ios/PORTING_NOTES.md`.
- **§5(b) — keep the whole work under GPL.** Done: GPL-3.0-or-later, unchanged.
- **§5(c) — keep all copyright/license notices intact.** No copyright header was removed or altered.
- **§4 / §6 — provide Corresponding Source.** This derivative is distributed as source. Any binary
  (e.g. an `.ipa` built for sideloading) must be accompanied by, or offer, the complete corresponding
  source — i.e. this repository at the built commit, including `ios/` and the Xcode project.

## 4. The App Store problem (important)

**The Apple App Store cannot be used to distribute this app.** Apple's App Store Terms of Service
impose usage restrictions and DRM (device limits, FairPlay) that constitute "further restrictions"
forbidden by GPL-3.0 §10, and §6's installation-information requirement for User Products also
conflicts. This is the same conflict that removed VLC from the App Store in 2011. It cannot be
cured by a third party: Praat has multiple copyright holders, so only *they* could grant an
App-Store exception, and they have not.

**Therefore the supported distribution paths are GPL-clean ones that do not go through the App Store:**

- Building from source in Xcode and running on the Simulator or a connected device.
- Sideloading a self-built `.ipa` (free Apple Developer account 7-day signing, or AltStore/SideStore).
- Developer/enterprise/ad-hoc provisioning for one's own devices.

If App Store distribution were ever desired, it would require written permission from **all** Praat
copyright holders (Boersma, Weenink, and contributors) — that is the user's call to pursue, not
something this port can grant.

## 5. Naming / non-endorsement

This is an **unofficial** modified version, not produced or endorsed by the original Praat authors.
The About screen and README state this clearly, as GPL §5(a) good practice and to avoid implying
endorsement.
