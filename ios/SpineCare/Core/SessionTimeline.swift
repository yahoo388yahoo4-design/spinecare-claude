import Foundation

/// One analyzed frame of a session (see SessionTimeline for the file format).
struct TimelineSample {
    let t: Int                       // ms since session start
    let frameW: Int, frameH: Int
    let mirror: Bool
    let landmarks: [String: Metrics.LM]   // normalized 0...1, y down, as displayed
    let metrics: [String: Double]
    let signal: Double?
    let reps: Int
    let cue: String
    let ready: Bool
    let video: Bool                  // a video frame was written for this sample
    let head: [Double]?, trunk: [Double]?   // BLE pitch/roll/yaw
}

/// Per-frame sensor timeline saved next to each camera session so it can be
/// replayed with its overlay, with or without a video. Same JSONL format as
/// Android (SessionTimeline.kt): a header line, then one line per sample:
/// {"t","fw","fh","mi","lm":{name:[x,y,conf]},"m","s","r","c","ok","v","imu":{"head","trunk"}}.
/// iOS frames are mirrored by the capture connection already, so "mi" is false.
final class SessionTimeline {
    static let maxSamples = 20_000

    let exercise: String, metric: String, target: Int, started: Int
    let samples: [TimelineSample]

    init(exercise: String, metric: String, target: Int, started: Int, samples: [TimelineSample]) {
        self.exercise = exercise; self.metric = metric; self.target = target; self.started = started; self.samples = samples
    }

    var durationMs: Int { samples.last?.t ?? 0 }
    var videoOffsetMs: Int { samples.first { $0.video }?.t ?? 0 }

    lazy var repTimes: [Int] = {
        var out: [Int] = [], last = 0
        for s in samples { if s.reps > last { out.append(s.t) }; last = max(last, s.reps) }
        return out
    }()

    lazy var cueTimes: [Int] = {
        var out: [Int] = [], last = ""
        for s in samples { if !s.cue.isEmpty && s.cue != last { out.append(s.t) }; last = s.cue }
        return out
    }()

    /// Latest sample at or before `t`.
    func at(_ t: Int) -> TimelineSample? {
        guard let first = samples.first else { return nil }
        if t <= first.t { return first }
        var lo = 0, hi = samples.count - 1
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if samples[mid].t <= t { lo = mid } else { hi = mid - 1 }
        }
        return samples[lo]
    }

    /// The largest exercise signal, else the middle sample with landmarks (thumbnails).
    var peak: TimelineSample? {
        let withSignal = samples.filter { $0.signal != nil && !$0.landmarks.isEmpty }
        if let best = withSignal.max(by: { abs($0.signal!) < abs($1.signal!) }) { return best }
        let any = samples.filter { !$0.landmarks.isEmpty }
        return any.isEmpty ? nil : any[any.count / 2]
    }

    // MARK: encode / decode

    static func header(exercise: String, metric: String, target: Int, started: Int) -> String {
        line(["type": "header", "v": 1, "exercise": exercise, "metric": metric, "target": target, "started": started])
    }

    private static func r(_ v: Double, _ scale: Double) -> Double { (v * scale).rounded() / scale }

    private static func line(_ o: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(o),
              let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return "" }
        return String(data: d, encoding: .utf8) ?? ""
    }

    static func encode(_ s: TimelineSample) -> String {
        var o: [String: Any] = ["t": s.t, "fw": s.frameW, "fh": s.frameH, "r": s.reps, "ok": s.ready]
        if s.mirror { o["mi"] = true }
        o["lm"] = s.landmarks.mapValues { [r($0.x, 1e4), r($0.y, 1e4), r($0.conf, 100)] }
        o["m"] = s.metrics.filter { $0.value.isFinite }.mapValues { r($0, 1e4) }
        if let sig = s.signal, sig.isFinite { o["s"] = r(sig, 1e4) }
        if !s.cue.isEmpty { o["c"] = s.cue }
        if s.video { o["v"] = true }
        var imu: [String: Any] = [:]
        if let h = s.head, h.allSatisfy({ $0.isFinite }) { imu["head"] = h.map { r($0, 100) } }
        if let t = s.trunk, t.allSatisfy({ $0.isFinite }) { imu["trunk"] = t.map { r($0, 100) } }
        if !imu.isEmpty { o["imu"] = imu }
        return line(o)
    }

    static func read(_ url: URL) -> SessionTimeline? {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        var exercise = "", metric = "", target = 0, started = 0
        var samples: [TimelineSample] = []
        for raw in text.split(separator: "\n") {
            guard let o = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: Any] else { continue }
            if o["type"] as? String == "header" {
                exercise = o["exercise"] as? String ?? ""; metric = o["metric"] as? String ?? ""
                target = o["target"] as? Int ?? 0; started = o["started"] as? Int ?? 0
                continue
            }
            guard let t = o["t"] as? Int else { continue }
            var lms: [String: Metrics.LM] = [:]
            for (k, v) in o["lm"] as? [String: [Double]] ?? [:] where v.count >= 3 { lms[k] = Metrics.LM(x: v[0], y: v[1], conf: v[2]) }
            let imu = o["imu"] as? [String: [Double]]
            samples.append(TimelineSample(
                t: t, frameW: o["fw"] as? Int ?? 0, frameH: o["fh"] as? Int ?? 0, mirror: o["mi"] as? Bool ?? false,
                landmarks: lms, metrics: o["m"] as? [String: Double] ?? [:], signal: o["s"] as? Double,
                reps: o["r"] as? Int ?? 0, cue: o["c"] as? String ?? "", ready: o["ok"] as? Bool ?? false,
                video: o["v"] as? Bool ?? false,
                head: imu?["head"].flatMap { $0.count >= 3 ? $0 : nil }, trunk: imu?["trunk"].flatMap { $0.count >= 3 ? $0 : nil }))
        }
        return samples.isEmpty ? nil : SessionTimeline(exercise: exercise, metric: metric, target: target, started: started, samples: samples)
    }

    /// IMU triple from a BlePostureClient snapshot entry.
    static func imuTriple(_ o: Any?) -> [Double]? {
        guard let d = o as? [String: Double], let p = d["pitch"], let r = d["roll"], let y = d["yaw"] else { return nil }
        return [p, r, y]
    }

    /// Appends samples on the session queue; `close()` returns the count.
    final class Writer {
        let url: URL
        private let handle: FileHandle
        private(set) var count = 0
        private var closed = false

        init?(url: URL, header: String) {
            guard FileManager.default.createFile(atPath: url.path, contents: Data((header + "\n").utf8)),
                  let h = try? FileHandle(forWritingTo: url) else { return nil }
            h.seekToEndOfFile()
            self.url = url; handle = h
        }

        func add(_ s: TimelineSample) {
            guard !closed, count < SessionTimeline.maxSamples else { return }
            let l = SessionTimeline.encode(s)
            guard !l.isEmpty else { return }
            handle.write(Data((l + "\n").utf8)); count += 1
        }

        @discardableResult func close() -> Int {
            if !closed { closed = true; try? handle.close() }
            return count
        }
    }
}
