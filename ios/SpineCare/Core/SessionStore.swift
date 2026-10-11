import Foundation

/// Session row (JSONL). Videos are stored by file name inside Documents/recordings,
/// because the app container path changes between installs and updates.
struct SessionRecord: Codable, Identifiable {
    var id: Int { ts }
    let ts: Int
    let date: String
    let exercise: String
    let reps: Int
    var sets: Int?
    var durationS: Int?
    let avg: [String: Double]
    var cues: [String: Int]?
    let frames: Int
    let source: String
    var video: String?
    var recordedFrames: Int?
    var timeline: String?
    var timelineSamples: Int?
}

struct CheckinRecord: Codable, Identifiable {
    var id: Int { ts }
    let ts: Int
    let date: String
    let phase: String
    let neckPain: Int
    let armSymptoms: Int
    var sleepQuality: Int?
    let notes: String
}

/// On-device store for sessions, symptom check-ins, custom exercises and
/// clinician confirmations (mirrors Android LocalStore). Nothing leaves the
/// phone unless the user exports it.
final class SessionStore: ObservableObject {
    static let shared = SessionStore()
    @Published private(set) var sessions: [SessionRecord] = []
    @Published private(set) var checkins: [CheckinRecord] = []
    @Published private(set) var customExercises: [Exercise] = []
    @Published private(set) var approvals: [String: Bool] = [:]

    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    var recordingsDir: URL { docs.appendingPathComponent("recordings", isDirectory: true) }
    private var dataDir: URL { docs.appendingPathComponent("data", isDirectory: true) }
    private var sessionsURL: URL { dataDir.appendingPathComponent("sessions.jsonl") }
    private var checkinsURL: URL { dataDir.appendingPathComponent("checkins.jsonl") }
    private var customURL: URL { dataDir.appendingPathComponent("custom_exercises.json") }
    private var approvalsURL: URL { dataDir.appendingPathComponent("approvals.json") }

    private let enc: JSONEncoder = { let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase; return e }()
    private let dec: JSONDecoder = { let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d }()

    private init() {
        try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        load()
    }

    private static func today() -> String {
        let df = DateFormatter(); df.locale = Locale(identifier: "en_US_POSIX"); df.dateFormat = "yyyy-MM-dd"
        return df.string(from: Date())
    }

    private func append<T: Encodable>(_ value: T, to url: URL) {
        guard let line = try? enc.encode(value) else { return }
        let data = line + Data("\n".utf8)
        if let fh = try? FileHandle(forWritingTo: url) {
            fh.seekToEndOfFile(); fh.write(data); try? fh.close()
        } else {
            try? data.write(to: url, options: .atomic)
        }
    }

    private func readLines<T: Decodable>(_ url: URL, as: T.Type) -> [T] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").compactMap { try? dec.decode(T.self, from: Data($0.utf8)) }
    }

    func load() {
        sessions = readLines(sessionsURL, as: SessionRecord.self).reversed()
        checkins = readLines(checkinsURL, as: CheckinRecord.self).reversed()
        customExercises = (try? Data(contentsOf: customURL)).flatMap { try? Plan.decoder.decode([Exercise].self, from: $0) } ?? []
        approvals = (try? Data(contentsOf: approvalsURL)).flatMap { try? JSONDecoder().decode([String: Bool].self, from: $0) } ?? [:]
    }

    // MARK: sessions + check-ins

    func saveSession(exerciseId: String, reps: Int, sets: Int, durationS: Int, metrics: [String: Double],
                     cues: [String: Int], frames: Int, source: String, videoURL: URL?, recordedFrames: Int,
                     timelineURL: URL? = nil, timelineSamples: Int = 0) {
        var rec = SessionRecord(ts: Int(Date().timeIntervalSince1970 * 1000), date: Self.today(), exercise: exerciseId,
                                reps: reps, sets: sets, durationS: durationS, avg: metrics.mapValues { ($0 * 1000).rounded() / 1000 },
                                cues: cues, frames: frames, source: source)
        if let v = videoURL { rec.video = v.lastPathComponent; rec.recordedFrames = recordedFrames }
        if let t = timelineURL { rec.timeline = t.lastPathComponent; rec.timelineSamples = timelineSamples }
        append(rec, to: sessionsURL)
        DispatchQueue.main.async { self.load() }
    }

    func saveCheckin(phase: String, neckPain: Int, armSymptoms: Int, sleepQuality: Int?, note: String) {
        let c = CheckinRecord(ts: Int(Date().timeIntervalSince1970 * 1000), date: Self.today(), phase: phase,
                              neckPain: neckPain, armSymptoms: armSymptoms, sleepQuality: sleepQuality,
                              notes: String(note.prefix(160)))
        append(c, to: checkinsURL)
        load()
    }

    func videoURL(_ rec: SessionRecord) -> URL? {
        guard let v = rec.video, !v.isEmpty else { return nil }
        return recordingsDir.appendingPathComponent((v as NSString).lastPathComponent)
    }

    func videoExists(_ rec: SessionRecord) -> Bool {
        guard let u = videoURL(rec) else { return false }
        return FileManager.default.fileExists(atPath: u.path)
    }

    /// Sensor timeline for a session (see SessionTimeline), stored next to recordings.
    func timelineURL(_ rec: SessionRecord) -> URL? {
        guard let v = rec.timeline, !v.isEmpty else { return nil }
        let u = recordingsDir.appendingPathComponent((v as NSString).lastPathComponent)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    func newTimelineURL(_ base: String) -> URL {
        try? FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        return recordingsDir.appendingPathComponent("\(base).timeline.jsonl")
    }

    func clearHistory() {
        try? FileManager.default.removeItem(at: sessionsURL)
        try? FileManager.default.removeItem(at: checkinsURL)
        if let files = try? FileManager.default.contentsOfDirectory(at: recordingsDir, includingPropertiesForKeys: nil) {
            files.forEach { try? FileManager.default.removeItem(at: $0) }
        }
        load()
    }

    // MARK: plan

    func addCustom(_ ex: Exercise) {
        let list = customExercises + [ex]
        if let data = try? Plan.encoder.encode(list) { try? data.write(to: customURL, options: .atomic) }
        load()
    }

    func removeCustom(_ id: String) {
        let list = customExercises.filter { $0.id != id }
        if let data = try? Plan.encoder.encode(list) { try? data.write(to: customURL, options: .atomic) }
        load()
    }

    func isApproved(_ id: String) -> Bool { approvals[id] ?? false }

    func setApproved(_ id: String, _ v: Bool) {
        var a = approvals; a[id] = v
        if let data = try? JSONEncoder().encode(a) { try? data.write(to: approvalsURL, options: .atomic) }
        load()
    }

    // MARK: export

    /// CSV of sessions and check-ins in the temporary directory, for the share sheet.
    func exportCSV() -> URL? {
        func esc(_ v: Any?) -> String { "\"" + String(describing: v ?? "").replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        func json(_ d: [String: Any]?) -> String {
            guard let d, let data = try? JSONSerialization.data(withJSONObject: d, options: [.sortedKeys]) else { return "" }
            return String(data: data, encoding: .utf8) ?? ""
        }
        var lines = ["type,ts,date,exercise,reps,sets,duration_s,frames,source,phase,neck_pain,arm_symptoms,sleep_quality,notes,avg_metrics,cue_counts"]
        for s in sessions.reversed() {
            let row: [Any] = ["session", s.ts, s.date, s.exercise, s.reps, s.sets ?? 0, s.durationS ?? 0, s.frames, s.source,
                              "", "", "", "", "", json(s.avg as [String: Any]), json(s.cues.map { $0 as [String: Any] })]
            lines.append(row.map { esc($0) }.joined(separator: ","))
        }
        for c in checkins.reversed() {
            let row: [Any] = ["checkin", c.ts, c.date, "", "", "", "", "", "", c.phase, c.neckPain, c.armSymptoms,
                              c.sleepQuality.map { String($0) } ?? "", c.notes, "", ""]
            lines.append(row.map { esc($0) }.joined(separator: ","))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("spinecare_\(Self.today()).csv")
        do { try (lines.joined(separator: "\n") + "\n").write(to: url, atomically: true, encoding: .utf8); return url }
        catch { return nil }
    }
}
