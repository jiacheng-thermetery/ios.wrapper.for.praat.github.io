// ContentView.swift — Spraak UI: Analyze (spectrogram editor) + Script console.
// GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
import SwiftUI
import UniformTypeIdentifiers

/// A request to load a sound into the Analyze tab (e.g. "Analyze" on a selected Sound object).
struct AnalyzeRequest: Equatable {
    let id = UUID()
    var samples: [Float]
    var rate: Double
    var name: String
    static func == (a: AnalyzeRequest, b: AnalyzeRequest) -> Bool { a.id == b.id }
}

/// App-wide state shared across tabs: the selected tab (so we can switch programmatically)
/// and a pending request to hand a sound from the Objects window to the Analyze tab.
@MainActor final class AppStore: ObservableObject {
    @Published var tab = 0
    @Published var analyzeRequest: AnalyzeRequest?

    func sendToAnalyze(_ samples: [Float], rate: Double, name: String) {
        analyzeRequest = AnalyzeRequest(samples: samples, rate: rate, name: name)
        tab = 0
    }
}

struct ContentView: View {
    @StateObject private var store = AppStore()
    @State private var showAbout = false
    var body: some View {
        TabView(selection: $store.tab) {
            AnalyzeView().tabItem { Label("Analyze", systemImage: "waveform") }.tag(0)
            ObjectsView().tabItem { Label("Objects", systemImage: "list.bullet") }.tag(1)
            VowelView().tabItem { Label("Vowel", systemImage: "mouth") }.tag(2)
            ManipulationView().tabItem { Label("Manip.", systemImage: "slider.vertical.3") }.tag(3)
            ExperimentMFCView().tabItem { Label("Experiment", systemImage: "checklist") }.tag(4)
            ScriptConsoleView().tabItem { Label("Script", systemImage: "terminal") }.tag(5)
        }
        .environmentObject(store)
        .overlay(alignment: .topTrailing) {
            Button { showAbout = true } label: { Image(systemName: "info.circle") }
                .padding(.top, 6).padding(.trailing, 12)
        }
        .sheet(isPresented: $showAbout) { AboutView() }
    }
}

// MARK: - Analyze

struct AnalyzeView: View {
    @EnvironmentObject private var store: AppStore
    @StateObject private var model = PraatModel()
    @StateObject private var audio = AudioEngine()

    @State private var samples: [Float] = []
    @State private var rate: Double = 16000
    @State private var soundName = "sound"   // [iOS port] names the synced "<sound>_grid" object
    @State private var cursorTime: Double?
    @State private var selection: TimeRange?
    @State private var slice: PraatModel.Slice?
    // [iOS port] interval tiers start with a t=0 label carrier (see TextGridEditor.swift)
    @State private var tiers: [TGTier] = [.interval(named: "phones"), .interval(named: "words")]
    @State private var selectedMark: TGMarkRef?
    @State private var gridSyncTask: Task<Void, Never>?   // [iOS port] debounced grid → engine sync
    @State private var gridSynced = false                 // a grid for this sound exists in the engine
    @State private var zoomHistory: [TimeRange] = []
    @State private var cursorValues: PraatModel.CursorValues?

    @State private var showZoomDialog = false
    @State private var showPlayDialog = false
    @State private var dlgFrom = ""
    @State private var dlgTo = ""
    @State private var settings = AnalysisSettings()
    @State private var showSettings = false
    @State private var showImporter = false

    @State private var showPitch = true
    @State private var showFormants = true
    @State private var showIntensity = true
    @State private var pictureExport: ExportItem?
    @State private var showSpeak = false
    @State private var speakText = "Frogs are cute. I love frogs. Frogs!"
    @State private var speakLang = "English (Great Britain)"
    @State private var speakVoice = "Female1"

    var body: some View {
        ScrollView {                       // [iOS port] scrollable so all panels are reachable in landscape
            VStack(spacing: 6) {
                topControls
                overlayToggles

                if model.hasSound {
                    waveform.frame(height: 52)
                    SpectrogramView(model: model, cursorTime: $cursorTime, selection: $selection,
                                    showPitch: showPitch, showFormants: showFormants, showIntensity: showIntensity,
                                    onZoom: { a, b in pushZoom(); model.setView(a, b) })
                        .frame(minHeight: 190)
                    Text("Press & hold then drag to select · pinch to zoom · tap to place cursor")
                        .font(.caption2).foregroundStyle(.secondary)
                    timeAxis
                    if let cv = cursorValues { cursorReadout(cv) }
                    tierControls
                    TextGridTiersView(tiers: $tiers, selected: $selectedMark,
                                      viewStart: model.viewStart, viewEnd: model.viewEnd)
                        .frame(height: CGFloat(tiers.count) * 36)
                    markEditor
                    if let s = slice, let t = cursorTime { SpectrumSliceView(slice: s, time: t) }
                } else {
                    Text("Tap ● Record (or ▶︎ Demo) to analyse a sound.")
                        .font(.callout).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 240)
                }
            }
            .padding(8)
        }
        .overlay(alignment: .top) {
            if audio.isRecording {
                // [iOS port] The banner must NOT cover the Record/Stop toggle in topControls, or the
                // recording can't be stopped — carry its own Stop button, as the Objects tab does.
                HStack(spacing: 8) {
                    RecordingBanner(seconds: audio.recordSeconds, level: audio.recordLevel)
                    Button { record() } label: { Label("Stop", systemImage: "stop.fill") }
                        .buttonStyle(.borderedProminent).tint(.red).controlSize(.small)
                }
                .padding(.top, 8).padding(.horizontal, 12)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.default, value: audio.isRecording)
        .onChange(of: cursorTime) { _, t in
            if let t { slice = model.spectrumSlice(at: t); cursorValues = model.valuesAt(t) }
            else { slice = nil; cursorValues = nil }
        }
        .onChange(of: store.analyzeRequest) { _, req in
            if let req { loadFromObjects(req) }
        }
        // [iOS port] every annotation edit (re)arms the debounced engine sync
        .onChange(of: tiers) { _, _ in scheduleGridSync() }
        .sheet(isPresented: $showZoomDialog) {
            TimeRangeDialog(title: "Zoom", actionLabel: "Zoom", from: $dlgFrom, to: $dlgTo) { a, b in
                pushZoom(); model.setView(a, b)
            }
        }
        .sheet(isPresented: $showPlayDialog) {
            TimeRangeDialog(title: "Play", actionLabel: "Play", from: $dlgFrom, to: $dlgTo) { a, b in
                audio.play(samples, rate: rate, from: a, to: b)
            }
        }
        .sheet(isPresented: $showSettings) {
            AnalysisSettingsView(settings: $settings) { model.applySettings(settings) }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.audio]) { result in
            if case .success(let url) = result { openFile(url) }
        }
        .sheet(item: $pictureExport) { ActivityView(items: [$0.url]) }
        .sheet(isPresented: $showSpeak) {
            SpeakView(text: $speakText, language: $speakLang, voice: $speakVoice) { speak() }
        }
        .onAppear {
            if !model.hasSound {
                loadDemo()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { cursorTime = model.duration * 0.5 }
            }
        }
    }

    // MARK: controls

    // [iOS port] Ordered by actual use: play (audio) · record · time · speak · demo, then open/settings/export.
    private var topControls: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                audioMenu
                Button { record() } label: {
                    Label(audio.isRecording ? "Stop" : "Record",
                          systemImage: audio.isRecording ? "stop.circle.fill" : "record.circle")
                        .foregroundStyle(audio.isRecording ? .red : .primary)
                }
                timeMenu
                Button { showSpeak = true } label: { Label("Speak", systemImage: "text.bubble") }
                Button { loadDemo() } label: { Label("Demo", systemImage: "play.rectangle") }
                Button { showImporter = true } label: { Label("Open", systemImage: "folder") }
                Button { settings = model.settings; showSettings = true } label: { Image(systemName: "gearshape") }
                Button { exportPicture() } label: { Image(systemName: "square.and.arrow.up") }
                    .disabled(!model.hasSound)
                if audio.permissionDenied { Text("Mic denied").font(.caption2).foregroundStyle(.red) }
            }
            .padding(.trailing, 28)
        }
        .buttonStyle(.bordered).controlSize(.small).font(.callout)
    }

    private var timeMenu: some View {
        Menu {
            Button("Zoom...") { dlgFrom = fmt(model.viewStart); dlgTo = fmt(model.viewEnd); showZoomDialog = true }
            Button("Show all") { showAll() }.keyboardShortcut("a")
            Button("Zoom in") { zoomIn() }.keyboardShortcut("i")
            Button("Zoom out") { zoomOut() }.keyboardShortcut("o")
            Button("Zoom to selection") { zoomToSelection() }.keyboardShortcut("n").disabled(selection == nil)
            Button("Zoom back") { zoomBack() }.keyboardShortcut("b").disabled(zoomHistory.isEmpty)
            Divider()
            Button("Scroll page back") { scroll(-1) }.keyboardShortcut(.upArrow, modifiers: [])
            Button("Scroll page forward") { scroll(1) }.keyboardShortcut(.downArrow, modifiers: [])
        } label: { Label("Time", systemImage: "arrow.left.and.right") }
    }

    private var audioMenu: some View {
        Menu {
            Button("Play...") {
                let r = selection ?? TimeRange(a: model.viewStart, b: model.viewEnd)
                dlgFrom = fmt(r.lo); dlgTo = fmt(r.hi); showPlayDialog = true
            }
            Button(audio.isPlaying ? "Stop" : "Play window") { playOrStop() }.keyboardShortcut(.space, modifiers: [])
            Button("Play selection") { playSelection() }.disabled(selection == nil)
            Button("Interrupt playing") { audio.stopPlayback() }.disabled(!audio.isPlaying)
        } label: { Label("Audio", systemImage: audio.isPlaying ? "stop.fill" : "speaker.wave.2") }
    }

    private var overlayToggles: some View {
        HStack(spacing: 12) {
            Toggle(isOn: $showPitch) { Text("Pitch").foregroundStyle(.cyan) }
            Toggle(isOn: $showFormants) { Text("Formants").foregroundStyle(.red) }
            Toggle(isOn: $showIntensity) { Text("Intensity").foregroundStyle(.orange) }
            Spacer()
            Button { quickPlay() } label: {
                Label(audio.isPlaying ? "Stop" : "Play",
                      systemImage: audio.isPlaying ? "stop.fill" : "play.fill")
            }
            .buttonStyle(.borderedProminent).controlSize(.small).font(.caption)
            .disabled(!model.hasSound)
        }
        .toggleStyle(.button).controlSize(.small).font(.caption)
    }

    private var timeAxis: some View {
        HStack {
            Text(String(format: "%.3f", model.viewStart)).font(.caption2).foregroundStyle(.secondary)
            Spacer()
            if let s = selection {
                Text(String(format: "sel %.3f–%.3f s (%.3f)", s.lo, s.hi, s.hi - s.lo))
                    .font(.caption2).foregroundStyle(.pink)
            } else if let c = cursorTime {
                Text(String(format: "cursor %.3f s", c)).font(.caption2).foregroundStyle(.red)
            } else {
                Text(String(format: "%.3f s total", model.duration)).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            Text(String(format: "%.3f", model.viewEnd)).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private var waveform: some View {
        Canvas { ctx, size in
            let n = model.waveMax.count
            guard n > 0 else { return }
            let midY = size.height / 2
            if let s = selection {
                let x0 = size.width * (s.lo - model.viewStart) / model.viewSpan
                let x1 = size.width * (s.hi - model.viewStart) / model.viewSpan
                ctx.fill(Path(CGRect(x: x0, y: 0, width: x1 - x0, height: size.height)), with: .color(.pink.opacity(0.25)))
            }
            var amp = 0.0001
            for i in 0..<n { amp = max(amp, Double(max(abs(model.waveMin[i]), abs(model.waveMax[i])))) }
            var path = Path()
            for i in 0..<n {
                let x = size.width * Double(i) / Double(n)
                path.move(to: .init(x: x, y: midY - midY * Double(model.waveMax[i]) / amp))
                path.addLine(to: .init(x: x, y: midY - midY * Double(model.waveMin[i]) / amp))
            }
            ctx.stroke(path, with: .color(.black), lineWidth: 0.5)
            if let t = cursorTime, t >= model.viewStart, t <= model.viewEnd {
                let x = size.width * (t - model.viewStart) / model.viewSpan
                ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: size.height)) },
                           with: .color(.red), lineWidth: 1)
            }
        }
        .background(Color(white: 0.97))
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(.gray.opacity(0.4)))
    }

    private var tierControls: some View {
        HStack(spacing: 8) {
            Text("Tiers").font(.caption2).foregroundStyle(.secondary)
            Button { tiers.append(.interval(named: "tier\(tiers.count + 1)")) }   // [iOS port] t=0 carrier
                label: { Label("Interval", systemImage: "plus") }
            Button { tiers.append(TGTier(name: "points\(tiers.count + 1)", isInterval: false)) }
                label: { Label("Point", systemImage: "plus") }
            Spacer()
            Button { exportTextGrid() } label: { Label("TextGrid", systemImage: "square.and.arrow.up") }
                .disabled(!tiers.hasAnnotation)   // [iOS port] the t=0 carriers alone don't count
        }
        .buttonStyle(.bordered).controlSize(.mini).font(.caption2)
    }

    @ViewBuilder private var markEditor: some View {
        if let ref = selectedMark,
           let ti = tiers.firstIndex(where: { $0.id == ref.tier }),
           let mi = tiers[ti].marks.firstIndex(where: { $0.id == ref.mark }) {
            HStack {
                Text("\(tiers[ti].name) @ \(String(format: "%.3f s", tiers[ti].marks[mi].time))")
                    .font(.caption2).foregroundStyle(.secondary)
                TextField("label", text: $tiers[ti].marks[mi].label)
                    .textFieldStyle(.roundedBorder).font(.callout)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                Button(role: .destructive) {
                    // [iOS port] the first interval's t=0 label carrier has no boundary to
                    // remove: "deleting" it just clears its label.
                    if tiers[ti].isInterval && tiers[ti].marks[mi].time <= 1e-9 {
                        tiers[ti].marks[mi].label = ""
                    } else {
                        tiers[ti].marks.remove(at: mi)
                    }
                    selectedMark = nil
                }
                    label: { Image(systemName: "trash") }
            }
        }
    }

    // MARK: actions

    /// Load PCM into the Analyze view. If `pushToObjects`, also add it to the engine object
    /// list so it appears in the Objects window (the two tabs share one engine).
    private func setAnalysisSound(_ s: [Float], _ r: Double, name: String, pushToObjects: Bool) {
        samples = s; rate = r; soundName = name; resetAnalysis(); model.setSamples(s, rate: r)
        if pushToObjects {
            _ = s.withUnsafeBufferPointer { praatios_addSoundObject($0.baseAddress, Int32(s.count), r, name) }
        }
    }
    private func record() {
        audio.toggleRecording { captured, sr in
            guard !captured.isEmpty else { return }
            setAnalysisSound(captured, sr, name: "recording", pushToObjects: true)
        }
    }
    private func loadDemo() {
        let (s, r) = DemoSound.vowel(); setAnalysisSound(s, r, name: "vowel", pushToObjects: false)
    }
    private func openFile(_ url: URL) {
        guard let (s, r) = AudioEngine.decode(url: url) else { return }
        setAnalysisSound(s, r, name: url.deletingPathExtension().lastPathComponent, pushToObjects: true)
    }
    /// A sound handed over from the Objects window — already an engine object, so don't re-add it.
    private func loadFromObjects(_ req: AnalyzeRequest) {
        setAnalysisSound(req.samples, req.rate, name: req.name, pushToObjects: false)
    }

    /// Export the annotation tiers as a Praat .TextGrid file and present the share sheet.
    private func exportTextGrid() {
        let text = TextGridIO.textGrid(tiers: tiers, xmin: 0, xmax: model.duration)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("annotation.TextGrid")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        pictureExport = ExportItem(url: url)   // reuse the share sheet
    }

    /// [iOS port] Debounce annotation edits, then mirror the grid into the engine object list
    /// ("one shared engine", like addSoundObject does for sounds): typing a label syncs once,
    /// not per keystroke. Untouched default grids are not pushed; once a grid has been synced
    /// it keeps mirroring, so deletions propagate too.
    private func scheduleGridSync() {
        gridSyncTask?.cancel()
        guard model.hasSound, tiers.hasAnnotation || gridSynced else { return }
        gridSyncTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            gridSynced = true
            syncTextGridToObjects()
        }
    }

    /// [iOS port] Mirror the tiers into the engine as TextGrid "<sound>_grid", replacing any
    /// previous synced grid of that name: write the .TextGrid text to a temp file, Read +
    /// Rename it, and restore the user's selection so a sync never disturbs what they selected.
    /// (Runs the engine on the main actor, like the rest of this view.)
    private func syncTextGridToObjects() {
        let objName = gridObjectName(soundName)
        let text = TextGridIO.textGrid(tiers: tiers, xmin: 0, xmax: model.duration)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(objName).TextGrid")
        try? text.write(to: url, atomically: true, encoding: .utf8)

        // remember the selection and find stale synced grids; objectInfo's name field is
        // "ClassName givenName", so match the part after the class name too
        var selectedIds: [Int] = [], staleIds: [Int] = []
        let n = Int(praatios_objectCount())
        if n > 0 {
            for i in 1...n {
                let parts = String(cString: praatios_objectInfo(Int32(i))).components(separatedBy: "|")
                guard parts.count >= 4, let id = Int(parts[0]) else { continue }
                if parts[3] == "1" { selectedIds.append(id) }
                if parts[1] == "TextGrid", parts[2] == objName || parts[2].hasSuffix(" " + objName) {
                    staleIds.append(id)
                }
            }
        }
        var script = ""
        if !staleIds.isEmpty {
            script += "removeObject: " + staleIds.map(String.init).joined(separator: ", ") + "\n"
        }
        script += "Read from file: \"\(url.path)\"\nRename: \"\(objName)\"\n"
        let keep = selectedIds.filter { !staleIds.contains($0) }
        script += keep.isEmpty ? "selectObject()"
                               : "selectObject: " + keep.map(String.init).joined(separator: ", ")
        _ = String(cString: praatios_run(script))
    }

    /// [iOS port] Engine object name for the mirrored grid (Praat object names dislike punctuation).
    private func gridObjectName(_ name: String) -> String {
        name.replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "_", options: .regularExpression) + "_grid"
    }

    /// Synthesize speech with eSpeak (via the engine), then load it into the Analyze view.
    private func speak() {
        let safe = speakText.replacingOccurrences(of: "\"", with: "\"\"")
            .replacingOccurrences(of: "\n", with: " ")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("speech.wav")
        try? FileManager.default.removeItem(at: url)
        let script = """
        synth = Create SpeechSynthesizer: "\(speakLang)", "\(speakVoice)"
        selectObject: synth
        sound = To Sound: "\(safe)", "no"
        selectObject: sound
        Save as WAV file: "\(url.path)"
        removeObject: synth, sound
        """
        _ = String(cString: praatios_run(script))
        if let (s, r) = AudioEngine.decode(url: url) {
            setAnalysisSound(s, r, name: "speech", pushToObjects: true)
        }
    }
    private func resetAnalysis() {
        cursorTime = nil; selection = nil; slice = nil; zoomHistory = []
        gridSyncTask?.cancel(); gridSyncTask = nil; gridSynced = false   // [iOS port]
        tiers = [.interval(named: "phones"), .interval(named: "words")]  // [iOS port] t=0 carriers
        selectedMark = nil
    }

    private func zoomCenter() -> Double {
        if let t = cursorTime, t >= model.viewStart, t <= model.viewEnd { return t }
        return (model.viewStart + model.viewEnd) / 2
    }
    private func pushZoom() { zoomHistory.append(TimeRange(a: model.viewStart, b: model.viewEnd)) }
    private func showAll() { pushZoom(); model.setView(0, model.duration) }
    private func zoomIn() { pushZoom(); let c = zoomCenter(), s = model.viewSpan / 2; model.setView(c - s/2, c + s/2) }
    private func zoomOut() { pushZoom(); let c = zoomCenter(), s = model.viewSpan * 2; model.setView(c - s/2, c + s/2) }
    private func zoomToSelection() { guard let sel = selection else { return }; pushZoom(); model.setView(sel.lo, sel.hi) }
    private func zoomBack() { guard let r = zoomHistory.popLast() else { return }; model.setView(r.a, r.b) }
    private func scroll(_ dir: Double) {
        let span = model.viewSpan
        var s = model.viewStart + span * 0.8 * dir
        s = max(0, min(s, model.duration - span))
        model.setView(s, s + span)
    }
    private func playOrStop() { if audio.isPlaying { audio.stopPlayback() } else { audio.play(samples, rate: rate, from: model.viewStart, to: model.viewEnd) } }
    private func playSelection() { if let s = selection { audio.play(samples, rate: rate, from: s.lo, to: s.hi) } }

    /// Quick Play (next to the overlay toggles): the selection if one exists, otherwise from the
    /// cursor (or the window start) to the end of the visible window. Tapping again stops.
    private func quickPlay() {
        if audio.isPlaying { audio.stopPlayback(); return }
        if let s = selection {
            audio.play(samples, rate: rate, from: s.lo, to: s.hi)
        } else {
            let from = max(model.viewStart, min(cursorTime ?? model.viewStart, model.viewEnd))
            audio.play(samples, rate: rate, from: from, to: model.viewEnd)
        }
    }

    /// Render the spectrogram + overlays to a PNG and present the share sheet ("export the picture").
    @MainActor private func exportPicture() {
        let picture = SpectrogramView(model: model, cursorTime: .constant(nil), selection: .constant(nil),
                                      showPitch: showPitch, showFormants: showFormants, showIntensity: showIntensity)
            .frame(width: 1100, height: 620).background(.white)
        let renderer = ImageRenderer(content: picture)
        renderer.scale = 2
        guard let img = renderer.uiImage, let data = img.pngData() else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("praat-spectrogram.png")
        try? data.write(to: url)
        pictureExport = ExportItem(url: url)
    }

    private func fmt(_ x: Double) -> String { String(format: "%.3f", x) }

    // read-out of analysis values at the cursor (like Praat's editor)
    @ViewBuilder private func cursorReadout(_ v: PraatModel.CursorValues) -> some View {
        HStack(spacing: 12) {
            chip("F0", v.f0, "Hz", .cyan)
            ForEach(Array(v.formants.enumerated()), id: \.offset) { i, f in chip("F\(i + 1)", f, "", .red) }
            chip("Int", v.intensity, "dB", .orange)
            Spacer()
        }
        .font(.system(.caption2, design: .monospaced))
        .padding(.vertical, 2)
    }
    private func chip(_ name: String, _ val: Double?, _ unit: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Text(name).foregroundStyle(color).bold()
            Text(val != nil ? String(format: "%.0f", val!) + (unit.isEmpty ? "" : " " + unit) : "—")
                .foregroundStyle(.primary)
        }
    }
}

// MARK: - From/to time entry dialog (Zoom… / Play…)

struct TimeRangeDialog: View {
    let title: String
    let actionLabel: String
    @Binding var from: String
    @Binding var to: String
    var onCommit: (Double, Double) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                LabeledContent("From (s)") {
                    TextField("from", text: $from).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
                LabeledContent("To (s)") {
                    TextField("to", text: $to).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionLabel) {
                        if let a = Double(from), let b = Double(to), b > a { onCommit(a, b) }
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.height(230)])
    }
}

// MARK: - Script console

struct ScriptConsoleView: View {
    @State private var script = """
    writeInfoLine: "Spraak"
    appendInfoLine: "Engine: Praat ", praatVersion$
    """
    @State private var output = "Tap Run to execute the script."
    @State private var running = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Praat script").font(.headline)
                Spacer()
                Button(action: run) { Label(running ? "Running…" : "Run", systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent).disabled(running)
            }
            TextEditor(text: $script)
                .font(.system(.callout, design: .monospaced))
                .autocorrectionDisabled().textInputAutocapitalization(.never)
                .frame(maxHeight: 220)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))
            ScrollView {
                Text(output).font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
            }
            .padding(8).background(Color(white: 0.96))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))
        }
        .padding()
    }

    private func run() {
        running = true
        let src = script
        DispatchQueue.global(qos: .userInitiated).async {
            let result = String(cString: praatios_run(src))
            DispatchQueue.main.async { output = result; running = false }
        }
    }
}

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Spraak").font(.title2).bold()
                    Text("An unofficial iOS port of Praat. NOT produced or endorsed by the original authors.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Group {
                        Text("Based on Praat — doing phonetics by computer").bold()
                        Text("© 1992–2026 Paul Boersma & David Weenink, University of Amsterdam, and contributors. https://praat.org")
                    }
                    Group {
                        Text("License").bold()
                        Text("Free software under the GNU General Public License, version 3 or later. Distributed WITHOUT ANY WARRANTY. See the bundled gpl-3.0.txt.")
                    }
                    Group {
                        Text("Corresponding source").bold()
                        Text("You have the right to the complete corresponding source under GPL §6: the praat-ios repository at this build's commit, including the ios/ folder. See ios/LICENSING.md.")
                    }
                    Text("GPL software cannot be distributed via the Apple App Store; this build is for source distribution and sideloading only.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("About")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SpeakView: View {
    @Binding var text: String
    @Binding var language: String
    @Binding var voice: String
    var onSpeak: () -> Void
    @Environment(\.dismiss) private var dismiss
    private let languages = ["English (Great Britain)", "English (America)", "French (France)",
                             "German", "Spanish (Spain)", "Italian", "Dutch", "Russian",
                             "Mandarin Chinese", "Japanese"]
    private let voices = ["Female1", "Male1", "default"]
    var body: some View {
        NavigationView {
            Form {
                Section("Text") {
                    TextField("text to speak", text: $text, axis: .vertical).lineLimit(2...5)
                }
                Section {
                    Picker("Language", selection: $language) { ForEach(languages, id: \.self) { Text($0) } }
                    Picker("Voice", selection: $voice) { ForEach(voices, id: \.self) { Text($0) } }
                }
            }
            .navigationTitle("Speak (eSpeak)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Speak") { onSpeak(); dismiss() } }
            }
        }
        .presentationDetents([.medium])
    }
}
