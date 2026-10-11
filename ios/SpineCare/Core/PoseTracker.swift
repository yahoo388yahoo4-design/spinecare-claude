import Foundation
import AVFoundation
import Vision

/// On-device pose via Apple Vision (VNDetectHumanBodyPoseRequest), no network.
/// Frames arrive already upright (the capture connection rotates them to
/// portrait and mirrors the front camera), so Vision runs with `.up` and the
/// normalized points line up with the preview. Joints are mapped into the
/// shared name space used by Metrics/Plan.
final class PoseTracker {
    private let request = VNDetectHumanBodyPoseRequest()

    static let jointMap: [VNHumanBodyPoseObservation.JointName: String] = [
        .nose: "nose", .leftEar: "lear", .rightEar: "rear",
        .leftShoulder: "lsho", .rightShoulder: "rsho",
        .leftElbow: "lelb", .rightElbow: "relb",
        .leftWrist: "lwri", .rightWrist: "rwri",
        .leftHip: "lhip", .rightHip: "rhip",
        .leftKnee: "lknee", .rightKnee: "rknee",
        .leftAnkle: "lank", .rightAnkle: "rank",
    ]

    struct Result {
        let normalized: [String: Metrics.LM]   // 0...1, y top-down
        let width: Int
        let height: Int
        let ms: Double

        /// Same points in pixels, so angles are not distorted by the aspect ratio.
        var pixels: [String: Metrics.LM] {
            normalized.mapValues { Metrics.LM(x: $0.x * Double(width), y: $0.y * Double(height), conf: $0.conf) }
        }
    }

    func detect(_ pixelBuffer: CVPixelBuffer) -> Result {
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        let t0 = CFAbsoluteTimeGetCurrent()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        try? handler.perform([request])
        var out: [String: Metrics.LM] = [:]
        if let obs = request.results?.first, let points = try? obs.recognizedPoints(.all) {
            for (joint, name) in Self.jointMap {
                if let pt = points[joint], pt.confidence > 0.05 {
                    out[name] = Metrics.LM(x: Double(pt.location.x), y: Double(1 - pt.location.y), conf: Double(pt.confidence))
                }
            }
        }
        return Result(normalized: out, width: w, height: h, ms: (CFAbsoluteTimeGetCurrent() - t0) * 1000)
    }
}
