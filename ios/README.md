# Spraak (unofficial)

An **unofficial** iOS port of [Praat](https://praat.org) — *doing phonetics by computer*,
by Paul Boersma & David Weenink (University of Amsterdam). This derivative is **not produced
or endorsed by the original Praat authors**. It is free software under
**GPL-3.0-or-later**, exactly like upstream Praat (see [LICENSING.md](LICENSING.md)).

It brings Praat's analysis + scripting engine — pitch, formants, spectrum, intensity,
the full Praat scripting language, etc. — to iOS (arm64), with a small SwiftUI front end.

> **Installing / building / sideloading?** Those instructions now live in the
> [**root README → Installation**](../README.md#installation) — the project's front-door install
> guide. This document is the technical deep-dive: what works, the design, and the file map.

## What works today

- The complete Praat **compute + scripting engine** is cross-compiled for iOS (all 14 Praat
  libraries + all 12 bundled `external/` libraries), verified running on the iOS 18.1 Simulator.
- A SwiftUI app (`ios/app/`) with six tabs (the 6th collapses into iOS "More"). The **Analyze** view and
  the **Objects** window share one live engine, so sounds flow between them:
  - **Analyze** — record from the microphone (AVAudioEngine), load a demo sound, **Speak** (eSpeak TTS),
    or **Open** an audio file (WAV/AIFF/CAF/m4a/mp3); recorded/opened/spoken sounds also appear in
    **Objects**. See a live **spectrogram** with **pitch** (cyan), **formant** (red), and **intensity**
    (yellow) overlays — computed by Praat's real DSP (`Sound_to_Spectrogram_e`, `Sound_to_Pitch`,
    `Sound_to_Formant_burg`, `Sound_to_Intensity`). Tap to place a **movable cursor** (drag its handle)
    and read the **spectral slice** at that point (Praat's Cmd+L); **drag to select** a time range and
    drag the pink **edge handles** to resize it. A read-out under the cursor shows **F0, F1–F4 and
    intensity**. A quick **Play** button (next to the overlay toggles) plays from the cursor to the end
    of the window, or just the selection. A **TextGrid-style annotation tier** places interval
    boundaries with labels. A **Time** menu mirrors Praat's Time-domain commands (Zoom…/Show all/Zoom
    in/out/to selection/back, Scroll page) and re-analyses the visible window as you zoom; an **Audio**
    menu plays the window/selection/a numeric range; a **gear** opens **Analysis settings** (spectrogram,
    pitch, formant, intensity parameters) that re-run the analyses. The view **scrolls** so every panel
    is reachable in landscape.
  - **Objects** — a native Praat "Objects window": create (New, incl. **Record mono Sound**) / open (any
    Praat file) objects, select them, and run commands (a per-class palette, parameter forms, or any
    typed command) through the live interpreter — including **filter / resample / scale / convert** and a
    **Combine** menu (Combine-to-stereo, Concatenate). **Draw** renders the selected object to a PNG via
    Praat's own Quartz graphics; **Save** shares it as WAV/text; **Analyze** sends the selected Sound to
    the Analyze tab. This exposes the engine's ~3000 commands without writing full scripts.
  - **Vowel** — drag an F1×F2 point to formant-synthesize and hear a vowel, with IPA reference vowels and
    an F0 slider.
  - **Manip.** — Praat **Manipulation/PSOLA**: `Sound_to_Manipulation` → drag a few pitch-tier points
    (a reusable curve-tier editor) → `Manipulation_to_Sound` overlap-add resynthesis, played back.
  - **Experiment** — an **ExperimentMFC** perception-experiment runner (built-in tone-height demo or a
    user-opened `.MFCexperiment`); plays each trial's stimulus, records the forced-choice response and
    reaction time, and exports CSV.
  - **Script** — type a Praat script, tap Run, see the Info-window output.
- A headless CLI (`ios/praat_barren_ios`) runs `--run script.praat` under `simctl spawn`.

Praat does the numbers; SwiftUI does the drawing (the bridge in `ios/app/PraatBridge.mm` returns the
spectrogram dB matrix and the analysis arrays, which `PraatModel.swift` renders as a `CGImage` + Canvas
overlays). This native-rendering approach sidesteps Praat's macOS-only Cocoa Graphics backend.

## Not yet ported

- The interactive **Cocoa editors** (SoundEditor, TextGridEditor, …) are **replaced** by native SwiftUI
  views (Analyze, TextGrid, Vowel, Manipulation, ExperimentMFC) rather than ported pixel-for-pixel.
- Object **drawing** works via Praat's real Quartz Graphics backend rendered to **PNG** (the Objects
  "Draw" button); a full interactive **Picture window** with vector **EPS/PDF** export and click-to-draw
  is not yet built.
- A handful of **niche grid editors** (KlattGrid, OT-grammar, Table/Matrix/Strings, a full Spectrum band
  editor) are not yet native — all remain usable through the **Script** tab. See
  [`FEATURES_ROADMAP.md`](FEATURES_ROADMAP.md) §9.

## Install, build & run

Build, run, and real-device **sideloading** instructions are in the
[**root README → Installation**](../README.md#installation) — that's the project's front-door install
guide. In brief, from the repo root: build the engine libraries with `source ios/iosenv.sh` then
`make` each one, then run `bash ios/app/build-app.sh "iPhone 16"` for the Simulator.

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
| `app/PraatBridge.{h,mm}` | C bridge: script runner, analysis extraction (spectrogram/pitch/formant/intensity/slice), object↔analysis sync, Manipulation, ExperimentMFC, draw-to-PNG |
| `app/PraatModel.swift` | Swift model; builds the spectrogram `CGImage` and analysis curves |
| `app/AudioEngine.swift` | AVAudioEngine mic recording + playback, level/timer, demo sound synth, `RecordingBanner` |
| `app/SpectrogramView.swift` | spectrogram + overlays, annotation tier, spectral-slice views |
| `app/ContentView.swift` | tab shell + shared `AppStore`, Analyze view, Speak/About/license screens |
| `app/ObjectsView.swift` | native Objects window (command runner, Record, Analyze, Draw, Save, Combine) |
| `app/CommandForms.swift` | parameter-form specs/renderer for "…" commands and the New menu |
| `app/SettingsView.swift` | analysis-settings dialog |
| `app/TextGridEditor.swift` | multi-tier TextGrid editor + `.TextGrid` export |
| `app/VowelView.swift` | F1×F2 vowel-synthesis editor |
| `app/ManipulationView.swift` | Manipulation/PSOLA editor + reusable `TierCurveView` |
| `app/ExperimentMFCView.swift` | ExperimentMFC perception-experiment runner |
| `app/test_bridge.mm`, `app/test_cmds.mm` | `simctl spawn` harnesses verifying the bridge + command strings on the real engine (not built into the app) |
| `app/build-app.sh` | compile + bundle + install + launch the app on a simulator |
| `LICENSING.md` | license audit + GPL compliance plan |
| `PORTING_NOTES.md` | every source change made for the port |
| `FEATURES_ROADMAP.md` | feature inventory + status |
