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

enum SpecDragMode { case none, moveCursor, resizeLo, resizeHi, newSelection }

struct SpectrogramView: View {
    @ObservedObject var model: PraatModel
    @Binding var cursorTime: Double?
    @Binding var selection: TimeRange?
    var showPitch: Bool
    var showFormants: Bool
    var showIntensity: Bool
    /// Commit a pinch-zoom to a new [start,end] window. ContentView records zoom
    /// history and calls model.setView; default no-op for the non-interactive picture.
    var onZoom: (Double, Double) -> Void = { _, _ in }

    @State private var dragMode: SpecDragMode = .none
    @State private var selectionArmed = false        // long-press fired → dragging now selects
    @State private var pressStartX: Double = 0        // x where the current touch began
    @State private var pinchScale: CGFloat = 1        // live horizontal scale during a pinch
    @State private var pinchAnchor: UnitPoint = .center
    @State private var isPinching = false

    var body: some View {
        GeometryReader { geo in
            let W = geo.size.width, H = geo.size.height
            ZStack(alignment: .topLeading) {     // gesture surface (never scaled)
                ZStack(alignment: .topLeading) { // visual layer (scaled live during a pinch)
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
                .frame(width: W, height: H)
                .scaleEffect(x: pinchScale, y: 1, anchor: pinchAnchor)
                .clipped()
            }
            .frame(width: W, height: H)
            .contentShape(Rectangle())
            // [iOS port] Pinch = zoom time; press-and-hold then drag = select (desktop-style);
            // tap = place cursor; grabbing a cursor/selection edge stays immediate (no hold).
            // highPriority so these win over the enclosing ScrollView (landscape).
            .highPriorityGesture(
                pinchZoom(W).simultaneously(with:
                    mainDrag(W).simultaneously(with: armSelection(W)))
            )
            // a tick of haptic feedback the instant the long-press arms selection
            .sensoryFeedback(trigger: selectionArmed) { _, armed in armed ? .selection : nil }
        }
    }

    // MARK: gestures

    private func mainDrag(_ W: Double) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { g in
                guard model.hasSound, !isPinching else { return }
                if dragMode == .none {
                    pressStartX = g.startLocation.x
                    dragMode = decideMode(startX: g.startLocation.x, W: W)
                }
                let t = timeAt(g.location.x, W)
                switch dragMode {
                case .moveCursor: cursorTime = t
                case .resizeLo: if let s = selection { selection = TimeRange(a: t, b: s.hi) }
                case .resizeHi: if let s = selection { selection = TimeRange(a: s.lo, b: t) }
                case .newSelection:
                    if selectionArmed { selection = TimeRange(a: timeAt(pressStartX, W), b: t); cursorTime = nil }
                case .none: break
                }
            }
            .onEnded { g in
                defer { dragMode = .none; selectionArmed = false }
                guard model.hasSound, !isPinching else { return }
                if dragMode == .newSelection {
                    if selectionArmed {
                        // held but never dragged: drop the zero-width selection, place the cursor
                        if let s = selection, s.hi - s.lo < 0.002 * model.viewSpan {
                            selection = nil; cursorTime = timeAt(g.location.x, W)
                        }
                    } else if abs(g.translation.width) <= 6 {
                        cursorTime = timeAt(g.location.x, W); selection = nil       // quick tap = cursor
                    }
                    // a quick un-held swipe does nothing: selection now requires a hold
                }
            }
    }

    private func armSelection(_ W: Double) -> some Gesture {
        LongPressGesture(minimumDuration: 0.3, maximumDistance: 30)
            .onEnded { _ in
                guard model.hasSound, !isPinching else { return }
                // A hold always begins a selection — even over the cursor or an edge handle.
                // (A quick drag there still moves/resizes, since the long-press only fires if
                // the finger stays put, so immediate manipulation is unaffected.)
                dragMode = .newSelection
                selectionArmed = true
                let t = timeAt(pressStartX, W)
                selection = TimeRange(a: t, b: t); cursorTime = nil   // show the anchor immediately
            }
    }

    private func pinchZoom(_ W: Double) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in
                guard model.hasSound else { return }
                isPinching = true; dragMode = .none; selectionArmed = false
                pinchAnchor = v.startAnchor
                pinchScale = max(0.2, min(v.magnification, 8))
            }
            .onEnded { v in
                defer { pinchScale = 1; isPinching = false }
                guard model.hasSound else { return }
                applyZoom(scale: v.magnification, anchorX: Double(v.startAnchor.x))
            }
    }

    /// Map a pinch (scale about a horizontal anchor) to a new time window, keeping the
    /// anchored time fixed on screen. Commit (with history) via onZoom → model.setView.
    private func applyZoom(scale: CGFloat, anchorX: Double) {
        guard scale > 0 else { return }
        let ax = min(max(anchorX, 0), 1)
        let anchorTime = model.viewStart + ax * model.viewSpan
        let newSpan = model.viewSpan / Double(scale)
        let lo = anchorTime - ax * newSpan
        onZoom(lo, lo + newSpan)
    }

    private func timeAt(_ x: Double, _ W: Double) -> Double {
        min(max(model.viewStart + (x / W) * model.viewSpan, model.viewStart), model.viewEnd)
    }
    private func xFor(time t: Double, _ W: Double) -> Double { W * (t - model.viewStart) / model.viewSpan }

    private func decideMode(startX: Double, W: Double) -> SpecDragMode {
        let grab = 18.0
        if let s = selection {
            if abs(startX - xFor(time: s.lo, W)) < grab { return .resizeLo }
            if abs(startX - xFor(time: s.hi, W)) < grab { return .resizeHi }
        }
        if let c = cursorTime, c >= model.viewStart, c <= model.viewEnd,
           abs(startX - xFor(time: c, W)) < grab { return .moveCursor }
        return .newSelection
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        guard model.hasSound, model.viewEnd > model.viewStart else { return }
        let W = size.width, H = size.height

        // selection highlight + draggable edge handles (drawn under the overlays)
        if let s = selection {
            let x0 = xFor(time: s.lo, W), x1 = xFor(time: s.hi, W)
            ctx.fill(Path(CGRect(x: x0, y: 0, width: x1 - x0, height: H)), with: .color(.pink.opacity(0.22)))
            for x in [x0, x1] {
                ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                           with: .color(.pink), lineWidth: 1.5)
                ctx.fill(Path(roundedRect: CGRect(x: x - 5, y: 2, width: 10, height: 18), cornerRadius: 3),
                         with: .color(.pink))
            }
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

        // time cursor + grab handle
        if let t = cursorTime, t >= model.viewStart, t <= model.viewEnd {
            let x = xFor(time: t, W)
            ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                       with: .color(.red), lineWidth: 1)
            ctx.fill(Path(roundedRect: CGRect(x: x - 4, y: 2, width: 8, height: 14), cornerRadius: 2),
                     with: .color(.red))
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
