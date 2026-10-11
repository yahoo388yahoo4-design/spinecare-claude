import SwiftUI
import AVFoundation
import Combine
import CoreImage
import UIKit

/// Coaching engine for one camera session. Runs on the camera queue: Vision
/// pose -> framing gate -> calibrated signal -> reps + form cues, always on the
/// phone. A configured, reachable server takes over the display (landmarks-only
/// by default) and on-device coaching continues instantly if it drops.
/// Every session also saves a sensor timeline for replay; optional voice
/// guidance speaks setup, cues and rep counts, and optional voice control
/// takes hands-free commands (published on `commands` for the view).
final class SessionController: ObservableObject {
    struct UIState {
        var landmarks: [String: Metrics.LM] = [:]
        var frame = CGSize(width: 9, height: 16)
        var ready = false
        var missing: Set<String> = []
        var reps = 0
        var cue = ""
        var metrics = ""
    }

    @Published var ui = UIState()
    @Published var running = false
    @Published var paused = false
    @Published var voiceListening = false
    @Published var voiceHeard: String?
    let commands = PassthroughSubject<VoiceCommands.Cmd, Never>()
    let coach = VoiceCoach(guidance: ServerConfig.shared.audioCues)
    private var voice: VoiceControl?

    let exercise: Exercise
    private let pose = PoseTracker()
    private let client = ServerClient()
    private let ciContext = CIContext()
    private let haptic = UINotificationFeedbackGenerator()
    private let queue = DispatchQueue(label: "spinecare.session")   // guards the state below

    private var tracker: ExerciseTracker?
    private var recorder: SessionRecorder?
    private var videoURL: URL?
    private var serverPose: [String: Any]?
    private var useServer = false
    private var serverUsed = false
    private var lastAnalysis: TimeInterval = 0
    private var lastSend: TimeInterval = 0
    private var startedAt = Date()
    private var frames = 0
    private var sums: [String: (Double, Int)] = [:]
    private var cueCounts: [String: Int] = [:]
    private var lastCue = ""
    private var timeline: SessionTimeline.Writer?
    private var lastReadyAnnounce: TimeInterval = 0
    private var wasCalibrating = true
    private var lastRepsAnnounced = 0
    private var cfg = ServerConfig.shared

    init(exercise: Exercise) {
        self.exercise = exercise
        coach.onSpeaking = { [weak self] speaking in self?.voice?.hold(speaking) }
    }

    // MARK: voice (main thread)

    func startVoice() {
        if voice == nil {
            let v = VoiceControl(lang: L.lang)
            v.onState = { [weak self] listening, heard in
                self?.voiceListening = listening
                if let heard, !heard.isEmpty { self?.voiceHeard = heard }
            }
            v.onCommand = { [weak self] cmd, heard in self?.voiceHeard = heard; self?.commands.send(cmd) }
            voice = v
        }
        voice?.start()
    }

    func stopVoice() { voice?.stop(); voiceListening = false }

    var voiceAvailable: Bool { voice?.available ?? VoiceControl(lang: L.lang).available }

    func setPaused(_ p: Bool) {
        guard running, paused != p else { return }
        queue.sync { paused = p }
        let msg = L.t(p ? "vg_paused" : "vg_resumed")
        lastCue = msg; ui.cue = msg
        coach.speak(msg, flush: true)
    }

    var currentReps: Int {
        queue.sync {
            let fresh = useServer && client.connected && Date().timeIntervalSince1970 - client.lastPoseAt < 2
            return fresh ? (serverPose?["reps"] as? Int ?? 0) : (tracker?.reps.reps ?? 0)
        }
    }

    // MARK: control (main thread)

    func start(record: Bool) {
        guard !running else { return }
        let lang = L.lang
        coach.guidance = cfg.audioCues
        queue.sync {
            tracker = ExerciseTracker(exercise, calibration: Plan.calibrationSeconds, lang: lang)
            frames = 0; sums = [:]; cueCounts = [:]; serverPose = nil; serverUsed = false; startedAt = Date()
            paused = false; wasCalibrating = true; lastRepsAnnounced = 0
            let startedMs = Int(startedAt.timeIntervalSince1970 * 1000)
            let base = "session_\(exercise.id)_\(startedMs)"
            let metric = exercise.signal.hasPrefix("abs:") ? String(exercise.signal.dropFirst(4)) : exercise.signal
            timeline = SessionTimeline.Writer(url: SessionStore.shared.newTimelineURL(base),
                                              header: SessionTimeline.header(exercise: exercise.id, metric: metric,
                                                                             target: exercise.targetReps, started: startedMs))
            videoURL = record ? SessionStore.shared.recordingsDir.appendingPathComponent("\(base).mp4") : nil
            recorder = nil
            useServer = cfg.serverUsable
        }
        if useServer {
            client.connect(cfg.baseURL, auth: cfg.authHeader) { [weak self] msg in
                guard let self, msg["type"] as? String == "pose" else { return }
                self.queue.async { self.serverPose = msg }
            }
            client.startSession(exercise.id, lang: lang)
        } else if cfg.mode == .server {
            let why = ServerConfig.urlProblem(cfg.baseURL, allowInsecureLan: cfg.allowInsecureLan) ?? "server_offline"
            setCue(L.t(why) + " — " + L.t("using_device"))
        }
        running = true
        setCue(L.f("calibrate_hint", exercise.stops(lang).joined(separator: ", ")))
    }

    func recalibrate() {
        queue.async { self.tracker?.recalibrate() }
        client.recalibrate(); BlePostureClient.shared.calibrateNeutral()
        setCue(L.t("calibrating"))
    }

    /// Stops and saves; `done` gets the saved rep count.
    func stop(_ done: @escaping (Int) -> Void) {
        guard running else { return }
        running = false
        paused = false
        queue.async {
            let poseFresh = self.useServer && self.client.connected && Date().timeIntervalSince1970 - self.client.lastPoseAt < 2
            let reps = poseFresh ? (self.serverPose?["reps"] as? Int ?? 0) : (self.tracker?.reps.reps ?? 0)
            self.client.endSession(); self.client.disconnect()
            let avg = self.sums.mapValues { $0.0 / Double(max(1, $0.1)) }
            let duration = Int(Date().timeIntervalSince(self.startedAt))
            let source = self.serverUsed ? "server+on-device" : "on-device"
            let frames = self.frames, cues = self.cueCounts
            let tl = self.timeline; self.timeline = nil
            let samples = tl?.close() ?? 0
            if samples == 0, let u = tl?.url { try? FileManager.default.removeItem(at: u) }
            let save: (URL?, Int) -> Void = { url, n in
                SessionStore.shared.saveSession(exerciseId: self.exercise.id, reps: reps, sets: 1, durationS: duration,
                                                metrics: avg, cues: cues, frames: frames, source: source,
                                                videoURL: url, recordedFrames: n,
                                                timelineURL: samples > 0 ? tl?.url : nil, timelineSamples: samples)
                DispatchQueue.main.async { self.coach.guide(L.f("vg_saved", reps), flush: true); done(reps) }
            }
            if let rec = self.recorder, let url = self.videoURL {
                self.recorder = nil
                rec.finish { n in
                    if n == 0 { try? FileManager.default.removeItem(at: url) }
                    save(n > 0 ? url : nil, n)
                }
            } else { save(nil, 0) }
        }
    }

    // MARK: frames (camera queue)

    func process(_ buf: CMSampleBuffer) {
        guard let pb = CMSampleBufferGetImageBuffer(buf) else { return }
        let now = Date().timeIntervalSince1970
        let interval = Double(cfg.frameIntervalMs) / 1000
        queue.sync {
            guard now - lastAnalysis >= interval else { return }
            lastAnalysis = now
            analyze(pb, buf, now)
        }
    }

    private func analyze(_ pb: CVPixelBuffer, _ buf: CMSampleBuffer, _ now: TimeInterval) {
        let r = pose.detect(pb)
        let px = r.pixels
        let w = Double(r.width), h = Double(r.height)
        let framing = Framing.check(px, required: exercise.requiredLandmarks, minConf: cfg.minConfidence, w: w, h: h)
        var state = UIState(landmarks: r.normalized, frame: CGSize(width: w, height: h),
                            ready: framing.ready, missing: Set(framing.missing.map { Framing.resolve(px, $0) }),
                            reps: tracker?.reps.reps ?? 0)
        guard running, !paused, let tracker else {
            if !running, framing.ready, now - lastReadyAnnounce > 15 {
                lastReadyAnnounce = now
                coach.guide(L.t("vg_framing_ok"))
            }
            publish(state); return
        }

        frames += 1
        let metrics = Metrics.compute(px)
        for (k, v) in metrics { let s = sums[k] ?? (0, 0); sums[k] = (s.0 + v, s.1 + 1) }
        let local = tracker.update(metrics, ready: framing.ready, t: now)

        let imu = BlePostureClient.shared.snapshot()
        // optional server enhancement; never blocks on-device coaching
        if useServer, client.connected, now - lastSend >= Double(cfg.serverFrameIntervalMs) / 1000 {
            lastSend = now
            if cfg.stream == .frames, let jpeg = jpegData(pb) { client.sendFrame(jpeg: jpeg, tMs: Int(now * 1000), imu: imu) }
            else if !r.normalized.isEmpty { client.sendLandmarks(r.normalized, w: r.width, h: r.height, tMs: Int(now * 1000), imu: imu) }
        }
        let sp: [String: Any]? = (useServer && client.connected && now - client.lastPoseAt < 1) ? serverPose : nil

        var cue = ""
        if let sp {
            serverUsed = true
            let f = sp["framing"] as? [String: Any]
            state.ready = f?["ready"] as? Bool ?? false
            state.missing = Set(((f?["missing"] as? [String]) ?? []).map { Framing.resolve(px, $0) })
            state.reps = sp["reps"] as? Int ?? 0
            let cues = sp["cues"] as? [[String: Any]] ?? []
            if !state.ready { cue = ((f?["issues"] as? [String]) ?? []).map(Framing.fixFor).joined(separator: " ") }
            else if sp["calibrating"] as? Bool == true { cue = L.t("calibrating") }
            else if let c = cues.first?["cue"] as? String { cue = c }
            if sp["rep_just_done"] as? Bool == true {
                cue = L.f("rep_done", state.reps, exercise.targetReps)
                serverPose?["rep_just_done"] = false
                repFeedback()
            }
        } else {
            state.reps = local.reps
            if !framing.ready { cue = Array(Set(framing.issues)).sorted().map(Framing.fixFor).joined(separator: " ") }
            else if local.calibrating { cue = L.t("calibrating") }
            else if let c = local.cues.first { cue = c.text }
            else if local.repJustDone { cue = L.f("rep_done", local.reps, exercise.targetReps) }
            if local.repJustDone { repFeedback() }
            for c in local.cues { cueCounts[c.metric, default: 0] += 1 }
        }
        // voice guidance: calibration finished, then every new rep
        let calibratingNow = sp.map { $0["calibrating"] as? Bool ?? false } ?? local.calibrating
        if wasCalibrating && !calibratingNow && state.ready { coach.guide(L.t("vg_calibrated"), flush: true) }
        wasCalibrating = calibratingNow
        if state.reps > lastRepsAnnounced {
            lastRepsAnnounced = state.reps
            coach.rep(state.reps, target: exercise.targetReps)
        }
        if !cue.isEmpty { setCue(cue) }
        let sig = local.signal.map { String(format: "%.2f", $0) } ?? "-"
        state.metrics = metrics.sorted { $0.key < $1.key }.map { "\($0.key)=\(String(format: "%.2f", $0.value))" }
            .joined(separator: "  ") + "\nsignal=\(sig) " + (sp != nil ? "server" : "on-device \(Int(r.ms))ms") + " frames=\(frames)"
        state.cue = lastCue

        var wroteVideo = false
        if let url = videoURL {
            if recorder == nil { recorder = SessionRecorder(url: url, width: r.width, height: r.height) }
            let before = recorder?.framesWritten ?? 0
            recorder?.append(pb, landmarks: r.normalized, ready: state.ready, cue: lastCue, metrics: state.metrics,
                             reps: "\(state.reps)/\(exercise.targetReps)", timestamp: CMSampleBufferGetPresentationTimeStamp(buf))
            wroteVideo = (recorder?.framesWritten ?? 0) > before
        }
        timeline?.add(TimelineSample(
            t: Int((now - startedAt.timeIntervalSince1970) * 1000), frameW: r.width, frameH: r.height, mirror: false,
            landmarks: r.normalized, metrics: metrics, signal: local.signal, reps: state.reps, cue: lastCue,
            ready: state.ready, video: wroteVideo,
            head: SessionTimeline.imuTriple(imu?["head"]), trunk: SessionTimeline.imuTriple(imu?["trunk"])))
        publish(state)
    }

    private func jpegData(_ pb: CVPixelBuffer) -> Data? {
        let ci = CIImage(cvPixelBuffer: pb)
        let scaled = ci.transformed(by: CGAffineTransform(scaleX: 0.5, y: 0.5))
        guard let cg = ciContext.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.7)
    }

    private func publish(_ s: UIState) { DispatchQueue.main.async { self.ui = s } }

    private func setCue(_ text: String) {
        guard text != lastCue else { return }
        lastCue = text
        coach.cue(text)
        DispatchQueue.main.async { self.ui.cue = text }
    }

    private func repFeedback() {
        guard cfg.haptics else { return }
        DispatchQueue.main.async { self.haptic.notificationOccurred(.success) }
    }
}

/// Real-time coaching screen with front/back switching, recording toggle,
/// recalibration and optional before/after symptom check-ins.
struct ExerciseSessionView: View {
    let exercise: Exercise
    @StateObject private var session: SessionController
    @ObservedObject private var cfg = ServerConfig.shared
    @State private var frontCamera = true
    @State private var askPhase: String?
    @State private var neck = 0.0
    @State private var arm = 0.0
    @State private var savedText = ""
    @Environment(\.scenePhase) private var scenePhase

    init(exercise: Exercise) {
        self.exercise = exercise
        _session = StateObject(wrappedValue: SessionController(exercise: exercise))
    }

    var body: some View {
        let lang = L.lang
        let guide = FramingText.forView(exercise.view)
        ZStack(alignment: .top) {
            CameraPreview(frontCamera: frontCamera) { session.process($0) }
                .ignoresSafeArea()
            Canvas { ctx, size in
                OverlayRenderer.draw(ctx: &ctx, size: size, frame: session.ui.frame, lms: session.ui.landmarks,
                                     ready: session.ui.ready, missing: session.ui.missing)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            VStack(spacing: 6) {
                Text("\(exercise.title(lang))\n\(guide)")
                    .font(.callout).padding(8).frame(maxWidth: .infinity, alignment: .leading)
                    .background(.black.opacity(0.6)).foregroundStyle(.white)
                Button(action: toggleVoice) {
                    Text(voiceLabel).font(.caption).multilineTextAlignment(.leading).padding(8)
                        .background(.black.opacity(0.7)).foregroundStyle(cfg.voiceControl ? .green : .gray)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 8)
                Spacer()
                if !session.ui.cue.isEmpty {
                    Text(session.ui.cue).font(.subheadline).padding(8).frame(maxWidth: .infinity)
                        .background(.black.opacity(0.7)).foregroundStyle(.yellow)
                }
                if !session.ui.metrics.isEmpty {
                    Text(session.ui.metrics).font(.system(.caption2, design: .monospaced)).padding(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.black.opacity(0.7)).foregroundStyle(.white)
                }
                if !savedText.isEmpty { Text(savedText).font(.caption).foregroundStyle(.green) }
                HStack {
                    Button(session.running ? L.t("stop_save") : L.t("start")) {
                        if session.running { stopSession() } else { ask("before") }
                    }.buttonStyle(.borderedProminent)
                    if session.running {
                        Button(session.paused ? L.t("resume") : L.t("pause")) { session.setPaused(!session.paused) }
                            .buttonStyle(.bordered)
                    }
                    Button(L.t("flip")) { frontCamera.toggle() }.buttonStyle(.bordered)
                    Button(L.t("recalibrate")) { session.recalibrate() }.buttonStyle(.bordered).disabled(!session.running)
                }
                HStack {
                    Toggle(isOn: $cfg.recordSessions) { Label("REC", systemImage: "record.circle") }
                        .disabled(session.running).fixedSize()
                    Picker("", selection: $cfg.mode) {
                        Text(L.t("mode_btn_auto")).tag(ServerConfig.Mode.auto)
                        Text(L.t("mode_btn_device")).tag(ServerConfig.Mode.onDevice)
                        Text(L.t("mode_btn_server")).tag(ServerConfig.Mode.server)
                    }.disabled(session.running)
                }.padding(.horizontal).background(.black.opacity(0.5)).foregroundStyle(.white)
            }
            .padding(.bottom)
            Text("\(session.ui.reps)/\(exercise.targetReps)")
                .font(.system(size: 44, weight: .bold)).foregroundStyle(.green).shadow(radius: 4)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(.top, 90).padding(.trailing)
        }
        .navigationTitle(exercise.title(lang))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            session.coach.guidance = cfg.audioCues
            session.coach.guide("\(exercise.title(lang)). \(guide.replacingOccurrences(of: "\n", with: ". ")). \(L.t("vg_setup"))")
            if cfg.voiceControl { startVoice(announce: true) }
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            session.stopVoice()
            session.coach.stop()
            if session.running { session.stop { _ in } }   // leaving mid-session keeps the data
            cfg.save()
        }
        .onChange(of: scenePhase) { phase in
            // never listen in the background
            if phase == .active { if cfg.voiceControl { startVoice(announce: false) } } else { session.stopVoice() }
        }
        .onReceive(session.commands) { handle($0) }
        .sheet(item: Binding(get: { askPhase.map { PhaseItem(id: $0) } }, set: { askPhase = $0?.id })) { item in
            SymptomSheet(phase: item.id, neck: $neck, arm: $arm) { save in finishCheckin(item.id, save: save) }
        }
    }

    // MARK: actions shared by buttons and voice commands

    private func ask(_ phase: String) {
        askPhase = phase
        if cfg.voiceControl { session.coach.speak(L.t("vg_checkin")) }
    }

    private func finishCheckin(_ phase: String, save: Bool) {
        if save { SessionStore.shared.saveCheckin(phase: phase, neckPain: Int(neck), armSymptoms: Int(arm), sleepQuality: nil, note: "") }
        if phase == "before" { cfg.save(); session.start(record: cfg.recordSessions) }
        askPhase = nil
    }

    private func stopSession() {
        session.stop { reps in savedText = L.f("session_saved", reps); ask("after") }
    }

    private var voiceLabel: String {
        guard cfg.voiceControl else { return "🎤 " + L.t("voice_off") }
        var s = "🎤 " + L.t(session.voiceListening ? "voice_listening" : "voice_on")
        if let h = session.voiceHeard { s += "\n" + L.f("voice_heard", h) }
        return s
    }

    private func toggleVoice() {
        cfg.voiceControl.toggle(); cfg.save()
        if cfg.voiceControl { startVoice(announce: true) } else { session.stopVoice() }
    }

    private func startVoice(announce: Bool) {
        VoiceControl.requestAccess { ok in
            guard ok else { savedText = L.t("mic_needed"); cfg.voiceControl = false; return }
            guard session.voiceAvailable else { savedText = L.t("voice_unavailable"); cfg.voiceControl = false; return }
            session.startVoice()
            if announce { session.coach.speak(L.t("vg_listening")) }
        }
    }

    private func handle(_ cmd: VoiceCommands.Cmd) {
        let coach = session.coach
        switch cmd {
        case .start:
            if askPhase != nil { break }
            if session.running { if session.paused { session.setPaused(false) } } else { ask("before") }
        case .skip:
            if let p = askPhase { finishCheckin(p, save: false) }
        case .stop:
            if session.running { stopSession() } else if let p = askPhase { finishCheckin(p, save: false) }
        case .pause: session.setPaused(true)
        case .resume: session.setPaused(false)
        case .recalibrate: if session.running { session.recalibrate() }
        case .flip:
            if session.running { coach.speak(L.t("vg_flip_running"), flush: true) } else { frontCamera.toggle() }
        case .status:
            coach.speak(session.running ? L.f("vg_status", session.currentReps, exercise.targetReps) : L.t("vg_not_started"), flush: true)
        case .again:
            coach.speak(coach.lastSpoken.isEmpty ? session.ui.cue : coach.lastSpoken, flush: true)
        case .help:
            coach.speak(L.t("vg_help"), flush: true)
        }
    }
}

struct PhaseItem: Identifiable { let id: String }

/// Optional 0-10 symptom check-in before/after a session. Skipping never blocks.
struct SymptomSheet: View {
    let phase: String
    @Binding var neck: Double
    @Binding var arm: Double
    let done: (Bool) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Text(L.t("symptom_question")).font(.callout)
                VStack(alignment: .leading) {
                    Text("\(L.t("neck_pain")): \(Int(neck))"); Slider(value: $neck, in: 0...10, step: 1)
                }
                VStack(alignment: .leading) {
                    Text("\(L.t("arm_symptoms")): \(Int(arm))"); Slider(value: $arm, in: 0...10, step: 1)
                }
                Text(L.t("pain_warning")).font(.caption).foregroundStyle(.red)
                Button(L.t("save_checkin")) { done(true) }
                Button(L.t("skip")) { done(false) }
            }
            .navigationTitle(L.t("phase_" + phase))
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }
}

enum FramingText {
    static func forView(_ v: String) -> String {
        switch v {
        case "side": return [L.t("guide_side_title"), L.t("guide_side_3")].joined(separator: "\n")
        case "front": return [L.t("guide_front_title"), L.t("guide_front_1")].joined(separator: "\n")
        case "back": return [L.t("guide_back_title"), L.t("guide_back_1")].joined(separator: "\n")
        default: return L.t("guide_any_title")
        }
    }
}

/// Camera preview + capture. Frames are delivered upright (portrait) and
/// mirrored for the front camera, so pose points match the preview directly.
struct CameraPreview: UIViewRepresentable {
    let frontCamera: Bool
    let onFrame: (CMSampleBuffer) -> Void

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.videoGravity = .resizeAspectFill
        v.previewLayer.session = context.coordinator.session
        context.coordinator.start(front: frontCamera)
        return v
    }

    func updateUIView(_ v: PreviewView, context: Context) {
        // Only reconfigure when the camera actually changes (SwiftUI calls this on every state update).
        if context.coordinator.front != frontCamera { context.coordinator.start(front: frontCamera) }
    }

    static func dismantleUIView(_ v: PreviewView, coordinator: Coordinator) { coordinator.stop() }

    func makeCoordinator() -> Coordinator { Coordinator(onFrame: onFrame) }

    final class Coordinator: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
        let session = AVCaptureSession()
        let onFrame: (CMSampleBuffer) -> Void
        private(set) var front: Bool?
        private let output = AVCaptureVideoDataOutput()
        private let queue = DispatchQueue(label: "spinecare.camera")
        private var input: AVCaptureDeviceInput?

        init(onFrame: @escaping (CMSampleBuffer) -> Void) { self.onFrame = onFrame }

        func start(front: Bool) {
            self.front = front
            requestAccess { [weak self] ok in
                guard ok, let self else { return }
                self.queue.async { self.configure(front: front) }
            }
        }

        private func requestAccess(_ done: @escaping (Bool) -> Void) {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: done(true)
            case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { done($0) }
            default: done(false)
            }
        }

        private func configure(front: Bool) {
            let position: AVCaptureDevice.Position = front ? .front : .back
            guard let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
                    ?? AVCaptureDevice.default(for: .video),
                  let newInput = try? AVCaptureDeviceInput(device: cam) else { return }
            session.beginConfiguration()
            session.sessionPreset = .hd1280x720
            if let old = input { session.removeInput(old) }
            if session.canAddInput(newInput) { session.addInput(newInput); input = newInput }
            if session.outputs.isEmpty {
                output.alwaysDiscardsLateVideoFrames = true
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                output.setSampleBufferDelegate(self, queue: queue)
                if session.canAddOutput(output) { session.addOutput(output) }
            }
            if let c = output.connection(with: .video) {
                if #available(iOS 17.0, *) {
                    if c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
                } else if c.isVideoOrientationSupported {
                    c.videoOrientation = .portrait
                }
                if c.isVideoMirroringSupported { c.automaticallyAdjustsVideoMirroring = false; c.isVideoMirrored = front }
            }
            session.commitConfiguration()
            if !session.isRunning { session.startRunning() }
        }

        func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() } } }

        func captureOutput(_ out: AVCaptureOutput, didOutput buf: CMSampleBuffer, from conn: AVCaptureConnection) {
            onFrame(buf)
        }
    }
}
