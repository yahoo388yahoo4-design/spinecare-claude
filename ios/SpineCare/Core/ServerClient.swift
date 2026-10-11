import Foundation
import Security

/// Coaching-server settings, mirroring Android ServerConfig. Offline-first:
/// with no URL (the default) or in on-device mode the network is never used.
/// The password is stored in the Keychain.
final class ServerConfig: ObservableObject {
    static let shared = ServerConfig()

    enum Mode: Int { case auto = 0, onDevice = 1, server = 2 }
    enum Stream: Int { case landmarks = 0, frames = 1 }

    @Published var url: String
    @Published var user: String
    @Published var pass: String
    @Published var mode: Mode
    @Published var stream: Stream
    @Published var allowInsecureLan: Bool
    @Published var frameIntervalMs: Int
    @Published var serverFrameIntervalMs: Int
    @Published var minConfidence: Double
    @Published var haptics: Bool
    @Published var audioCues: Bool
    @Published var recordSessions: Bool
    @Published var voiceControl: Bool

    private let d = UserDefaults.standard
    private init() {
        url = d.string(forKey: "server_url") ?? ""
        user = d.string(forKey: "server_user") ?? ""
        // migrate a password saved in UserDefaults by earlier builds
        if let old = d.string(forKey: "server_pass") { Keychain.set(old, for: "server_pass"); d.removeObject(forKey: "server_pass") }
        pass = Keychain.get("server_pass") ?? ""
        mode = Mode(rawValue: d.integer(forKey: "mode")) ?? .auto
        stream = Stream(rawValue: d.integer(forKey: "stream_mode")) ?? .landmarks
        allowInsecureLan = d.bool(forKey: "allow_insecure_lan")
        frameIntervalMs = d.object(forKey: "frame_ms") as? Int ?? 150
        serverFrameIntervalMs = d.object(forKey: "server_frame_ms") as? Int ?? 150
        minConfidence = d.object(forKey: "min_conf") as? Double ?? 0.5
        haptics = d.object(forKey: "haptics") as? Bool ?? true
        audioCues = d.object(forKey: "audio_cues") as? Bool ?? false
        recordSessions = d.object(forKey: "record_sessions") as? Bool ?? false
        voiceControl = d.object(forKey: "voice_control") as? Bool ?? false
    }

    var baseURL: String { url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    func save() {
        d.set(baseURL, forKey: "server_url"); d.set(user, forKey: "server_user")
        Keychain.set(pass, for: "server_pass")
        d.set(mode.rawValue, forKey: "mode"); d.set(stream.rawValue, forKey: "stream_mode")
        d.set(allowInsecureLan, forKey: "allow_insecure_lan")
        d.set(min(2000, max(33, frameIntervalMs)), forKey: "frame_ms")
        d.set(min(2000, max(50, serverFrameIntervalMs)), forKey: "server_frame_ms")
        d.set(minConfidence, forKey: "min_conf"); d.set(haptics, forKey: "haptics")
        d.set(audioCues, forKey: "audio_cues"); d.set(recordSessions, forKey: "record_sessions")
        d.set(voiceControl, forKey: "voice_control")
    }

    /// nil when the server may be used, otherwise a string key explaining why not.
    static func urlProblem(_ url: String, allowInsecureLan: Bool) -> String? {
        if url.isEmpty { return "srv_no_url" }
        guard let u = URLComponents(string: url), let host = u.host, !host.isEmpty else { return "srv_bad_url" }
        switch u.scheme?.lowercased() {
        case "https": return nil
        case "http": return !allowInsecureLan ? "srv_http_disabled" : (isPrivateLanHost(host) ? nil : "srv_http_public")
        default: return "srv_bad_url"
        }
    }

    static func isPrivateLanHost(_ hostIn: String) -> Bool {
        let h = hostIn.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "localhost" || h.hasSuffix(".local") || h == "::1" { return true }
        let patterns = [#"^10\."#, #"^192\.168\."#, #"^172\.(1[6-9]|2\d|3[01])\."#, #"^169\.254\."#,
                        #"^f[cd][0-9a-f:]*$"#, #"^fe[89ab][0-9a-f:]*$"#]
        return patterns.contains { h.range(of: $0, options: .regularExpression) != nil }
    }

    var serverUsable: Bool { mode != .onDevice && Self.urlProblem(baseURL, allowInsecureLan: allowInsecureLan) == nil }

    var authHeader: String? {
        user.isEmpty ? nil : "Basic " + Data("\(user):\(pass)".utf8).base64EncodedString()
    }

    func testConnection(_ done: @escaping (String) -> Void) {
        if let problem = Self.urlProblem(baseURL, allowInsecureLan: allowInsecureLan) { done(L.t(problem)); return }
        guard let u = URL(string: baseURL + "/health") else { done(L.t("srv_bad_url")); return }
        var req = URLRequest(url: u, timeoutInterval: 5)
        if let a = authHeader { req.setValue(a, forHTTPHeaderField: "Authorization") }
        URLSession.shared.dataTask(with: req) { data, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            let text: String
            if err != nil { text = L.t("server_offline") }
            else if code == 200 { text = L.t("server_online") + " — " + String(body.prefix(120)) }
            else if code == 401 { text = L.t("server_auth_failed") }
            else { text = "HTTP \(code)" }
            DispatchQueue.main.async { done(text) }
        }.resume()
    }
}

/// Minimal Keychain wrapper for the server password.
enum Keychain {
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.spinecare.app",
         kSecAttrAccount as String: key]
    }
    static func set(_ value: String, for key: String) {
        SecItemDelete(query(key) as CFDictionary)
        guard !value.isEmpty else { return }
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
    static func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}

/// WebSocket streaming client (URLSessionWebSocketTask). Sends landmarks
/// (privacy default) or JPEG frames; the session keeps coaching on-device
/// whenever this is not connected.
final class ServerClient {
    private var task: URLSessionWebSocketTask?
    private let lock = NSLock()
    private var _connected = false
    private var _lastPoseAt: TimeInterval = 0
    var connected: Bool { lock.lock(); defer { lock.unlock() }; return _connected }
    var lastPoseAt: TimeInterval { lock.lock(); defer { lock.unlock() }; return _lastPoseAt }

    func connect(_ base: String, auth: String?, onResult: @escaping ([String: Any]) -> Void) {
        disconnect()
        let wsBase: String
        if base.hasPrefix("https://") { wsBase = "wss://" + base.dropFirst(8) }
        else if base.hasPrefix("http://") { wsBase = "ws://" + base.dropFirst(7) }
        else { wsBase = base }
        guard let ws = URL(string: wsBase + "/ws/session") else { return }
        var req = URLRequest(url: ws)
        if let auth { req.setValue(auth, forHTTPHeaderField: "Authorization") }
        let t = URLSession.shared.webSocketTask(with: req)
        task = t
        t.resume()
        t.sendPing { [weak self] err in
            guard let self else { return }
            self.lock.lock(); self._connected = (err == nil && self.task === t); self.lock.unlock()
        }
        receiveLoop(t, onResult)
    }

    private func receiveLoop(_ t: URLSessionWebSocketTask, _ onResult: @escaping ([String: Any]) -> Void) {
        t.receive { [weak self] result in
            guard let self, self.task === t else { return }
            switch result {
            case .success(let msg):
                if case .string(let s) = msg, let data = s.data(using: .utf8),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    self.lock.lock()
                    self._connected = true
                    if obj["type"] as? String == "pose" { self._lastPoseAt = Date().timeIntervalSince1970 }
                    self.lock.unlock()
                    onResult(obj)
                }
                self.receiveLoop(t, onResult)
            case .failure:
                // stop here: retrying a failed socket in a loop would spin the CPU
                self.lock.lock(); self._connected = false; self.lock.unlock()
            }
        }
    }

    func send(_ obj: [String: Any]) {
        guard let t = task, let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return }
        t.send(.string(s)) { _ in }
    }

    func startSession(_ exerciseId: String, lang: String) { send(["type": "session", "exercise_id": exerciseId, "lang": lang]) }
    func recalibrate() { send(["type": "calibrate"]) }
    func endSession() { send(["type": "end_session"]) }

    /// [normalized] are 0...1 image coordinates; [w]x[h] is the frame size in pixels.
    func sendLandmarks(_ normalized: [String: Metrics.LM], w: Int, h: Int, tMs: Int, imu: [String: Any]?) {
        var named: [String: Any] = [:]
        for (k, v) in normalized { named[k] = ["x": v.x, "y": v.y, "visibility": v.conf] }
        var msg: [String: Any] = ["type": "landmarks", "format": "named", "t": tMs, "w": w, "h": h, "landmarks": named]
        if let imu { msg["imu"] = imu }
        send(msg)
    }

    func sendFrame(jpeg: Data, tMs: Int, imu: [String: Any]?) {
        var msg: [String: Any] = ["type": "frame", "t": tMs, "img": jpeg.base64EncodedString()]
        if let imu { msg["imu"] = imu }
        send(msg)
    }

    func disconnect() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        lock.lock(); _connected = false; lock.unlock()
    }
}
