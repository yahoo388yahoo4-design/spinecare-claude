import SwiftUI

/// The session's guide box (mirrors Android MoveGuideView + GuideBox): the
/// guide's figure loops one or two reps of the movement with arrows showing
/// where the head, shoulders or arms travel. When `adjustable`, drag it
/// anywhere, resize it with the corner grip or a pinch (it keeps the demo's 3:4
/// frame, so the figure is never cut) and close it with ✕; a tap replays the
/// loop. The place and size are saved in GuideBoxState.
struct GuideBoxOverlay: View {
    let demo: GuideDemo
    let adjustable: Bool
    @Binding var shown: Bool
    @State private var origin: CGPoint?
    @State private var dragStart: CGPoint?
    @State private var width = GuideBoxState.width
    @State private var sizeStart: CGFloat?
    @State private var startedAt = Date()

    var body: some View {
        GeometryReader { geo in
            let area = geo.size
            let w = GuideBoxState.clampWidth(width, in: area), h = w * GuideBoxState.aspect
            let saved = CGPoint(x: GuideBoxState.x * max(0, area.width - w), y: GuideBoxState.y * max(0, area.height - h))
            let o = clamp(origin ?? saved, w: w, h: h, area: area)
            MoveGuideCanvas(demo: demo, startedAt: startedAt)
                .frame(width: w, height: h)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.cyan, lineWidth: 2))
                .overlay(alignment: .topTrailing) {
                    if adjustable {
                        Button { shown = false; GuideBoxState.shown = false } label: {
                            Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 26, height: 26).background(Circle().fill(.black.opacity(0.7)))
                        }
                        .padding(3)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if adjustable {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white).frame(width: 26, height: 26).background(Circle().fill(.black.opacity(0.7)))
                            .padding(3)
                            .highPriorityGesture(DragGesture(minimumDistance: 0)
                                .onChanged { v in
                                    if sizeStart == nil { sizeStart = w; dragStart = o }
                                    width = GuideBoxState.clampWidth((sizeStart ?? w) + max(v.translation.width, v.translation.height / GuideBoxState.aspect), in: area)
                                    origin = dragStart
                                }
                                .onEnded { _ in sizeStart = nil; dragStart = nil; save(area) })
                    }
                }
                .position(x: o.x + w / 2, y: o.y + h / 2)
                .onTapGesture { startedAt = Date() }
                .gesture(DragGesture(minimumDistance: 4)
                    .onChanged { v in
                        if dragStart == nil { dragStart = o }
                        let s = dragStart ?? o
                        origin = clamp(CGPoint(x: s.x + v.translation.width, y: s.y + v.translation.height), w: w, h: h, area: area)
                    }
                    .onEnded { _ in dragStart = nil; save(area) }, including: adjustable ? .all : .none)
                .simultaneousGesture(MagnificationGesture()
                    .onChanged { k in
                        if sizeStart == nil { sizeStart = w }
                        width = GuideBoxState.clampWidth((sizeStart ?? w) * k, in: area)
                    }
                    .onEnded { _ in sizeStart = nil; save(area) }, including: adjustable ? .all : .none)
        }
    }

    private func clamp(_ p: CGPoint, w: CGFloat, h: CGFloat, area: CGSize) -> CGPoint {
        CGPoint(x: min(max(0, p.x), max(0, area.width - w)), y: min(max(0, p.y), max(0, area.height - h)))
    }

    private func save(_ area: CGSize) {
        guard adjustable else { return }
        let w = GuideBoxState.clampWidth(width, in: area), h = w * GuideBoxState.aspect
        let o = clamp(origin ?? CGPoint(x: GuideBoxState.x * max(0, area.width - w), y: GuideBoxState.y * max(0, area.height - h)),
                      w: w, h: h, area: area)
        GuideBoxState.width = w
        GuideBoxState.x = area.width - w > 1 ? o.x / (area.width - w) : 0
        GuideBoxState.y = area.height - h > 1 ? o.y / (area.height - h) : 0
    }
}

/// One or two reps of the demo on a loop, with movement arrows.
struct MoveGuideCanvas: View {
    let demo: GuideDemo
    let startedAt: Date
    private let arrows: [[(Metrics.LM, Metrics.LM)]]

    init(demo: GuideDemo, startedAt: Date) {
        self.demo = demo; self.startedAt = startedAt
        arrows = Self.computeArrows(demo)
    }

    var body: some View {
        TimelineView(.animation) { tl in
            Canvas { ctx, size in
                let shown = Double(min(2, demo.reps))
                let loop = demo.repS * shown
                let t = demo.leadS + tl.date.timeIntervalSince(startedAt).truncatingRemainder(dividingBy: loop)
                let f = demo.frame(at: t)
                GuideDrawing.demo(&ctx, size: size, demo: demo, frame: f, hud: DemoPlayer.Hud(signal: nil, reps: 0, cue: ""), overlay: false)
                let list = arrows.isEmpty ? [] : arrows[max(0, f.rep - 1) % arrows.count]
                let s = max(size.width / GuideDemo.w, size.height / GuideDemo.h)
                let ox = (size.width - GuideDemo.w * s) / 2, oy = (size.height - GuideDemo.h * s) / 2
                for (a, b) in list {
                    let p0 = CGPoint(x: ox + a.x * s, y: oy + a.y * s), p1 = CGPoint(x: ox + b.x * s, y: oy + b.y * s)
                    var path = Path(); path.move(to: p0); path.addLine(to: p1)
                    ctx.stroke(path, with: .color(.green), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                    let ang = atan2(p1.y - p0.y, p1.x - p0.x), hl: CGFloat = 9
                    var head = Path()
                    head.move(to: p1)
                    head.addLine(to: CGPoint(x: p1.x - hl * cos(ang - 0.5), y: p1.y - hl * sin(ang - 0.5)))
                    head.addLine(to: CGPoint(x: p1.x - hl * cos(ang + 0.5), y: p1.y - hl * sin(ang + 0.5)))
                    head.closeSubpath()
                    ctx.fill(head, with: .color(.green))
                }
            }
        }
    }

    /// For each shown rep, up to three arrows from the neutral pose to the held position
    /// for the landmarks that move most, lengthened so small moves (a chin tuck) still read.
    static func computeArrows(_ d: GuideDemo) -> [[(Metrics.LM, Metrics.LM)]] {
        let keys = ["nose", "lwri", "rwri", "lelb", "relb", "lsho", "rsho", "lear", "rear"]
        return (0..<min(2, d.reps)).map { i in
            let held = d.frame(at: d.leadS + Double(i) * d.repS + d.inS + 0.001).landmarks
            let moved = keys.compactMap { k -> (Metrics.LM, Metrics.LM, Double)? in
                guard let a = d.base[k], let b = held[k] else { return nil }
                let len = hypot(b.x - a.x, b.y - a.y)
                return len < 4 ? nil : (a, b, len)
            }.sorted { $0.2 > $1.2 }
            var out: [(Metrics.LM, Metrics.LM)] = []
            for (a, b, len) in moved {
                if out.count == 3 { break }
                if out.contains(where: { hypot($0.0.x - a.x, $0.0.y - a.y) < 60 }) { continue }
                let k = max(1, 70 / len)
                out.append((a, Metrics.LM(x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k, conf: 1)))
            }
            return out
        }
    }
}

/// Framing-coach layer under the skeleton: a ghost outline of where to sit and
/// a pulsing arrow for which way to move (mirrors Android OverlayView).
enum FrameCoachDrawing {
    private static let lines: [(String, String)] = [
        ("lsho", "rsho"), ("lsho", "lhip"), ("rsho", "rhip"), ("lhip", "rhip"),
        ("lsho", "lelb"), ("rsho", "relb"), ("lelb", "lwri"), ("relb", "rwri"),
        ("lhip", "lknee"), ("rhip", "rknee"), ("lknee", "lank"), ("rknee", "rank"),
    ]

    static func draw(_ ctx: inout GraphicsContext, size: CGSize, frame: CGSize, hint: FrameCoach.Hint, t: TimeInterval) {
        guard frame.width > 0, frame.height > 0 else { return }
        let map = OverlayRenderer.mapper(size: size, frame: frame)
        func pt(_ x: Double, _ y: Double) -> CGPoint { map(Metrics.LM(x: x / frame.width, y: y / frame.height, conf: 1)) }
        let s = max(size.width / frame.width, size.height / frame.height)
        let g = hint.ghost
        let ghostColor: Color = hint.ok ? .green.opacity(0.6) : .white.opacity(0.45)
        let style = StrokeStyle(lineWidth: 5, lineCap: .round, dash: [9, 6])
        var ghost = Path()
        for (a, b) in lines {
            guard let pa = g[a], let pb = g[b], pa.conf >= 0.5, pb.conf >= 0.5 else { continue }
            ghost.move(to: pt(pa.x, pa.y)); ghost.addLine(to: pt(pb.x, pb.y))
        }
        var head: CGPoint?
        if let l = g["lear"], let r = g["rear"], l.conf > 0.5, r.conf > 0.5 { head = pt((l.x + r.x) / 2, (l.y + r.y) / 2 - 6 * frame.height / 960) }
        else if let l = g["lear"], let n = g["nose"] { head = pt(l.x + (n.x - l.x) * 0.3, l.y) }
        if let hc = head {
            let r = FrameCoach.headRadius(g) * s
            ghost.addEllipse(in: CGRect(x: hc.x - r, y: hc.y - r, width: 2 * r, height: 2 * r))
        }
        ctx.stroke(ghost, with: .color(ghostColor), style: style)

        if hint.ok {
            let hc = head ?? CGPoint(x: size.width / 2, y: size.height * 0.3)
            let c = CGPoint(x: hc.x + 70, y: hc.y)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 22, y: c.y - 22, width: 44, height: 44)), with: .color(.green.opacity(0.85)))
            var tick = Path()
            tick.move(to: CGPoint(x: c.x - 9, y: c.y)); tick.addLine(to: CGPoint(x: c.x - 2, y: c.y + 8)); tick.addLine(to: CGPoint(x: c.x + 11, y: c.y - 8))
            ctx.stroke(tick, with: .color(.white), style: StrokeStyle(lineWidth: 4.5, lineCap: .round, lineJoin: .round))
            return
        }
        let pulse = 0.5 + 0.5 * sin(t / 0.26)
        let color = Color.yellow.opacity(0.65 + 0.3 * pulse)
        let center = hint.center.map { pt($0.x, $0.y) } ?? CGPoint(x: size.width / 2, y: size.height / 2)
        let len = 70 + 14 * pulse
        func arrow(_ a: CGPoint, _ b: CGPoint) {
            var p = Path(); p.move(to: a); p.addLine(to: b)
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 8, lineCap: .round))
            let ang = atan2(b.y - a.y, b.x - a.x), hl: CGFloat = 22
            var h = Path()
            h.move(to: CGPoint(x: b.x + cos(ang) * hl * 0.4, y: b.y + sin(ang) * hl * 0.4))
            h.addLine(to: CGPoint(x: b.x - hl * cos(ang - 0.6), y: b.y - hl * sin(ang - 0.6)))
            h.addLine(to: CGPoint(x: b.x - hl * cos(ang + 0.6), y: b.y - hl * sin(ang + 0.6)))
            h.closeSubpath()
            ctx.fill(h, with: .color(color))
        }
        switch hint.kind {
        case .left, .right:
            let dir: CGFloat = hint.kind == .right ? 1 : -1
            arrow(center, CGPoint(x: center.x + dir * len * 1.4, y: center.y))
        case .closer:
            arrow(CGPoint(x: center.x - 30, y: center.y), CGPoint(x: center.x - 30 - len, y: center.y))
            arrow(CGPoint(x: center.x + 30, y: center.y), CGPoint(x: center.x + 30 + len, y: center.y))
        case .back:
            arrow(CGPoint(x: center.x - 30 - len, y: center.y), CGPoint(x: center.x - 30, y: center.y))
            arrow(CGPoint(x: center.x + 30 + len, y: center.y), CGPoint(x: center.x + 30, y: center.y))
        case .up, .down:
            let x = size.width - 44, mid = size.height * 0.45, d: CGFloat = hint.kind == .up ? -1 : 1
            arrow(CGPoint(x: x, y: mid - d * len * 0.7), CGPoint(x: x, y: mid + d * len * 0.7))
        default: break
        }
    }
}
