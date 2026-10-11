import Foundation

/// Hands-free command vocabulary (shared/voice_commands.json, also used by Android).
/// Speech is matched as whole words after lower-casing and removing diacritics,
/// in English and Romanian regardless of the app language. When several
/// commands match, the earlier one in `priority` wins (stop and pause first),
/// then the longest phrase.
final class VoiceCommands {
    enum Cmd: String { case stop, pause, resume, skip, recalibrate, flip, status, again = "repeat", help, start }

    private struct Phrase { let cmd: Cmd; let text: String; let rank: Int }
    private let phrases: [Phrase]

    static let shared: VoiceCommands = {
        let data = Bundle.main.url(forResource: "voice_commands", withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        return VoiceCommands(json: data ?? Data("{}".utf8))
    }()

    init(json: Data) {
        var out: [Phrase] = []
        if let o = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
           let prio = o["priority"] as? [String], let cmds = o["commands"] as? [String: [String: [String]]] {
            for (i, key) in prio.enumerated() {
                guard let cmd = Cmd(rawValue: key), let langs = cmds[key] else { continue }
                for list in langs.values {
                    for p in list { let n = Self.normalize(p); if !n.isEmpty { out.append(Phrase(cmd: cmd, text: n, rank: i)) } }
                }
            }
        }
        phrases = out
    }

    static func normalize(_ s: String) -> String {
        let folded = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
            .lowercased().replacingOccurrences(of: "’", with: "'")
        let mapped = folded.unicodeScalars.map { c -> Character in
            (("a"..."z").contains(c) || ("0"..."9").contains(c) || c == "'") ? Character(c) : " "
        }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }

    func parse(_ heard: String) -> Cmd? {
        let text = " " + Self.normalize(heard) + " "
        guard text.trimmingCharacters(in: .whitespaces).count > 0 else { return nil }
        return phrases.filter { text.contains(" \($0.text) ") }
            .sorted { $0.rank != $1.rank ? $0.rank < $1.rank : $0.text.count > $1.text.count }
            .first?.cmd
    }
}
