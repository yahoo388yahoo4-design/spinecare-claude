import Foundation

struct FormRule: Codable {
    let metric: String
    let maxAbs: Double
    var relative: Bool?
    let cue: [String: String]
}

struct RepBand: Codable { let low: Double; let high: Double }

/// One plan entry. Built-in entries come from shared/exercises.json (camera
/// tracked); custom entries are added by the user after clinician approval and
/// are tracked manually (timer + set counter).
struct Exercise: Codable, Identifiable, Hashable {
    let id: String
    let name: [String: String]
    let description: [String: String]
    let view: String
    var tracking: String?
    let requiredLandmarks: [String]
    let minConf: Double
    let signal: String
    var direction: Double?
    let repBand: RepBand
    let targetReps: Int
    var targetSets: Int?
    let formRules: [FormRule]
    let stopConditions: [String: [String]]
    let clinicianApproved: Bool
    var custom: Bool?
    var videoUrl: String?

    var cameraTracked: Bool { (tracking ?? "camera") == "camera" }
    var isCustom: Bool { custom ?? false }
    func title(_ lang: String) -> String { name[lang] ?? name["en"] ?? id }
    func details(_ lang: String) -> String { description[lang] ?? description["en"] ?? "" }
    func stops(_ lang: String) -> [String] { stopConditions[lang] ?? stopConditions["en"] ?? [] }

    static func == (a: Exercise, b: Exercise) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }

    static func makeCustom(name: String, instructions: String, sets: Int, reps: Int, videoUrl: String) -> Exercise {
        Exercise(id: "custom-\(Int(Date().timeIntervalSince1970 * 1000))",
                 name: ["en": name, "ro": name], description: ["en": instructions, "ro": instructions],
                 view: "any", tracking: "manual", requiredLandmarks: [], minConf: 0.5, signal: "",
                 direction: 1, repBand: RepBand(low: 0, high: 0), targetReps: reps, targetSets: sets,
                 formRules: [], stopConditions: [:], clinicianApproved: true, custom: true, videoUrl: videoUrl)
    }
}

private struct PlanFile: Codable { let calibrationS: Double?; let exercises: [Exercise] }

/// Built-in plan (shared/exercises.json bundled as a resource) plus custom
/// entries. Nothing here is self-prescribed.
enum Plan {
    static let decoder: JSONDecoder = { let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d }()
    static let encoder: JSONEncoder = { let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase; return e }()

    private static let file: PlanFile = {
        guard let url = Bundle.main.url(forResource: "exercises", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let plan = try? decoder.decode(PlanFile.self, from: data) else {
            return PlanFile(calibrationS: 1.5, exercises: [])
        }
        return plan
    }()

    static var builtIn: [Exercise] { file.exercises }
    static var calibrationSeconds: Double { file.calibrationS ?? 1.5 }
    static var all: [Exercise] { builtIn + SessionStore.shared.customExercises }
    static func byId(_ id: String) -> Exercise? { all.first { $0.id == id } }
}
