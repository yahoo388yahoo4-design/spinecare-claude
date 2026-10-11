import Foundation

/// Body-segment metrics, mirroring server/pose.py and Android Metrics.kt
/// (definitions pinned by shared/metric_vectors.json). Landmarks use the shared
/// name space (nose, lear, rear, lsho, rsho, ...) in an isotropic unit (pixels).
/// All angles degrees. ESTIMATES only, never vertebral measurements.
enum Metrics {
    struct LM: Equatable { var x: Double; var y: Double; var z: Double = 0; var conf: Double }

    static let vis = 0.5

    private static func mid(_ a: LM, _ b: LM) -> LM {
        LM(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, conf: min(a.conf, b.conf))
    }
    private static func dist(_ a: LM, _ b: LM) -> Double { hypot(a.x - b.x, a.y - b.y) }
    private static func angBetween(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> Double? {
        let n1 = hypot(x1, y1), n2 = hypot(x2, y2)
        if n1 < 1e-6 || n2 < 1e-6 { return nil }
        return acos(max(-1, min(1, (x1 * x2 + y1 * y2) / (n1 * n2)))) * 180 / .pi
    }
    private static func angFromVertical(_ a: LM, _ b: LM) -> Double? { angBetween(b.x - a.x, b.y - a.y, 0, 1) }

    /// "l" or "r": the side whose ear/shoulder/hip chain is most visible, or nil.
    static func bestSide(_ p: [String: LM]) -> String? {
        func score(_ s: String) -> Double {
            min(p[s + "ear"]?.conf ?? 0, p[s + "sho"]?.conf ?? 0, p[s + "hip"]?.conf ?? 0)
        }
        let s = score("l") >= score("r") ? "l" : "r"
        return score(s) >= vis ? s : nil
    }

    /// "l" or "r": the side whose ear/shoulder pair is most visible, or nil.
    /// Used by head-and-shoulders and waist-up exercises (hips out of view).
    static func bestSideUpper(_ p: [String: LM]) -> String? {
        func score(_ s: String) -> Double { min(p[s + "ear"]?.conf ?? 0, p[s + "sho"]?.conf ?? 0) }
        let s = score("l") >= score("r") ? "l" : "r"
        return score(s) >= vis ? s : nil
    }

    static func compute(_ p: [String: LM]) -> [String: Double] {
        var m: [String: Double] = [:]
        let zero = LM(x: 0, y: 0, conf: 0)
        func g(_ k: String) -> LM { p[k] ?? zero }
        func v(_ k: String) -> Bool { (p[k]?.conf ?? 0) >= vis }
        let lsho = g("lsho"), rsho = g("rsho"), lhip = g("lhip"), rhip = g("rhip")
        let lear = g("lear"), rear = g("rear"), nose = g("nose")
        let bothSho = v("lsho") && v("rsho"), bothHip = v("lhip") && v("rhip")
        let shoMid = mid(lsho, rsho), hipMid = mid(lhip, rhip)
        let shoW = dist(lsho, rsho)

        if bothSho && bothHip {
            let torso = dist(shoMid, hipMid)
            if let a = angFromVertical(shoMid, hipMid) { m["trunk_tilt_frontal"] = a }
            if torso > 1e-6 { m["shoulder_width_ratio"] = shoW / torso }
        }
        if v("lear") && v("rear") && bothSho, let a = angFromVertical(mid(lear, rear), shoMid) {
            m["cervical_tilt"] = a
        }
        // Signed lateral nose offset vs shoulder midline (front view); survives ear occlusion.
        if v("nose") && bothSho && shoW > 1e-6 {
            let ratio = max(-1, min(1, (nose.x - shoMid.x) / (0.28 * shoW)))
            m["neck_yaw_est"] = asin(ratio) * 180 / .pi
        }
        if bothSho {
            let deg = atan2(rsho.y - lsho.y, rsho.x - lsho.x) * 180 / .pi
            let w = (deg + 90).truncatingRemainder(dividingBy: 180)
            m["shoulder_line_tilt"] = (w < 0 ? w + 180 : w) - 90
        }
        // Side view: most visible ear/shoulder/hip chain, oriented by facing direction.
        if let side = bestSide(p), v("nose") {
            let ear = g(side + "ear"), sho = g(side + "sho"), hip = g(side + "hip")
            let facing: Double = nose.x >= ear.x ? 1 : -1
            let torso = dist(sho, hip)
            if torso > 1e-6 {
                m["head_forward_proxy"] = (ear.x - sho.x) * facing / torso
                m["trunk_lean_sagittal"] = atan2((sho.x - hip.x) * -facing, hip.y - sho.y) * 180 / .pi
            }
            if let a = angBetween(nose.x - ear.x, nose.y - ear.y, sho.x - ear.x, sho.y - ear.y) {
                m["cervical_sag_proxy"] = a
            }
        }
        // Upper-body-only metrics: nothing below the shoulders is needed, so they
        // work with a head-and-shoulders (neck programme) or waist-up framing.
        if v("lear") && v("rear") {
            let deg = atan2(rear.y - lear.y, rear.x - lear.x) * 180 / .pi
            let w = (deg + 90).truncatingRemainder(dividingBy: 180)
            m["head_roll"] = (w < 0 ? w + 180 : w) - 90
            if bothSho && shoW > 1e-6 { m["shoulder_elev"] = ((lsho.y - lear.y) + (rsho.y - rear.y)) / 2 / shoW }
        }
        for s in ["l", "r"] where v(s + "sho") && v(s + "elb") {
            if let a = angFromVertical(g(s + "sho"), g(s + "elb")) { m["arm_elev_" + s] = a }
        }
        if let l = m["arm_elev_l"], let r = m["arm_elev_r"] {
            m["arm_elevation"] = min(l, r); m["arm_elev_asym"] = l - r
        }
        if let up = bestSideUpper(p), v("nose") {
            let ear = g(up + "ear"), sho = g(up + "sho")
            let facing: Double = nose.x >= ear.x ? 1 : -1
            let neck = dist(ear, sho)
            if neck > 1e-6 { m["head_forward_neck"] = (ear.x - sho.x) * facing / neck }
            m["head_pitch_side"] = atan2(nose.y - ear.y, (nose.x - ear.x) * facing) * 180 / .pi
            if v(up + "elb") {
                let elb = g(up + "elb")
                m["arm_ext_side"] = atan2((sho.x - elb.x) * facing, elb.y - sho.y) * 180 / .pi
            }
        }
        return m
    }
}

/// Hysteresis rep counter, mirroring the server RepEngine.
final class RepEngine {
    enum Phase: String { case rest, out, inn = "in" }
    private let low: Double, high: Double, minHold: TimeInterval
    private(set) var phase = Phase.rest
    private(set) var reps = 0
    private var tEnter: TimeInterval = 0
    init(low: Double, high: Double, minHold: TimeInterval = 0.4) {
        self.low = low; self.high = high; self.minHold = minHold
    }
    @discardableResult
    func update(_ value: Double?, _ t: TimeInterval) -> Bool {
        guard let v = value, !v.isNaN else { return false }
        var done = false
        switch phase {
        case .rest, .inn:
            if v >= high { phase = .out; tEnter = t }
        case .out:
            if v <= low {
                if t - tEnter >= minHold { reps += 1; done = true }
                phase = .inn; tEnter = t
            }
        }
        return done
    }
}

/// Framing gate, mirroring the server framing_check ("any_ear" = either side).
enum Framing {
    static func resolve(_ p: [String: Metrics.LM], _ name: String) -> String {
        guard name.hasPrefix("any_") else { return name }
        let part = String(name.dropFirst(4))
        return (p["l" + part]?.conf ?? 0) >= (p["r" + part]?.conf ?? 0) ? "l" + part : "r" + part
    }

    static func check(_ p: [String: Metrics.LM]?, required: [String], minConf: Double,
                      w: Double, h: Double, margin: Double = 0.03) -> (ready: Bool, issues: [String], missing: [String]) {
        guard let p, !p.isEmpty else { return (false, ["no_person"], required) }
        var issues: [String] = [], missing: [String] = []
        for req in required {
            guard let lm = p[resolve(p, req)], lm.conf >= minConf else {
                missing.append(req); issues.append("\(req):low_conf"); continue
            }
            if lm.x < margin * w || lm.x > (1 - margin) * w || lm.y < margin * h || lm.y > (1 - margin) * h {
                missing.append(req); issues.append("\(req):out_of_frame")
            }
        }
        return (missing.isEmpty, issues, missing)
    }

    static func fixFor(_ issue: String) -> String {
        let name = String(issue.split(separator: ":").first ?? "")
        var part = name.hasPrefix("any_") ? String(name.dropFirst(4)) : name
        if part != "nose", part.hasPrefix("l") || part.hasPrefix("r") { part = String(part.dropFirst()) }
        let body = L.t("part_" + part)
        if issue.hasSuffix(":low_conf") { return L.f("fix_low_conf", body) }
        if issue.hasSuffix(":out_of_frame") { return L.f("fix_out_of_frame", body) }
        if issue == "no_person" { return L.t("fix_no_person") }
        return L.t("fix_generic")
    }
}

/// Calibration + signal + reps + form cues for one exercise (mirrors the server/Android tracker).
final class ExerciseTracker {
    struct Cue { let metric: String; let value: Double; let text: String }
    struct State { var signal: Double?; var reps: Int; var repJustDone: Bool; var cues: [Cue]; var calibrating: Bool }

    let reps: RepEngine
    private let ex: Exercise
    private let calibration: TimeInterval
    private let lang: String
    private let useAbs: Bool
    private let metric: String
    private var calibStart: TimeInterval?
    private var samples: [String: [Double]] = [:]
    private(set) var baseline: [String: Double]?
    var calibrating: Bool { baseline == nil }

    init(_ ex: Exercise, calibration: TimeInterval, lang: String) {
        self.ex = ex; self.calibration = calibration; self.lang = lang
        reps = RepEngine(low: ex.repBand.low, high: ex.repBand.high)
        useAbs = ex.signal.hasPrefix("abs:")
        metric = useAbs ? String(ex.signal.dropFirst(4)) : ex.signal
    }

    func recalibrate() { calibStart = nil; samples = [:]; baseline = nil }

    func signal(_ m: [String: Double]) -> Double? {
        guard let raw = m[metric], let b = baseline else { return nil }
        let v = (raw - (b[metric] ?? 0)) * (ex.direction ?? 1)
        return useAbs ? abs(v) : v
    }

    func update(_ m: [String: Double], ready: Bool, t: TimeInterval) -> State {
        guard ready else { return State(signal: nil, reps: reps.reps, repJustDone: false, cues: [], calibrating: calibrating) }
        if baseline == nil {
            if calibStart == nil { calibStart = t }
            for (k, v) in m { samples[k, default: []].append(v) }
            if t - (calibStart ?? t) >= calibration, samples[metric] != nil {
                baseline = samples.mapValues(Self.median)
            }
            return State(signal: nil, reps: reps.reps, repJustDone: false, cues: [], calibrating: baseline == nil)
        }
        let sig = signal(m)
        let done = reps.update(sig, t)
        var cues: [Cue] = []
        for rule in ex.formRules {
            guard var v = m[rule.metric] else { continue }
            if rule.relative == true, let b = baseline?[rule.metric] { v -= b }
            if abs(v) > rule.maxAbs { cues.append(Cue(metric: rule.metric, value: v, text: rule.cue[lang] ?? rule.cue["en"] ?? "")) }
        }
        return State(signal: sig, reps: reps.reps, repJustDone: done, cues: cues, calibrating: false)
    }

    static func median(_ v: [Double]) -> Double {
        let s = v.sorted(), n = s.count
        guard n > 0 else { return 0 }
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
