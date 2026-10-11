import AVFoundation
import Speech

/// Hands-free commands while an exercise screen is open. Streams the
/// microphone into SFSpeechRecognizer (on-device when supported), matches each
/// result against VoiceCommands, and restarts the recognition after every
/// command, error or ~50 s (Apple's per-request limit). `hold(true)` pauses it
/// while the app speaks. No audio is stored. Main thread only.
final class VoiceControl {
    var onCommand: ((VoiceCommands.Cmd, String) -> Void)?
    var onState: ((Bool, String?) -> Void)?

    private let commands: VoiceCommands
    private let recognizer: SFSpeechRecognizer?
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var active = false
    private var held = false
    private var generation = 0
    private var errors = 0
    private var restartWork: DispatchWorkItem?

    init(lang: String, commands: VoiceCommands = .shared) {
        self.commands = commands
        recognizer = SFSpeechRecognizer(locale: Locale(identifier: lang == "ro" ? "ro-RO" : "en-US"))
            ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    }

    var available: Bool { recognizer?.isAvailable ?? false }

    /// Asks for speech recognition and microphone access.
    static func requestAccess(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else { DispatchQueue.main.async { done(false) }; return }
            AVAudioSession.sharedInstance().requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
        }
    }

    func start() {
        guard !active else { return }
        active = true; errors = 0
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .duckOthers, .allowBluetooth])
        try? s.setActive(true, options: [])
        begin()
    }

    func stop() {
        active = false
        restartWork?.cancel()
        end()
        onState?(false, nil)
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    func hold(_ on: Bool) {
        guard held != on else { return }
        held = on
        guard active else { return }
        if on { restartWork?.cancel(); end(); onState?(false, nil) } else { restart(after: 0.3) }
    }

    private func restart(after delay: TimeInterval) {
        restartWork?.cancel()
        guard active, !held else { return }
        let w = DispatchWorkItem { [weak self] in self?.end(); self?.begin() }
        restartWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: w)
    }

    private func end() {
        generation += 1
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio(); request = nil
        task?.cancel(); task = nil
    }

    private func begin() {
        guard active, !held, let recognizer, recognizer.isAvailable else { return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buf, _ in req.append(buf) }
        engine.prepare()
        do { try engine.start() } catch { input.removeTap(onBus: 0); fail(); return }
        request = req
        generation += 1
        let gen = generation
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async { self?.handle(result, error, gen) }
        }
        onState?(true, nil)
        restart(after: 50)
    }

    private func handle(_ result: SFSpeechRecognitionResult?, _ error: Error?, _ gen: Int) {
        guard gen == generation, active else { return }
        if let result {
            errors = 0
            let texts = [result.bestTranscription.formattedString] + result.transcriptions.dropFirst().prefix(3).map { $0.formattedString }
            for text in texts {
                if let cmd = commands.parse(text) {
                    onState?(false, text)
                    onCommand?(cmd, text)
                    restart(after: 0.2)   // fresh request so the same words don't fire again
                    return
                }
            }
            if result.isFinal { onState?(false, result.bestTranscription.formattedString); restart(after: 0.2) }
        } else if error != nil {
            fail()
        }
    }

    private func fail() {
        errors += 1
        onState?(false, nil)
        restart(after: min(8, 0.6 * Double(errors)))
    }
}
