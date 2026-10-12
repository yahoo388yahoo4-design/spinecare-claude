// Replays demo videos through the iOS app's coaching code on a macOS CI runner:
// Apple Vision body pose (the app's PoseTracker) -> Metrics -> Framing gate ->
// ExerciseTracker, compiled together with ios/SpineCare/Core/{PoseTracker,Engines,Plan,Strings,SessionStore}.swift.
// The overlay drawn here is a CoreGraphics stand-in for the UIKit OverlayRenderer.
//
// Usage: ios_replay <videos_dir with sources.json> <shared/exercises.json> <out_dir> [max_frames]
import AVFoundation
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct PlanFileCLI: Codable { let calibrationS: Double?; let exercises: [Exercise] }

let args = CommandLine.arguments
guard args.count >= 4 else { print("usage: ios_replay <videos_dir> <exercises.json> <out_dir> [max_frames]"); exit(2) }
let videosDir = URL(fileURLWithPath: args[1])
let plan = try Plan.decoder.decode(PlanFileCLI.self, from: Data(contentsOf: URL(fileURLWithPath: args[2])))
let outRoot = URL(fileURLWithPath: args[3])
let maxFrames = args.count > 4 ? Int(args[4]) ?? 270 : 270
try FileManager.default.createDirectory(at: outRoot, withIntermediateDirectories: true)

let sources = try JSONSerialization.jsonObject(with: Data(contentsOf: videosDir.appendingPathComponent("sources.json"))) as! [String: Any]
let videos = sources["videos"] as? [[String: Any]] ?? []
let fps = Double(sources["fps"] as? Int ?? 6)

let connections = [("lsho", "rsho"), ("lsho", "lhip"), ("rsho", "rhip"), ("lhip", "rhip"), ("lsho", "lelb"), ("rsho", "relb"),
                   ("lelb", "lwri"), ("relb", "rwri"), ("lhip", "lknee"), ("rhip", "rknee"), ("lknee", "lank"), ("rknee", "rank")]

func pixelBuffer(_ img: CGImage) -> CVPixelBuffer? {
    var pb: CVPixelBuffer?
    let attrs = [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary
    CVPixelBufferCreate(nil, img.width, img.height, kCVPixelFormatType_32BGRA, attrs, &pb)
    guard let pb else { return nil }
    CVPixelBufferLockBaseAddress(pb, [])
    defer { CVPixelBufferUnlockBaseAddress(pb, []) }
    let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: img.width, height: img.height, bitsPerComponent: 8,
                        bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
    ctx?.draw(img, in: CGRect(x: 0, y: 0, width: img.width, height: img.height))
    return pb
}

func drawText(_ ctx: CGContext, _ s: String, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) {
    let font = CTFontCreateWithName("Helvetica" as CFString, size, nil)
    let attr = NSAttributedString(string: s, attributes: [kCTFontAttributeName as NSAttributedString.Key: font,
                                                          kCTForegroundColorAttributeName as NSAttributedString.Key: color])
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(CTLineCreateWithAttributedString(attr), ctx)
}

func annotate(_ img: CGImage, lms: [String: Metrics.LM], ready: Bool, missing: [String], lines: [String], reps: String, to url: URL) {
    let w = img.width, h = img.height
    guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
    let H = CGFloat(h)
    let ok = CGColor(red: 0.20, green: 0.83, blue: 0.60, alpha: 1), warn = CGColor(red: 0.98, green: 0.75, blue: 0.14, alpha: 1)
    let bad = CGColor(red: 0.97, green: 0.44, blue: 0.44, alpha: 1)
    let color = ready ? ok : warn
    ctx.setLineWidth(4); ctx.setStrokeColor(color)
    for (a, b) in connections {
        guard let pa = lms[a], let pb = lms[b], pa.conf > 0.3, pb.conf > 0.3 else { continue }
        ctx.move(to: CGPoint(x: pa.x, y: H - pa.y)); ctx.addLine(to: CGPoint(x: pb.x, y: H - pb.y)); ctx.strokePath()
    }
    let missingNames = Set(missing.map { Framing.resolve(lms, $0) })
    for (name, p) in lms where p.conf > 0.3 || missingNames.contains(name) {
        let r: CGFloat = missingNames.contains(name) ? 10 : 5
        ctx.setFillColor(missingNames.contains(name) ? bad : color)
        ctx.fillEllipse(in: CGRect(x: p.x - r, y: H - p.y - r, width: 2 * r, height: 2 * r))
    }
    let panel = CGFloat(lines.count) * 22 + 12
    ctx.setFillColor(CGColor(red: 0.04, green: 0.07, blue: 0.13, alpha: 0.8))
    ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(w), height: panel))
    for (i, ln) in lines.enumerated() {
        drawText(ctx, ln, x: 10, y: panel - CGFloat(i + 1) * 22 + 4, size: 15, color: CGColor(gray: 1, alpha: 1))
    }
    drawText(ctx, reps, x: CGFloat(w) - 110, y: H - 50, size: 36, color: ok)
    guard let out = ctx.makeImage(), let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return }
    CGImageDestinationAddImage(dest, out, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
    CGImageDestinationFinalize(dest)
}

var summary: [[String: Any]] = []
let pose = PoseTracker()
for v in videos {
    guard let id = v["id"] as? String, let file = v["file"] as? String, let exId = v["exercise_id"] as? String,
          let ex = plan.exercises.first(where: { $0.id == exId }) else { continue }
    let out = outRoot.appendingPathComponent(id), framesDir = out.appendingPathComponent("frames")
    try FileManager.default.createDirectory(at: framesDir, withIntermediateDirectories: true)
    let asset = AVURLAsset(url: videosDir.appendingPathComponent(file))
    let gen = AVAssetImageGenerator(asset: asset)
    gen.appliesPreferredTrackTransform = true
    gen.requestedTimeToleranceBefore = .zero; gen.requestedTimeToleranceAfter = .zero
    let duration = CMTimeGetSeconds(asset.duration)
    let n = min(Int(duration * fps), maxFrames)
    let tracker = ExerciseTracker(ex, calibration: plan.calibrationS ?? 1.5, lang: "en")
    var log: [[String: Any]] = [], lat: [Double] = []
    var frames = 0, detected = 0, readyN = 0, lastCue = ""
    for i in 0..<n {
        let t = Double(i) / fps
        guard let img = try? gen.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil),
              let pb = pixelBuffer(img) else { continue }
        frames += 1
        let r = pose.detect(pb)
        lat.append(r.ms)
        let px = r.pixels
        if !px.isEmpty { detected += 1 }
        let framing = Framing.check(px.isEmpty ? nil : px, required: ex.requiredLandmarks, minConf: ex.minConf,
                                    w: Double(r.width), h: Double(r.height))
        if framing.ready { readyN += 1 }
        let metrics = px.isEmpty ? [:] : Metrics.compute(px)
        let st = tracker.update(metrics, ready: framing.ready, t: t)
        let cue: String
        if !framing.ready { cue = Array(Set(framing.issues)).sorted().map(Framing.fixFor).joined(separator: " ") }
        else if st.calibrating { cue = L.t("calibrating") }
        else if let c = st.cues.first { cue = c.text }
        else if st.repJustDone { cue = "rep \(st.reps)/\(ex.targetReps)" }
        else { cue = "" }
        if !cue.isEmpty { lastCue = cue }
        let mline = metrics.sorted { $0.key < $1.key }.prefix(6).map { "\($0.key)=\(String(format: "%.1f", $0.value))" }.joined(separator: "  ")
        let sig = st.signal.map { String(format: "%.2f", $0) } ?? "-"
        annotate(img, lms: px, ready: framing.ready, missing: framing.missing,
                 lines: [String(lastCue.prefix(80)), mline, "signal=\(sig)  Vision \(Int(r.ms))ms  frame=\(i)  \(ex.id)"],
                 reps: "\(st.reps)/\(ex.targetReps)", to: framesDir.appendingPathComponent(String(format: "f_%05d.jpg", i)))
        log.append(["frame": i, "detected": !px.isEmpty, "ready": framing.ready, "missing": framing.missing,
                    "metrics": metrics.filter { $0.value.isFinite }, "signal": st.signal.flatMap { $0.isFinite ? $0 as Any : nil } ?? NSNull(),
                    "reps": st.reps, "rep_done": st.repJustDone, "cue": cue, "latency_ms": r.ms])
    }
    let sorted = lat.sorted()
    let res: [String: Any] = [
        "id": id, "exercise_id": exId, "title": v["title"] ?? "", "license": v["license"] ?? "",
        "frames": frames, "detected_frames": detected, "detection_rate": frames > 0 ? Double(detected) / Double(frames) : 0,
        "ready_frames": readyN, "ready_rate": frames > 0 ? Double(readyN) / Double(frames) : 0,
        "calibrated": !tracker.calibrating, "reps": tracker.reps.reps,
        "latency_p50_ms": sorted.isEmpty ? 0 : sorted[sorted.count / 2],
        "latency_p95_ms": sorted.isEmpty ? 0 : sorted[sorted.count * 95 / 100],
    ]
    print("\(id): \(res)")
    summary.append(res)
    let logData = try JSONSerialization.data(withJSONObject: ["summary": res, "frames": log])
    try logData.write(to: out.appendingPathComponent("log.json"))
}
let host = ProcessInfo.processInfo.operatingSystemVersionString
try JSONSerialization.data(withJSONObject: ["device": "macOS \(host) (Apple Vision, iOS app engine code)", "videos": summary], options: .prettyPrinted)
    .write(to: outRoot.appendingPathComponent("summary.json"))
if summary.isEmpty { print("no video replayed"); exit(1) }
