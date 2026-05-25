// TextGridEditor.swift — multi-tier TextGrid annotation (interval + point tiers).
// Part of the Spraak derivative. GPL-3.0-or-later.
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

/// Stacked interval/point tiers aligned to the view window. Tap a tier to add a boundary/point
/// at that time; tap an existing mark to select it (edit its label / delete it below).
struct TextGridTiersView: View {
    @Binding var tiers: [TGTier]
    @Binding var selected: TGMarkRef?
    var viewStart: Double
    var viewEnd: Double
    private var span: Double { max(viewEnd - viewStart, 1e-6) }

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
    }

    private func tierCanvas(_ tier: Binding<TGTier>) -> some View {
        GeometryReader { geo in
            let W = Double(geo.size.width)
            ZStack(alignment: .topLeading) {
                Color(white: tier.wrappedValue.isInterval ? 0.98 : 0.95)
                Canvas { ctx, size in
                    let H = Double(size.height)
                    let marks = tier.wrappedValue.marks.sorted { $0.time < $1.time }
                    for (i, m) in marks.enumerated() where m.time >= viewStart && m.time <= viewEnd {
                        let x = W * (m.time - viewStart) / span
                        let isSel = selected == TGMarkRef(tier: tier.wrappedValue.id, mark: m.id)
                        ctx.stroke(Path { $0.move(to: .init(x: x, y: 0)); $0.addLine(to: .init(x: x, y: H)) },
                                   with: .color(isSel ? .blue : (tier.wrappedValue.isInterval ? .gray : .purple)),
                                   lineWidth: isSel ? 2 : 1)
                        if tier.wrappedValue.isInterval {
                            let next = i + 1 < marks.count ? marks[i + 1].time : viewEnd
                            let midX = W * ((m.time + min(next, viewEnd)) / 2 - viewStart) / span
                            if !m.label.isEmpty { ctx.draw(Text(m.label).font(.system(size: 11)), at: .init(x: midX, y: H / 2)) }
                        } else if !m.label.isEmpty {
                            ctx.draw(Text(m.label).font(.system(size: 11)), at: .init(x: x, y: H / 2 - 8))
                        }
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { loc in
                let t = min(max(viewStart, viewStart + (Double(loc.x) / W) * span), viewEnd)
                let marks = tier.wrappedValue.marks
                if let hit = marks.min(by: { abs($0.time - t) < abs($1.time - t) }),
                   abs(hit.time - t) / span * W < 12 {
                    selected = TGMarkRef(tier: tier.wrappedValue.id, mark: hit.id)
                } else {
                    let m = TGMark(time: t)
                    tier.wrappedValue.marks.append(m)
                    selected = TGMarkRef(tier: tier.wrappedValue.id, mark: m.id)
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(.gray.opacity(0.3)))
        }
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
