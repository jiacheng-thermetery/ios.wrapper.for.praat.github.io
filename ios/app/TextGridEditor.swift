// TextGridEditor.swift — multi-tier TextGrid annotation (interval + point tiers).
// Part of the Spraak derivative. GPL-3.0-or-later.
//
// [iOS port] LONG-PRESS a tier to add a boundary/point at that time; TAP to select the
// interval under the finger (or the nearest point) and edit its label / delete it in the
// editor row. Interval tiers carry a label mark at t=0 so the first interval is editable
// like any other. (Backported from the Android TextGridEditor.kt.)
import SwiftUI

struct TGMark: Identifiable, Equatable {
    let id = UUID()
    var time: Double        // interval tier: interval start (boundary); point tier: the point
    var label: String = ""
}

struct TGTier: Identifiable, Equatable {
    let id = UUID()
    var name: String
    var isInterval: Bool
    var marks: [TGMark] = []
}

struct TGMarkRef: Equatable { var tier: UUID; var mark: UUID }

// [iOS port] Interval tiers start with a label carrier at t=0: a TGMark at time t labels the
// interval starting at t, so without it the FIRST interval could never be edited. TextGridIO
// already understands it (filtered from the boundary list, matched as the first interval's label).
extension TGTier {
    static func interval(named name: String) -> TGTier {
        TGTier(name: name, isInterval: true, marks: [TGMark(time: 0)])
    }
}

// [iOS port] True if the user actually annotated something (the t=0 carriers alone don't count).
extension Array where Element == TGTier {
    var hasAnnotation: Bool {
        contains { tier in tier.marks.contains { $0.time > 1e-9 || !$0.label.isEmpty } }
    }
}

/// Stacked interval/point tiers aligned to the view window. [iOS port] Long-press a tier to add
/// a boundary/point at that time; tap to select the interval containing the tap (or the nearest
/// point) and edit its label / delete it below.
struct TextGridTiersView: View {
    @Binding var tiers: [TGTier]
    @Binding var selected: TGMarkRef?
    var viewStart: Double
    var viewEnd: Double
    private var span: Double { max(viewEnd - viewStart, 1e-6) }

    // [iOS port] long-press state, as in SpectrogramView: the press location comes from the
    // zero-distance drag (a LongPressGesture carries no location of its own).
    @State private var pressX: Double = 0      // x where the current touch began
    @State private var markAdded = false       // long-press fired → this touch already added a mark
    @State private var hapticTick = 0          // bumped when a long-press adds a mark

    var body: some View {
        VStack(spacing: 2) {
            ForEach($tiers) { $tier in
                HStack(spacing: 4) {
                    Text(tier.name).font(.caption2).foregroundStyle(.secondary)
                        .frame(width: 52, alignment: .leading).lineLimit(1)
                    tierCanvas($tier)
                }
                .frame(height: 34)
            }
        }
        // [iOS port] a tick of haptic feedback the instant the long-press adds a mark
        .sensoryFeedback(.impact, trigger: hapticTick)
    }

    private func tierCanvas(_ tier: Binding<TGTier>) -> some View {
        GeometryReader { geo in
            let W = Double(geo.size.width)
            ZStack(alignment: .topLeading) {
                Color(white: tier.wrappedValue.isInterval ? 0.98 : 0.95)
                Canvas { ctx, size in
                    let H = Double(size.height)
                    let marks = tier.wrappedValue.marks.sorted { $0.time < $1.time }
                    for (i, m) in marks.enumerated() {
                        let isSel = selected == TGMarkRef(tier: tier.wrappedValue.id, mark: m.id)
                        if tier.wrappedValue.isInterval {
                            // [iOS port] interval [m.time, next): subtle highlight when selected,
                            // label centred over the visible part; the t=0 carrier draws no line.
                            let next = i + 1 < marks.count ? marks[i + 1].time : Double.infinity
                            let a = max(m.time, viewStart), b = min(next, viewEnd)
                            if b > a {
                                let x0 = W * (a - viewStart) / span
                                let x1 = W * (b - viewStart) / span
                                if isSel {
                                    ctx.fill(Path(CGRect(x: x0, y: 0, width: x1 - x0, height: H)),
                                             with: .color(.blue.opacity(0.12)))
                                }
                                if !m.label.isEmpty {
                                    ctx.draw(Text(m.label).font(.system(size: 11)), at: .init(x: (x0 + x1) / 2, y: H / 2))
                                }
                            }
                            if m.time > 1e-9 && m.time >= viewStart && m.time <= viewEnd {
                                let x = W * (m.time - viewStart) / span
                                ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                                           with: .color(isSel ? .blue : .gray), lineWidth: isSel ? 2 : 1)
                            }
                        } else {
                            guard m.time >= viewStart && m.time <= viewEnd else { continue }
                            let x = W * (m.time - viewStart) / span
                            ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                                       with: .color(isSel ? .blue : .purple), lineWidth: isSel ? 2 : 1)
                            if !m.label.isEmpty {
                                ctx.draw(Text(m.label).font(.system(size: 11)), at: .init(x: x, y: H / 2 - 8))
                            }
                        }
                    }
                }
            }
            .contentShape(Rectangle())
            // [iOS port] Long-press adds a boundary/point; tap only selects (it never adds
            // marks anymore). simultaneousGesture so the enclosing ScrollView still scrolls.
            .simultaneousGesture(tierTouch(tier, W).simultaneously(with: tierLongPress(tier, W)))
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(.gray.opacity(0.3)))
        }
    }

    // MARK: gestures   [iOS port]

    /// Records where the touch began (for the long-press) and treats a quick, still release
    /// as a tap: select what's under the finger.
    private func tierTouch(_ tier: Binding<TGTier>, _ W: Double) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { g in
                if g.translation == .zero { markAdded = false }   // a new touch began
                pressX = g.startLocation.x
            }
            .onEnded { g in
                defer { markAdded = false }
                guard W > 0, !markAdded,
                      abs(g.translation.width) <= 6, abs(g.translation.height) <= 6 else { return }
                select(in: tier.wrappedValue, atX: g.location.x, W: W)
            }
    }

    /// A hold adds a boundary/point at the press position and selects it.
    private func tierLongPress(_ tier: Binding<TGTier>, _ W: Double) -> some Gesture {
        LongPressGesture(minimumDuration: 0.4, maximumDistance: 12)
            .onEnded { _ in
                guard W > 0 else { return }
                let m = TGMark(time: timeAt(pressX, W))
                tier.wrappedValue.marks.append(m)
                selected = TGMarkRef(tier: tier.wrappedValue.id, mark: m.id)
                markAdded = true
                hapticTick += 1
            }
    }

    /// Tap selection: interval tiers select the interval CONTAINING t (the latest mark with
    /// time <= t — the t=0 carrier guarantees the first interval has one); point tiers select
    /// the nearest point within 16 pt.
    private func select(in tier: TGTier, atX x: Double, W: Double) {
        let t = timeAt(x, W)
        if tier.isInterval {
            let container = tier.marks.filter { $0.time <= t }.max { $0.time < $1.time }
            selected = container.map { TGMarkRef(tier: tier.id, mark: $0.id) }
        } else if let hit = tier.marks.min(by: { abs($0.time - t) < abs($1.time - t) }),
                  abs(hit.time - t) / span * W < 16 {
            selected = TGMarkRef(tier: tier.id, mark: hit.id)
        } else {
            selected = nil
        }
    }

    private func timeAt(_ x: Double, _ W: Double) -> Double {
        min(max(viewStart + (x / W) * span, viewStart), viewEnd)
    }
}

enum TextGridIO {
    /// Generate Praat .TextGrid text. Interval tiers partition [xmin,xmax] at their boundary marks
    /// (a mark at time t labels the interval starting at t); point tiers list their points.
    static func textGrid(tiers: [TGTier], xmin: Double, xmax: Double) -> String {
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var out = """
        File type = "ooTextFile"
        Object class = "TextGrid"

        xmin = \(xmin)
        xmax = \(xmax)
        tiers? <exists>
        size = \(tiers.count)
        item []:

        """
        for (ti, tier) in tiers.enumerated() {
            out += "    item [\(ti + 1)]:\n"
            if tier.isInterval {
                // build a contiguous partition
                let bounds = Array(Set(tier.marks.map { $0.time }.filter { $0 > xmin && $0 < xmax })).sorted()
                let points = [xmin] + bounds + [xmax]
                out += "        class = \"IntervalTier\"\n        name = \(q(tier.name))\n"
                out += "        xmin = \(xmin)\n        xmax = \(xmax)\n        intervals: size = \(points.count - 1)\n"
                for i in 0..<(points.count - 1) {
                    let start = points[i], end = points[i + 1]
                    let label = tier.marks.first { abs($0.time - start) < 1e-9 }?.label ?? ""
                    out += "        intervals [\(i + 1)]:\n            xmin = \(start)\n            xmax = \(end)\n            text = \(q(label))\n"
                }
            } else {
                let pts = tier.marks.sorted { $0.time < $1.time }
                out += "        class = \"TextTier\"\n        name = \(q(tier.name))\n"
                out += "        xmin = \(xmin)\n        xmax = \(xmax)\n        points: size = \(pts.count)\n"
                for (i, p) in pts.enumerated() {
                    out += "        points [\(i + 1)]:\n            number = \(p.time)\n            mark = \(q(p.label))\n"
                }
            }
        }
        return out
    }
}
