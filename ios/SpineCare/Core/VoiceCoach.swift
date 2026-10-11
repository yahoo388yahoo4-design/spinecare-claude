import AVFoundation

/// Spoken guidance for exercise sessions: setup and calibration prompts, form
/// cues (throttled so they never talk over each other), rep counts, milestones
/// and answers to voice commands. `guidance` = speak automatic coaching; when
/// false only explicit replies are spoken. `onSpeaking` lets voice control stop
/// listening while the phone talks. Main thread only.
final class VoiceCoach: NSObject, AVSpeechSynthesizerDelegate {
    var guidance: Bool
    var onSpeaking: ((Bool) -> Void)?
    private(set) var lastSpoken = ""
    private(set) var speaking = false
    private let synth = AVSpeechSynthesizer()
    private var lastCueAt = Date.distantPast

    init(guidance: Bool) {
        self.guidance = guidance
        super.init()
        synth.delegate = self
    }

    private func onMain(_ f: @escaping () -> Void) { Thread.isMainThread ? f() : DispatchQueue.main.async(execute: f) }

    /// Always spoken (flush interrupts what is playing).
    func speak(_ text: String, flush: Bool = false) {
        guard !text.isEmpty else { return }
        onMain {
            self.lastSpoken = text
            if flush { self.synth.stopSpeaking(at: .immediate) }
            let u = AVSpeechUtterance(string: text)
            u.voice = AVSpeechSynthesisVoice(language: L.lang == "ro" ? "ro-RO" : "en-US")
            self.synth.speak(u)
        }
    }

    func guide(_ text: String, flush: Bool = false) { if guidance { speak(text, flush: flush) } }

    /// Form/framing cue: dropped if another cue was spoken in the last 2.5 s.
    func cue(_ text: String) {
        guard guidance, !text.isEmpty else { return }
        onMain {
            let now = Date()
            if now.timeIntervalSince(self.lastCueAt) < 2.5 || (self.speaking && now.timeIntervalSince(self.lastCueAt) < 4) { return }
            self.lastCueAt = now
            self.speak(text, flush: true)
        }
    }

    /// Rep count with milestone phrases at halfway and at the target.
    func rep(_ reps: Int, target: Int) {
        guard guidance else { return }
        let text: String
        if target > 0 && reps == target { text = L.f("vg_target", target) }
        else if target >= 6 && reps == target / 2 { text = "\(reps). " + L.t("vg_halfway") }
        else { text = "\(reps)" }
        onMain { self.lastCueAt = Date(); self.speak(text, flush: true) }
    }

    func stop() { onMain { self.synth.stopSpeaking(at: .immediate) } }

    private func setSpeaking(_ v: Bool) {
        onMain { if self.speaking != v { self.speaking = v; self.onSpeaking?(v) } }
    }

    func speechSynthesizer(_ s: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) { setSpeaking(true) }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        onMain { if !self.synth.isSpeaking { self.setSpeaking(false) } }
    }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        onMain { if !self.synth.isSpeaking { self.setSpeaking(false) } }
    }
}
