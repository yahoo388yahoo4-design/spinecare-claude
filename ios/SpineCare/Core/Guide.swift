import Foundation
import CoreGraphics

/// Synthetic demonstration for one exercise, generated from its plan entry
/// (mirrors Android GuideDemo.kt): a seated figure in the needed camera view
/// calibrates, then moves in / holds / returns for each rep at the plan's tempo.
/// The amplitude is solved so the app's own signal crosses the rep band, and
/// the "mistake" variant adds the movement one form rule warns about.
/// Landmarks are in pixels of a `GuideDemo.w` x `GuideDemo.h` portrait frame.
final class GuideDemo {
    enum Phase: String { case calibrate, `in`, hold, out, rest }
    enum Move: CaseIterable { case headShift, headNod, headYaw, headTilt, shoulderHike, shrug, trunkRotate, shoulderSqueeze, armsOut, armSwing }

    struct Frame { let t: TimeInterval; let landmarks: [String: Metrics.LM]; let phase: Phase; let phaseLeft: TimeInterval; let rep: Int }
    struct Motion { let move: Move; let amounts: [Double] }

    static let w = 720.0, h = 960.0, demoReps = 3

    let exercise: Exercise
    let calibration: TimeInterval
    let mistake: Bool
    let base: [String: Metrics.LM]
    let reps: Int
    let inS: TimeInterval, holdS: TimeInterval, outS: TimeInterval, restS: TimeInterval = 1
    var leadS: TimeInterval { calibration + 1 }
    var repS: TimeInterval { inS + holdS + outS + restS }
    var duration: TimeInterval { leadS + Double(reps) * repS }
    private(set) var motion: Motion?
    private(set) var mistakeRule: FormRule?
    private var mistakeMotion: Motion?
    private let useAbs: Bool
    private let metric: String
    private let baseMetrics: [String: Double]
    private let zoom: Double
    var countable: Bool { motion != nil }

    init(_ ex: Exercise, calibration: TimeInterval, mistake: Bool = false) {
        exercise = ex; self.calibration = calibration; self.mistake = mistake
        base = Self.basePose(ex.view, program: ex.programId)
        zoom = Self.zoom(ex.programId)
        reps = max(1, min(Self.demoReps, ex.targetReps))
        inS = max(0.5, ex.tempoS?.in ?? 1.5); holdS = max(0.6, ex.tempoS?.hold ?? 1); outS = max(0.5, ex.tempoS?.out ?? 1.5)
        useAbs = ex.signal.hasPrefix("abs:")
        metric = useAbs ? String(ex.signal.dropFirst(4)) : ex.signal
        baseMetrics = Metrics.compute(base)
        motion = solveSignal()
        if mistake {
            for rule in ex.formRules { if let m = solveForm(rule) { mistakeRule = rule; mistakeMotion = m; break } }
        }
    }

    func frame(at tIn: TimeInterval) -> Frame {
        let t = min(max(0, tIn), duration)
        if t < leadS { return Frame(t: t, landmarks: Self.breathe(base, t), phase: .calibrate, phaseLeft: leadS - t, rep: 0) }
        let i = min(reps - 1, Int((t - leadS) / repS))
        let r = t - leadS - Double(i) * repS
        let seg: (Phase, TimeInterval, Double)
        if r < inS { seg = (.in, inS - r, Self.ease(r / inS)) }
        else if r < inS + holdS { seg = (.hold, inS + holdS - r, 1) }
        else if r < inS + holdS + outS { seg = (.out, inS + holdS + outS - r, 1 - Self.ease((r - inS - holdS) / outS)) }
        else { seg = (.rest, repS - r, 0) }
        let (phase, left, env) = seg
        var p = base
        if let m = motion { p = Self.apply(p, m.move, m.amounts[i % m.amounts.count] * env) }
        if let m = mistakeMotion { p = Self.apply(p, m.move, m.amounts[0] * env) }
        return Frame(t: t, landmarks: Self.breathe(p, t), phase: phase, phaseLeft: left, rep: i + 1)
    }

    func signal(of p: [String: Metrics.LM]) -> Double? {
        guard let v = Metrics.compute(p)[metric], let b = baseMetrics[metric] else { return nil }
        let d = (v - b) * (exercise.direction ?? 1)
        return useAbs ? abs(d) : d
    }

    // MARK: solving

    private func candidates(_ metric: String) -> [Move] {
        let pref = Self.preferred[metric]
        return (pref.map { [$0] } ?? []) + Move.allCases.filter { $0 != pref }
    }

    private func solveSignal() -> Motion? {
        guard !metric.isEmpty, baseMetrics[metric] != nil else { return nil }
        let target = exercise.repBand.high * 1.4
        for move in candidates(metric) {
            let found = [solve(move, target, signal(of:)), solve(move, -target, signal(of:))].compactMap { $0 }
            if found.isEmpty { continue }
            if useAbs && found.count == 2 { return Motion(move: move, amounts: found) }
            return Motion(move: move, amounts: [found.min { abs($0) < abs($1) }!])
        }
        return nil
    }

    private func solveForm(_ rule: FormRule) -> Motion? {
        guard let b = baseMetrics[rule.metric] else { return nil }
        let target = rule.maxAbs * 1.6
        let value: ([String: Metrics.LM]) -> Double? = { p in
            guard let v = Metrics.compute(p)[rule.metric] else { return nil }
            return abs(rule.relative == true ? v - b : v)
        }
        for move in candidates(rule.metric) where move != motion?.move {
            if let u = solve(move, target, value) ?? solve(move, -target, value) { return Motion(move: move, amounts: [u]) }
        }
        return nil
    }

    private func solve(_ move: Move, _ target: Double, _ f: ([String: Metrics.LM]) -> Double?) -> Double? {
        let sign: Double = target < 0 ? -1 : 1, goal = abs(target)
        let limit = (Self.limits[move] ?? 40) * (Self.degrees.contains(move) ? 1 : zoom)
        var prev = 0.0
        for k in 1...60 {
            let u = sign * limit * Double(k) / 60
            guard let v = f(Self.apply(base, move, u)) else { continue }
            if v >= goal {
                var lo = prev, hi = u
                for _ in 0..<30 {
                    let mid = (lo + hi) / 2
                    if let mv = f(Self.apply(base, move, mid)), mv >= goal { hi = mid } else { lo = mid }
                }
                return hi
            }
            prev = u
        }
        return nil
    }

    // MARK: poses and movements

    private static let preferred: [String: Move] = [
        "head_forward_proxy": .headShift, "cervical_sag_proxy": .headNod, "neck_yaw_est": .headYaw,
        "cervical_tilt": .headTilt, "shoulder_line_tilt": .shoulderHike, "trunk_lean_sagittal": .trunkRotate,
        "trunk_tilt_frontal": .trunkRotate, "shoulder_width_ratio": .shoulderSqueeze, "head_forward_neck": .headShift,
        "head_pitch_side": .headNod, "head_roll": .headTilt, "shoulder_elev": .shrug, "arm_elevation": .armsOut,
        "arm_elev_asym": .armSwing, "arm_ext_side": .armSwing,
    ]
    private static let limits: [Move: Double] = [
        .headShift: 80, .headNod: 45, .headYaw: 60, .headTilt: 40, .shoulderHike: 60, .shrug: 60,
        .trunkRotate: 35, .shoulderSqueeze: 60, .armsOut: 110, .armSwing: 60,
    ]
    private static let degrees: Set<Move> = [.headNod, .headTilt, .trunkRotate, .armsOut, .armSwing]
    private static let head = ["nose", "lear", "rear"]
    private static let upper = ["nose", "lear", "rear", "lsho", "rsho", "lelb", "relb", "lwri", "rwri"]

    static func zoom(_ program: String) -> Double { program == "neck" ? 2.2 : program == "upper_body" ? 1.25 : 1 }

    /// Vertical centre of the programme zoom (about the shoulders), in frame pixels.
    static let zoomCenterY = 330.0

    /// Landmarks mapped back from a programme's zoomed framing to the whole-body frame, for drawing.
    static func unzoom(_ p: [String: Metrics.LM], program: String) -> [String: Metrics.LM] {
        let z = zoom(program)
        if z == 1 { return p }
        return p.mapValues { Metrics.LM(x: w / 2 + ($0.x - w / 2) / z, y: zoomCenterY + ($0.y - zoomCenterY) / z, conf: $0.conf) }
    }

    private static func p(_ x: Double, _ y: Double, _ c: Double = 0.95) -> Metrics.LM { Metrics.LM(x: x, y: y, conf: c) }

    /// Seated figure framed for the programme: whole body, waist up, or head and shoulders.
    static func basePose(_ view: String, program: String = "full") -> [String: Metrics.LM] {
        let body = seated(view), z = zoom(program)
        if z == 1 { return body }
        return body.mapValues { v in
            let x = w / 2 + (v.x - w / 2) * z, y = zoomCenterY + (v.y - zoomCenterY) * z
            return Metrics.LM(x: x, y: y, conf: (x > 0 && x < w && y > 0 && y < h) ? v.conf : 0)
        }
    }

    private static func seated(_ view: String) -> [String: Metrics.LM] {
        if view == "side" {
            return [
                "nose": p(405, 312), "lear": p(352, 300), "rear": p(346, 298, 0.2),
                "lsho": p(340, 400), "rsho": p(334, 398, 0.3), "lelb": p(362, 530), "relb": p(356, 528, 0.3),
                "lwri": p(440, 600), "rwri": p(434, 598, 0.3), "lhip": p(330, 640), "rhip": p(324, 638, 0.3),
                "lknee": p(480, 650), "rknee": p(474, 648, 0.3), "lank": p(480, 830), "rank": p(474, 828, 0.3),
            ]
        }
        let back = view == "back"
        func x(_ v: Double) -> Double { back ? w - v : v }
        return [
            "nose": p(360, 305, back ? 0.05 : 0.95),
            "lear": p(x(395), 295, back ? 0.7 : 0.9), "rear": p(x(325), 295, back ? 0.7 : 0.9),
            "lsho": p(x(465), 400), "rsho": p(x(255), 400), "lelb": p(x(490), 540), "relb": p(x(230), 540),
            "lwri": p(x(430), 610), "rwri": p(x(290), 610), "lhip": p(x(420), 650), "rhip": p(x(300), 650),
            "lknee": p(x(440), 720), "rknee": p(x(280), 720), "lank": p(x(440), 880), "rank": p(x(280), 880),
        ]
    }

    private static func mid(_ a: Metrics.LM?, _ b: Metrics.LM?) -> (Double, Double) {
        guard let a, let b else { return (w / 2, h / 2) }
        return ((a.x + b.x) / 2, (a.y + b.y) / 2)
    }

    private static func rotate(_ q: Metrics.LM, _ cx: Double, _ cy: Double, _ deg: Double) -> Metrics.LM {
        let r = deg * .pi / 180, dx = q.x - cx, dy = q.y - cy
        return Metrics.LM(x: cx + dx * cos(r) - dy * sin(r), y: cy + dx * sin(r) + dy * cos(r), conf: q.conf)
    }

    static func apply(_ p: [String: Metrics.LM], _ move: Move, _ u: Double) -> [String: Metrics.LM] {
        if u == 0 { return p }
        var out = p
        let facing: Double = (p["nose"]?.x ?? 0) < (p["lear"]?.x ?? 0) ? -1 : 1
        let (smx, _) = mid(p["lsho"], p["rsho"])
        let (hmx, hmy) = mid(p["lhip"], p["rhip"])
        func edit(_ k: String, _ f: (Metrics.LM) -> Metrics.LM) { if let v = p[k] { out[k] = f(v) } }
        switch move {
        case .headShift: for k in head { edit(k) { Metrics.LM(x: $0.x + u * facing, y: $0.y, conf: $0.conf) } }
        case .headNod:
            guard let ear = p["lear"] else { return p }
            edit("nose") { rotate($0, ear.x, ear.y, u) }
        case .headYaw:
            edit("nose") { Metrics.LM(x: $0.x + u, y: $0.y, conf: $0.conf) }
            for k in ["lear", "rear"] { edit(k) { Metrics.LM(x: $0.x + u * 0.35, y: $0.y, conf: $0.conf) } }
        case .headTilt:
            let (ex, ey) = mid(p["lear"], p["rear"])
            for k in head { edit(k) { rotate($0, ex, ey, u) } }
        case .shoulderHike:
            edit("rsho") { Metrics.LM(x: $0.x, y: $0.y - u, conf: $0.conf) }
            edit("relb") { Metrics.LM(x: $0.x, y: $0.y - u * 0.7, conf: $0.conf) }
            edit("rwri") { Metrics.LM(x: $0.x, y: $0.y - u * 0.3, conf: $0.conf) }
        case .shrug:
            for s in ["l", "r"] {
                edit(s + "sho") { Metrics.LM(x: $0.x, y: $0.y - u, conf: $0.conf) }
                edit(s + "elb") { Metrics.LM(x: $0.x, y: $0.y - u * 0.8, conf: $0.conf) }
                edit(s + "wri") { Metrics.LM(x: $0.x, y: $0.y - u * 0.6, conf: $0.conf) }
            }
        case .armsOut:
            for s in ["l", "r"] {
                guard let sho = p[s + "sho"] else { continue }
                let outward: Double = sho.x > smx ? -1 : 1
                for k in [s + "elb", s + "wri"] { edit(k) { rotate($0, sho.x, sho.y, u * outward) } }
            }
        case .armSwing:
            for s in ["l", "r"] {
                guard let sho = p[s + "sho"] else { continue }
                for k in [s + "elb", s + "wri"] { edit(k) { rotate($0, sho.x, sho.y, u) } }
            }
        case .trunkRotate: for k in upper { edit(k) { rotate($0, hmx, hmy, u) } }
        case .shoulderSqueeze:
            for k in ["lsho", "rsho", "lelb", "relb"] {
                edit(k) { v in
                    let toward: Double = v.x > smx ? -1 : 1
                    return Metrics.LM(x: v.x + toward * u * (k.hasSuffix("elb") ? 0.8 : 1), y: v.y, conf: v.conf)
                }
            }
        }
        return out
    }

    static func breathe(_ p: [String: Metrics.LM], _ t: TimeInterval) -> [String: Metrics.LM] {
        let dy = sin(t / 4 * 2 * .pi)
        var out = p
        for (k, v) in p where k.hasSuffix("sho") || k.hasSuffix("elb") { out[k] = Metrics.LM(x: v.x, y: v.y + dy, conf: v.conf) }
        return out
    }

    private static func ease(_ x: Double) -> Double { let c = min(1, max(0, x)); return c * c * (3 - 2 * c) }

    static func phaseText(_ f: Frame) -> String {
        let secs = Int(ceil(f.phaseLeft))
        switch f.phase {
        case .calibrate: return L.t("gd_phase_calibrate")
        case .in: return L.f("gd_phase_in", secs)
        case .hold: return L.f("gd_phase_hold", secs)
        case .out: return L.f("gd_phase_out", secs)
        case .rest: return L.t("gd_phase_rest")
        }
    }
}

/// Runs the demo through the real framing gate + tracker at 10 Hz (mirrors Android DemoPlayer).
final class DemoPlayer {
    struct Hud { var signal: Double?; var reps: Int; var cue: String }
    let demo: GuideDemo
    private var tracker: ExerciseTracker
    private var fedUntil: TimeInterval = -0.1
    private var current = Hud(signal: nil, reps: 0, cue: "")

    init(_ demo: GuideDemo) {
        self.demo = demo
        tracker = ExerciseTracker(demo.exercise, calibration: demo.calibration, lang: L.lang)
    }

    func hud(at t: TimeInterval) -> Hud {
        if t < fedUntil - 1e-6 {
            tracker = ExerciseTracker(demo.exercise, calibration: demo.calibration, lang: L.lang)
            fedUntil = -0.1; current = Hud(signal: nil, reps: 0, cue: "")
        }
        while fedUntil + 0.1 <= t + 1e-9 {
            fedUntil += 0.1
            let lms = demo.frame(at: fedUntil).landmarks
            let framing = Framing.check(lms, required: demo.exercise.requiredLandmarks, minConf: demo.exercise.minConf,
                                        w: GuideDemo.w, h: GuideDemo.h)
            let st = tracker.update(Metrics.compute(lms), ready: framing.ready, t: fedUntil)
            var cue = current.cue
            if st.calibrating { cue = L.t("calibrating") }
            else if let c = st.cues.first { cue = c.text }
            else if st.repJustDone { cue = L.f("rep_done", st.reps, demo.reps) }
            else if cue == L.t("calibrating") { cue = "" }
            current = Hud(signal: st.signal, reps: st.reps, cue: cue)
        }
        return current
    }
}

/// Camera-placement coach (mirrors Android PlacementCoach.kt).
enum PlacementCoach {
    struct Item: Identifiable { let key: String; let ok: Bool; var fixKey = ""; var id: String { key } }
    struct Result {
        let items: [Item]
        var ready: Bool { items.allSatisfy(\.ok) }
        var firstFix: Item? { items.first { !$0.ok } }
    }

    static let maxTilt = 12.0, maxRoll = 8.0

    /// Phone tilt from gravity (device axes, in g, as Core Motion reports): pitch forward/back, roll sideways.
    /// Core Motion's y axis points up the screen, so an upright portrait phone reads gravity ≈ (0, -1, 0).
    static func tilt(gx: Double, gy: Double, gz: Double) -> (pitch: Double, roll: Double) {
        (atan2(-gz, hypot(gx, gy)) * 180 / .pi, atan2(-gx, -gy) * 180 / .pi)
    }

    static func assess(_ p: [String: Metrics.LM]?, w: Double, h: Double, ex: Exercise, minConf: Double = 0.5,
                       pitch: Double? = nil, roll: Double? = nil) -> Result {
        var items: [Item] = []
        func phone() {
            if let pitch { items.append(Item(key: "upright", ok: abs(pitch) <= maxTilt, fixKey: "pc_fix_upright")) }
            if let roll { items.append(Item(key: "level", ok: abs(roll) <= maxRoll, fixKey: "pc_fix_level")) }
        }
        func vis(_ k: String) -> Bool { (p?[k]?.conf ?? 0) >= minConf }
        let anyBody = p != nil && (vis("lsho") || vis("rsho") || vis("nose"))
        items.append(Item(key: "person", ok: anyBody, fixKey: "pc_fix_person"))
        guard let p, anyBody else { phone(); return Result(items: items) }

        let shoMid = mid(p["lsho"], p["rsho"]), hipMid = mid(p["lhip"], p["rhip"])
        var torso: Double?
        if let s = shoMid, let hp = hipMid, vis("lhip") || vis("rhip") { torso = hypot(s.0 - hp.0, s.1 - hp.1) }
        var neck: Double?
        if let side = Metrics.bestSideUpper(p), let e = p[side + "ear"], let q = p[side + "sho"] { neck = hypot(e.x - q.x, e.y - q.y) }
        var shoW: Double?
        if let l = p["lsho"], let r = p["rsho"] { shoW = abs(l.x - r.x) }
        var ratio: Double?
        if let sw = shoW {
            if let t = torso, t > 1e-6 { ratio = sw / t } else if let n = neck, n > 1e-6 { ratio = sw / n * 0.45 }
        }
        let faceSeen = (p["nose"]?.conf ?? 0) >= 0.5
        let view: String? = ratio.map { $0 < 0.45 ? "side" : (faceSeen ? "front" : "back") }
        var viewFix: String?
        switch ex.view {
        case "side": if let v = view, v != "side" { viewFix = "pc_fix_turn_side" }
        case "front": if view == "side" || view == "back" { viewFix = "pc_fix_face" }
        case "back": if view == "side" || view == "front" { viewFix = "pc_fix_turn_back" }
        default: break
        }
        items.append(Item(key: "view", ok: viewFix == nil, fixKey: viewFix ?? ""))

        let headTop = ["nose", "lear", "rear"].compactMap { p[$0] }.filter { $0.conf >= minConf }.map(\.y).min()
        let prog = ex.programId
        let (lo, hi) = prog == "neck" ? (0.14, 0.40) : prog == "upper_body" ? (0.09, 0.30) : (0.12, 0.45)
        let size = (prog == "neck" || prog == "upper_body") ? neck.map { $0 / h } : torso.map { $0 / h }
        let tooClose = (size.map { $0 > hi } ?? false) || (size == nil && (shoW ?? 0) / w > 0.75)
        let tooFar = size.map { $0 < lo } ?? false
        items.append(Item(key: "distance", ok: !tooClose && !tooFar,
                          fixKey: tooClose ? "pc_fix_step_back" : tooFar ? "pc_fix_come_closer" : ""))

        let cx = shoMid?.0 ?? p["nose"]?.x
        items.append(Item(key: "center", ok: cx.map { $0 >= 0.3 * w && $0 <= 0.7 * w } ?? true, fixKey: "pc_fix_center"))

        var heightFix: String?
        if let top = headTop, top < 0.06 * h { heightFix = "pc_fix_raise" }
        else if let s = shoMid, s.1 > 0.62 * h { heightFix = "pc_fix_lower" }
        items.append(Item(key: "height", ok: heightFix == nil, fixKey: heightFix ?? ""))
        phone()

        let framing = Framing.check(p, required: ex.requiredLandmarks, minConf: minConf, w: w, h: h)
        items.append(Item(key: "visible", ok: framing.ready, fixKey: framing.issues.first.map { "fix:" + $0 } ?? ""))

        let confs = ex.requiredLandmarks.compactMap { p[Framing.resolve(p, $0)]?.conf }.filter { $0 >= minConf }
        let lightOk = confs.isEmpty || confs.reduce(0, +) / Double(confs.count) >= 0.65
        items.append(Item(key: "light", ok: lightOk, fixKey: "pc_fix_light"))
        return Result(items: items)
    }

    static func fixText(_ key: String) -> String {
        key.hasPrefix("fix:") ? Framing.fixFor(String(key.dropFirst(4))) : L.t(key)
    }

    static func itemText(_ item: Item) -> String { L.t("pc_item_" + item.key) }

    private static func mid(_ a: Metrics.LM?, _ b: Metrics.LM?) -> (Double, Double)? {
        if let a, let b { return ((a.x + b.x) / 2, (a.y + b.y) / 2) }
        if let a { return (a.x, a.y) }
        if let b { return (b.x, b.y) }
        return nil
    }
}

/// Running summary of a processed video (mirrors Android VideoReport).
final class VideoReport {
    let exercise: Exercise
    private(set) var frames = 0, framedFrames = 0, trackedFrames = 0, reps = 0
    private(set) var calibrated = false
    private(set) var duration: TimeInterval = 0
    private(set) var placementCounts: [String: Int] = [:]
    private(set) var cueCounts: [String: Int] = [:]

    init(_ ex: Exercise) { exercise = ex }

    func add(_ t: TimeInterval, placement: PlacementCoach.Result, ready: Bool, state: ExerciseTracker.State?) {
        frames += 1; duration = max(duration, t)
        if ready { framedFrames += 1 }
        if let f = placement.firstFix { placementCounts[f.fixKey.isEmpty ? f.key : f.fixKey, default: 0] += 1 }
        if let s = state {
            reps = s.reps
            if !s.calibrating { calibrated = true }
            if ready && !s.calibrating {
                trackedFrames += 1
                for c in s.cues { cueCounts[c.text, default: 0] += 1 }
            }
        }
    }

    var framedFraction: Double { frames == 0 ? 0 : Double(framedFrames) / Double(frames) }

    func tips() -> [String] {
        guard frames > 0 else { return [L.t("va_tip_no_frames")] }
        var out: [String] = []
        if let top = placementCounts.max(by: { $0.value < $1.value }), Double(top.value) >= Double(frames) * 0.15 {
            out.append(L.f("va_tip_placement", PlacementCoach.fixText(top.key), String(100 * top.value / frames)))
        }
        if !calibrated { out.append(L.t("va_tip_calibrate")) }
        else if reps == 0 && framedFraction >= 0.6 { out.append(L.t("va_tip_no_reps")) }
        if let top = cueCounts.max(by: { $0.value < $1.value }), trackedFrames > 0, Double(top.value) >= Double(trackedFrames) * 0.1 {
            out.append(L.f("va_tip_cue", top.key, String(100 * top.value / trackedFrames)))
        }
        if out.isEmpty { out.append(L.t("va_tip_good")) }
        return out
    }
}

/// Live help getting into position on the session screen (mirrors Android
/// FrameCoach.kt): the most important thing to fix, said in the person's own
/// terms, which way to move (drawn as an arrow) and a ghost outline of where
/// the body should be. `Stabilizer` keeps the spoken hint from flickering.
enum FrameCoach {
    /// left / right / up / down: the way the body has to move in the camera picture.
    enum Kind { case none, person, view, closer, back, left, right, up, down, light, visible }

    struct Hint {
        let ok: Bool
        /// L key, or "fix:<framing issue>" (see PlacementCoach.fixText).
        let key: String
        let kind: Kind
        /// Middle of the body in frame pixels.
        let center: CGPoint?
        /// Where the body should be, in frame pixels.
        let ghost: [String: Metrics.LM]
    }

    static let keyOK = "fc_good"

    /// `mirrored`: the frames are mirrored like a selfie (front camera), which swaps left and right.
    static func assess(_ p: [String: Metrics.LM]?, w: Double, h: Double, ex: Exercise, minConf: Double = 0.5,
                       mirrored: Bool = false) -> Hint {
        let g = ghost(ex.view, program: ex.programId, w: w, h: h)
        let c = p.flatMap { bodyCenter($0, minConf: minConf) }
        guard let fix = PlacementCoach.assess(p, w: w, h: h, ex: ex, minConf: minConf).firstFix else {
            return Hint(ok: true, key: keyOK, kind: .none, center: c, ghost: g)
        }
        func hint(_ key: String, _ kind: Kind) -> Hint { Hint(ok: false, key: key, kind: kind, center: c, ghost: g) }
        switch fix.key {
        case "person": return hint("pc_fix_person", .person)
        case "view": return hint(fix.fixKey, .view)
        case "distance": return fix.fixKey == "pc_fix_step_back" ? hint("fc_step_back", .back) : hint("fc_come_closer", .closer)
        case "center":
            let right = Double(c?.x ?? CGFloat(w / 2)) < w / 2
            return hint(sideways(ex.view, right: right, p, mirrored: mirrored), right ? .right : .left)
        case "height": return fix.fixKey == "pc_fix_raise" ? hint("fc_raise", .up) : hint("fc_lower", .down)
        case "visible":
            let issue = fix.fixKey.hasPrefix("fix:") ? String(fix.fixKey.dropFirst(4)) : fix.fixKey
            guard let p, issue.hasSuffix(":out_of_frame"),
                  let lm = p[Framing.resolve(p, String(issue.split(separator: ":").first ?? ""))] else { return hint(fix.fixKey, .visible) }
            let m = 0.03
            if lm.y < m * h { return hint("fc_edge_top", .up) }
            if lm.y > (1 - m) * h { return hint("fc_edge_bottom", .down) }
            if lm.x < m * w { return hint(sideways(ex.view, right: true, p, mirrored: mirrored), .right) }
            if lm.x > (1 - m) * w { return hint(sideways(ex.view, right: false, p, mirrored: mirrored), .left) }
            return hint(fix.fixKey, .visible)
        case "light": return hint("pc_fix_light", .light)
        default: return hint(fix.fixKey.isEmpty ? "fix_generic" : fix.fixKey, .none)
        }
    }

    /// The way to move sideways in the person's terms, for a body that must move toward
    /// the picture's right. Facing an unmirrored camera the picture's right is their left;
    /// a selfie (mirrored) picture or a view from behind swaps it; in profile it is
    /// forward or back depending on which way they face.
    static func sideways(_ view: String, right: Bool, _ p: [String: Metrics.LM]?, mirrored: Bool = false) -> String {
        switch view {
        case "front": return (right != mirrored) ? "fc_step_left" : "fc_step_right"
        case "back": return (right != mirrored) ? "fc_step_right" : "fc_step_left"
        case "side":
            let side = p.flatMap { Metrics.bestSideUpper($0) } ?? "l"
            guard let nose = p?["nose"], let ear = p?[side + "ear"] else { return "pc_fix_center" }
            return (nose.x > ear.x) == right ? "fc_move_forward" : "fc_move_backward"
        default: return "pc_fix_center"
        }
    }

    static func bodyCenter(_ p: [String: Metrics.LM], minConf: Double) -> CGPoint? {
        var pts = ["lsho", "rsho", "lhip", "rhip"].compactMap { p[$0] }.filter { $0.conf >= minConf }
        if pts.isEmpty, let n = p["nose"], n.conf >= minConf { pts = [n] }
        guard !pts.isEmpty else { return nil }
        return CGPoint(x: pts.map(\.x).reduce(0, +) / Double(pts.count), y: pts.map(\.y).reduce(0, +) / Double(pts.count))
    }

    /// The guide's seated figure scaled to a w x h camera frame (fitted by height, centred).
    static func ghost(_ view: String, program: String, w: Double, h: Double) -> [String: Metrics.LM] {
        let k = h / GuideDemo.h, ox = (w - GuideDemo.w * k) / 2
        return GuideDemo.basePose(view, program: program).mapValues { Metrics.LM(x: ox + $0.x * k, y: $0.y * k, conf: $0.conf) }
    }

    static func headRadius(_ g: [String: Metrics.LM]) -> Double {
        var r = 8.0
        if let l = g["lear"], let rr = g["rear"] { r = max(r, hypot(l.x - rr.x, l.y - rr.y) * 0.75) }
        if let l = g["lear"], let n = g["nose"] { r = max(r, hypot(l.x - n.x, l.y - n.y) * 0.95) }
        return r
    }

    static func text(_ key: String, beforeStart: Bool) -> String {
        key == keyOK ? L.t(beforeStart ? "vg_framing_ok" : keyOK) : PlacementCoach.fixText(key)
    }

    /// Speaks a hint once it has held for `hold` seconds, again every `repeatEvery`
    /// while still a problem, and "good position" once.
    final class Stabilizer {
        private var candidate: String?
        private var since: TimeInterval = 0
        private var spokenAt: TimeInterval = 0
        private(set) var stable: String?
        private let hold: TimeInterval, repeatEvery: TimeInterval
        init(hold: TimeInterval = 0.9, repeatEvery: TimeInterval = 7) { self.hold = hold; self.repeatEvery = repeatEvery }

        func update(_ key: String, now: TimeInterval) -> String? {
            if key != candidate { candidate = key; since = now }
            if now - since < hold { return nil }
            if key != stable { stable = key; spokenAt = now; return key }
            if key != FrameCoach.keyOK && now - spokenAt >= repeatEvery { spokenAt = now; return key }
            return nil
        }

        func reset() { candidate = nil; stable = nil; since = 0; spokenAt = 0 }
    }
}

/// Saved place of the session's guide box (mirrors Android GuideBox.kt):
/// position as fractions of the free space, width in points (height = 4/3 of
/// it), and whether it is shown or hidden.
enum GuideBoxState {
    static let aspect: CGFloat = 4 / 3
    static let minWidth: CGFloat = 96
    static let defaultX: CGFloat = 0.03, defaultY: CGFloat = 0.25, defaultWidth: CGFloat = 132
    private static let d = UserDefaults.standard

    static var x: CGFloat { get { d.object(forKey: "guide_box_x") as? CGFloat ?? defaultX } set { d.set(Double(newValue), forKey: "guide_box_x") } }
    static var y: CGFloat { get { d.object(forKey: "guide_box_y") as? CGFloat ?? defaultY } set { d.set(Double(newValue), forKey: "guide_box_y") } }
    static var width: CGFloat { get { d.object(forKey: "guide_box_w") as? CGFloat ?? defaultWidth } set { d.set(Double(newValue), forKey: "guide_box_w") } }
    static var shown: Bool { get { d.object(forKey: "guide_box_shown") as? Bool ?? true } set { d.set(newValue, forKey: "guide_box_shown") } }

    static func reset() {
        for k in ["guide_box_x", "guide_box_y", "guide_box_w", "guide_box_shown"] { d.removeObject(forKey: k) }
    }

    static func clampWidth(_ w: CGFloat, in area: CGSize) -> CGFloat {
        let maxW = max(1, min(area.width * 0.9, area.height * 0.9 / aspect))
        return min(max(w, min(minWidth, maxW)), maxW)
    }
}
