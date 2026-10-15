import SwiftUI
import AVFoundation
import UIKit

/// Playback state for one saved session: the recorded video (overlay burned
/// in) and/or the sensor timeline, on one clock in session milliseconds.
final class ReplayModel: ObservableObject {
    @Published var clock = 0
    @Published var playing = false
    @Published var useVideo = false
    @Published var speed: Float = 1
    @Published var loaded = false
    @Published private(set) var timeline: SessionTimeline?
    let videoURL: URL?
    let player: AVPlayer?
    private var timer: Timer?
    private var lastTick = Date()
    private var observer: Any?
    private var endObserver: NSObjectProtocol?

    init(videoURL: URL?) {
        self.videoURL = videoURL
        player = videoURL.map { AVPlayer(url: $0) }
        useVideo = videoURL != nil
        if let player {
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 30), queue: .main) { [weak self] t in
                guard let self, self.useVideo, self.playing else { return }
                self.clock = Int(t.seconds * 1000) + self.videoOffset
            }
            endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
                                                                 object: player.currentItem, queue: .main) { [weak self] _ in
                self?.pause()
            }
        }
    }

    deinit {
        timer?.invalidate()
        if let observer { player?.removeTimeObserver(observer) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    }

    func load(_ url: URL?) {
        guard let url else { loaded = true; return }
        DispatchQueue.global(qos: .userInitiated).async {
            let tl = SessionTimeline.read(url)
            DispatchQueue.main.async { self.timeline = tl; self.loaded = true }
        }
    }

    var videoOffset: Int { timeline?.videoOffsetMs ?? 0 }

    var duration: Int {
        var videoEnd = 0
        if let d = player?.currentItem?.duration, d.isNumeric { videoEnd = Int(d.seconds * 1000) + videoOffset }
        return max(timeline?.durationMs ?? 0, videoEnd, 1)
    }

    var sample: TimelineSample? { timeline?.at(clock) }

    func play() {
        if clock >= duration - 50 { seek(0) }
        playing = true
        if useVideo, let player { player.play(); player.rate = speed }
        lastTick = Date()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.tick() }
    }

    func pause() {
        playing = false
        player?.pause()
        timer?.invalidate(); timer = nil
    }

    func toggle() { playing ? pause() : play() }

    private func tick() {
        let now = Date(); let dt = now.timeIntervalSince(lastTick); lastTick = now
        guard !(useVideo && player != nil) else { return }   // the player's time observer drives the clock
        clock += Int(dt * 1000 * Double(speed))
        if clock >= duration { clock = duration; pause() }
    }

    func seek(_ ms: Int) {
        clock = min(max(0, ms), duration)
        if useVideo, let player {
            player.seek(to: CMTime(value: CMTimeValue(max(0, clock - videoOffset)), timescale: 1000),
                        toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    func setMode(video: Bool) {
        let was = playing
        pause()
        useVideo = video && player != nil
        seek(clock)
        if was { play() }
    }

    func cycleSpeed() {
        speed = speed == 1 ? 2 : (speed == 2 ? 0.5 : 1)
        if playing, useVideo { player?.rate = speed }
    }

    /// Jumps to 2 s before the next/previous rep completes, so the whole movement shows.
    func jumpRep(_ dir: Int) {
        guard let reps = timeline?.repTimes, !reps.isEmpty else { return }
        let marks = reps.map { max(0, $0 - 2000) }
        let next = dir > 0 ? marks.first { $0 > clock + 300 } : marks.last { $0 < clock - 300 }
        seek(next ?? (dir > 0 ? marks.last! : 0))
    }
}

/// AVPlayerLayer without system controls (the replay has its own transport).
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    /// Cover the whole view (centre crop) instead of fitting inside it.
    var fill = true
    final class V: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> V {
        let v = V(); v.playerLayer.player = player; v.backgroundColor = .black
        v.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        v.clipsToBounds = true
        return v
    }
    func updateUIView(_ v: V, context: Context) { v.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect }
}

/// In-app preview of a saved session: video and/or skeleton redrawn from the
/// sensor timeline, a metric chart with rep and cue marks and BLE sensor
/// lines, and a readout at the playhead. The picture fills its area (centre
/// crop) by default; Fill / Fit shows the whole frame instead and is remembered.
/// Everything stays on the device.
struct SessionReplayView: View {
    let rec: SessionRecord
    @AppStorage("replayFill") private var fill = true
    @StateObject private var model: ReplayModel
    private let timelineURL: URL?
    private let target: Int

    init(rec: SessionRecord) {
        self.rec = rec
        let store = SessionStore.shared
        let video = store.videoExists(rec) ? store.videoURL(rec) : nil
        timelineURL = store.timelineURL(rec)
        target = Plan.byId(rec.exercise)?.targetReps ?? 0
        _model = StateObject(wrappedValue: ReplayModel(videoURL: video))
    }

    private func fmt(_ ms: Int) -> String { String(format: "%d:%02d", ms / 1000 / 60, ms / 1000 % 60) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !model.loaded {
                ProgressView(L.t("replay_loading")).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if model.timeline == nil && model.player == nil {
                Text(L.t("replay_no_data")).foregroundStyle(.secondary).padding()
                Spacer()
            } else {
                if model.timeline == nil { Text(L.t("replay_video_only")).font(.caption).foregroundStyle(.secondary) }
                else if model.player == nil { Text(L.t("replay_sensor_only")).font(.caption).foregroundStyle(.secondary) }
                stage.frame(maxWidth: .infinity, maxHeight: .infinity)
                if model.player != nil && model.timeline != nil {
                    Picker("", selection: Binding(get: { model.useVideo }, set: { model.setMode(video: $0) })) {
                        Text(L.t("replay_video")).tag(true)
                        Text(L.t("replay_sensors")).tag(false)
                    }.pickerStyle(.segmented)
                }
                HStack {
                    Slider(value: Binding(get: { Double(model.clock) }, set: { model.seek(Int($0)) }),
                           in: 0...Double(max(1, model.duration)))
                    Text("\(fmt(model.clock)) / \(fmt(model.duration))").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                HStack {
                    Button(L.t("replay_prev_rep")) { model.jumpRep(-1) }.disabled(model.timeline == nil)
                    Spacer()
                    Button(model.playing ? L.t("replay_pause") : L.t("replay_play")) { model.toggle() }.buttonStyle(.borderedProminent)
                    Button(model.speed == 1 ? "1×" : (model.speed == 2 ? "2×" : "0.5×")) { model.cycleSpeed() }.buttonStyle(.bordered)
                    Button(L.t(fill ? "replay_fill" : "replay_fit")) { fill.toggle() }.buttonStyle(.bordered)
                    Spacer()
                    Button(L.t("replay_next_rep")) { model.jumpRep(1) }.disabled(model.timeline == nil)
                }
                if let tl = model.timeline {
                    ReplayChart(timeline: tl, playhead: model.clock) { model.seek($0) }.frame(height: 110)
                    Text(L.t("replay_legend")).font(.caption2).foregroundStyle(.secondary)
                }
                if let s = model.sample { readout(s).font(.system(.caption, design: .monospaced)) }
                if let url = model.videoURL { ShareLink(L.t("replay_external"), item: url).font(.caption) }
            }
        }
        .padding(12)
        .navigationTitle("\(rec.date) · \(Plan.byId(rec.exercise)?.title(L.lang) ?? rec.exercise)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.load(timelineURL) }
        .onDisappear { model.pause() }
    }

    @ViewBuilder private var stage: some View {
        if model.useVideo, let player = model.player {
            PlayerLayerView(player: player, fill: fill).clipped()
        } else {
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black))
                guard let s = model.sample else { return }
                let frame = CGSize(width: max(1, s.frameW), height: max(1, s.frameH))
                let k = min(size.width / frame.width, size.height / frame.height)
                // fill: the whole view, and OverlayRenderer centre-crops the frame into it
                let fit = fill ? size : CGSize(width: frame.width * k, height: frame.height * k)
                var c = ctx
                c.translateBy(x: (size.width - fit.width) / 2, y: (size.height - fit.height) / 2)
                c.fill(Path(CGRect(origin: .zero, size: fit)), with: .color(Color(white: 0.09)))
                OverlayRenderer.draw(ctx: &c, size: fit, frame: frame, lms: s.landmarks, ready: s.ready, missing: [])
                c.draw(Text("\(s.reps)/\(target)").font(.system(size: 34, weight: .bold)).foregroundColor(.green),
                       at: CGPoint(x: fit.width - 12, y: 12), anchor: .topTrailing)
                if !s.cue.isEmpty {
                    c.draw(Text(s.cue).font(.caption).foregroundColor(.yellow),
                           in: CGRect(x: 8, y: fit.height - 40, width: fit.width - 16, height: 36))
                }
            }
        }
    }

    private func readout(_ s: TimelineSample) -> some View {
        var lines = [L.f("replay_reps_at", s.reps, target)]
        if !s.cue.isEmpty { lines.append(s.cue) }
        if !s.metrics.isEmpty {
            lines.append(s.metrics.sorted { $0.key < $1.key }.map { "\($0.key)=\(String(format: "%.2f", $0.value))" }.joined(separator: "  "))
        }
        if let h = s.head { lines.append(L.f("replay_imu_head", h[0], h[1], h[2])) }
        if let t = s.trunk { lines.append(L.f("replay_imu_trunk", t[0], t[1], t[2])) }
        return Text(lines.joined(separator: "\n"))
    }
}

/// Metric-over-time chart: the exercise metric and BLE head/trunk pitch (each
/// scaled to its own range), green rep ticks, amber cue ticks and the playhead.
/// Tap or drag to seek.
struct ReplayChart: View {
    let timeline: SessionTimeline
    let playhead: Int
    let onSeek: (Int) -> Void

    private struct Series { let label: String; let color: Color; let values: [Double?] }

    private var series: [Series] {
        var out: [Series] = []
        let s = timeline.samples
        if !timeline.metric.isEmpty { out.append(Series(label: timeline.metric, color: .cyan, values: s.map { $0.metrics[timeline.metric] })) }
        if s.contains(where: { $0.head != nil }) { out.append(Series(label: L.t("replay_head_pitch"), color: .purple, values: s.map { $0.head?[0] })) }
        if s.contains(where: { $0.trunk != nil }) { out.append(Series(label: L.t("replay_trunk_pitch"), color: .pink, values: s.map { $0.trunk?[0] })) }
        return out
    }

    var body: some View {
        let dur = Double(max(1, timeline.durationMs))
        let all = series
        GeometryReader { geo in
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(white: 0.1)))
                let top: CGFloat = 18, bottom = size.height - 8
                func x(_ t: Int) -> CGFloat { CGFloat(Double(t) / dur) * size.width }
                var lx: CGFloat = 6
                for s in all {
                    let text = ctx.resolve(Text(s.label).font(.caption2).foregroundColor(s.color))
                    ctx.draw(text, at: CGPoint(x: lx, y: 2), anchor: .topLeading)
                    lx += text.measure(in: size).width + 12
                }
                let times = timeline.samples.map { $0.t }
                let step = max(1, times.count / max(1, Int(size.width)))
                for s in all {
                    let vals = s.values.compactMap { $0 }
                    guard let lo = vals.min(), let hi = vals.max() else { continue }
                    let span = hi - lo > 1e-9 ? hi - lo : 1
                    var path = Path(); var pen = false
                    for i in stride(from: 0, to: times.count, by: step) {
                        guard let v = s.values[i] else { pen = false; continue }
                        let p = CGPoint(x: x(times[i]), y: bottom - CGFloat((v - lo) / span) * (bottom - top))
                        if pen { path.addLine(to: p) } else { path.move(to: p); pen = true }
                    }
                    ctx.stroke(path, with: .color(s.color), lineWidth: 2)
                }
                for t in timeline.cueTimes {
                    var p = Path(); p.move(to: CGPoint(x: x(t), y: bottom - 6)); p.addLine(to: CGPoint(x: x(t), y: bottom))
                    ctx.stroke(p, with: .color(.orange), lineWidth: 2)
                }
                for t in timeline.repTimes {
                    var p = Path(); p.move(to: CGPoint(x: x(t), y: top)); p.addLine(to: CGPoint(x: x(t), y: top + 8))
                    ctx.stroke(p, with: .color(.green), lineWidth: 2)
                }
                var ph = Path(); ph.move(to: CGPoint(x: x(playhead), y: top)); ph.addLine(to: CGPoint(x: x(playhead), y: bottom))
                ctx.stroke(ph, with: .color(.white), lineWidth: 2)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let f = min(max(0, g.location.x / max(1, geo.size.width)), 1)
                onSeek(Int(f * dur))
            })
        }
    }
}

/// Thumbnail for the saved-sessions list: a video frame from mid-session, or
/// the skeleton at the deepest point of the movement plus a metric sparkline.
struct SessionThumb: View {
    let rec: SessionRecord
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image { Image(uiImage: image).resizable().scaledToFill() }
        }
        .frame(width: 96, height: 72).clipped().clipShape(RoundedRectangle(cornerRadius: 6))
        .task(id: rec.ts) { image = await Self.render(rec) }
    }

    private static var cache: [Int: UIImage] = [:]

    static func render(_ rec: SessionRecord) async -> UIImage? {
        if let c = await MainActor.run(body: { cache[rec.ts] }) { return c }
        let store = SessionStore.shared
        let video = store.videoExists(rec) ? store.videoURL(rec) : nil
        let tlURL = store.timelineURL(rec)
        let img: UIImage? = await Task.detached(priority: .utility) { () -> UIImage? in
            if let video {
                let gen = AVAssetImageGenerator(asset: AVURLAsset(url: video))
                gen.appliesPreferredTrackTransform = true
                gen.maximumSize = CGSize(width: 320, height: 320)
                let asset = AVURLAsset(url: video)
                let mid = CMTime(seconds: max(0, asset.duration.seconds / 2), preferredTimescale: 600)
                if let cg = try? gen.copyCGImage(at: mid, actualTime: nil) { return UIImage(cgImage: cg) }
            }
            guard let url = tlURL, let tl = SessionTimeline.read(url) else { return nil }
            return SessionThumb.skeletonImage(tl)
        }.value
        if let img { await MainActor.run { cache[rec.ts] = img } }
        return img
    }

    /// The skeleton zoomed onto the body so it fills the 4:3 tile.
    static func skeletonImage(_ tl: SessionTimeline) -> UIImage {
        let s = tl.peak
        let fw = CGFloat(max(1, s?.frameW ?? 3)), fh = CGFloat(max(1, s?.frameH ?? 4))
        let size = CGSize(width: 320, height: 240)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { r in
            let cg = r.cgContext
            cg.setFillColor(UIColor(white: 0.09, alpha: 1).cgColor); cg.fill(CGRect(origin: .zero, size: size))
            if let s {
                let frameSize = CGSize(width: fw, height: fh)
                let vis = s.landmarks.values.filter { $0.conf > Metrics.vis }
                cg.saveGState()
                if vis.count >= 2, let x0 = vis.map(\.x).min(), let x1 = vis.map(\.x).max(),
                   let y0r = vis.map(\.y).min(), let y1 = vis.map(\.y).max() {
                    // normalized bounds -> frame pixels, at least a fifth of the frame, a little room above the head
                    var bx0 = CGFloat(x0) * fw, bx1 = CGFloat(x1) * fw, by0 = CGFloat(y0r) * fh, by1 = CGFloat(y1) * fh
                    if bx1 - bx0 < fw / 5 { let m = (bx0 + bx1) / 2; bx0 = m - fw / 10; bx1 = m + fw / 10 }
                    if by1 - by0 < fh / 5 { let m = (by0 + by1) / 2; by0 = m - fh / 10; by1 = m + fh / 10 }
                    by0 -= (by1 - by0) * 0.08
                    let k = min(size.width / ((bx1 - bx0) * 1.3), size.height / ((by1 - by0) * 1.3))
                    cg.translateBy(x: size.width / 2 - (bx0 + bx1) / 2 * k, y: size.height / 2 - (by0 + by1) / 2 * k)
                    cg.scaleBy(x: k, y: k)
                    OverlayRenderer.draw(cg: cg, size: frameSize, lms: s.landmarks, ready: true, cue: "", metrics: "", reps: "")
                } else {
                    OverlayRenderer.draw(cg: cg, size: size, lms: s.landmarks, ready: true, cue: "", metrics: "", reps: "")
                }
                cg.restoreGState()
            }
            let vals = tl.samples.compactMap { smp in smp.metrics[tl.metric].map { (smp.t, $0) } }
            guard vals.count >= 2, let lo = vals.map({ $0.1 }).min(), let hi = vals.map({ $0.1 }).max() else { return }
            let span = hi - lo > 1e-9 ? hi - lo : 1
            let dur = CGFloat(max(1, tl.durationMs))
            let top = size.height * 0.75, bottom = size.height - 6
            cg.setStrokeColor(UIColor.systemCyan.cgColor); cg.setLineWidth(3)
            for (i, (t, v)) in vals.enumerated() {
                let p = CGPoint(x: CGFloat(t) / dur * size.width, y: bottom - CGFloat((v - lo) / span) * (bottom - top))
                if i == 0 { cg.move(to: p) } else { cg.addLine(to: p) }
            }
            cg.strokePath()
        }
    }
}
