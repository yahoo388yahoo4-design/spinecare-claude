import SwiftUI

/// Draws the guide's demonstration person from pose landmarks (mirrors Android
/// FigurePainter.kt): a seated figure with a shaped head (hair, ears, eyes that
/// follow head turns), neck, a shirt with sleeves over a tapered torso,
/// trousers, hands and shoes, on a chair, from the front, the side or the back.
/// Every part hangs off the landmarks, so the body moves as the tracker sees it.
/// Coordinates are the demo's unzoomed 720x960 frame; the caller transforms ctx.
enum FigurePainter {
    static let skin = Color(red: 0.94, green: 0.77, blue: 0.63)
    static let skinShade = Color(red: 0.83, green: 0.61, blue: 0.47)
    static let hair = Color(red: 0.23, green: 0.16, blue: 0.13)
    static let shirt = Color(red: 0.23, green: 0.51, blue: 0.96)
    static let shirtShade = Color(red: 0.11, green: 0.31, blue: 0.85)
    static let pants = Color(red: 0.28, green: 0.33, blue: 0.41)
    static let shoe = Color(red: 0.89, green: 0.91, blue: 0.94)
    static let chair = Color(red: 0.2, green: 0.25, blue: 0.33)
    static let chairDark = Color(red: 0.12, green: 0.16, blue: 0.23)
    private static let feature = Color(red: 0.12, green: 0.16, blue: 0.22)
    private static let mouth = Color(red: 0.62, green: 0.31, blue: 0.27)

    // far-side limbs in profile: the same colours, darker
    private static let farSkin = Color(red: 0.66, green: 0.54, blue: 0.44)
    private static let farSkinShade = Color(red: 0.58, green: 0.43, blue: 0.33)
    private static let farShirt = Color(red: 0.16, green: 0.36, blue: 0.67)
    private static let farPants = Color(red: 0.2, green: 0.23, blue: 0.29)
    private static let farShoe = Color(red: 0.55, green: 0.56, blue: 0.58)

    static func draw(_ ctx: inout GraphicsContext, _ p: [String: Metrics.LM], view: String) {
        if view == "side" { side(&ctx, p) } else { frontBack(&ctx, p, back: view == "back") }
    }

    // MARK: geometry helpers

    private static func pt(_ l: Metrics.LM) -> CGPoint { CGPoint(x: l.x, y: l.y) }
    private static func add(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: a.x + b.x, y: a.y + b.y) }
    private static func sub(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: a.x - b.x, y: a.y - b.y) }
    private static func mul(_ a: CGPoint, _ k: CGFloat) -> CGPoint { CGPoint(x: a.x * k, y: a.y * k) }
    private static func len(_ a: CGPoint) -> CGFloat { hypot(a.x, a.y) }
    private static func norm(_ a: CGPoint) -> CGPoint { let l = len(a); return l < 1e-4 ? CGPoint(x: 0, y: 1) : CGPoint(x: a.x / l, y: a.y / l) }
    /// Perpendicular, rotated 90° clockwise on screen (y down).
    private static func perp(_ a: CGPoint) -> CGPoint { CGPoint(x: -a.y, y: a.x) }
    private static func lerp(_ a: CGPoint, _ b: CGPoint, _ k: CGFloat) -> CGPoint { CGPoint(x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k) }
    private static func deg(_ v: CGPoint) -> CGFloat { atan2(v.y, v.x) * 180 / .pi }
    private static func rot(_ v: CGPoint, _ degrees: CGFloat) -> CGPoint {
        let r = degrees * .pi / 180
        return CGPoint(x: v.x * cos(r) - v.y * sin(r), y: v.x * sin(r) + v.y * cos(r))
    }
    private static func circle(_ c: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)) }

    /// Oval centred on `c`, rotated by `angle` degrees.
    private static func oval(_ c: CGPoint, _ rx: CGFloat, _ ry: CGFloat, _ angle: CGFloat = 0) -> Path {
        Path(ellipseIn: CGRect(x: -rx, y: -ry, width: 2 * rx, height: 2 * ry))
            .applying(CGAffineTransform(rotationAngle: angle * .pi / 180).concatenating(CGAffineTransform(translationX: c.x, y: c.y)))
    }

    /// Tapered limb segment: a quad between two end circles.
    private static func capsule(_ ctx: inout GraphicsContext, _ a: CGPoint, _ b: CGPoint, _ ra: CGFloat, _ rb: CGFloat, _ color: Color) {
        let n = perp(norm(sub(b, a)))
        var path = Path()
        path.move(to: add(a, mul(n, ra))); path.addLine(to: add(b, mul(n, rb)))
        path.addLine(to: sub(b, mul(n, rb))); path.addLine(to: sub(a, mul(n, ra))); path.closeSubpath()
        ctx.fill(path, with: .color(color))
        ctx.fill(circle(a, ra), with: .color(color))
        ctx.fill(circle(b, rb), with: .color(color))
    }

    private static func line(_ ctx: inout GraphicsContext, _ a: CGPoint, _ b: CGPoint, _ c: Color, _ w: CGFloat) {
        var path = Path(); path.move(to: a); path.addLine(to: b)
        ctx.stroke(path, with: .color(c), style: StrokeStyle(lineWidth: w, lineCap: .round))
    }

    /// Upper arm with a short sleeve, forearm and hand.
    private static func arm(_ ctx: inout GraphicsContext, _ sho: CGPoint, _ elb: CGPoint, _ wri: CGPoint, dark: Bool) {
        let s = dark ? farSkin : skin
        capsule(&ctx, sho, elb, 17, 13.5, s)
        capsule(&ctx, elb, wri, 13, 10, s)
        capsule(&ctx, sho, lerp(sho, elb, 0.55), 20, 17, dark ? farShirt : shirt)
        let d = norm(sub(wri, elb))
        ctx.fill(oval(add(wri, mul(d, 13)), 16, 11.5, deg(d)), with: .color(s))
        ctx.fill(circle(add(add(wri, mul(d, 6)), mul(perp(d), 9)), 5.5), with: .color(dark ? farSkinShade : skinShade))
    }

    private static func leg(_ ctx: inout GraphicsContext, _ hip: CGPoint, _ knee: CGPoint, _ ank: CGPoint, dark: Bool, facing: CGFloat) {
        let pc = dark ? farPants : pants
        capsule(&ctx, hip, knee, 30, 24, pc)
        capsule(&ctx, knee, ank, 23, 17, pc)
        let sc = dark ? farShoe : shoe
        if facing != 0 {
            let heel = ank.x - facing * 16, toe = ank.x + facing * 52
            ctx.fill(Path(roundedRect: CGRect(x: min(heel, toe), y: ank.y - 4, width: abs(toe - heel), height: 28), cornerRadius: 14), with: .color(sc))
        } else {
            ctx.fill(Path(roundedRect: CGRect(x: ank.x - 24, y: ank.y + 2, width: 48, height: 24), cornerRadius: 12), with: .color(sc))
        }
        ctx.fill(Path(CGRect(x: ank.x - 11, y: ank.y - 4, width: 22, height: 7)), with: .color(Color(red: 0.39, green: 0.45, blue: 0.55)))
    }

    private static func chairLegs(_ ctx: inout GraphicsContext, _ xs: [CGFloat], top: CGFloat) {
        for x in xs { line(&ctx, CGPoint(x: x, y: top), CGPoint(x: x, y: 862), chair, 13) }
    }

    // MARK: front / back

    private static func frontBack(_ ctx: inout GraphicsContext, _ p: [String: Metrics.LM], back: Bool) {
        func v(_ k: String) -> CGPoint? { p[k].map(pt) }
        guard let ls = v("lsho"), let rs = v("rsho"), let lh = v("lhip"), let rh = v("rhip") else { return }
        let (sL, sR) = ls.x <= rs.x ? (ls, rs) : (rs, ls)
        let (hL, hR) = lh.x <= rh.x ? (lh, rh) : (rh, lh)
        let shoMid = lerp(sL, sR, 0.5), hipMid = lerp(hL, hR, 0.5)

        ctx.fill(Path(roundedRect: CGRect(x: hipMid.x - 118, y: shoMid.y + 4, width: 236, height: hipMid.y + 26 - shoMid.y), cornerRadius: 20),
                 with: .color(chairDark))
        ctx.fill(Path(roundedRect: CGRect(x: hipMid.x - 140, y: hipMid.y + 16, width: 280, height: 30), cornerRadius: 10), with: .color(chair))
        chairLegs(&ctx, [hipMid.x - 122, hipMid.x + 122], top: hipMid.y + 46)

        for s in ["l", "r"] {
            if let hip = v(s + "hip"), let knee = v(s + "knee"), let ank = v(s + "ank") { leg(&ctx, hip, knee, ank, dark: false, facing: 0) }
        }
        let hu = norm(sub(hR, hL))
        let a = sub(hL, mul(hu, 30)), b = add(hR, mul(hu, 30))
        var seat = Path()
        seat.move(to: CGPoint(x: a.x, y: a.y - 26)); seat.addLine(to: CGPoint(x: b.x, y: b.y - 26))
        seat.addQuadCurve(to: CGPoint(x: b.x - 14, y: b.y + 30), control: CGPoint(x: b.x + 4, y: b.y + 26))
        seat.addLine(to: CGPoint(x: a.x + 14, y: a.y + 30))
        seat.addQuadCurve(to: CGPoint(x: a.x, y: a.y - 26), control: CGPoint(x: a.x - 4, y: a.y + 26))
        ctx.fill(seat, with: .color(pants))

        // head geometry
        let lear = v("lear"), rear = v("rear"), nose = v("nose")
        var eL = CGPoint(x: shoMid.x - 35, y: shoMid.y - 105), eR = CGPoint(x: shoMid.x + 35, y: shoMid.y - 105)
        if let l = lear, let r = rear { (eL, eR) = l.x <= r.x ? (l, r) : (r, l) }
        let earHalf = max(20, len(sub(eR, eL)) / 2)
        let hc = add(lerp(eL, eR, 0.5), CGPoint(x: 0, y: -6))
        let tilt = deg(sub(eR, eL))
        let rx = earHalf * 1.43, ry = rx * 1.2
        let up = mul(perp(norm(sub(eR, eL))), -1)
        let chin = sub(hc, mul(up, ry * 0.78))
        capsule(&ctx, add(shoMid, CGPoint(x: 0, y: -2)), chin, 21, 19, skinShade)

        // torso
        let u = norm(sub(sR, sL)), down = perp(u), shoW = len(sub(sR, sL))
        let nL = sub(sub(shoMid, mul(u, shoW * 0.17)), mul(down, 6)), nR = sub(add(shoMid, mul(u, shoW * 0.17)), mul(down, 6))
        let oL = add(sub(sL, mul(u, 16)), mul(down, 10)), oR = add(add(sR, mul(u, 16)), mul(down, 10))
        let wL = add(lerp(sL, hL, 0.64), mul(u, shoW * 0.07)), wR = sub(lerp(sR, hR, 0.64), mul(u, shoW * 0.07))
        let hemL = add(sub(hL, mul(hu, 28)), CGPoint(x: 0, y: 8)), hemR = add(add(hR, mul(hu, 28)), CGPoint(x: 0, y: 8))
        let collar = add(shoMid, mul(down, back ? 2 : 16))
        var torso = Path()
        torso.move(to: nL)
        torso.addQuadCurve(to: oL, control: sub(sL, mul(down, 8)))
        torso.addCurve(to: wL, control1: add(oL, mul(down, 60)), control2: sub(wL, mul(down, 40)))
        torso.addQuadCurve(to: hemL, control: sub(lerp(wL, hemL, 0.6), mul(hu, 6)))
        torso.addQuadCurve(to: hemR, control: add(lerp(hemL, hemR, 0.5), CGPoint(x: 0, y: 6)))
        torso.addQuadCurve(to: wR, control: add(lerp(wR, hemR, 0.6), mul(hu, 6)))
        torso.addCurve(to: oR, control1: sub(wR, mul(down, 40)), control2: add(oR, mul(down, 60)))
        torso.addQuadCurve(to: nR, control: sub(sR, mul(down, 8)))
        torso.addQuadCurve(to: nL, control: collar)
        torso.closeSubpath()
        ctx.fill(torso, with: .linearGradient(Gradient(stops: [.init(color: shirtShade, location: 0), .init(color: shirt, location: 0.3),
                                                                 .init(color: shirt, location: 0.7), .init(color: shirtShade, location: 1)]),
                                               startPoint: CGPoint(x: oL.x, y: 0), endPoint: CGPoint(x: oR.x, y: 0)))
        if !back {
            var c = Path(); c.move(to: nL); c.addQuadCurve(to: nR, control: collar)
            ctx.stroke(c, with: .color(shirtShade), lineWidth: 4)
        } else {
            line(&ctx, add(shoMid, mul(down, 10)), sub(lerp(hemL, hemR, 0.5), CGPoint(x: 0, y: 20)), shirtShade, 3)
        }

        // head
        var yaw: CGFloat = 0
        if !back, let n = nose {
            let d = sub(n, hc)
            yaw = max(-1, min(1, (d.x * u.x + d.y * u.y) / earHalf))
        }
        var h = ctx
        h.translateBy(x: hc.x, y: hc.y)
        h.rotate(by: .degrees(Double(tilt)))
        h.fill(oval(CGPoint(x: -rx * (0.96 + 0.05 * yaw), y: ry * 0.06), 10, 16), with: .color(skinShade))
        h.fill(oval(CGPoint(x: rx * (0.96 - 0.05 * yaw), y: ry * 0.06), 10, 16), with: .color(skinShade))
        let headPath = oval(.zero, rx, ry)
        if back {
            h.fill(headPath, with: .color(hair))
            var nape = h; nape.clip(to: headPath)
            nape.fill(Path(CGRect(x: -rx * 0.45, y: ry * 0.62, width: rx * 0.9, height: ry * 0.4)), with: .color(skinShade))
        } else {
            h.fill(headPath, with: .color(skin))
            var hairCtx = h; hairCtx.clip(to: headPath)
            var fringe = Path()
            fringe.move(to: CGPoint(x: -rx - 4, y: -ry * 0.05))
            fringe.addQuadCurve(to: CGPoint(x: rx * 0.15 + yaw * rx * 0.3, y: -ry * 0.5), control: CGPoint(x: -rx * 0.55 + yaw * rx * 0.3, y: -ry * 0.72))
            fringe.addQuadCurve(to: CGPoint(x: rx + 4, y: -ry * 0.02), control: CGPoint(x: rx * 0.75, y: -ry * 0.38))
            fringe.addLine(to: CGPoint(x: rx + 4, y: -ry - 4)); fringe.addLine(to: CGPoint(x: -rx - 4, y: -ry - 4)); fringe.closeSubpath()
            hairCtx.fill(fringe, with: .color(hair))
            let fx = yaw * rx * 0.5, gap = rx * 0.36 * (1 - abs(yaw) * 0.3), eyeY = ry * 0.04
            h.fill(oval(CGPoint(x: fx - gap, y: eyeY), 5, 5.5), with: .color(feature))
            h.fill(oval(CGPoint(x: fx + gap, y: eyeY), 5, 5.5), with: .color(feature))
            line(&h, CGPoint(x: fx - gap - 9, y: eyeY - 13), CGPoint(x: fx - gap + 8, y: eyeY - 15), hair, 4)
            line(&h, CGPoint(x: fx + gap - 8, y: eyeY - 15), CGPoint(x: fx + gap + 9, y: eyeY - 13), hair, 4)
            let nx = yaw * rx * 0.62
            var np = Path()
            np.move(to: CGPoint(x: nx, y: ry * 0.1)); np.addLine(to: CGPoint(x: nx + yaw * 7, y: ry * 0.3))
            np.addLine(to: CGPoint(x: nx - 5 * (1 - abs(yaw)), y: ry * 0.33))
            h.stroke(np, with: .color(skinShade), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
            let mx = yaw * rx * 0.55, my = ry * 0.52, mw = rx * 0.24
            var mp = Path()
            mp.move(to: CGPoint(x: mx - mw, y: my)); mp.addQuadCurve(to: CGPoint(x: mx + mw, y: my), control: CGPoint(x: mx, y: my + 10))
            h.stroke(mp, with: .color(mouth), style: StrokeStyle(lineWidth: 4, lineCap: .round))
        }

        for s in ["l", "r"] {
            if let sho = v(s + "sho"), let elb = v(s + "elb"), let wri = v(s + "wri") { arm(&ctx, sho, elb, wri, dark: false) }
        }
    }

    // MARK: side

    private static func side(_ ctx: inout GraphicsContext, _ p: [String: Metrics.LM]) {
        func v(_ k: String) -> CGPoint? { p[k].map(pt) }
        guard let sho = v("lsho"), let hip = v("lhip") else { return }
        let lear = v("lear"), nose = v("nose")
        let f: CGFloat = (nose != nil && lear != nil && nose!.x < lear!.x) ? -1 : 1

        let br = [hip.x - f * 92, hip.x - f * 70]
        ctx.fill(Path(roundedRect: CGRect(x: br.min()!, y: sho.y - 6, width: 22, height: hip.y + 40 - sho.y), cornerRadius: 8), with: .color(chairDark))
        let sx = [hip.x - f * 92, hip.x + f * 118]
        ctx.fill(Path(roundedRect: CGRect(x: sx.min()!, y: hip.y + 22, width: 210, height: 24), cornerRadius: 8), with: .color(chair))
        chairLegs(&ctx, [hip.x - f * 76, hip.x + f * 100], top: hip.y + 46)

        if let rh = v("rhip"), let rk = v("rknee"), let ra = v("rank") { leg(&ctx, rh, rk, ra, dark: true, facing: f) }
        if let rs = v("rsho"), let re = v("relb"), let rw = v("rwri") { arm(&ctx, rs, re, rw, dark: true) }
        if let lk = v("lknee"), let la = v("lank") { leg(&ctx, hip, lk, la, dark: false, facing: f) }

        var hc = add(sho, CGPoint(x: f * 25, y: -108)), pitch: CGFloat = 0
        if let e = lear, let n = nose {
            hc = add(lerp(e, n, 0.3), CGPoint(x: 0, y: -8))
            pitch = deg(sub(n, e)) - deg(CGPoint(x: 53 * f, y: 12))
        }
        let rx: CGFloat = 52, ry: CGFloat = 60
        capsule(&ctx, add(sho, CGPoint(x: f * 10, y: -8)), add(hc, rot(CGPoint(x: -f * 8, y: ry * 0.72), pitch)), 21, 18, skinShade)

        let d = norm(sub(hip, sho)), length = len(sub(hip, sho))
        var nf = perp(d); if nf.x * f < 0 { nf = mul(nf, -1) }
        let top = sub(sho, mul(d, 14))
        let chest = add(add(sho, mul(d, length * 0.28)), mul(nf, 44))
        let waist = add(add(sho, mul(d, length * 0.7)), mul(nf, 37))
        let hipF = add(add(hip, mul(nf, 42)), mul(d, 14))
        let hipB = add(sub(hip, mul(nf, 46)), mul(d, 14))
        let lowBack = sub(add(sho, mul(d, length * 0.74)), mul(nf, 34))
        let upBack = sub(add(sho, mul(d, length * 0.22)), mul(nf, 42))
        let start = add(top, mul(nf, 12)), backTop = sub(top, mul(nf, 16))
        var torso = Path()
        torso.move(to: start)
        torso.addCurve(to: chest, control1: sub(add(sho, mul(nf, 42)), mul(d, 8)), control2: sub(chest, mul(d, 34)))
        torso.addQuadCurve(to: waist, control: add(lerp(chest, waist, 0.55), mul(nf, 2)))
        torso.addQuadCurve(to: hipF, control: add(sub(hipF, mul(d, 24)), mul(nf, 4)))
        torso.addLine(to: hipB)
        torso.addQuadCurve(to: lowBack, control: add(sub(lowBack, mul(nf, 8)), mul(d, 30)))
        torso.addQuadCurve(to: upBack, control: sub(lerp(lowBack, upBack, 0.5), mul(nf, 2)))
        torso.addQuadCurve(to: backTop, control: sub(sub(sho, mul(nf, 40)), mul(d, 12)))
        torso.addQuadCurve(to: start, control: CGPoint(x: top.x, y: top.y - 4))
        torso.closeSubpath()
        ctx.fill(torso, with: .linearGradient(Gradient(colors: [shirtShade, shirt]),
                                               startPoint: sub(sho, mul(nf, 46)), endPoint: add(sho, mul(nf, 46))))

        var h = ctx
        h.translateBy(x: hc.x, y: hc.y)
        h.rotate(by: .degrees(Double(pitch)))
        var nosePath = Path()
        nosePath.move(to: CGPoint(x: f * rx * 0.86, y: -ry * 0.06))
        nosePath.addLine(to: CGPoint(x: f * (rx + 13), y: ry * 0.2))
        nosePath.addLine(to: CGPoint(x: f * rx * 0.88, y: ry * 0.3)); nosePath.closeSubpath()
        h.fill(nosePath, with: .color(skin))
        let headPath = oval(.zero, rx, ry)
        h.fill(headPath, with: .color(skin))
        var hairCtx = h; hairCtx.clip(to: headPath)
        var hp = Path()
        hp.move(to: CGPoint(x: f * rx * 0.6, y: -ry - 6))
        hp.addQuadCurve(to: CGPoint(x: -f * rx * 0.02, y: -ry * 0.36), control: CGPoint(x: f * rx * 0.3, y: -ry * 0.48))
        hp.addQuadCurve(to: CGPoint(x: -f * rx * 0.36, y: ry * 0.58), control: CGPoint(x: -f * rx * 0.24, y: ry * 0.12))
        hp.addLine(to: CGPoint(x: -f * rx * 1.2, y: ry * 0.62)); hp.addLine(to: CGPoint(x: -f * rx * 1.2, y: -ry - 6)); hp.closeSubpath()
        hairCtx.fill(hp, with: .color(hair))
        h.fill(oval(CGPoint(x: -f * rx * 0.1, y: ry * 0.06), 10, 15), with: .color(skinShade))
        h.fill(circle(CGPoint(x: f * rx * 0.56, y: -ry * 0.04), 5), with: .color(feature))
        line(&h, CGPoint(x: f * rx * 0.44, y: -ry * 0.2), CGPoint(x: f * rx * 0.72, y: -ry * 0.22), hair, 4)
        line(&h, CGPoint(x: f * rx * 0.84, y: ry * 0.5), CGPoint(x: f * rx * 0.62, y: ry * 0.53), mouth, 4)

        if let le = v("lelb"), let lw = v("lwri") { arm(&ctx, sho, le, lw, dark: false) }
    }
}
