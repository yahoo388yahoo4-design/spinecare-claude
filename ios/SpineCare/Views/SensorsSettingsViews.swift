import SwiftUI

/// Sensors & sleep: ESP32 posture nodes over BLE, sleep-setup experiment,
/// and what the sensors can/cannot measure.
struct SensorsView: View {
    @ObservedObject var ble = BlePostureClient.shared

    var body: some View {
        NavigationStack {
            List {
                Section(L.t("ble_title")) {
                    Text(L.t("ble_desc")).font(.callout)
                    Text(L.t("ble_protocol")).font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Button(L.t("ble_scan")) { ble.scan() }.buttonStyle(.bordered)
                        Button(L.t("ble_calibrate")) { ble.calibrateNeutral() }.buttonStyle(.bordered)
                        Button(L.t("ble_disconnect")) { ble.disconnectAll() }.buttonStyle(.bordered)
                    }
                    if !ble.status.isEmpty { Text(ble.status).font(.caption).foregroundStyle(.secondary) }
                    ForEach(ble.found, id: \.identifier) { p in
                        Button(L.f("ble_connect_to", p.name ?? p.identifier.uuidString)) { ble.connect(p) }
                    }
                    if let h = ble.headPacket { Text(L.f("ble_head", h.pitch, h.roll, h.yaw)).font(.caption).foregroundStyle(.green) }
                    if let t = ble.trunkPacket { Text(L.f("ble_trunk", t.pitch, t.roll, t.yaw)).font(.caption).foregroundStyle(.green) }
                }
                Section(L.t("phone_sensors")) {
                    Label(L.t("sensor_camera"), systemImage: "camera")
                    Label(L.t("sensor_imu"), systemImage: "sensor")
                    Label(L.t("sensor_audio"), systemImage: "speaker.wave.2")
                }
                Section(L.t("sleep_title")) {
                    ForEach(1...6, id: \.self) { i in Text("\(i). " + L.t("sleep_\(i)")).font(.callout) }
                }
                Section(L.t("sleep_tracking")) {
                    ForEach(1...3, id: \.self) { i in Text("• " + L.t("sleep_track_\(i)")).font(.callout) }
                    Text(L.t("sleep_honest")).font(.caption).foregroundStyle(.secondary)
                }
                Section(L.t("can_cannot")) {
                    Text(L.t("can"))
                    Text(L.t("cannot")).foregroundStyle(.red)
                }
            }
            .navigationTitle(L.t("tab_sensors"))
        }
    }
}

struct SettingsView: View {
    @ObservedObject var cfg = ServerConfig.shared
    @State private var status = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button(L.t("lang_switch")) { L.toggle() }
                    Text(L.t("local_status")).font(.caption)
                }
                Section(L.t("mode_title")) {
                    Picker(L.t("mode_title"), selection: $cfg.mode) {
                        Text(L.t("mode_auto")).tag(ServerConfig.Mode.auto)
                        Text(L.t("mode_device")).tag(ServerConfig.Mode.onDevice)
                        Text(L.t("mode_server")).tag(ServerConfig.Mode.server)
                    }.pickerStyle(.inline).labelsHidden()
                }
                Section(L.t("server_title")) {
                    TextField("https://192.168.1.20:8300", text: $cfg.url)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField(L.t("server_user"), text: $cfg.user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(L.t("server_pass"), text: $cfg.pass)
                    Picker(L.t("stream_title"), selection: $cfg.stream) {
                        Text(L.t("stream_landmarks")).tag(ServerConfig.Stream.landmarks)
                        Text(L.t("stream_frames")).tag(ServerConfig.Stream.frames)
                    }.pickerStyle(.inline)
                    Toggle(L.t("allow_http"), isOn: $cfg.allowInsecureLan)
                    Text(L.t("http_warning")).font(.caption).foregroundStyle(.orange)
                    Text(L.t("remote_privacy")).font(.caption)
                    Button(L.t("test_server")) {
                        cfg.save()
                        status = L.f("testing", cfg.baseURL)
                        cfg.testConnection { status = $0 }
                    }
                    Text(status.isEmpty ? L.t("server_unknown") : status).font(.caption).foregroundStyle(.secondary)
                }
                Section(L.t("tracking_title")) {
                    Stepper("\(L.t("frame_ms")): \(cfg.frameIntervalMs)", value: $cfg.frameIntervalMs, in: 50...1000, step: 25)
                    Stepper("\(L.t("server_frame_ms")): \(cfg.serverFrameIntervalMs)", value: $cfg.serverFrameIntervalMs, in: 50...1000, step: 25)
                    VStack(alignment: .leading) {
                        Text("\(L.t("min_conf")): \(cfg.minConfidence, specifier: "%.2f")")
                        Slider(value: $cfg.minConfidence, in: 0.1...0.9)
                    }
                }
                Section(L.t("feedback_title")) {
                    Toggle(L.t("haptics"), isOn: $cfg.haptics)
                    Toggle(L.t("audio"), isOn: $cfg.audioCues)
                    Toggle(L.t("record"), isOn: $cfg.recordSessions)
                    Text(L.t("record_privacy")).font(.caption)
                }
                Section {
                    Button(L.t("save_settings")) { cfg.save(); status = L.t("settings_saved") }
                    Text(L.t("mediapipe_metrics")).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L.t("settings"))
            .onDisappear { cfg.save() }
        }
    }
}
