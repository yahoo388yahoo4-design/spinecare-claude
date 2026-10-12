import AVFoundation
import CoreImage
import UIKit

/// Records the session as MP4 with the sensor overlay burned in. Camera frames
/// arrive upright (and mirrored for the front camera) from the capture
/// connection, so each frame is composited with the overlay at its own size and
/// appended through AVAssetWriter. Runs on the camera queue.
///
/// Fix vs. the first prototype: `finish` now calls `finishWriting`, without
/// which the MP4 was never finalized and could not be played.
final class SessionRecorder {
    private let writer: AVAssetWriter
    private let input: AVAssetWriterInput
    private let adaptor: AVAssetWriterInputPixelBufferAdaptor
    private let width: Int
    private let height: Int
    private var started = false
    private var lastTime = CMTime.invalid
    private let ciContext = CIContext(options: nil)
    private(set) var framesWritten = 0

    init?(url: URL, width: Int, height: Int) {
        guard let w = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return nil }
        writer = w
        input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        guard writer.canAdd(input) else { return nil }
        writer.add(input)
        guard writer.startWriting() else { return nil }
        self.width = width; self.height = height
    }

    func append(_ pixelBuffer: CVPixelBuffer, landmarks: [String: Metrics.LM], ready: Bool,
                cue: String, metrics: String, reps: String, timestamp: CMTime) {
        guard writer.status == .writing, input.isReadyForMoreMediaData else { return }
        if lastTime.isValid && timestamp <= lastTime { return }
        if !started { started = true; writer.startSession(atSourceTime: timestamp) }

        // Fit without stretching: if the frame shape changes mid-session (camera
        // switched) the picture is letterboxed, and the overlay is drawn in the
        // same rectangle so it still lines up.
        var ci = CIImage(cvPixelBuffer: pixelBuffer)
        let fw = ci.extent.width, fh = ci.extent.height
        let s = min(CGFloat(width) / fw, CGFloat(height) / fh)
        let pic = CGSize(width: fw * s, height: fh * s)
        let ox = (CGFloat(width) - pic.width) / 2, oy = (CGFloat(height) - pic.height) / 2
        ci = ci.transformed(by: CGAffineTransform(scaleX: s, y: s).concatenating(CGAffineTransform(translationX: ox, y: oy)))
        let black = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: width, height: height))
        ci = ci.composited(over: black)

        let size = CGSize(width: width, height: height)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = false
        let overlay = UIGraphicsImageRenderer(size: size, format: format).image { r in
            r.cgContext.translateBy(x: ox, y: oy)
            OverlayRenderer.draw(cg: r.cgContext, size: pic, lms: landmarks, ready: ready,
                                 cue: cue, metrics: metrics, reps: reps)
        }
        guard let overlayCG = overlay.cgImage else { return }
        let composed = CIImage(cgImage: overlayCG).composited(over: ci)

        var out: CVPixelBuffer?
        guard let pool = adaptor.pixelBufferPool,
              CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out) == kCVReturnSuccess, let ob = out else { return }
        ciContext.render(composed, to: ob)
        if adaptor.append(ob, withPresentationTime: timestamp) { lastTime = timestamp; framesWritten += 1 }
    }

    /// Finalizes the file; `done` gets the number of frames written (0 = nothing usable).
    func finish(_ done: @escaping (Int) -> Void) {
        guard started, writer.status == .writing else { writer.cancelWriting(); done(0); return }
        input.markAsFinished()
        writer.endSession(atSourceTime: lastTime)
        let n = framesWritten
        writer.finishWriting { [writer] in done(writer.status == .completed ? n : 0) }
    }
}
