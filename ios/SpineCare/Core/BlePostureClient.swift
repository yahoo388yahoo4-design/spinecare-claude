import CoreBluetooth
import Foundation

/// CoreBluetooth client for the ESP32 posture nodes (firmware/esp32_posture_node),
/// mirroring Android BlePostureClient. Service 0xFFF0; notify 0xFFF1 (head) /
/// 0xFFF2 (trunk); write 0xFFF3 (0 = recalibrate neutral). Payload: 20 bytes LE
/// seq u16, pitch/roll/yaw i16 (0.01 deg), accel i16 (mg) x3, gyro i16 (0.1 dps) x3.
final class BlePostureClient: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let shared = BlePostureClient()

    static let service = CBUUID(string: "FFF0")
    static let head = CBUUID(string: "FFF1")
    static let trunk = CBUUID(string: "FFF2")
    static let ctrl = CBUUID(string: "FFF3")

    struct Packet { let seq: Int; let pitch: Double; let roll: Double; let yaw: Double; let at: Date }

    @Published var status = ""
    @Published var found: [CBPeripheral] = []
    @Published var headPacket: Packet?
    @Published var trunkPacket: Packet?
    @Published var connectedCount = 0

    private var central: CBCentralManager?
    private var connected: [UUID: CBPeripheral] = [:]
    private let lock = NSLock()
    private var latestHead: Packet?
    private var latestTrunk: Packet?

    /// Creating the manager triggers the Bluetooth permission prompt, so it is lazy.
    func scan() {
        if central == nil { central = CBCentralManager(delegate: self, queue: nil) }
        else if central?.state == .poweredOn { startScan() }
    }

    private func startScan() {
        status = L.t("ble_scanning")
        central?.scanForPeripherals(withServices: [Self.service])
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self, self.central?.isScanning == true else { return }
            self.central?.stopScan(); self.status = L.t("ble_scan_done")
        }
    }

    func connect(_ p: CBPeripheral) {
        central?.stopScan()
        status = L.t("ble_connecting")
        connected[p.identifier] = p
        central?.connect(p)
    }

    func disconnectAll() {
        for p in connected.values { central?.cancelPeripheralConnection(p) }
        connected = [:]; connectedCount = 0
        lock.lock(); latestHead = nil; latestTrunk = nil; lock.unlock()
        headPacket = nil; trunkPacket = nil
        status = L.t("ble_disconnected")
    }

    func calibrateNeutral() {
        for p in connected.values {
            guard let ch = p.services?.first(where: { $0.uuid == Self.service })?
                .characteristics?.first(where: { $0.uuid == Self.ctrl }) else { continue }
            p.writeValue(Data([0]), for: ch, type: .withResponse)
        }
    }

    /// Latest orientation (<1 s old) for the coaching server; thread-safe.
    func snapshot() -> [String: Any]? {
        lock.lock(); let h = latestHead, t = latestTrunk; lock.unlock()
        var o: [String: Any] = [:]
        let now = Date()
        if let h, now.timeIntervalSince(h.at) < 1 { o["head"] = ["pitch": h.pitch, "roll": h.roll, "yaw": h.yaw] }
        if let t, now.timeIntervalSince(t.at) < 1 { o["trunk"] = ["pitch": t.pitch, "roll": t.roll, "yaw": t.yaw] }
        return o.isEmpty ? nil : o
    }

    static func parse(_ d: Data) -> Packet? {
        guard d.count >= 20 else { return nil }
        let b = [UInt8](d)
        func i16(_ o: Int) -> Int { Int(Int16(bitPattern: UInt16(b[o]) | (UInt16(b[o + 1]) << 8))) }
        return Packet(seq: i16(0) & 0xFFFF, pitch: Double(i16(2)) / 100, roll: Double(i16(4)) / 100,
                      yaw: Double(i16(6)) / 100, at: Date())
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn: startScan()
        case .unauthorized: status = L.t("ble_need_perm")
        default: status = L.t("ble_off")
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        if !found.contains(where: { $0.identifier == p.identifier }) { found.append(p) }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        connectedCount = connected.count
        status = L.f("ble_connected", p.name ?? p.identifier.uuidString)
        p.delegate = self
        p.discoverServices([Self.service])
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        connected[p.identifier] = nil; connectedCount = connected.count
        status = L.t("ble_disconnected")
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        connected[p.identifier] = nil; connectedCount = connected.count
        status = L.t("ble_disconnected")
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == Self.service }) else { status = L.t("ble_no_service"); return }
        p.discoverCharacteristics([Self.head, Self.trunk, Self.ctrl], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for ch in s.characteristics ?? [] where ch.uuid == Self.head || ch.uuid == Self.trunk {
            p.setNotifyValue(true, for: ch)   // CoreBluetooth queues these itself
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let v = ch.value, let pkt = Self.parse(v) else { return }
        lock.lock()
        if ch.uuid == Self.head { latestHead = pkt } else { latestTrunk = pkt }
        lock.unlock()
        if ch.uuid == Self.head { headPacket = pkt } else { trunkPacket = pkt }
    }
}
