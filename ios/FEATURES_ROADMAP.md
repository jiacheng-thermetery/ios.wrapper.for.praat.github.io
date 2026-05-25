# Spraak — feature roadmap

What Praat can do, what this wrapper exposes today, and how to reasonably implement the rest on iOS.

## 0. The scale of Praat (measured from this repo)

| Surface | Count | Notes |
|---|---:|---|
| Object classes (with menu commands) | **137** | Sound, Pitch, Formant, Spectrum, TextGrid, Table, Matrix, KlattGrid, OTGrammar, … |
| Object-action commands (`praat_addAction*`) | **3192** | the dynamic menu you get when an object is selected |
| Fixed Objects-window menu commands | **427** | New / Open / Save / Praat / Goodies / Settings / Technical / Help |
| Editors (`*Editor` classes) | **37** | Sound, TextGrid, Manipulation, Pitch, Spectrum, FormantGrid, Vowel, OTGrammar, … |
| Editor menu commands | **299** | per-editor menus |

**The single most important fact:** this wrapper already embeds the *complete* Praat engine. Every one
of those 3192 commands and the entire scripting language **already runs today** through the **Script**
tab (`praat_executeScriptFromText`). Nothing is missing *computationally*. The gap is almost entirely
**GUI exposure** — letting users reach the engine without writing a script.

## 1. Strategy — five ways to "implement" a Praat feature on iOS

Every Praat feature maps to one of these, in rough order of leverage:

1. **Scriptable now (T0).** Already works via the Script tab. Implementing = nothing, or a convenience button.
2. **Objects window + command runner (T1).** A native object list where selecting an object and tapping a
   command runs the equivalent script line through the live interpreter (objects persist between calls).
   One generic mechanism exposes *thousands* of commands. **Highest leverage.**
3. **CoreGraphics `Graphics` backend (T2).** Praat's macOS drawing backend *is* Core Graphics, which exists
   on iOS. Implementing a `GraphicsScreen` that draws into a `CGContext` unlocks **all** `Draw…/Paint…`
   /plot commands and the **Picture window** at once.
4. **Native SwiftUI editor (T3).** Reimplement an interactive editor (Sound, TextGrid, Manipulation, …) in
   SwiftUI over the engine. High effort *per editor*; do the high-value ones.
5. **Generic form renderer (T4).** Praat commands ending in "…" pop a settings form (`UiForm`). A renderer
   that turns a `UiForm` field list into SwiftUI controls makes *every* parameterised command usable.

> Guiding principle: prefer T1+T2+T4 (generic mechanisms that expose hundreds of features each) over T3
> (one editor at a time).

## 2. What the wrapper exposes today

- **Engine + scripting:** full Praat scripting language (Script tab) — *all* analysis/synthesis/stats.
- **Sound editor (native, partial):** record (mic) / open file / demo; spectrogram with pitch, formant and
  intensity overlays; movable cursor + draggable selection; spectral slice (Cmd+L); zoom/scroll/selection
  (Time menu); play window/selection (Audio menu); cursor value read-outs; analysis settings
  (spectrogram/pitch/formant/intensity); a basic TextGrid-style annotation tier.

Everything below is **not yet exposed natively** (but is scriptable today).

## 3. Objects window & the object system  — *T1, highest priority*

Praat's core workflow is missing: a **list of objects** you create/import, select, and act on via a
**dynamic command menu**. ~137 classes, 3192 commands hang off this.

**iOS approach (T1):** a SwiftUI **Objects** tab.
- A `List` of live objects (id, class, name) read from the engine's object table via new bridge calls
  (`praatios_objectCount/Info/select/remove`). Objects already persist across `praat_executeScriptFromText`
  calls, so the engine state is the model.
- **New** and **Open** populate it; selecting an object shows the **commands** valid for its class.
- Tapping a command runs the generated script line (`selectObject: id` + command) through the interpreter;
  new objects appear in the list, Info text shows in a results pane. Parameterised commands ("…") open a
  **form** (T4) or, initially, a prefilled editable script line.
- Per-object **Save** (write to Files via `Data_writeToTextFile`/binary or `Save as WAV`).

**Effort:** medium for the list + run-via-script + results; the form generator (T4) is a follow-up. This
one feature turns the app from "spectrogram + console" into "Praat".

## 4. The "New" menu — object creation  — *T1/T4*

Dozens of `Create …` commands (each a form). Examples: Sound (from formula / pure tone / tone complex /
silence), Matrix, Table, TextGrid, PitchTier/DurationTier/IntensityTier/AmplitudeTier/FormantGrid,
Polygon, Permutation, Strings, Photo, **KlattGrid** (Klatt synthesis), **Artword/Articulation** (articulatory
synthesis), **FFNet / Net** (neural nets), **HMM**, **OT grammars** (NoCoda, Create …), Configuration,
Categories, Corpus, FileInMemory, etc.

**iOS approach:** these are just parameterised commands → render via the **form generator (T4)** in the
Objects window. No new engine code. Effort: comes "for free" once T1+T4 exist; until then, scriptable.

## 5. Open / Save — file I/O & formats  — *T1, partly done*

- **Open (done, partial):** audio via `.fileImporter` + `AVAudioFile`. **To generalise:** route any picked
  file through `Data_readFromFile` (auto-detects Praat's own formats, TextGrid, Table, Matrix, …) and add
  the format-specific readers (raw 16-bit, A-law, Buckeye/TIMIT/Xwaves/ESPS annotation, CSV/TSV tables, …).
- **Save (todo):** write the selected object to the Files app — `Save as WAV/AIFF/FLAC/MP3` for Sound,
  `Data_writeToTextFile`/binary for everything, CSV for Table, `.TextGrid` for TextGrid, PNG/PDF for Picture.
  Use a `.fileExporter`/`UIDocumentPicker`. **Effort:** small–medium; all writers exist in the engine.

## 6. Object-action commands by class group  — *T1 (+ T2 for the Draw… ones)*

All scriptable now; exposed natively once the Objects window (T1) lands. Major groups (commands each):

| Group | Representative classes (cmds) | iOS exposure |
|---|---|---|
| **Acoustics** | Sound (235), Pitch (71), Spectrum (64), PointProcess (56), Formant (42), Ltas (38), Intensity (25), Harmonicity (19), Spectrogram (18), Cepstrum (30/23) | T1 for Query/Modify/Convert; T2 for the `Draw…` half |
| **Annotation** | TextGrid (89), TextGridNavigator (32) | T1 + native TextGrid editor (T3) |
| **Tables / stats** | Table (117), TableOfReal (58), Discriminant (44), DataModeler (44), PCA (20), Configuration/MDS (20), SSCP/Covariance/Correlation | T1; results as text/tables; `Draw…` via T2 |
| **Synthesis** | KlattGrid (151), LPC (28), source/filter | T1 to build/play; KlattGrid/Vowel editors are T3 |
| **Learning** | OTGrammar (50), OTMulti (30), FFNet (31), HMM (23), Net (18), Network (28) | T1 (train/test/Info); editors T3 |
| **Modelling** | FormantModeler (50), FormantPath (22), DataModeler (44) | T1; `Draw…` via T2 |
| **Signals/▸** | DTW (46), Permutation (29), Polygon (28), Strings (32), Matrix (83), Photo (21), EEG (40), ERP (27) | T1; EEG/ERP & Photo need draw (T2) |

Roughly **half** of these commands are `Get…/Query…/Modify…/To …/Extract…` → pure T1 (run + show
result/new object). The other half are `Draw…/Paint…` → need **T2** (Graphics backend) to render.

## 7. Picture window & Graphics  — *T2, very high value*

Praat's **Picture window** is where all plots go (`Draw…`, `Paint…`, axes, text, etc.). Not exposed at all.

**iOS approach (T2):** implement a `Graphics` screen backend over **Core Graphics** (`CGContext`) — the same
API Praat's macOS backend uses (currently compiled out by `NO_GRAPHICS`). Then a SwiftUI **Picture** tab
hosts a `CGContext`-backed view, and *every* `Draw…`/`Paint…` command renders into it. Export to **PNG/PDF**
(CoreGraphics native). This single backend unlocks hundreds of plotting commands and the spectrogram/pitch
"Draw visible … " editor commands. **Effort:** medium-high (port the `GraphicsScreen` Quartz path + font
metrics), but it's *one* implementation for a huge feature surface. Alternative interim: keep computing in
Swift (as the Analyze tab already does) for the few plots we care about.

## 8. Settings/forms (`UiForm`)  — *T4*

Parameterised commands & `Create…`/`Settings…` dialogs are described by Praat's `UiForm` (typed fields:
real, integer, boolean, choice, text, …). **iOS approach:** a generic SwiftUI form that reads a command's
`UiForm` field list (new bridge call) and emits the corresponding script arguments. Makes every "…" command
usable without hand-writing a dialog. **Effort:** medium; pairs with T1. Interim: prefilled editable script
line per command (trivial).

## 9. Editors (37) — native reimplementations  — *T3*

| Editor | Status | iOS approach |
|---|---|---|
| Sound / LongSound | **partial (native)** | extend current Analyze view (pulses, intensity/formant listings, extract, save) |
| TextGrid | **partial** (1 tier) | multi-tier interval/point editor, IPA keyboard, boundaries snap to cursor |
| Manipulation (PSOLA) | todo | pitch-tier + duration-tier editing over the sound; resynthesis playback |
| Pitch / PitchTier | todo | native curve editor (drag points) |
| Spectrum / Spectrogram | todo | Spectrum slice already done; full Spectrum editor = band view |
| FormantGrid / FormantPath | todo | formant-track editor (drag) |
| Intensity/Amplitude/Duration/RealTier | todo | shared "tier editor" component (drag points on a curve) |
| KlattGrid | todo | synthesizer parameter editor (many tiers) — niche |
| Artword / Vowel | todo | articulatory synthesis editors — niche, but the **Vowel editor** (drag F1×F2 → hear a vowel) is a fun, tractable native build |
| OTGrammar / OTMulti | todo | constraint-ranking table + tableau view |
| Table / Matrix / Strings / Categories | todo | grid/list editors (SwiftUI `Table`) |
| Script / Notebook | **partial** | the Script tab is a minimal ScriptEditor; add open/save, run-selection |
| Demo (DemoEditor) | todo | see §11 |

Shared insight: most tier editors (Pitch/Intensity/Duration/Amplitude/FormantGrid) are the **same component**
(drag points/curves on a time axis) — build it once.

## 10. Synthesis & TTS  — *T1 (+ T3 editors)*

- **eSpeak TTS** (linked): `Create SpeechSynthesizer…` + `To Sound…` already work → expose a "Speak" feature
  (text → Sound → play/spectrogram). **Easy, high delight. Good early add-on.**
- **Source/filter, LPC resynthesis, KlattGrid, articulatory (Artword)** — all scriptable (T1); editors T3.

## 11. Scripting, automation & experiments

- **Script editor (partial):** add open/save script files, run-selection, a command palette. T0/T3.
- **Demo window** (`demo …` commands): a scriptable full-screen draw+click surface — maps to a SwiftUI
  `Canvas` driven by the Demo Graphics (needs **T2**). Powering point: enables interactive teaching demos.
- **ExperimentMFC** (listening/perception experiments): runs `.MFC` experiments (play stimuli, collect
  responses). Very valuable for phoneticians; a native runner over the existing `ExperimentMFC` engine
  is a self-contained T3 project.
- **Buttons/preferences, menu commands, add-to-dynamic-menu:** desktop-customisation; low priority on iOS.

## 12. ASR

- **Whisper** (`whispercpp` linked) + `SpeechRecognizer` (HMM): `Sound: To TextGrid (speech recognition)…`
  type flows — expose "Transcribe" → TextGrid. Needs a Whisper model file bundled/downloaded. Medium.

## 13. Things that don't fit mobile (skip or rethink)

- Multi-window desktop layout, printing, `sendpraat`/AppleEvents IPC, the macOS Picture clipboard, opening
  external editors, file-system-wide batch loops. On iOS these become: tabs/sheets, PDF export, share sheet,
  document-scoped storage.

---

## 14. Prioritised implementation plan

1. **Objects window + command runner (T1)** — the keystone; turns the app into Praat. *(starting now)*
2. **Open-any-file + Save/export (T1)** — `Data_readFromFile` for any type; `.fileExporter` for Sound/TextGrid/Table/Data.
3. **Generic form renderer (T4)** — makes "…" commands and the whole **New** menu usable.
4. **CoreGraphics Graphics backend + Picture tab (T2)** — *partially done:* the Analyze view exports a
   PNG of the spectrogram + overlays (share sheet, via `ImageRenderer`). **Still open:** rendering
   *arbitrary* Praat `Draw…`/`Paint…` commands. Finding from a probe: Praat's Quartz backend is
   iOS-compatible at the drawing level (CGContext/CoreText), and `Graphics_create_pdffile` is pure
   CoreGraphics, **but** it requires building the **"nogui" edition** (graphics-on, GUI-off) rather than
   the current **barren** (`NO_GRAPHICS`) edition — the Graphics structs gate fields on
   `#if defined(NO_GRAPHICS)` while the drawing code gates on the `quartz` macro, so the two disagree
   unless we switch the Graphics subsystem to `NO_GUI` and gate the ~6 Graphics files' AppKit/screen and
   CoreText-font touchpoints (`d_macView`, `GuiCocoaDrawingArea`, `NSGraphicsContext`, `NSFontManager`).
   That's a self-contained but non-trivial subsystem port — the right next big effort.
5. **eSpeak "Speak" feature (T1)** — quick, high-delight.
6. **Shared tier editor + full TextGrid editor (T3)** — the most-used interactive editing.
7. **Manipulation (PSOLA) editor, Vowel editor, ExperimentMFC runner (T3)** — flagship native editors.

Items 1–3 and 5 are bounded and expose the vast majority of Praat's value; 4 is the big multiplier for
visualisation; 6–7 are the marquee interactive editors.
