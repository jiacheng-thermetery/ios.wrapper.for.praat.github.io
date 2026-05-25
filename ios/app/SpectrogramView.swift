// SpectrogramView.swift — spectrogram + pitch/formant/intensity overlays,
// time cursor, annotation tier, and spectral slice.
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

struct Annotation: Identifiable {
    let id = UUID()
    var start: Double      // interval start time (s)
    var label: String = ""
}

struct TimeRange: Equatable {
    var a: Double, b: Double
    var lo: Double { min(a, b) }
    var hi: Double { max(a, b) }
}

struct SpectrogramView: View {
    @ObservedObject var model: PraatModel
    @Binding var cursorTime: Double?
    @Binding var selection: TimeRange?
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
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    guard model.hasSound else { return }
                    if abs(g.translation.width) > 6 {
                        selection = TimeRange(a: timeAt(g.startLocation.x, W), b: timeAt(g.location.x, W))
                    }
                }
                .onEnded { g in
                    guard model.hasSound else { return }
                    if abs(g.translation.width) <= 6 {
                        cursorTime = timeAt(g.location.x, W); selection = nil
                    } else {
                        selection = TimeRange(a: timeAt(g.startLocation.x, W), b: timeAt(g.location.x, W))
                        cursorTime = nil
                    }
                })
        }
    }

    private func timeAt(_ x: Double, _ W: Double) -> Double {
        min(max(model.viewStart + (x / W) * model.viewSpan, model.viewStart), model.viewEnd)
    }
    private func xFor(time t: Double, _ W: Double) -> Double { W * (t - model.viewStart) / model.viewSpan }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        guard model.hasSound, model.viewEnd > model.viewStart else { return }
        let W = size.width, H = size.height

        // selection highlight (drawn under the overlays)
        if let s = selection {
            let x0 = xFor(time: s.lo, W), x1 = xFor(time: s.hi, W)
            ctx.fill(Path(CGRect(x: x0, y: 0, width: x1 - x0, height: H)), with: .color(.pink.opacity(0.25)))
        }

        // frequency gridlines + labels (every 1000 Hz)
        var f = 1000.0
        while f < model.fmax {
            let y = H * (1 - f / model.fmax)
            ctx.stroke(Path { $0.move(to: .init(x: 0, y: y)); $0.addLine(to: .init(x: W, y: y)) },
                       with: .color(.white.opacity(0.18)), lineWidth: 0.5)
            ctx.draw(Text("\(Int(f))").font(.system(size: 9)).foregroundColor(.white.opacity(0.8)),
                     at: .init(x: 26, y: y - 6))
            f += 1000
        }

        func cx(_ i: Int, _ n: Int) -> Double { W * (Double(i) + 0.5) / Double(n) }

        if showIntensity { drawCurve(ctx, model.intensity, W, H, .yellow, lineWidth: 2) }
        if showPitch { drawCurve(ctx, model.pitch, W, H, .cyan, lineWidth: 2.5) }
        if showFormants {
            for fc in model.formants {
                let n = fc.values.count
                for i in 0..<n {
                    let v = fc.values[i]
                    guard v.isFinite, Double(v) <= model.fmax else { continue }
                    let x = cx(i, n), y = H * (1 - Double(v) / model.fmax)
                    ctx.fill(Path(ellipseIn: CGRect(x: x - 1.3, y: y - 1.3, width: 2.6, height: 2.6)),
                             with: .color(.red))
                }
            }
        }

        // time cursor
        if let t = cursorTime, t >= model.viewStart, t <= model.viewEnd {
            let x = xFor(time: t, W)
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
    var viewStart: Double
    var viewEnd: Double
    var duration: Double

    private var span: Double { max(viewEnd - viewStart, 1e-6) }

    var body: some View {
        GeometryReader { geo in
            let W = geo.size.width
            ZStack(alignment: .topLeading) {
                Color(white: 0.98)
                Canvas { ctx, size in
                    let sorted = annotations.sorted { $0.start < $1.start }
                    for (idx, a) in sorted.enumerated() {
                        let x = W * (a.start - viewStart) / span
                        if a.start >= viewStart && a.start <= viewEnd {
                            ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: size.height)) },
                                       with: .color(a.id == selected ? .blue : .gray), lineWidth: a.id == selected ? 2 : 1)
                        }
                        let next = idx + 1 < sorted.count ? sorted[idx + 1].start : duration
                        let midX = W * ((a.start + next) / 2 - viewStart) / span
                        if !a.label.isEmpty && midX > 0 && midX < W {
                            ctx.draw(Text(a.label).font(.system(size: 12)), at: .init(x: midX, y: size.height / 2))
                        }
                    }
                }
                .frame(width: W)
            }
            .contentShape(Rectangle())
            .onTapGesture { loc in
                guard span > 0 else { return }
                let t = min(max(viewStart, viewStart + Double(loc.x / W) * span), viewEnd)
                if let hit = nearestBoundary(t: t), abs(hit.start - t) / span * W < 12 {
                    selected = hit.id
                } else {
                    let a = Annotation(start: t)
                    annotations.append(a); selected = a.id
                }
            }
        }
    }

    private func nearestBoundary(t: Double) -> Annotation? {
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
