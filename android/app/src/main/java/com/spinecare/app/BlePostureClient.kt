package com.spinecare.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/** BLE client for the ESP32 posture nodes (see firmware/esp32_posture_node).
 *  Service 0xFFF0; notify 0xFFF1 (head IMU) / 0xFFF2 (trunk IMU); write 0xFFF3
 *  (0 = recalibrate neutral, 1 = zero yaw). Payload: 20 bytes little-endian
 *  seq u16, pitch/roll/yaw i16 (0.01 deg), accel i16 (mg) x3, gyro i16 (0.1 dps) x3.
 *
 *  Process-wide singleton so the Sensors tab connects and exercise sessions
 *  read the latest head/trunk orientation (sent to the server with each frame). */
object BlePostureClient {
    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHR_HEAD: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val CHR_TRUNK: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CHR_CTRL: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val SCAN_MS = 12_000L

    data class ImuPacket(val seq: Int, val pitch: Double, val roll: Double, val yaw: Double,
                         val ax: Int, val ay: Int, val az: Int, val gx: Int, val gy: Int, val gz: Int,
                         val atMs: Long = System.currentTimeMillis())

    interface Listener {
        fun onStatus(text: String) {}
        fun onDevice(name: String, address: String) {}
        fun onPacket(role: String, p: ImuPacket) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val gatts = HashMap<String, BluetoothGatt>()
    private val found = HashMap<String, BluetoothDevice>()
    @Volatile var head: ImuPacket? = null; private set
    @Volatile var trunk: ImuPacket? = null; private set
    private var scanning = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun status(t: String) = main.post { listeners.toList().forEach { it.onStatus(t) } }

    /** Permissions needed for scanning/connecting on this Android version. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(c: Context) = requiredPermissions().all {
        ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (found.put(d.address, d) == null) {
                val name = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: d.address
                main.post { listeners.toList().forEach { it.onDevice(name, d.address) } }
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; status(I18n.t("ble_scan_failed").format(errorCode)) }
    }

    @SuppressLint("MissingPermission")
    fun startScan(c: Context): Boolean {
        if (!hasPermissions(c)) { status(I18n.t("ble_need_perm")); return false }
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { status(I18n.t("ble_off")); return false }
        val scanner = adapter.bluetoothLeScanner ?: run { status(I18n.t("ble_off")); return false }
        if (scanning) return true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        scanning = true
        status(I18n.t("ble_scanning"))
        main.postDelayed({ stopScan(c) }, SCAN_MS)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan(c: Context) {
        if (!scanning) return
        scanning = false
        runCatching {
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner?.stopScan(scanCb)
        }
        status(I18n.t("ble_scan_done"))
    }

    @SuppressLint("MissingPermission")
    fun connect(c: Context, address: String) {
        if (!hasPermissions(c)) { status(I18n.t("ble_need_perm")); return }
        val dev = found[address] ?: return
        stopScan(c)
        gatts.remove(address)?.close()
        status(I18n.t("ble_connecting"))
        gatts[address] = dev.connectGatt(c.applicationContext, false, gattCb(), BluetoothDevice.TRANSPORT_LE)
    }

    /** Ask every connected node to recapture its neutral orientation. */
    @SuppressLint("MissingPermission")
    fun calibrateNeutral() {
        for (g in gatts.values) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHR_CTRL) ?: continue
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, byteArrayOf(0), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            else legacyWrite(g, ch, byteArrayOf(0))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        gatts.values.forEach { runCatching { it.disconnect(); it.close() } }
        gatts.clear(); head = null; trunk = null
    }

    val connectedCount get() = gatts.size

    /** Latest IMU snapshot (<1 s old) for the coaching server, or null. */
    fun snapshot(): JSONObject? {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        head?.takeIf { now - it.atMs < 1000 }?.let { o.put("head", it.toJson()) }
        trunk?.takeIf { now - it.atMs < 1000 }?.let { o.put("trunk", it.toJson()) }
        return if (o.length() == 0) null else o
    }

    private fun ImuPacket.toJson() = JSONObject().put("pitch", pitch).put("roll", roll).put("yaw", yaw)

    @SuppressLint("MissingPermission")
    private fun gattCb() = object : BluetoothGattCallback() {
        // GATT allows one outstanding operation: enable notifications one descriptor at a time.
        private val pending = ArrayDeque<BluetoothGattDescriptor>()

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status(I18n.t("ble_connected").format(runCatching { g.device.name }.getOrNull() ?: g.device.address))
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatts.remove(g.device.address); g.close()
                status(I18n.t("ble_disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SERVICE) ?: run { status(I18n.t("ble_no_service")); return }
            for (uid in listOf(CHR_HEAD, CHR_TRUNK)) {
                val ch = svc.getCharacteristic(uid) ?: continue
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { pending.add(it) }
            }
            writeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { writeNext(g) }

        private fun writeNext(g: BluetoothGatt) {
            val d = pending.removeFirstOrNull() ?: return
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, enable)
            else legacyWriteDescriptor(g, d, enable)
        }

        // API 33+ delivers the value directly; older versions use the deprecated overload.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handle(ch.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handle(ch.uuid, legacyValue(ch))
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray) {
        ch.value = v; g.writeCharacteristic(ch)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        d.value = v; g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun legacyValue(ch: BluetoothGattCharacteristic): ByteArray? = ch.value

    private fun handle(uuid: UUID, value: ByteArray?) {
        val p = parse(value) ?: return
        val role = when (uuid) { CHR_HEAD -> "head"; CHR_TRUNK -> "trunk"; else -> return }
        if (role == "head") head = p else trunk = p
        main.post { listeners.toList().forEach { it.onPacket(role, p) } }
    }

    fun parse(v: ByteArray?): ImuPacket? {
        if (v == null || v.size < 20) return null
        fun i16(o: Int) = (v[o + 1].toInt() shl 8) or (v[o].toInt() and 0xFF)
        return ImuPacket(
            seq = i16(0) and 0xFFFF,
            pitch = i16(2) / 100.0, roll = i16(4) / 100.0, yaw = i16(6) / 100.0,
            ax = i16(8), ay = i16(10), az = i16(12),
            gx = i16(14), gy = i16(16), gz = i16(18),
        )
    }
}
