import SwiftUI
import AVFoundation
import AVKit
import CoreMotion
import PhotosUI
import CoreTransferable
import UniformTypeIdentifiers

/// Video guide for one camera-tracked exercise (mirrors Android GuideActivity):
/// camera placement diagram, a demonstration processed by the real tracker
/// (with a "common mistake" variant), a live setup check, and video analysis.
struct GuideView: View {
    let exercise: Exercise
    @State private var mistake = false
    @State private var playing = true
    @State private var overlay = true
    @State private var startedAt = Date()
    @State private var pausedAt: TimeInterval = 0
    @State private var pickedItem: PhotosPickerItem?
    @State private var pickedURL: URL?
    @State private var loadingVideo = false

    var body: some View {
        let lang = L.lang
        let demo = GuideDemo(exercise, calibration: Plan.calibrationSeconds, mistake: mistake)
        List {
            Section {
                Text(L.t("gd_approval_note")).font(.caption).foregroundStyle(.secondary)
            }
            Section(L.t("gd_step1")) {
                TimelineView(.animation) { tl in
                    Canvas { ctx, size in
                        GuideDrawing.placement(&ctx, size: size, view: exercise.view, program: exercise.programId,
                                               t: tl.date.timeIntervalSince(startedAt))
                    }
                }
                .aspectRatio(1 / 0.62, contentMode: .fit)
                .listRowInsets(EdgeInsets())
                Text(FramingText.forView(exercise.view, program: exercise.programId)).font(.callout)
                Text(L.t("guide_light")).font(.caption).foregroundStyle(.secondary)
            }
            Section(L.t("gd_step2")) {
                Text(exercise.details(lang)).font(.callout)
                DemoCanvas(demo: demo, playing: playing, overlay: overlay, startedAt: startedAt, pausedAt: pausedAt)
                    .aspectRatio(GuideDemo.w / GuideDemo.h, contentMode: .fit)
                    .listRowInsets(EdgeInsets())
                    .id(mistake)
                HStack {
                    Button(playing ? L.t("gd_pause") : L.t("gd_play")) {
                        if playing { pausedAt = Date().timeIntervalSince(startedAt) }
                        else { startedAt = Date().addingTimeInterval(-pausedAt) }
                        playing.toggle()
                    }
                    Spacer()
                    Button(L.t("gd_replay")) { startedAt = Date(); pausedAt = 0; playing = true }
                    Spacer()
                    Button(overlay ? L.t("gd_overlay_on") : L.t("gd_overlay_off")) { overlay.toggle() }
                }
                .buttonStyle(.borderless).font(.callout)
                if !exercise.formRules.isEmpty {
                    Picker("", selection: Binding(get: { mistake }, set: { m in
                        mistake = m; startedAt = Date(); pausedAt = 0; playing = true
                    })) {
                        Text(L.t("gd_correct")).tag(false)
                        Text(L.t("gd_mistake")).tag(true)
                    }
                    .pickerStyle(.segmented)
                }
                if let rule = demo.mistakeRule {
                    Text(L.f("gd_mistake_note", rule.cue[lang] ?? rule.cue["en"] ?? "")).font(.callout).foregroundStyle(.orange)
                }
                if !demo.countable { Text(L.t("gd_not_countable")).font(.caption).foregroundStyle(.red) }
                Text(L.f("gd_tempo", secs(demo.inS), secs(demo.holdS), secs(demo.outS))).font(.caption)
                let metric = exercise.signal.hasPrefix("abs:") ? String(exercise.signal.dropFirst(4)) : exercise.signal
                Text(L.f("gd_measures", L.en["gd_sig_" + metric] != nil ? L.t("gd_sig_" + metric) : metric)).font(.caption)
                Text(L.t("gd_demo_body")).font(.caption).foregroundStyle(.secondary)
                if let v = exercise.videoUrl, !v.isEmpty, let url = URL(string: v) { Link(L.t("open_video"), destination: url) }
                if !exercise.stops(lang).isEmpty {
                    Text(L.t("stop_if") + " " + exercise.stops(lang).joined(separator: ", ")).font(.caption).foregroundStyle(.orange)
                }
            }
            Section(L.t("gd_step3")) {
                Text(L.t("gd_setup_body")).font(.caption)
                NavigationLink(L.t("gd_setup_open")) { SetupCheckView(exercise: exercise) }
            }
            Section(L.t("gd_step4")) {
                Text(L.t("gd_video_body")).font(.caption)
                PhotosPicker(selection: $pickedItem, matching: .videos) { Text(L.t("gd_video_pick")) }
                if loadingVideo { ProgressView() }
                if let url = pickedURL {
                    NavigationLink(L.t("va_title")) { VideoAnalysisView(exercise: exercise, url: url) }
                }
            }
        }
        .navigationTitle(L.t("gd_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: pickedItem) { item in
            guard let item else { return }
            loadingVideo = true; pickedURL = nil
            Task {
                let movie = try? await item.loadTransferable(type: PickedMovie.self)
                await MainActor.run { pickedURL = movie?.url; loadingVideo = false }
            }
        }
    }

    private func secs(_ v: Double) -> String { v == v.rounded() ? String(Int(v)) : String(format: "%.1f", v) }
}

/// A picked video copied to a temporary file the analysis can read.
struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("guide_\(UUID().uuidString).\(received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension)")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }
}

/// Animated demonstration with the tracker's live numbers.
struct DemoCanvas: View {
    let demo: GuideDemo
    let playing: Bool
    let overlay: Bool
    let startedAt: Date
    let pausedAt: TimeInterval
    @State private var player: DemoPlayer?

    var body: some View {
        TimelineView(.animation(paused: !playing)) { tl in
            Canvas { ctx, size in
                let raw = playing ? tl.date.timeIntervalSince(startedAt) : pausedAt
                // loop with a short pause on the last frame
                let t = min(raw.truncatingRemainder(dividingBy: demo.duration + 1.5), demo.duration)
                let f = demo.frame(at: t)
                let hud = (player ?? DemoPlayer(demo)).hud(at: t)
                GuideDrawing.demo(&ctx, size: size, demo: demo, frame: f, hud: hud, overlay: overlay)
            }
        }
        .onAppear { player = DemoPlayer(demo) }
    }
}

/// SwiftUI Canvas drawing for the guide (mirrors Android GuideRenderer).
enum GuideDrawing {
    private static let body = Color(red: 0.39, green: 0.45, blue: 0.55)
    private static let bodyFar = Color(red: 0.28, green: 0.33, blue: 0.41)
    private static let furniture = Color(red: 0.2, green: 0.25, blue: 0.33)

    static func demo(_ ctx: inout GraphicsContext, size: CGSize, demo: GuideDemo, frame f: GuideDemo.Frame,
                     hud: DemoPlayer.Hud, overlay: Bool) {
        let frame = CGSize(width: GuideDemo.w, height: GuideDemo.h)
        let map = OverlayRenderer.mapper(size: size, frame: frame)
        let s = max(size.width / frame.width, size.height / frame.height)
        func pt(_ x: Double, _ y: Double) -> CGPoint { map(Metrics.LM(x: x / GuideDemo.w, y: y / GuideDemo.h, conf: 1)) }
        let p = f.landmarks
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(red: 0.04, green: 0.07, blue: 0.13)))
        let floorY = pt(0, 860).y
        ctx.fill(Path(CGRect(x: 0, y: floorY, width: size.width, height: size.height - floorY)), with: .color(Color(red: 0.07, green: 0.09, blue: 0.15)))

        let side = demo.exercise.view == "side"
        func line(_ a: CGPoint, _ b: CGPoint, _ c: Color, _ w: CGFloat) {
            var path = Path(); path.move(to: a); path.addLine(to: b)
            ctx.stroke(path, with: .color(c), style: StrokeStyle(lineWidth: w * s, lineCap: .round, lineJoin: .round))
        }
        func limb(_ c: Color, _ w: CGFloat, _ keys: [String]) {
            let pts = keys.compactMap { p[$0] }.map { pt($0.x, $0.y) }
            guard pts.count >= 2 else { return }
            var path = Path(); path.move(to: pts[0]); for q in pts.dropFirst() { path.addLine(to: q) }
            ctx.stroke(path, with: .color(c), style: StrokeStyle(lineWidth: w * s, lineCap: .round, lineJoin: .round))
        }
        // seat
        if side, let hip = p["lhip"] {
            let a = pt(hip.x - 80, hip.y + 22), b = pt(hip.x + 120, hip.y + 44)
            ctx.fill(Path(roundedRect: CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y), cornerRadius: 6), with: .color(furniture))
            line(pt(hip.x - 60, hip.y + 44), pt(hip.x - 60, 860), furniture, 14)
            line(pt(hip.x + 100, hip.y + 44), pt(hip.x + 100, 860), furniture, 14)
        } else {
            let a = pt(230, 668), b = pt(490, 700)
            ctx.fill(Path(roundedRect: CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y), cornerRadius: 6), with: .color(furniture))
            line(pt(250, 700), pt(250, 860), furniture, 14); line(pt(470, 700), pt(470, 860), furniture, 14)
        }
        if side {
            limb(bodyFar, 44, ["rhip", "rknee", "rank"]); limb(bodyFar, 28, ["rsho", "relb", "rwri"])
            limb(body, 46, ["lhip", "lknee", "lank"]); limb(body, 92, ["lsho", "lhip"])
        } else {
            limb(body, 46, ["lhip", "lknee", "lank"]); limb(body, 46, ["rhip", "rknee", "rank"])
            let q = ["lsho", "rsho", "rhip", "lhip"].compactMap { p[$0] }.map { pt($0.x, $0.y) }
            if q.count == 4 {
                var torso = Path(); torso.addLines(q); torso.closeSubpath()
                ctx.fill(torso, with: .color(body))
                ctx.stroke(torso, with: .color(body), style: StrokeStyle(lineWidth: 36 * s, lineJoin: .round))
            }
        }
        // neck + head
        let lear = p["lear"], rear = p["rear"], nose = p["nose"]
        var head = (360.0, 300.0)
        if side, let e = lear, let n = nose { head = (e.x + (n.x - e.x) * 0.3, e.y - 8) }
        else if let l = lear, let r = rear { head = ((l.x + r.x) / 2, (l.y + r.y) / 2 - 6) }
        var neckBase: (Double, Double)?
        if side, let a = p["lsho"] { neckBase = (a.x, a.y) }
        else if let a = p["lsho"], let b = p["rsho"] { neckBase = ((a.x + b.x) / 2, (a.y + b.y) / 2) }
        if let nb = neckBase { line(pt(nb.0, nb.1 - 10), pt(head.0, head.1 + 30), body, 40) }
        let back = demo.exercise.view == "back"
        let hc = pt(head.0, head.1), hr = 54 * s
        ctx.fill(Path(ellipseIn: CGRect(x: hc.x - hr, y: hc.y - hr, width: 2 * hr, height: 2 * hr)),
                 with: .color(back ? Color(red: 0.12, green: 0.16, blue: 0.22) : body))
        func dot(_ c: CGPoint, _ r: CGFloat) {
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * s, y: c.y - r * s, width: 2 * r * s, height: 2 * r * s)), with: .color(Color(white: 0.8)))
        }
        if !back, let n = nose {
            if side { dot(pt(n.x, n.y), 9) } else {
                let ex = n.x - head.0
                dot(pt(head.0 + ex - 18, head.1 - 4), 6); dot(pt(head.0 + ex + 18, head.1 - 4), 6); dot(pt(n.x, n.y + 6), 5)
            }
        }
        limb(body, 30, ["lsho", "lelb", "lwri"])
        if !side { limb(body, 30, ["rsho", "relb", "rwri"]) }

        if overlay {
            let norm = p.mapValues { Metrics.LM(x: $0.x / GuideDemo.w, y: $0.y / GuideDemo.h, conf: $0.conf) }
            OverlayRenderer.draw(ctx: &ctx, size: size, frame: frame, lms: norm, ready: true, missing: [])
            // signal meter with the rep band
            let top = size.height * 0.16, bottom = size.height * 0.62, left = size.width - 34, right = size.width - 16
            let maxV = max(demo.exercise.repBand.high * 1.6, 1e-6)
            func yv(_ v: Double) -> CGFloat { bottom - CGFloat(min(1, max(0, v / maxV))) * (bottom - top) }
            ctx.fill(Path(roundedRect: CGRect(x: left - 4, y: top - 4, width: right - left + 8, height: bottom - top + 8), cornerRadius: 6),
                     with: .color(.black.opacity(0.6)))
            let v = hud.signal ?? 0
            let c: Color = v >= demo.exercise.repBand.high ? .green : v > demo.exercise.repBand.low ? .yellow : .gray
            ctx.fill(Path(CGRect(x: left, y: yv(v), width: right - left, height: bottom - yv(v))), with: .color(c))
            line(CGPoint(x: left - 8, y: yv(demo.exercise.repBand.high)), CGPoint(x: right + 8, y: yv(demo.exercise.repBand.high)), .green, 3 / s)
            line(CGPoint(x: left - 8, y: yv(demo.exercise.repBand.low)), CGPoint(x: right + 8, y: yv(demo.exercise.repBand.low)), .yellow, 3 / s)
            ctx.draw(Text("\(hud.reps)/\(demo.reps)").font(.system(size: 30, weight: .bold)).foregroundColor(.green),
                     at: CGPoint(x: size.width - 60, y: 30), anchor: .topTrailing)
            let sig = hud.signal.map { String(format: "%.3f", $0) } ?? "-"
            var lines = [String]()
            if !hud.cue.isEmpty { lines.append(hud.cue) }
            lines.append("signal=\(sig)  band \(fmt(demo.exercise.repBand.low))–\(fmt(demo.exercise.repBand.high))")
            let text = Text(lines.joined(separator: "\n")).font(.system(size: 12, design: .monospaced)).foregroundColor(.white)
            ctx.fill(Path(CGRect(x: 0, y: size.height - 44, width: size.width, height: 44)), with: .color(.black.opacity(0.7)))
            ctx.draw(text, in: CGRect(x: 8, y: size.height - 42, width: size.width - 16, height: 40))
        }
        // phase banner
        let banner = (demo.mistake ? L.t("gd_mistake") + " · " : "") + GuideDemo.phaseText(f)
        let bannerText = Text(banner).font(.system(size: 15, weight: .bold)).foregroundColor(demo.mistake ? .white : .green)
        let resolved = ctx.resolve(bannerText)
        let bs = resolved.measure(in: CGSize(width: size.width * 0.7, height: 60))
        ctx.fill(Path(roundedRect: CGRect(x: 10, y: 10, width: bs.width + 20, height: bs.height + 12), cornerRadius: 10),
                 with: .color(demo.mistake ? Color(red: 0.5, green: 0.11, blue: 0.11).opacity(0.9) : .black.opacity(0.75)))
        ctx.draw(resolved, in: CGRect(x: 20, y: 16, width: bs.width, height: bs.height))
        if f.rep > 0 {
            ctx.draw(Text(L.f("gd_rep", f.rep, demo.reps)).font(.caption).foregroundColor(.gray),
                     at: CGPoint(x: 20, y: bs.height + 30), anchor: .topLeading)
        }
    }

    private static func fmt(_ v: Double) -> String { v < 1 ? String(format: "%.3f", v) : String(format: "%.0f", v) }

    /// Top-down plan (distance, which way to face) and side elevation (phone upright, lens height).
    static func placement(_ ctx: inout GraphicsContext, size: CGSize, view: String, program: String, t: TimeInterval) {
        let w = size.width, h = size.height
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(red: 0.07, green: 0.1, blue: 0.17)))
        let k = min(1, max(0, t.truncatingRemainder(dividingBy: 6) / 1.8))
        let e = k * k * (3 - 2 * k)
        let unit = min(w / 2, h) / 10
        func stroke(_ a: CGPoint, _ b: CGPoint, _ c: Color, _ lw: CGFloat, dash: [CGFloat] = []) {
            var p = Path(); p.move(to: a); p.addLine(to: b)
            ctx.stroke(p, with: .color(c), style: StrokeStyle(lineWidth: lw, lineCap: .round, dash: dash))
        }
        // top-down
        let pcx = w * 0.25, phoneY = h * 0.86, personY = h * 0.30
        var cone = Path()
        cone.move(to: CGPoint(x: pcx, y: phoneY))
        cone.addLine(to: CGPoint(x: pcx - unit * 3.2, y: personY - unit * 1.6))
        cone.addLine(to: CGPoint(x: pcx + unit * 3.2, y: personY - unit * 1.6)); cone.closeSubpath()
        ctx.fill(cone, with: .color(Color.cyan.opacity(0.15 + 0.1 * (0.5 + 0.5 * sin(t / 0.6 * .pi)))))
        ctx.fill(Path(roundedRect: CGRect(x: pcx - unit * 0.6, y: phoneY - unit * 0.18, width: unit * 1.2, height: unit * 0.36), cornerRadius: 4),
                 with: .color(.white))
        let want: Double = view == "side" ? 90 : view == "back" ? 180 : 0
        let start: Double = view == "side" || view == "back" ? 0 : 90
        let ang = (start + (want - start) * e) * .pi / 180
        let fx = CGFloat(sin(ang)), fy = CGFloat(cos(ang))
        stroke(CGPoint(x: pcx - fy * unit * 1.1, y: personY + fx * unit * 1.1), CGPoint(x: pcx + fy * unit * 1.1, y: personY - fx * unit * 1.1),
               body, unit * 0.7)
        let r = unit * 0.62
        ctx.fill(Path(ellipseIn: CGRect(x: pcx - r, y: personY - r, width: 2 * r, height: 2 * r)), with: .color(.gray))
        var nose = Path()
        nose.move(to: CGPoint(x: pcx + fx * unit * 1.05, y: personY + fy * unit * 1.05))
        nose.addLine(to: CGPoint(x: pcx + fx * unit * 0.5 - fy * unit * 0.3, y: personY + fy * unit * 0.5 + fx * unit * 0.3))
        nose.addLine(to: CGPoint(x: pcx + fx * unit * 0.5 + fy * unit * 0.3, y: personY + fy * unit * 0.5 - fx * unit * 0.3))
        nose.closeSubpath()
        ctx.fill(nose, with: .color(.yellow))
        let ax = pcx + unit * 3.4
        stroke(CGPoint(x: ax, y: personY), CGPoint(x: ax, y: phoneY), .cyan, 2)
        let dist = L.t(program == "neck" ? "gd_label_1m" : program == "upper_body" ? "gd_label_15m" : "gd_label_2m")
        ctx.draw(Text(dist).font(.caption).foregroundColor(.cyan), at: CGPoint(x: ax - 6, y: (personY + phoneY) / 2), anchor: .trailing)
        ctx.draw(Text(L.t("gd_label_you")).font(.caption2).foregroundColor(.gray), at: CGPoint(x: pcx, y: personY - unit * 1.3))
        ctx.draw(Text(L.t("gd_label_phone")).font(.caption2).foregroundColor(.gray), at: CGPoint(x: pcx, y: phoneY + unit * 0.7))

        // side elevation
        let tableTop = h * 0.56, baseY = h * 0.88, tx = w * 0.56
        ctx.fill(Path(CGRect(x: tx, y: tableTop, width: unit * 2.2, height: unit * 0.3)), with: .color(furniture))
        ctx.fill(Path(CGRect(x: tx + unit * 0.2, y: tableTop, width: unit * 0.25, height: baseY - tableTop)), with: .color(furniture))
        ctx.fill(Path(CGRect(x: tx + unit * 1.75, y: tableTop, width: unit * 0.25, height: baseY - tableTop)), with: .color(furniture))
        let phx = tx + unit * 1.1
        var phoneCtx = ctx
        phoneCtx.translateBy(x: phx, y: tableTop)
        phoneCtx.rotate(by: .degrees(-28 * (1 - e)))
        phoneCtx.fill(Path(roundedRect: CGRect(x: -unit * 0.28, y: -unit * 1.9, width: unit * 0.56, height: unit * 1.9), cornerRadius: 5), with: .color(.white))
        let px = w * 0.9, chestY = tableTop - unit * 1.65
        stroke(CGPoint(x: px, y: chestY - unit * 0.6), CGPoint(x: px, y: chestY + unit * 2.4), body, unit * 0.55)
        stroke(CGPoint(x: px, y: chestY + unit * 2.4), CGPoint(x: px - unit * 1.2, y: chestY + unit * 2.5), body, unit * 0.55)
        stroke(CGPoint(x: px - unit * 1.2, y: chestY + unit * 2.5), CGPoint(x: px - unit * 1.2, y: baseY), body, unit * 0.55)
        let hr = unit * 0.55
        ctx.fill(Path(ellipseIn: CGRect(x: px - hr, y: chestY - unit * 1.35 - hr, width: 2 * hr, height: 2 * hr)), with: .color(body))
        stroke(CGPoint(x: phx + unit * 0.3, y: tableTop - unit * 1.65), CGPoint(x: px - unit * 0.4, y: chestY), .cyan, 2, dash: [7, 5])
        let done = e >= 1
        let l1 = (done ? "✓ " : "") + L.t("gd_label_upright")
        let l2 = (done ? "✓ " : "") + L.t(program == "neck" || view == "front" ? "gd_label_face" : "gd_label_chest")
        ctx.draw(Text(l1 + "\n" + l2).font(.caption).foregroundColor(done ? .green : .gray),
                 in: CGRect(x: w * 0.54, y: h * 0.05, width: w * 0.44, height: h * 0.3))
        stroke(CGPoint(x: w / 2, y: 0), CGPoint(x: w / 2, y: h), Color(red: 0.04, green: 0.07, blue: 0.13), 3)
    }
}

/// Live camera placement check before a session (mirrors Android SetupCheckActivity).
final class SetupCheckController: ObservableObject {
    @Published var lms: [String: Metrics.LM] = [:]
    @Published var frame = CGSize(width: 9, height: 16)
    @Published var result = PlacementCoach.Result(items: [PlacementCoach.Item(key: "person", ok: false, fixKey: "pc_fix_person")])
    @Published var readyStable = false

    let exercise: Exercise
    private let pose = PoseTracker()
    private let motion = CMMotionManager()
    private let speech = AVSpeechSynthesizer()
    private let queue = DispatchQueue(label: "spinecare.setup")
    private var pitch: Double?
    private var roll: Double?
    private var last: TimeInterval = 0
    private var readySince: Date?
    private var lastSpoken = Date.distantPast
    private var lastSpokenText = ""

    init(exercise: Exercise) { self.exercise = exercise }

    func start() {
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 0.1
        motion.startDeviceMotionUpdates(to: .main) { [weak self] m, _ in
            guard let g = m?.gravity, let self else { return }
            // device axes -> screen axes, so "level" and "upright" also hold in landscape
            let (sx, sy) = CameraOptions.screenGravity(gx: g.x, gy: g.y, rotationDeg: CameraOptions.rotationDegrees(OrientationLock.current))
            let (p, r) = PlacementCoach.tilt(gx: sx, gy: sy, gz: g.z)
            self.queue.async { self.pitch = p; self.roll = r }
        }
    }

    func stop() { motion.stopDeviceMotionUpdates(); speech.stopSpeaking(at: .immediate) }

    func process(_ buf: CMSampleBuffer) {
        guard let pb = CMSampleBufferGetImageBuffer(buf) else { return }
        let now = Date().timeIntervalSince1970
        queue.sync {
            guard now - last >= 0.15 else { return }
            last = now
            let r = pose.detect(pb)
            let res = PlacementCoach.assess(r.pixels, w: Double(r.width), h: Double(r.height), ex: exercise,
                                            minConf: ServerConfig.shared.minConfidence, pitch: pitch, roll: roll)
            let size = CGSize(width: r.width, height: r.height)
            DispatchQueue.main.async { self.update(r.normalized, size, res) }
        }
    }

    private func update(_ l: [String: Metrics.LM], _ size: CGSize, _ res: PlacementCoach.Result) {
        lms = l; frame = size; result = res
        if res.ready {
            if readySince == nil { readySince = Date() }
            if !readyStable, let s = readySince, Date().timeIntervalSince(s) >= 1.5 {
                readyStable = true; speak(L.t("sc_ready"), force: true)
            }
        } else {
            readySince = nil; readyStable = false
            if let f = res.firstFix { speak(PlacementCoach.fixText(f.fixKey)) }
        }
    }

    private func speak(_ text: String, force: Bool = false) {
        guard ServerConfig.shared.audioCues else { return }
        let gap = Date().timeIntervalSince(lastSpoken)
        if !force && (gap < 3.5 || (text == lastSpokenText && gap < 8)) { return }
        lastSpoken = Date(); lastSpokenText = text
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: L.lang == "ro" ? "ro-RO" : "en-US")
        speech.stopSpeaking(at: .immediate); speech.speak(u)
    }
}

struct SetupCheckView: View {
    let exercise: Exercise
    @StateObject private var ctl: SetupCheckController
    @ObservedObject private var store = SessionStore.shared
    @State private var frontCamera = true
    @StateObject private var camera = CameraControl()
    @State private var orient = CameraOptions.orientation
    @Environment(\.verticalSizeClass) private var vSize

    init(exercise: Exercise) {
        self.exercise = exercise
        _ctl = StateObject(wrappedValue: SetupCheckController(exercise: exercise))
    }

    var body: some View {
        // landscape: the panel sits on the right instead of the bottom
        ZStack(alignment: vSize == .compact ? .trailing : .bottom) {
            CameraPreview(frontCamera: frontCamera, camera: camera) { ctl.process($0) }.ignoresSafeArea()
                .gesture(MagnificationGesture().onChanged { camera.pinch($0) }.onEnded { _ in camera.pinchEnded() })
            Canvas { ctx, size in
                let zone = CGRect(x: size.width * 0.28, y: size.height * 0.06, width: size.width * 0.44, height: size.height * 0.56)
                ctx.stroke(Path(roundedRect: zone, cornerRadius: 24), with: .color(ctl.result.ready ? .green : .yellow.opacity(0.8)),
                           style: StrokeStyle(lineWidth: 3, dash: [14, 10]))
                OverlayRenderer.draw(ctx: &ctx, size: size, frame: ctl.frame, lms: ctl.lms, ready: ctl.result.ready, missing: [])
            }
            .ignoresSafeArea().allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 4) {
                Text(exercise.title(L.lang)).font(.headline)
                Text(L.t("sc_body")).font(.caption).foregroundStyle(.secondary)
                ForEach(ctl.result.items) { item in
                    Text(item.ok ? "✓ " + PlacementCoach.itemText(item)
                                 : "✗ " + PlacementCoach.itemText(item) + ": " + PlacementCoach.fixText(item.fixKey))
                        .font(.caption).foregroundStyle(item.ok ? .green : .yellow)
                }
                if ctl.readyStable { Text(L.t("sc_ready")).font(.callout.bold()).foregroundStyle(.green) }
                HStack {
                    if camera.lenses.count > 1 { Button(camera.lensLabel) { camera.nextLens() }.buttonStyle(.bordered) }
                    Button(camera.zoomLabel) { camera.cycleZoom() }.buttonStyle(.bordered)
                    Button("⟳ " + L.t(orient.key)) {
                        orient = orient.next; CameraOptions.orientation = orient; OrientationLock.refresh()
                    }.buttonStyle(.bordered)
                }.font(.caption)
                HStack {
                    Button(L.t("flip")) { frontCamera.toggle() }.buttonStyle(.bordered)
                    if exercise.isCustom || store.isApproved(exercise.id) {
                        NavigationLink(L.t("sc_start")) { ExerciseSessionView(exercise: exercise) }.buttonStyle(.borderedProminent)
                    } else {
                        Text(L.t("approved_required")).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding().frame(maxWidth: vSize == .compact ? 340 : .infinity, alignment: .leading)
            .background(.black.opacity(0.75)).foregroundStyle(.white)
        }
        .navigationTitle(L.t("sc_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true; OrientationLock.enterCameraScreen(); ctl.start() }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false; OrientationLock.leaveCameraScreen(); ctl.stop() }
    }
}

/// Processes a picked video every 100 ms: pose, framing, tracker and placement
/// coach, showing each processed frame with the session overlay, then tips and
/// a scrubber over the processed frames (mirrors Android VideoAnalysisActivity).
final class VideoAnalysisController: ObservableObject {
    struct Processed { let t: TimeInterval; let lms: [String: Metrics.LM]; let size: CGSize; let ready: Bool; let cue: String; let reps: Int }

    @Published var image: CGImage?
    @Published var current: Processed?
    @Published var progress = 0.0
    @Published var done = false
    @Published var failed = false
    @Published var tips: [String] = []
    @Published var summary: [String] = []
    private(set) var results: [Processed] = []

    let exercise: Exercise
    let url: URL
    private var cancelled = false
    private let queue = DispatchQueue(label: "spinecare.video")
    private lazy var generator: AVAssetImageGenerator = {
        let g = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        g.appliesPreferredTrackTransform = true
        g.requestedTimeToleranceBefore = .zero; g.requestedTimeToleranceAfter = .zero
        g.maximumSize = CGSize(width: 720, height: 720)
        return g
    }()

    init(exercise: Exercise, url: URL) { self.exercise = exercise; self.url = url }

    func start() {
        guard results.isEmpty, !done else { return }
        queue.async { self.run() }
    }

    func cancel() { cancelled = true }

    private func run() {
        let asset = AVURLAsset(url: url)
        let duration = CMTimeGetSeconds(asset.duration)
        guard duration.isFinite, duration > 0 else { DispatchQueue.main.async { self.failed = true }; return }
        let end = min(duration, 180)
        let pose = PoseTracker()
        let minConf = ServerConfig.shared.minConfidence
        let tracker = ExerciseTracker(exercise, calibration: Plan.calibrationSeconds, lang: L.lang)
        let report = VideoReport(exercise)
        var t = 0.0, lastCue = ""
        while t <= end && !cancelled {
            if let img = try? generator.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil) {
                let r = pose.detect(img)
                let px = r.pixels, w = Double(r.width), h = Double(r.height)
                let framing = Framing.check(px, required: exercise.requiredLandmarks, minConf: minConf, w: w, h: h)
                let st = tracker.update(Metrics.compute(px), ready: framing.ready, t: t)
                let placement = PlacementCoach.assess(px, w: w, h: h, ex: exercise, minConf: minConf)
                report.add(t, placement: placement, ready: framing.ready, state: st)
                var cue = lastCue
                if !placement.ready { cue = placement.firstFix.map { PlacementCoach.fixText($0.fixKey) } ?? "" }
                else if st.calibrating { cue = L.t("calibrating") }
                else if let c = st.cues.first { cue = c.text }
                else if st.repJustDone { cue = L.f("rep_done", st.reps, exercise.targetReps) }
                lastCue = cue
                let p = Processed(t: t, lms: r.normalized, size: CGSize(width: w, height: h), ready: framing.ready, cue: cue, reps: st.reps)
                results.append(p)
                let pct = t / end
                DispatchQueue.main.async { self.image = img; self.current = p; self.progress = pct }
            }
            t += 0.1
        }
        let lines = [L.f("va_frames", report.frames, Int(report.duration)),
                     L.f("va_reps", report.reps, exercise.targetReps),
                     L.f("va_framed", Int(report.framedFraction * 100))] + (duration > 180 ? [L.t("va_too_long")] : [])
        let tips = report.tips()
        DispatchQueue.main.async {
            self.summary = lines; self.tips = tips; self.done = true; self.progress = 1
            if report.frames == 0 { self.failed = true }
        }
    }

    /// Re-reads one processed frame for the scrubber.
    func show(_ i: Int) {
        guard i >= 0, i < results.count else { return }
        let p = results[i]
        queue.async {
            let img = try? self.generator.copyCGImage(at: CMTime(seconds: p.t, preferredTimescale: 600), actualTime: nil)
            DispatchQueue.main.async { if let img { self.image = img }; self.current = p }
        }
    }
}

struct VideoAnalysisView: View {
    let exercise: Exercise
    @StateObject private var ctl: VideoAnalysisController
    @State private var scrub = 0.0

    init(exercise: Exercise, url: URL) {
        self.exercise = exercise
        _ctl = StateObject(wrappedValue: VideoAnalysisController(exercise: exercise, url: url))
    }

    var body: some View {
        List {
            if let img = ctl.image, let p = ctl.current {
                ZStack {
                    Image(decorative: img, scale: 1).resizable()
                    Canvas { ctx, size in
                        OverlayRenderer.draw(ctx: &ctx, size: size, frame: p.size, lms: p.lms, ready: p.ready, missing: [])
                    }
                    VStack {
                        HStack { Spacer(); Text("\(p.reps)/\(exercise.targetReps)").font(.title.bold()).foregroundStyle(.green) }
                        Spacer()
                        if !p.cue.isEmpty {
                            Text(p.cue).font(.caption).padding(6).frame(maxWidth: .infinity)
                                .background(.black.opacity(0.7)).foregroundStyle(.yellow)
                        }
                    }.padding(6)
                }
                .aspectRatio(p.size.width / max(1, p.size.height), contentMode: .fit)
                .listRowInsets(EdgeInsets())
            }
            if ctl.failed { Text(L.t("va_failed")).foregroundStyle(.red) }
            else if !ctl.done {
                ProgressView(value: ctl.progress)
                Text(L.f("va_processing", Int(ctl.progress * 100))).font(.caption)
            } else {
                Section {
                    Text(L.t("va_done")).foregroundStyle(.green)
                    ForEach(ctl.summary, id: \.self) { Text($0).font(.callout) }
                    if ctl.results.count > 1 {
                        Slider(value: $scrub, in: 0...Double(ctl.results.count - 1), step: 1)
                            .onChange(of: scrub) { ctl.show(Int($0)) }
                        Text(L.t("va_scrub")).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Section(L.t("va_tips")) { ForEach(ctl.tips, id: \.self) { Text("• " + $0).font(.callout) } }
            }
        }
        .navigationTitle(L.t("va_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { ctl.start() }
        .onDisappear { ctl.cancel() }
    }
}
