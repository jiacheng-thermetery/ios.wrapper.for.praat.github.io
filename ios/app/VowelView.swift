// VowelView.swift — a Vowel editor: drag in the F1×F2 vowel space to hear a synthesized vowel.
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

/// Simple formant synthesis: a glottal-source harmonic series shaped by Lorentzian formant resonances.
enum VowelSynth {
    static func synth(f0: Double, f1: Double, f2: Double, f3: Double = 2700,
                      dur: Double = 0.5, rate: Double = 16000) -> [Float] {
        let n = Int(dur * rate)
        var s = [Float](repeating: 0, count: n)
        func gain(_ f: Double, _ c: Double, _ bw: Double) -> Double { let x = (f - c) / (bw / 2); return 1 / (1 + x * x) }
        for i in 0..<n {
            let t = Double(i) / rate
            var v = 0.0, h = 1
            while Double(h) * f0 < rate / 2 {
                let fh = Double(h) * f0
                let g = gain(fh, f1, 90) + 0.8 * gain(fh, f2, 110) + 0.4 * gain(fh, f3, 160)
                v += (g / Double(h)) * sin(2 * .pi * fh * t)
                h += 1
            }
            // gentle fade in/out to avoid clicks
            let env = min(1.0, min(Double(i), Double(n - i)) / (0.02 * rate))
            s[i] = Float(0.22 * env * v)
        }
        return s
    }
}

struct VowelView: View {
    @StateObject private var audio = AudioEngine()
    @State private var f1: Double = 500
    @State private var f2: Double = 1500
    @State private var f0: Double = 120

    // axis ranges (Hz)
    private let f1lo = 250.0, f1hi = 900.0      // vertical: close (top) → open (bottom)
    private let f2lo = 700.0, f2hi = 2500.0     // horizontal: back (right) → front (left)

    // reference vowels (approx. male formants)
    private let refs: [(String, Double, Double)] = [
        ("i", 280, 2250), ("e", 400, 2100), ("ɛ", 550, 1900), ("a", 700, 1500),
        ("ɑ", 750, 1100), ("ɔ", 550, 900), ("o", 450, 800), ("u", 320, 850), ("ə", 500, 1500),
    ]

    var body: some View {
        VStack(spacing: 10) {
            Text("Vowel editor").font(.headline)
            Text("Drag in the vowel space to hear it (formant synthesis).")
                .font(.caption).foregroundStyle(.secondary)

            GeometryReader { geo in
                let W = Double(geo.size.width), H = Double(geo.size.height)
                ZStack(alignment: .topLeading) {
                    Color(white: 0.97)
                    Canvas { ctx, size in
                        // reference vowels
                        for (name, rf1, rf2) in refs {
                            let p = pos(rf1, rf2, W, H)
                            ctx.draw(Text(name).font(.system(size: 15)).foregroundColor(.gray), at: p)
                        }
                        // current vowel dot
                        let p = pos(f1, f2, W, H)
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16)),
                                 with: .color(.red))
                    }
                    // axis labels
                    Text("F2 → front").font(.caption2).foregroundStyle(.secondary).position(x: 50, y: 12)
                    Text("F1 → open").font(.caption2).foregroundStyle(.secondary)
                        .rotationEffect(.degrees(-90)).position(x: 12, y: H / 2)
                }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        f2 = clamp(f2hi - (Double(g.location.x) / W) * (f2hi - f2lo), f2lo, f2hi)
                        f1 = clamp(f1lo + (Double(g.location.y) / H) * (f1hi - f1lo), f1lo, f1hi)
                    }
                    .onEnded { _ in play() })
            }
            .frame(height: 300)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))

            HStack {
                Text(String(format: "F1 %.0f  F2 %.0f Hz", f1, f2)).font(.system(.callout, design: .monospaced))
                Spacer()
                Button { play() } label: { Label("Play", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
            }
            HStack {
                Text("F0").font(.caption)
                Slider(value: $f0, in: 80...250)
                Text("\(Int(f0)) Hz").font(.caption.monospaced())
            }
            Spacer()
        }
        .padding()
    }

    private func pos(_ vf1: Double, _ vf2: Double, _ W: Double, _ H: Double) -> CGPoint {
        CGPoint(x: W * (f2hi - vf2) / (f2hi - f2lo), y: H * (vf1 - f1lo) / (f1hi - f1lo))
    }
    private func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }
    private func play() { audio.play(VowelSynth.synth(f0: f0, f1: f1, f2: f2), rate: 16000) }
}
