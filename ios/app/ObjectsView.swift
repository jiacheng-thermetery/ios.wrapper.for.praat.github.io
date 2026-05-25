// ObjectsView.swift — native "Objects window" backed by the live Praat engine.
// Part of the Spraak derivative. GPL-3.0-or-later.
//
// The engine's object table is global and persists across praatios_run() calls, so this view
// IS the engine state: commands run by generating `selectObject: <ids>` + the command line and
// executing it through the interpreter — the same mechanism that powers thousands of Praat commands.
import SwiftUI
import UniformTypeIdentifiers

struct PraatObject: Identifiable {
    let id: Int
    let className: String
    let name: String
}

@MainActor
final class ObjectsModel: ObservableObject {
    @Published var objects: [PraatObject] = []
    @Published var selected: Set<Int> = []
    @Published var output = ""

    func refresh() {
        praatios_init()
        let n = Int(praatios_objectCount())
        var arr: [PraatObject] = []
        if n > 0 {
            for i in 1...n {
                let parts = String(cString: praatios_objectInfo(Int32(i))).components(separatedBy: "|")
                if parts.count >= 4, let id = Int(parts[0]) {
                    arr.append(PraatObject(id: id, className: parts[1], name: parts[2]))
                }
            }
        }
        objects = arr
        selected.formIntersection(Set(arr.map { $0.id }))
    }

    /// Run a command on the current selection (prepends `selectObject:` for the selected ids).
    func run(_ command: String) {
        let sel = selected.sorted().map(String.init).joined(separator: ", ")
        let script = sel.isEmpty ? command : "selectObject: \(sel)\n\(command)"
        output = String(cString: praatios_run(script))
        refresh()
    }
    /// Run a raw script (e.g. a Create… / Read from file…).
    func runRaw(_ script: String) {
        output = String(cString: praatios_run(script))
        refresh()
    }

    var selectedClasses: [String] {
        Array(Set(objects.filter { selected.contains($0.id) }.map { $0.className }))
    }
}

struct ObjectsView: View {
    @EnvironmentObject private var store: AppStore
    @StateObject private var m = ObjectsModel()
    @StateObject private var audio = AudioEngine()
    @State private var showImporter = false
    @State private var scriptField = ""
    @State private var showRename = false
    @State private var renameText = ""
    @State private var exportItem: ExportItem?
    @State private var activeSpec: CmdSpec?
    @State private var drawnPicture: ExportItem?

    // a small curated command palette per class (everything else: type a command below)
    private let palette: [String: [(String, String)]] = [
        "Sound": [("To Pitch", "To Pitch: 0, 75, 600"),
                  ("To Spectrogram", "To Spectrogram: 0.005, 5000, 0.002, 20, \"Gaussian\""),
                  ("To Formant", "To Formant (burg): 0, 5, 5500, 0.025, 50"),
                  ("To Intensity", "To Intensity: 100, 0, \"yes\""),
                  ("To Spectrum", "To Spectrum: \"yes\""),
                  ("To Harmonicity", "To Harmonicity (cc): 0.01, 75, 0.1, 1"),
                  ("To mono", "Convert to mono"),
                  ("To stereo", "Convert to stereo"),
                  ("Play", "Play")],
        "Pitch": [("Get mean", "Get mean: 0, 0, \"Hertz\""),
                  ("Get min", "Get minimum: 0, 0, \"Hertz\", \"parabolic\""),
                  ("Get max", "Get maximum: 0, 0, \"Hertz\", \"parabolic\"")],
        "Formant": [("Mean F1", "Get mean: 1, 0, 0, \"hertz\""),
                    ("Mean F2", "Get mean: 2, 0, 0, \"hertz\"")],
        "Intensity": [("Get mean", "Get mean: 0, 0, \"energy\""),
                      ("Get max", "Get maximum: 0, 0, \"parabolic\"")],
        "Spectrum": [("Centre of gravity", "Get centre of gravity: 2"),
                     ("Std dev", "Get standard deviation: 2")],
        "Harmonicity": [("Get mean", "Get mean: 0, 0")],
        "Table": [("List", "List: \"no\""), ("# rows", "Get number of rows")],
        "TextGrid": [("# tiers", "Get number of tiers")],
    ]

    var body: some View {
        VStack(spacing: 6) {
            toolbar
            objectList.frame(maxHeight: 240)
            if !m.selected.isEmpty { commandPalette }
            scriptBar
            Text("Output").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            ScrollView {
                Text(m.output).font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
            }
            .padding(8).background(Color(white: 0.96))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))
        }
        .padding(8)
        .overlay(alignment: .top) {
            if audio.isRecording {
                HStack(spacing: 8) {
                    RecordingBanner(seconds: audio.recordSeconds, level: audio.recordLevel)
                    Button { recordSound() } label: { Label("Stop", systemImage: "stop.fill") }
                        .buttonStyle(.borderedProminent).tint(.red).controlSize(.small)
                }
                .padding(.top, 8).padding(.horizontal, 12)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.default, value: audio.isRecording)
        .onAppear { m.refresh() }
        // [iOS port] TabView doesn't reliably re-fire onAppear for a cached tab, so re-read the shared
        // engine table whenever the Objects tab becomes active — picks up sounds recorded in Analyze.
        .onChange(of: store.tab) { _, t in if t == 1 { m.refresh() } }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.audio, .text, .data, .item],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { openFile(url) }
        }
        .alert("Rename object", isPresented: $showRename) {
            TextField("name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { if !renameText.isEmpty { m.run("Rename: \"\(renameText)\"") } }
        }
        .sheet(item: $exportItem) { ActivityView(items: [$0.url]) }
        .sheet(item: $drawnPicture) { PictureSheet(url: $0.url) }
        .sheet(item: $activeSpec) { spec in
            CommandFormView(spec: spec) { line in
                if spec.isCreate { m.runRaw(line) } else { m.run(line) }
            }
        }
    }

    private func specsForSelection() -> [CmdSpec] {
        guard let cls = m.selectedClasses.first else { return [] }
        return CmdSpec.byClass[cls] ?? []
    }

    /// Number of currently-selected Sound objects (drives Analyze / Combine enablement).
    private var selectedSoundCount: Int {
        m.objects.filter { m.selected.contains($0.id) && $0.className == "Sound" }.count
    }

    /// Start/stop a recording; on stop, add the captured audio to the object list (like Praat's
    /// "New ▸ Record mono Sound…").
    private func recordSound() {
        audio.toggleRecording { captured, sr in
            guard !captured.isEmpty else { return }
            _ = captured.withUnsafeBufferPointer {
                praatios_addSoundObject($0.baseAddress, Int32(captured.count), sr, "recording")
            }
            m.refresh()
        }
    }

    /// Send the first selected Sound object to the Analyze tab (shared engine).
    private func analyzeSelected() {
        let maxN = 48_000 * 120          // up to ~2 min of mono audio at 48 kHz
        var buf = [Float](repeating: 0, count: maxN)
        var rate = 44100.0
        let ns = buf.withUnsafeMutableBufferPointer {
            Int(praatios_selectedSoundPCM($0.baseAddress, Int32(maxN), &rate))
        }
        guard ns > 0 else { m.output = "Select a Sound object to analyze."; return }
        let name = m.objects.first { m.selected.contains($0.id) && $0.className == "Sound" }?.name ?? "sound"
        store.sendToAnalyze(Array(buf[0 ..< ns]), rate: rate, name: name)
    }

    /// Draw the selected object to a PNG (via Praat's own Quartz Graphics) and show it.
    private func drawPicture() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("praat-picture.png")
        try? FileManager.default.removeItem(at: url)
        let result = String(cString: praatios_drawSelectedToPNG(url.path, 6.5, 4.0, 200))
        if result == "ok", FileManager.default.fileExists(atPath: url.path) {
            drawnPicture = ExportItem(url: url)
        } else {
            m.output = result
        }
    }

    /// Write the selected object to a temp file (WAV for Sound, Praat text otherwise) and share it.
    private func saveSelected() {
        guard let obj = m.objects.first(where: { m.selected.contains($0.id) }) else { return }
        let isSound = obj.className == "Sound"
        let base = obj.name.isEmpty ? obj.className : obj.name
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(base).\(isSound ? "wav" : "txt")")
        try? FileManager.default.removeItem(at: url)
        let cmd = isSound ? "Save as WAV file: \"\(url.path)\"" : "Save as text file: \"\(url.path)\""
        m.run(cmd)
        if FileManager.default.fileExists(atPath: url.path) { exportItem = ExportItem(url: url) }
    }

    private var toolbar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    Button { recordSound() } label: { Label("Record mono Sound…", systemImage: "record.circle") }
                    Divider()
                    Button("Sound (440 Hz tone)") { m.runRaw(#"Create Sound from formula: "tone", 1, 0, 1, 44100, ~ 0.5*sin(2*pi*440*x)"#) }
                    Button("Sound as tone complex") { m.runRaw(#"Create Sound as tone complex: "tones", 0, 1, 44100, "cosine", 100, 0, 0, 0"#) }
                    Button("TextGrid") { m.runRaw(#"Create TextGrid: 0, 1, "phones words", """#) }
                    Button("Table (10 rows)") { m.runRaw(#"Create Table with column names: "table", 10, "x y""#) }
                    Button("Strings (tokens)") { m.runRaw(#"Create Strings as tokens: "the quick brown fox", " ""#) }
                    Button("Matrix (10×10)") { m.runRaw(#"Create simple Matrix: "m", 10, 10, ~ row + col"#) }
                    Button("KlattGrid example") { m.runRaw("Create KlattGrid example") }
                    Divider()
                    ForEach(CmdSpec.creates) { spec in Button(spec.title) { activeSpec = spec } }
                } label: { Label("New", systemImage: "plus") }
                Button { showImporter = true } label: { Label("Open", systemImage: "folder") }
                Button { analyzeSelected() } label: { Label("Analyze", systemImage: "waveform") }
                    .disabled(selectedSoundCount == 0)
                Menu {
                    ForEach(specsForSelection()) { spec in Button(spec.title) { activeSpec = spec } }
                } label: { Label("Commands", systemImage: "slider.horizontal.3") }
                    .disabled(specsForSelection().isEmpty)
                Menu {
                    Button("Combine to stereo") { m.run("Combine to stereo") }
                    Button("Concatenate") { m.run("Concatenate") }
                } label: { Label("Combine", systemImage: "square.stack.3d.up") }
                    .disabled(selectedSoundCount < 2)
                Button { renameText = ""; showRename = true } label: { Label("Rename", systemImage: "pencil") }
                    .disabled(m.selected.count != 1)
                Button(role: .destructive) { m.run("Remove") } label: { Label("Remove", systemImage: "trash") }
                    .disabled(m.selected.isEmpty)
                Button { drawPicture() } label: { Label("Draw", systemImage: "photo") }
                    .disabled(m.selected.isEmpty)
                Button { saveSelected() } label: { Label("Save", systemImage: "square.and.arrow.up") }
                    .disabled(m.selected.count != 1)
                Button { m.refresh() } label: { Image(systemName: "arrow.clockwise") }
            }
        }
        .buttonStyle(.bordered).controlSize(.small).font(.callout)
    }

    private var objectList: some View {
        List {
            ForEach(m.objects) { obj in
                let isSel = m.selected.contains(obj.id)
                HStack {
                    Image(systemName: isSel ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(isSel ? Color.accentColor : .secondary)
                    Text("\(obj.id).").foregroundStyle(.secondary).font(.caption.monospaced())
                    Text(obj.className).bold()
                    Text(obj.name).foregroundStyle(.secondary).lineLimit(1)
                }
                .contentShape(Rectangle())
                .onTapGesture { if isSel { m.selected.remove(obj.id) } else { m.selected.insert(obj.id) } }
            }
            if m.objects.isEmpty {
                Text("No objects. Use New or Open, or create one in the Script tab.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .listStyle(.plain)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.3)))
    }

    private var commandPalette: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(m.selectedClasses, id: \.self) { cls in
                    ForEach(palette[cls] ?? [], id: \.0) { label, cmd in
                        Button(label) { m.run(cmd) }
                    }
                }
            }
        }
        .buttonStyle(.borderedProminent).controlSize(.small).font(.caption)
    }

    private var scriptBar: some View {
        HStack(spacing: 6) {
            TextField("command on selection… (e.g. To Pitch: 0, 75, 600)", text: $scriptField)
                .textFieldStyle(.roundedBorder).font(.system(.callout, design: .monospaced))
                .autocorrectionDisabled().textInputAutocapitalization(.never)
            Button("Run") { if !scriptField.isEmpty { m.run(scriptField) } }
                .buttonStyle(.borderedProminent).controlSize(.small)
        }
    }

    private func openFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: tmp)
        guard (try? FileManager.default.copyItem(at: url, to: tmp)) != nil else {
            m.output = "Could not read file."; return
        }
        m.runRaw("Read from file: \"\(tmp.path)\"")
    }
}

struct ExportItem: Identifiable { let id = UUID(); let url: URL }

/// Shows a rendered Praat picture (PNG) with a share button.
struct PictureSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationView {
            ScrollView([.horizontal, .vertical]) {
                if let img = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: img).resizable().aspectRatio(contentMode: .fit)
                        .frame(minWidth: 300)
                } else {
                    Text("Could not render picture.").foregroundStyle(.secondary).padding()
                }
            }
            .background(Color.white)
            .navigationTitle("Picture")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { ShareLink(item: url) }
            }
        }
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
