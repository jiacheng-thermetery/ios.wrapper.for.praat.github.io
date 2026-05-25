// ExperimentMFCView.swift — Praat ExperimentMFC perception-experiment runner.
// Plays each trial's stimulus and records the multiple-forced-choice response, then exports
// the results as CSV. Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

private func mfcStr(_ s: UnsafePointer<CChar>?) -> String { s.map { String(cString: $0) } ?? "" }

struct ExperimentMFCView: View {
    enum Phase { case intro, running, done }
    struct RButton: Identifiable { let id = UUID(); let index: Int; let label: String; let l, r, b, t: Double }

    @StateObject private var audio = AudioEngine()
    @State private var phase: Phase = .intro
    @State private var nTrials = 0
    @State private var trial = 1
    @State private var buttons: [RButton] = []
    @State private var stimText = ""
    @State private var stimPlayedAt = Date()
    @State private var correct = 0
    @State private var answered = 0
    @State private var resultsCSV = ""
    @State private var shareURL: URL?
    @State private var source = "demo"

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            switch phase {
            case .intro: card(text: mfcStr(praatios_mfcText(0)), button: "Begin") { start() }
            case .done:  doneView
            case .running: running
            }
        }
        .onAppear { if nTrials == 0 { loadDemo() } }
    }

    private var header: some View {
        HStack {
            Text("ExperimentMFC").font(.headline)
            Spacer()
            Menu {
                Button("Demo: tone height") { source = "demo"; loadDemo() }
                Button("Run selected (from file)") { source = "file"; loadSelected() }
            } label: { Label("Experiment", systemImage: "doc.badge.gearshape") }
                .controlSize(.small)
        }.padding(8)
    }

    private func card(text: String, button: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 20) {
            Spacer()
            Text(text).multilineTextAlignment(.center).padding(.horizontal, 24)
            Button(button, action: action).buttonStyle(.borderedProminent).controlSize(.large)
            Spacer()
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var running: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Trial \(trial) / \(nTrials)").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button { playStimulus() } label: { Label("Replay", systemImage: "arrow.clockwise") }
                    .controlSize(.small)
            }.padding(.horizontal, 12).padding(.top, 8)

            Text(mfcStr(praatios_mfcText(1))).font(.title3).fontWeight(.medium)
            if !stimText.isEmpty { Text(stimText).font(.subheadline).foregroundStyle(.secondary) }

            GeometryReader { geo in
                let W = Double(geo.size.width), H = Double(geo.size.height)
                ZStack(alignment: .topLeading) {
                    ForEach(buttons) { b in
                        Button { respond(b.index) } label: {
                            Text(b.label).frame(maxWidth: .infinity, maxHeight: .infinity)
                                .background(RoundedRectangle(cornerRadius: 10).fill(Color.accentColor.opacity(0.15)))
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.accentColor))
                        }
                        .frame(width: (b.r - b.l) * W, height: (b.t - b.b) * H)
                        .position(x: (b.l + (b.r - b.l) / 2) * W, y: (1 - (b.b + (b.t - b.b) / 2)) * H)
                    }
                }
            }
        }
    }

    private var doneView: some View {
        VStack(spacing: 14) {
            Spacer()
            Text(mfcStr(praatios_mfcText(3))).multilineTextAlignment(.center).padding(.horizontal, 24)
            if source == "demo" && answered > 0 {
                Text("Score: \(correct) / \(answered) correct").font(.headline)
            }
            ScrollView { Text(resultsCSV).font(.system(.caption2, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading).padding(8) }
                .frame(maxHeight: 220)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(white: 0.96)))
                .padding(.horizontal, 12)
            HStack {
                if let url = shareURL { ShareLink(item: url) { Label("Export CSV", systemImage: "square.and.arrow.up") } }
                Button("Run again") { start() }.buttonStyle(.bordered)
            }
            Spacer()
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - logic
    private func loadDemo() { nTrials = Int(praatios_mfcCreateDemo()); loadButtons(); phase = .intro }

    private func loadSelected() {
        let n = Int(praatios_mfcUseSelected())
        if n > 0 { nTrials = n; loadButtons(); phase = .intro }
        else { nTrials = 0; resultsCSV = "No ExperimentMFC object is selected.\nOpen a .MFCexperiment in the Objects tab first."; phase = .done }
    }

    private func loadButtons() {
        let count = Int(praatios_mfcResponseCount())
        buttons = (1...max(count, 1)).compactMap { i -> RButton? in
            guard i <= count else { return nil }
            let parts = mfcStr(praatios_mfcResponseInfo(Int32(i))).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count >= 5 else { return nil }
            return RButton(index: i, label: parts[0],
                           l: Double(parts[1]) ?? 0, r: Double(parts[2]) ?? 1,
                           b: Double(parts[3]) ?? 0, t: Double(parts[4]) ?? 1)
        }
    }

    private func start() { trial = 1; correct = 0; answered = 0; phase = .running; playStimulus() }

    private func playStimulus() {
        stimText = mfcStr(praatios_mfcStimulusText(Int32(trial)))
        let maxN = 48000 * 4
        var buf = [Float](repeating: 0, count: maxN); var rate = 44100.0
        let ns = buf.withUnsafeMutableBufferPointer {
            Int(praatios_mfcStimulusSound(Int32(trial), $0.baseAddress, Int32(maxN), &rate))
        }
        if ns > 0 { audio.play(Array(buf[0..<ns]), rate: rate) }
        stimPlayedAt = Date()
    }

    private func respond(_ iresp: Int) {
        let rt = Date().timeIntervalSince(stimPlayedAt)
        praatios_mfcRecordResponse(Int32(trial), Int32(iresp), 0, rt)
        answered += 1
        if Int(praatios_mfcStimulusForTrial(Int32(trial))) == iresp { correct += 1 }   // demo: stimulus i ↔ response i
        trial += 1
        if trial > nTrials { finish() } else { playStimulus() }
    }

    private func finish() {
        resultsCSV = mfcStr(praatios_mfcResultsCSV())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("mfc_results.csv")
        try? resultsCSV.write(to: url, atomically: true, encoding: .utf8)
        shareURL = url
        phase = .done
    }
}
