import AVFoundation
import UIKit

/// Camera-screen choices (mirrors Android CameraOptions): screen orientation,
/// lens (ultra-wide / wide / tele) and zoom stops.
enum CameraOptions {
    enum Orientation: Int, CaseIterable {
        case auto = 0, portrait = 1, landscape = 2

        var key: String {
            switch self {
            case .auto: return "orient_auto"
            case .portrait: return "orient_portrait"
            case .landscape: return "orient_landscape"
            }
        }

        var next: Orientation { Orientation(rawValue: (rawValue + 1) % 3) ?? .auto }

        var mask: UIInterfaceOrientationMask {
            switch self {
            case .auto: return .allButUpsideDown
            case .portrait: return .portrait
            case .landscape: return .landscape
            }
        }
    }

    /// The user's choice for camera screens, kept on the device.
    static var orientation: Orientation {
        get { Orientation(rawValue: UserDefaults.standard.integer(forKey: "camera_orientation")) ?? .auto }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "camera_orientation") }
    }

    enum LensKind: String {
        case ultraWide, wide, tele
        var key: String {
            switch self {
            case .ultraWide: return "lens_ultra_wide"
            case .wide: return "lens_wide"
            case .tele: return "lens_tele"
            }
        }
    }

    /// One physical lens on a side of the phone. `relZoom` is its field of view
    /// relative to the wide camera (0.5 = twice as wide).
    struct Lens: Equatable {
        let uniqueID: String
        let kind: LensKind
        let relZoom: CGFloat
    }

    static func kind(for type: AVCaptureDevice.DeviceType) -> LensKind {
        switch type {
        case .builtInUltraWideCamera: return .ultraWide
        case .builtInTelephotoCamera: return .tele
        default: return .wide
        }
    }

    /// Field of view of `fov` (degrees) relative to `wideFov`: tan(wide/2) / tan(fov/2).
    static func relZoom(fov: Double, wideFov: Double) -> CGFloat {
        guard fov > 0, wideFov > 0 else { return 1 }
        return CGFloat(tan(wideFov * .pi / 360) / tan(fov * .pi / 360))
    }

    /// Quick zoom stops for the zoom button, within the camera's range.
    static func zoomStops(min lo: CGFloat, max hi: CGFloat) -> [CGFloat] {
        let stops = [1, 2, 3, 5, 10].map { CGFloat($0) }.filter { $0 >= lo - 0.01 && $0 <= hi + 0.01 }
        return stops.isEmpty ? [lo] : stops
    }

    static func nextZoomStop(_ current: CGFloat, min lo: CGFloat, max hi: CGFloat) -> CGFloat {
        let stops = zoomStops(min: lo, max: hi)
        return stops.first { $0 > current + 0.05 } ?? stops[0]
    }

    /// "0.5×", "1×", "2.5×".
    static func zoomLabel(_ z: CGFloat) -> String {
        let r = Int((z * 10).rounded())
        return r % 10 == 0 ? "\(r / 10)×" : "\(r / 10).\(abs(r % 10))×"
    }

    /// Clockwise rotation of the screen from portrait, in degrees.
    static func rotationDegrees(_ o: UIInterfaceOrientation) -> Int {
        switch o {
        case .landscapeRight: return 90
        case .portraitUpsideDown: return 180
        case .landscapeLeft: return 270
        default: return 0
        }
    }

    /// Capture rotation that makes frames upright for this screen orientation.
    static func videoRotationAngle(_ o: UIInterfaceOrientation) -> CGFloat {
        switch o {
        case .landscapeRight: return 0
        case .landscapeLeft: return 180
        case .portraitUpsideDown: return 270
        default: return 90
        }
    }

    static func videoOrientation(_ o: UIInterfaceOrientation) -> AVCaptureVideoOrientation {
        switch o {
        case .landscapeRight: return .landscapeRight
        case .landscapeLeft: return .landscapeLeft
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    /// Gravity (device axes) in screen axes, so the placement tilt check also works in landscape.
    static func screenGravity(gx: Double, gy: Double, rotationDeg: Int) -> (Double, Double) {
        switch ((rotationDeg % 360) + 360) % 360 {
        case 90: return (-gy, gx)
        case 180: return (-gx, -gy)
        case 270: return (gy, -gx)
        default: return (gx, gy)
        }
    }
}

/// Which orientations the app allows right now (read by AppDelegate). Camera
/// screens widen it to the user's choice and hold one orientation while a
/// session runs; everything else stays portrait.
enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .portrait

    static var scene: UIWindowScene? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    }

    static var current: UIInterfaceOrientation { scene?.interfaceOrientation ?? .portrait }

    static func set(_ m: UIInterfaceOrientationMask) {
        guard m != mask else { return }
        mask = m
        guard let scene else { return }
        for w in scene.windows { w.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: m)) { _ in }
    }

    // Counted, because a pushed camera screen can appear before the one it
    // replaces disappears.
    private static var cameraScreens = 0
    private static var holding = false

    static func enterCameraScreen() { cameraScreens += 1; refresh() }
    static func leaveCameraScreen() { cameraScreens = max(0, cameraScreens - 1); refresh() }

    /// Hold the current orientation while a session runs, so calibration and the
    /// recording keep one frame shape.
    static func hold(_ on: Bool) { holding = on; refresh() }

    /// Re-applies the rules after the user changed their choice.
    static func refresh() {
        if cameraScreens == 0 { holding = false; set(.portrait); return }
        guard holding else { set(CameraOptions.orientation.mask); return }
        switch current {
        case .landscapeLeft: set(.landscapeLeft)
        case .landscapeRight: set(.landscapeRight)
        default: set(.portrait)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}
