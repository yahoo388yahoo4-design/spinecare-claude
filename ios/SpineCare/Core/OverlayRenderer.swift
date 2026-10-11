import SwiftUI
import UIKit

/// Shared skeleton + HUD drawing for the live overlay AND the session recorder,
/// so saved videos show what the user saw. Landmarks are normalized 0...1
/// (y top-down) in an upright frame of `frame` size; they are mapped with the
/// same aspect-fill crop the camera preview uses.
enum OverlayRenderer {
    private static let conns: [(String, String)] = [
        ("lsho", "rsho"), ("lsho", "lhip"), ("rsho", "rhip"), ("lhip", "rhip"),
        ("lsho", "lelb"), ("rsho", "relb"), ("lelb", "lwri"), ("relb", "rwri"),
        ("lhip", "lknee"), ("rhip", "rknee"), ("lknee", "lank"), ("rknee", "rank"),
    ]

    static func mapper(size: CGSize, frame: CGSize) -> (Metrics.LM) -> CGPoint {
        guard frame.width > 0, frame.height > 0 else {
            return { CGPoint(x: $0.x * size.width, y: $0.y * size.height) }
        }
        let s = max(size.width / frame.width, size.height / frame.height)
        let ox = (size.width - frame.width * s) / 2, oy = (size.height - frame.height * s) / 2
        return { CGPoint(x: ox + $0.x * frame.width * s, y: oy + $0.y * frame.height * s) }
    }

    private static func neckPair(_ lms: [String: Metrics.LM]) -> (Metrics.LM, Metrics.LM)? {
        guard let side = Metrics.bestSide(lms), let ear = lms[side + "ear"], let sho = lms[side + "sho"] else { return nil }
        return (ear, sho)
    }

    // MARK: CoreGraphics path (recorder; context is y-down, frame-sized)

    static func draw(cg: CGContext, size: CGSize, lms: [String: Metrics.LM], ready: Bool,
                     cue: String, metrics: String, reps: String) {
        let pt = mapper(size: size, frame: size)
        let lineColor = (ready ? UIColor.systemGreen : UIColor.systemYellow).cgColor
        cg.setLineWidth(3); cg.setStrokeColor(lineColor)
        for (a, b) in conns {
            guard let la = lms[a], let lb = lms[b], la.conf > Metrics.vis, lb.conf > Metrics.vis else { continue }
            cg.move(to: pt(la)); cg.addLine(to: pt(lb)); cg.strokePath()
        }
        if let pair = neckPair(lms) {
            let (ear, sho) = pair
            cg.setLineWidth(5); cg.setStrokeColor(UIColor.systemTeal.cgColor)
            cg.move(to: pt(ear)); cg.addLine(to: pt(sho)); cg.strokePath()
        }
        cg.setFillColor(lineColor)
        for lm in lms.values where lm.conf > Metrics.vis {
            let p = pt(lm); cg.fillEllipse(in: CGRect(x: p.x - 3.5, y: p.y - 3.5, width: 7, height: 7))
        }
        if !reps.isEmpty {
            let s = NSAttributedString(string: reps, attributes: [
                .font: UIFont.systemFont(ofSize: 40, weight: .bold), .foregroundColor: UIColor.systemGreen])
            s.draw(at: CGPoint(x: size.width - s.size().width - 20, y: 20))
        }
        if !cue.isEmpty || !metrics.isEmpty {
            let block = NSMutableAttributedString()
            if !cue.isEmpty {
                block.append(NSAttributedString(string: cue + "\n", attributes: [
                    .font: UIFont.systemFont(ofSize: 15, weight: .semibold), .foregroundColor: UIColor.systemYellow]))
            }
            if !metrics.isEmpty {
                block.append(NSAttributedString(string: metrics, attributes: [
                    .font: UIFont.monospacedSystemFont(ofSize: 11, weight: .regular), .foregroundColor: UIColor.white]))
            }
            let rect = block.boundingRect(with: CGSize(width: size.width - 24, height: .greatestFiniteMagnitude),
                                          options: [.usesLineFragmentOrigin], context: nil)
            let bgH = rect.height + 16
            cg.setFillColor(UIColor.black.withAlphaComponent(0.75).cgColor)
            cg.fill(CGRect(x: 0, y: size.height - bgH, width: size.width, height: bgH))
            UIGraphicsPushContext(cg)
            block.draw(with: CGRect(x: 12, y: size.height - bgH + 8, width: size.width - 24, height: rect.height),
                       options: [.usesLineFragmentOrigin], context: nil)
            UIGraphicsPopContext()
        }
    }

    // MARK: SwiftUI Canvas path (live preview; text HUD is drawn by SwiftUI views)

    static func draw(ctx: inout GraphicsContext, size: CGSize, frame: CGSize, lms: [String: Metrics.LM],
                     ready: Bool, missing: Set<String>) {
        let pt = mapper(size: size, frame: frame)
        let lineColor = ready ? Color.green : Color.yellow
        for (a, b) in conns {
            guard let la = lms[a], let lb = lms[b], la.conf > Metrics.vis, lb.conf > Metrics.vis else { continue }
            var path = Path(); path.move(to: pt(la)); path.addLine(to: pt(lb))
            ctx.stroke(path, with: .color(lineColor), lineWidth: 3)
        }
        if let pair = neckPair(lms) {
            let (ear, sho) = pair
            var path = Path(); path.move(to: pt(ear)); path.addLine(to: pt(sho))
            ctx.stroke(path, with: .color(.teal), lineWidth: 5)
        }
        for (name, lm) in lms where lm.conf > Metrics.vis || missing.contains(name) {
            let p = pt(lm), r: CGFloat = missing.contains(name) ? 10 : 3.5
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)),
                     with: .color(missing.contains(name) ? .red : lineColor))
        }
    }
}
