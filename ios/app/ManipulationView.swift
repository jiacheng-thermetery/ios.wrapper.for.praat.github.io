// ManipulationView.swift — Praat Manipulation (PSOLA): drag the pitch tier, hear the resynthesis.
// Includes a reusable draggable curve-tier component. Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

struct TierPoint: Identifiable, Equatable {
    let id = UUID()
    var time: Double
    var value: Double
}

/// Reusable draggable tier editor: points on a time×value plane. Drag a point to move it,
/// tap empty space to add one. Calls onCommit when a drag/edit finishes.
struct TierCurveView: View {
    @Binding var points: [TierPoint]
    var tmin: Double, tmax: Double, vmin: Double, vmax: Double
    var color: Color = .blue
    var onCommit: () -> Void = {}
    @State private var dragIndex: Int?

    private var span: Double { max(tmax - tmin, 1e-6) }
    private var vspan: Double { max(vmax - vmin, 1e-6) }

    var body: some View {
        GeometryReader { geo in
            let W = Double(geo.size.width), H = Double(geo.size.height)
            ZStack {
                Color(white: 0.98)
                Canvas { ctx, _ in
                    // gridlines + value labels
                    for frac in stride(from: 0.0, through: 1.0, by: 0.25) {
                        let y = H * (1 - frac)
                        ctx.stroke(Path { $0.move(to: .init(x: 0, y: y)); $0.addLine(to: .init(x: W, y: y)) },
                                   with: .color(.gray.opacity(0.2)), lineWidth: 0.5)
                        ctx.draw(Text("\(Int(vmin + frac * vspan))").font(.system(size: 9)).foregroundColor(.secondary),
                                 at: .init(x: 18, y: y - 6))
                    }
                    let sorted = points.sorted { $0.time < $1.time }
                    var line = Path()
                    for (i, p) in sorted.enumerated() {
                        let x = W * (p.time - tmin) / span
                        let y = H * (1 - (p.value - vmin) / vspan)
                        if i == 0 { line.move(to: .init(x: x, y: y)) } else { line.addLine(to: .init(x: x, y: y)) }
                    }
                    ctx.stroke(line, with: .color(color), lineWidth: 2)
                    for p in sorted {
                        let x = W * (p.time - tmin) / span
                        let y = H * (1 - (p.value - vmin) / vspan)
                        ctx.fill(Path(ellipseIn: CGRect(x: x - 5, y: y - 5, width: 10, height: 10)), with: .color(color))
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    let t = tmin + (Double(g.location.x) / W) * span
                    let v = vmin + (1 - Double(g.location.y) / H) * vspan
                    if dragIndex == nil {
                        // grab nearest point within ~22 px, else add a new one
                        if let near = points.indices.min(by: { abs(xOf($0, W) - g.location.x) < abs(xOf($1, W) - g.location.x) }),
                           abs(xOf(near, W) - g.location.x) < 22 {
                            dragIndex = near
                        } else {
                            points.append(TierPoint(time: clamp(t, tmin, tmax), value: clamp(v, vmin, vmax)))
                            dragIndex = points.count - 1
                        }
                    }
                    if let i = dragIndex, i < points.count {
                        points[i].time = clamp(t, tmin, tmax)
                        points[i].value = clamp(v, vmin, vmax)
                    }
                }
                .onEnded { _ in dragIndex = nil; onCommit() })
        }
    }
    private func xOf(_ i: Int, _ W: Double) -> CGFloat { CGFloat(W * (points[i].time - tmin) / span) }
    private func clamp(_ x: Double, _ lo: Double, _ hi: Double) -> Double { min(max(x, lo), hi) }
}

struct ManipulationView: View {
    @StateObject private var audio = AudioEngine()
    @State private var points: [TierPoint] = []
    @State private var duration: Double = 0
    @State private var rate: Double = 16000
    @State private var status = "Tap “Load” to manipulate a demo sound."
    private let maxN = 2000

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text("Manipulation").font(.headline)
                Text("(PSOLA pitch)").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button { load() } label: { Label("Load", systemImage: "waveform.path") }
                Button { resynth(); audio.play(lastSamples, rate: rate) } label: { Label("Play", systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent).disabled(points.isEmpty)
            }
            .buttonStyle(.bordered).controlSize(.small)

            Text("Drag the blue pitch points; tap empty space to add. Play hears the resynthesis.")
                .font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)

            if points.isEmpty {
                Spacer(); Text(status).foregroundStyle(.secondary); Spacer()
            } else {
                TierCurveView(points: $points, tmin: 0, tmax: max(duration, 0.001),
                              vmin: 50, vmax: 500, color: .blue) {
                    resynth(); audio.play(lastSamples, rate: rate)
                }
                .frame(maxHeight: .infinity)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))
                HStack {
                    Text(String(format: "%d points · %.2f s", points.count, duration)).font(.caption2).foregroundStyle(.secondary)
                    Spacer()
                    Button("Flatten 200 Hz") { flatten() }.font(.caption2)
                }
            }
        }
        .padding()
        .onAppear { if points.isEmpty { load() } }
    }

    @State private var lastSamples: [Float] = []

    private func load() {
        let (s, r) = DemoSound.vowel()
        _ = s.withUnsafeBufferPointer { praatios_setSound($0.baseAddress, Int32(s.count), r) }
        rate = praatios_soundSampleRate()
        var t = [Double](repeating: 0, count: maxN), v = [Double](repeating: 0, count: maxN)
        let n = t.withUnsafeMutableBufferPointer { tp in
            v.withUnsafeMutableBufferPointer { vp in
                Int(praatios_manipulationStart(Int32(maxN), tp.baseAddress, vp.baseAddress))
            }
        }
        duration = praatios_soundDuration()
        points = (0..<n).map { TierPoint(time: t[$0], value: v[$0]) }
        status = n > 0 ? "" : "Could not create a Manipulation."
        resynth()
    }

    private func flatten() { for i in points.indices { points[i].value = 200 }; resynth(); audio.play(lastSamples, rate: rate) }

    private func resynth() {
        guard !points.isEmpty else { return }
        let sorted = points.sorted { $0.time < $1.time }
        let ts = sorted.map { $0.time }, vs = sorted.map { $0.value }
        let maxSamples = Int(duration * rate) + 4096
        var out = [Float](repeating: 0, count: maxSamples)
        let ns = ts.withUnsafeBufferPointer { tp in
            vs.withUnsafeBufferPointer { vp in
                out.withUnsafeMutableBufferPointer { op in
                    Int(praatios_manipulationResynth(tp.baseAddress, vp.baseAddress, Int32(ts.count),
                                                     op.baseAddress, Int32(maxSamples), &rate))
                }
            }
        }
        lastSamples = ns > 0 ? Array(out[0..<ns]) : []
    }
}
