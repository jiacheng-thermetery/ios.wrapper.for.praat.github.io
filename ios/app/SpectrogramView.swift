// SpectrogramView.swift — spectrogram + pitch/formant/intensity overlays,
// time cursor, annotation tier, and spectral slice.
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

struct Annotation: Identifiable {
    let id = UUID()
    var start: Double      // interval start time (s)
    var label: String = ""
}

struct SpectrogramView: View {
    @ObservedObject var model: PraatModel
    @Binding var cursorTime: Double?
    var showPitch: Bool
    var showFormants: Bool
    var showIntensity: Bool

    var body: some View {
        GeometryReader { geo in
            let W = geo.size.width, H = geo.size.height
            ZStack(alignment: .topLeading) {
                if let img = model.spectrogram {
                    Image(decorative: img, scale: 1)
                        .resizable().interpolation(.low)
                        .frame(width: W, height: H)
                } else {
                    Color(white: 0.95)
                    Text("Record or load a sound").font(.caption)
                        .foregroundStyle(.secondary).padding(8)
                }
                Canvas { ctx, size in draw(ctx, size) }.frame(width: W, height: H)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                guard model.duration > 0 else { return }
                cursorTime = min(max(0, Double(g.location.x / W) * model.duration), model.duration)
            })
        }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        guard model.hasSound, model.duration > 0 else { return }
        let W = size.width, H = size.height

        // frequency gridlines + labels (every 1000 Hz)
        var f = 1000.0
        while f < model.fmax {
            let y = H * (1 - f / model.fmax)
            ctx.stroke(Path { $0.move(to: .init(x: 0, y: y)); $0.addLine(to: .init(x: W, y: y)) },
                       with: .color(.white.opacity(0.18)), lineWidth: 0.5)
            ctx.draw(Text("\(Int(f))").font(.system(size: 9)).foregroundStyle(.white.opacity(0.8)),
                     at: .init(x: 26, y: y - 6))
            f += 1000
        }

        func xFor(_ i: Int, _ n: Int) -> Double { W * (Double(i) + 0.5) / Double(n) }

        // intensity (yellow) on its own scale
        if showIntensity { drawCurve(ctx, model.intensity, W, H, .yellow, lineWidth: 2) }
        // pitch (cyan) on its own scale
        if showPitch { drawCurve(ctx, model.pitch, W, H, .cyan, lineWidth: 2.5) }
        // formants (red dots) on the spectrogram frequency axis
        if showFormants {
            for fc in model.formants {
                let n = fc.values.count
                for i in 0..<n {
                    let v = fc.values[i]
                    guard v.isFinite, Double(v) <= model.fmax else { continue }
                    let x = xFor(i, n), y = H * (1 - Double(v) / model.fmax)
                    ctx.fill(Path(ellipseIn: CGRect(x: x - 1.3, y: y - 1.3, width: 2.6, height: 2.6)),
                             with: .color(.red))
                }
            }
        }

        // time cursor
        if let t = cursorTime {
            let x = W * t / model.duration
            ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                       with: .color(.red), lineWidth: 1)
        }
    }

    private func drawCurve(_ ctx: GraphicsContext, _ c: AnalysisCurve,
                           _ W: Double, _ H: Double, _ color: Color, lineWidth: Double) {
        let n = c.values.count
        guard n > 1, c.hi > c.lo else { return }
        var path = Path()
        var started = false
        for i in 0..<n {
            let v = c.values[i]
            guard v.isFinite else { started = false; continue }
            let x = W * (Double(i) + 0.5) / Double(n)
            let y = H * (1 - (Double(v) - c.lo) / (c.hi - c.lo))
            if started { path.addLine(to: .init(x: x, y: y)) }
            else { path.move(to: .init(x: x, y: y)); started = true }
        }
        ctx.stroke(path, with: .color(color), lineWidth: lineWidth)
    }
}

// MARK: - Annotation tier (TextGrid-style interval tier)

struct AnnotationTierView: View {
    @Binding var annotations: [Annotation]
    @Binding var selected: UUID?
    var duration: Double

    var body: some View {
        GeometryReader { geo in
            let W = geo.size.width
            ZStack(alignment: .topLeading) {
                Color(white: 0.98)
                Canvas { ctx, size in
                    let sorted = annotations.sorted { $0.start < $1.start }
                    for (idx, a) in sorted.enumerated() {
                        let x = W * a.start / max(duration, 1e-6)
                        ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: size.height)) },
                                   with: .color(a.id == selected ? .blue : .gray), lineWidth: a.id == selected ? 2 : 1)
                        let next = idx + 1 < sorted.count ? sorted[idx + 1].start : duration
                        let midX = W * ((a.start + next) / 2) / max(duration, 1e-6)
                        if !a.label.isEmpty {
                            ctx.draw(Text(a.label).font(.system(size: 11)), at: .init(x: midX, y: size.height / 2))
                        }
                    }
                }
                .frame(width: W)
            }
            .contentShape(Rectangle())
            .onTapGesture { loc in
                guard duration > 0 else { return }
                let t = min(max(0, Double(loc.x / W) * duration), duration)
                // select the interval containing t, or add a boundary if tapping near none
                if let hit = nearestBoundary(t: t, W: W), abs(hit.start - t) * (W / duration) < 12 {
                    selected = hit.id
                } else {
                    let a = Annotation(start: t)
                    annotations.append(a); selected = a.id
                }
            }
        }
    }

    private func nearestBoundary(t: Double, W: Double) -> Annotation? {
        annotations.min { abs($0.start - t) < abs($1.start - t) }
    }
}

// MARK: - Spectral slice (Cmd+L)

struct SpectrumSliceView: View {
    let slice: PraatModel.Slice
    let time: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(format: "Spectral slice at %.3f s", time)).font(.caption2).foregroundStyle(.secondary)
            GeometryReader { geo in
                let W = geo.size.width, H = geo.size.height
                Canvas { ctx, size in
                    let n = slice.db.count
                    guard n > 1, slice.dbMax > slice.dbMin else { return }
                    var path = Path()
                    for i in 0..<n {
                        let x = size.width * Double(i) / Double(n - 1)
                        let t = (Double(slice.db[i]) - slice.dbMin) / (slice.dbMax - slice.dbMin)
                        let y = size.height * (1 - min(max(t, 0), 1))
                        if i == 0 { path.move(to: .init(x: x, y: y)) } else { path.addLine(to: .init(x: x, y: y)) }
                    }
                    ctx.stroke(path, with: .color(.black), lineWidth: 1)
                    // freq axis labels
                    ctx.draw(Text("0").font(.system(size: 9)).foregroundStyle(.secondary), at: .init(x: 8, y: H - 6))
                    ctx.draw(Text("\(Int(slice.fmax)) Hz").font(.system(size: 9)).foregroundStyle(.secondary), at: .init(x: W - 26, y: H - 6))
                }
            }
            .frame(height: 110)
            .background(Color(white: 0.97))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(.gray.opacity(0.4)))
        }
    }
}
