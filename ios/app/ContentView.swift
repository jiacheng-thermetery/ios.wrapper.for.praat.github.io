// ContentView.swift — Spraak UI: Analyze (spectrogram) + Script console.
// GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
import SwiftUI

struct ContentView: View {
    @State private var showAbout = false
    var body: some View {
        TabView {
            AnalyzeView().tabItem { Label("Analyze", systemImage: "waveform") }
            ScriptConsoleView().tabItem { Label("Script", systemImage: "terminal") }
        }
        .overlay(alignment: .topTrailing) {
            Button { showAbout = true } label: { Image(systemName: "info.circle") }
                .padding(.top, 6).padding(.trailing, 12)
        }
        .sheet(isPresented: $showAbout) { AboutView() }
    }
}

// MARK: - Analyze (record → spectrogram + overlays + slice + annotation)

struct AnalyzeView: View {
    @StateObject private var model = PraatModel()
    @StateObject private var audio = AudioEngine()

    @State private var samples: [Float] = []
    @State private var rate: Double = 16000
    @State private var cursorTime: Double?
    @State private var slice: PraatModel.Slice?
    @State private var annotations: [Annotation] = []
    @State private var selectedAnnotation: UUID?

    @State private var showPitch = true
    @State private var showFormants = true
    @State private var showIntensity = true

    var body: some View {
        VStack(spacing: 6) {
            controls
            overlayToggles

            if model.hasSound {
                waveform.frame(height: 56)
                SpectrogramView(model: model, cursorTime: $cursorTime,
                                showPitch: showPitch, showFormants: showFormants, showIntensity: showIntensity)
                    .frame(minHeight: 200)
                    .overlay(alignment: .topTrailing) { legend.padding(4) }
                tier.frame(height: 40)
                annotationEditor
                if let s = slice, let t = cursorTime {
                    SpectrumSliceView(slice: s, time: t)
                }
            } else {
                Spacer()
                Text("Tap ● Record (or ▶︎ Demo) to analyse a sound.")
                    .font(.callout).foregroundStyle(.secondary)
                Spacer()
            }
        }
        .padding(8)
        .onChange(of: cursorTime) { _, t in
            slice = (t != nil) ? model.spectrumSlice(at: t!) : nil
        }
        .onAppear {
            // Auto-load the demo on first launch so the analysis is visible immediately.
            if !model.hasSound {
                loadDemo()   // show an example analysis immediately; Record replaces it with your audio
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { cursorTime = model.duration * 0.5 }
            }
        }
    }

    private var controls: some View {
        HStack(spacing: 10) {
            Button { record() } label: {
                Label(audio.isRecording ? "Stop" : "Record",
                      systemImage: audio.isRecording ? "stop.circle.fill" : "record.circle")
                    .foregroundStyle(audio.isRecording ? .red : .primary)
            }
            Button { loadDemo() } label: { Label("Demo", systemImage: "play.rectangle") }
            Button { audio.play(samples, rate: rate) } label: { Label("Play", systemImage: "speaker.wave.2") }
                .disabled(samples.isEmpty)
            Spacer()
            if audio.permissionDenied {
                Text("Mic denied").font(.caption2).foregroundStyle(.red)
            }
        }
        .buttonStyle(.bordered)
        .font(.callout)
    }

    private var overlayToggles: some View {
        HStack(spacing: 14) {
            Toggle(isOn: $showPitch) { Text("Pitch").foregroundStyle(.cyan) }
            Toggle(isOn: $showFormants) { Text("Formants").foregroundStyle(.red) }
            Toggle(isOn: $showIntensity) { Text("Intensity").foregroundStyle(.orange) }
        }
        .toggleStyle(.button).controlSize(.small).font(.caption)
    }

    private var legend: some View {
        VStack(alignment: .trailing, spacing: 1) {
            Text("\(Int(model.fmax)) Hz").font(.system(size: 9)).foregroundStyle(.white)
            Spacer()
        }
    }

    private var waveform: some View {
        Canvas { ctx, size in
            let n = model.waveMax.count
            guard n > 0 else { return }
            let midY = size.height / 2
            var amp = 0.0001
            for i in 0..<n { amp = max(amp, Double(max(abs(model.waveMin[i]), abs(model.waveMax[i])))) }
            var path = Path()
            for i in 0..<n {
                let x = size.width * Double(i) / Double(n)
                let yTop = midY - midY * Double(model.waveMax[i]) / amp
                let yBot = midY - midY * Double(model.waveMin[i]) / amp
                path.move(to: .init(x: x, y: yTop)); path.addLine(to: .init(x: x, y: yBot))
            }
            ctx.stroke(path, with: .color(.black), lineWidth: 0.5)
            if let t = cursorTime {
                let x = size.width * t / model.duration
                ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: size.height)) },
                           with: .color(.red), lineWidth: 1)
            }
        }
        .background(Color(white: 0.97))
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(.gray.opacity(0.4)))
    }

    private var tier: some View {
        AnnotationTierView(annotations: $annotations, selected: $selectedAnnotation, duration: model.duration)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(.gray.opacity(0.4)))
    }

    @ViewBuilder private var annotationEditor: some View {
        if let id = selectedAnnotation, let idx = annotations.firstIndex(where: { $0.id == id }) {
            HStack {
                Text(String(format: "%.3f s", annotations[idx].start)).font(.caption2).foregroundStyle(.secondary)
                TextField("label", text: $annotations[idx].label)
                    .textFieldStyle(.roundedBorder).font(.callout)
                Button(role: .destructive) {
                    annotations.remove(at: idx); selectedAnnotation = nil
                } label: { Image(systemName: "trash") }
            }
        }
    }

    private func record() {
        audio.toggleRecording { captured, sr in
            guard !captured.isEmpty else { return }
            samples = captured; rate = sr
            resetAnalysis()
            model.setSamples(captured, rate: sr)
        }
    }
    private func loadDemo() {
        let (s, r) = DemoSound.vowel()
        samples = s; rate = r
        resetAnalysis()
        model.setSamples(s, rate: r)
    }
    private func resetAnalysis() {
        cursorTime = nil; slice = nil; annotations = []; selectedAnnotation = nil
    }
}

// MARK: - Script console (the original tab)

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
