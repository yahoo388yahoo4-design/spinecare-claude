package com.spinecare.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.spinecare.app.I18n.t
import kotlinx.coroutines.launch

/** Sensors & sleep: ESP32 posture nodes over BLE (scan, connect, live
 *  orientation), Health Connect import, and the sleep-setup experiment. */
class SensorsFragment : Fragment() {
    private var status: TextView? = null
    private var live: TextView? = null
    private var devices: LinearLayout? = null
    private lateinit var ui: Ui

    private val blePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) BlePostureClient.startScan(requireContext())
        else status?.text = t("ble_need_perm")
    }

    private val hcPerms = registerForActivityResult(HealthConnectHelper.permissionContract()) { _ -> refreshHealth() }
    private var health: TextView? = null

    private val bleListener = object : BlePostureClient.Listener {
        override fun onStatus(text: String) { status?.text = text }
        override fun onDevice(name: String, address: String) {
            val box = devices ?: return
            ui.button(t("ble_connect_to").format(name), box) { BlePostureClient.connect(requireContext(), address) }
        }
        override fun onPacket(role: String, p: BlePostureClient.ImuPacket) {
            val h = BlePostureClient.head?.let { x -> t("ble_head").format(x.pitch, x.roll, x.yaw) }
            val tr = BlePostureClient.trunk?.let { x -> t("ble_trunk").format(x.pitch, x.roll, x.yaw) }
            live?.text = listOfNotNull(h, tr).joinToString("\n")
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        ui = Ui(ctx)

        ui.h(t("ble_title"))
        ui.p(t("ble_desc"))
        ui.muted(t("ble_protocol"))
        status = ui.p(if (BlePostureClient.connectedCount > 0) t("ble_connected").format(BlePostureClient.connectedCount.toString()) else "", Ui.MUTED, 13f)
        val row = ui.row()
        ui.button(t("ble_scan"), row) {
            if (BlePostureClient.hasPermissions(ctx)) BlePostureClient.startScan(ctx)
            else blePerms.launch(BlePostureClient.requiredPermissions())
        }
        ui.button(t("ble_calibrate"), row) { BlePostureClient.calibrateNeutral() }
        ui.button(t("ble_disconnect"), row) { BlePostureClient.disconnectAll(); status?.text = t("ble_disconnected") }
        devices = ui.add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL })
        live = ui.p("", Ui.OK, 13f)

        ui.h(t("phone_sensors"))
        ui.p("• " + t("sensor_camera"))
        ui.p("• " + t("sensor_imu"))
        ui.p("• " + t("sensor_audio"))

        ui.h(t("hc_title"))
        health = ui.p("", Ui.MUTED, 13f)
        val hc = HealthConnectHelper(ctx)
        if (hc.isAvailable()) ui.button(t("hc_connect")) {
            runCatching { hcPerms.launch(HealthConnectHelper.PERMISSIONS) }.onFailure { health?.text = t("hc_unavailable") }
        } else health?.text = t("hc_unavailable")

        ui.h(t("sleep_title"))
        for (k in 1..6) ui.p("$k. " + t("sleep_$k"), size = 13f)
        ui.h(t("sleep_tracking"))
        for (k in 1..3) ui.p("• " + t("sleep_track_$k"), size = 13f)
        ui.muted(t("sleep_honest"))

        ui.h(t("can_cannot"))
        ui.p(t("can"))
        ui.warn(t("cannot"))
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { refreshHealth() }

    private fun refreshHealth() {
        val ctx = context ?: return
        if (view == null) return
        val hc = HealthConnectHelper(ctx)
        if (!hc.isAvailable()) return
        viewLifecycleOwner.lifecycleScope.launch {
            health?.text = if (!hc.hasPermissions()) t("hc_not_connected")
            else hc.readLastDay()?.let { t("hc_stats").format(it.steps, it.sleepMinutes / 60, it.sleepMinutes % 60) } ?: t("hc_unavailable")
        }
    }

    override fun onStart() { super.onStart(); BlePostureClient.addListener(bleListener) }
    override fun onStop() { super.onStop(); BlePostureClient.removeListener(bleListener) }
    override fun onDestroyView() { super.onDestroyView(); status = null; live = null; devices = null; health = null }
}
