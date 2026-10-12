package com.spinecare.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraCharacteristics
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.spinecare.app.I18n.t
import java.util.concurrent.Executor

/** Camera binding shared by the session and setup-check screens: front/back,
 *  lens choice (ultra-wide / wide / tele, see CameraOptions.lenses), zoom by
 *  pinch or quick stops, and use cases that follow the screen rotation, so the
 *  analysed frames are upright in portrait and landscape and the pose overlay
 *  lines up with the preview. Zoom crops preview and analysis alike. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraRig(
    private val activity: AppCompatActivity,
    private var previewView: PreviewView,
    private val executor: Executor,
    private val analyzer: (ImageProxy) -> Unit,
    /** Called on the main thread when the facing, lens or zoom changed. */
    private val onChanged: () -> Unit,
) {
    var front = true; private set
    var lenses: List<CameraOptions.Lens> = emptyList(); private set
    var lensIdx = 0; private set
    var zoom = 1f; private set
    var minZoom = 1f; private set
    var maxZoom = 1f; private set
    var bound = false; private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var infos: Map<String, CameraInfo> = emptyMap()
    private var descs: List<CameraOptions.CameraDesc> = emptyList()
    private var pendingZoom: Float? = null

    val lens: CameraOptions.Lens? get() = lenses.getOrNull(lensIdx)

    /** Starts the camera, restoring an earlier facing/lens/zoom (e.g. after a rotation). */
    fun start(front: Boolean, cameraId: String? = null, zoom: Float = Float.NaN) {
        this.front = front
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            describe(p)
            if (descs.none { it.front == this.front }) this.front = !this.front
            buildLenses(mainMinZoom = 1f)
            this.lensIdx = lenses.indexOfFirst { it.cameraId == cameraId }.takeIf { it >= 0 } ?: wideIndex()
            pendingZoom = zoom.takeIf { !it.isNaN() }
            bind()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun flip(): Boolean {
        if (descs.none { it.front == !front }) {
            Toast.makeText(activity, t("no_other_camera"), Toast.LENGTH_SHORT).show(); return false
        }
        front = !front
        buildLenses(mainMinZoom = 1f)
        lensIdx = wideIndex(); pendingZoom = null
        bind(); return true
    }

    /** Next lens on this side of the phone; false when there is only one. */
    fun nextLens(): Boolean {
        if (lenses.size < 2) { Toast.makeText(activity, t("lens_only_one"), Toast.LENGTH_SHORT).show(); return false }
        val old = lens
        lensIdx = CameraOptions.nextLens(lenses, lensIdx)
        val now = lens ?: return false
        pendingZoom = now.zoomRatio
        if (old?.cameraId == now.cameraId && camera != null) applyZoom(now.zoomRatio) else bind()
        return true
    }

    /** Steps through the quick zoom stops (1×, 2×, ...). */
    fun cycleZoom() = applyZoom(CameraOptions.nextZoomStop(zoom, minZoom, maxZoom))

    fun applyZoom(z: Float) {
        val cam = camera ?: return
        zoom = CameraOptions.clampZoom(z, minZoom, maxZoom)
        cam.cameraControl.setZoomRatio(zoom)
        syncLensToZoom()
        onChanged()
    }

    /** Pinch-to-zoom on the preview. */
    @SuppressLint("ClickableViewAccessibility")
    fun enablePinch() {
        val detector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { applyZoom(zoom * d.scaleFactor); return true }
        })
        previewView.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
    }

    /** Lens button text, e.g. "Lens: wide 1×". */
    fun lensLabel(): String {
        val l = lens ?: return t("lens") + ": —"
        return t("lens") + ": " + t(l.kind.key) + " " + CameraOptions.zoomLabel(l.relZoom)
    }

    fun zoomLabel(): String = "🔍 " + CameraOptions.zoomLabel(zoom)

    // ------------------------------------------------------------------ internals

    /** On a logical main camera, zooming below 1× is its ultra-wide lens (and back). */
    private fun syncLensToZoom() {
        val id = lens?.cameraId ?: return
        val i = CameraOptions.lensForZoom(lenses, id, zoom)
        if (i >= 0) lensIdx = i
    }

    private fun describe(p: ProcessCameraProvider) {
        val map = LinkedHashMap<String, CameraInfo>()
        val out = ArrayList<CameraOptions.CameraDesc>()
        for (info in p.availableCameraInfos) {
            val c2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull() ?: continue
            val facing = info.lensFacing
            if (facing != CameraSelector.LENS_FACING_FRONT && facing != CameraSelector.LENS_FACING_BACK) continue
            val focal = runCatching { c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() }
                .getOrNull() ?: 0f
            val sensor = runCatching { c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width }
                .getOrNull() ?: 0f
            map[c2.cameraId] = info
            out.add(CameraOptions.CameraDesc(c2.cameraId, facing == CameraSelector.LENS_FACING_FRONT, focal, sensor))
        }
        infos = map; descs = out
    }

    private fun mainId(): String? {
        val p = provider ?: return null
        val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val info = runCatching { sel.filter(p.availableCameraInfos).firstOrNull() }.getOrNull() ?: return null
        return runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
    }

    private fun buildLenses(mainMinZoom: Float) {
        lenses = CameraOptions.lenses(descs, front, mainId(), mainMinZoom)
    }

    private fun wideIndex() = lenses.indexOfFirst { it.kind == CameraOptions.LensKind.WIDE && it.zoomRatio == 1f }.coerceAtLeast(0)

    private fun selector(): CameraSelector {
        val id = lens?.cameraId
        if (id == null || id !in infos) return if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder().addCameraFilter { list ->
            list.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == id }
        }.build()
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = previewView.display?.rotation ?: activity.windowManager.defaultDisplay.rotation

    fun bind() {
        val p = provider ?: return
        val rotation = displayRotation()
        val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { it.setAnalyzer(executor, analyzer) }
        try {
            p.unbindAll()
            val cam = p.bindToLifecycle(activity, selector(), preview, analysis)
            camera = cam; bound = true
            val zs = cam.cameraInfo.zoomState.value
            minZoom = zs?.minZoomRatio ?: 1f
            maxZoom = zs?.maxZoomRatio ?: 1f
            // a logical main camera can reach its ultra-wide through a zoom below 1×
            val current = lens
            if (current != null && current.cameraId == mainId() && minZoom < 0.95f &&
                lenses.none { it.kind == CameraOptions.LensKind.ULTRA_WIDE }) {
                buildLenses(minZoom)
                lensIdx = lenses.indexOfFirst { it.cameraId == current.cameraId && it.zoomRatio == 1f }.coerceAtLeast(0)
            }
            val z = pendingZoom ?: lens?.zoomRatio ?: 1f
            pendingZoom = null
            applyZoom(z)
        } catch (e: Exception) {
            bound = false
            Toast.makeText(activity, t("no_camera"), Toast.LENGTH_LONG).show()
        }
        onChanged()
    }

    companion object {
        /** Activity orientation for a CameraOptions.ORIENT_* mode. */
        fun requestedOrientation(mode: Int) = when (mode) {
            CameraOptions.ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            CameraOptions.ORIENT_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}
