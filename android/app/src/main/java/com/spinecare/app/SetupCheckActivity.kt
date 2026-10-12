package com.spinecare.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.spinecare.app.I18n.t
import java.util.Locale
import java.util.concurrent.Executors

/** Live camera-placement check before a session: on-device pose + the phone's
 *  gravity sensor feed PlacementCoach, which shows (and optionally speaks) one
 *  fix at a time until every line is green. Nothing is recorded or saved.
 *  Works in portrait and landscape (the panel moves to the side), with the
 *  same lens, zoom and rotate controls as the session screen. */
class SetupCheckActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var exercise: Exercise
    private lateinit var preview: PreviewView
    private lateinit var overlay: SetupOverlayView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private var poseEngine: PoseEngine? = null
    private lateinit var rig: CameraRig
    private lateinit var lensBtn: Button
    private lateinit var zoomBtn: Button
    private lateinit var rotateBtn: Button
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var sensors: SensorManager? = null

    @Volatile private var pitch: Double? = null
    @Volatile private var roll: Double? = null
    private var lastFrame = 0L
    private var lastUi = 0L
    private var readySince = 0L
    private var announcedReady = false
    private var lastSpoken = ""
    private var lastSpokenAt = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, t("camera_needed"), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val ex = Plan.byId(this, intent.getStringExtra("exercise_id") ?: "")
        if (ex == null || !ex.cameraTracked) { finish(); return }
        exercise = ex
        title = t("sc_title")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        preview = PreviewView(this)
        overlay = SetupOverlayView(this)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xE00B1220.toInt()); setPadding(24, 16, 24, 16)
        }
        panel.addView(TextView(this).apply {
            text = "${ex.name(I18n.lang)}\n${FramingGuide.forView(ex.view, ex.program).headline}"; setTextColor(Ui.TEXT); textSize = 14f
        })
        panel.addView(TextView(this).apply { text = t("sc_body"); setTextColor(Ui.MUTED); textSize = 12f })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(list)
        status = TextView(this).apply { setTextColor(Ui.OK); textSize = 15f; setPadding(0, 8, 0, 8) }
        panel.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val flip = Button(this).apply { text = t("flip"); isAllCaps = false; setOnClickListener { rig.flip() } }
        val approved = ex.custom || LocalStore(this).isApproved(ex.id)
        startBtn = Button(this).apply {
            text = if (approved) t("sc_start") else t("approved_required"); isAllCaps = false; isEnabled = approved
            setOnClickListener {
                // keep the camera, lens and zoom the placement was checked with
                startActivity(Intent(this@SetupCheckActivity, ExerciseActivity::class.java).putExtra("exercise_id", ex.id)
                    .putExtra("front", rig.front).putExtra("camera_id", rig.lens?.cameraId).putExtra("zoom", rig.zoom))
                finish()
            }
        }
        row.addView(flip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(startBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        panel.addView(row)
        val camRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lensBtn = Button(this).apply { isAllCaps = false; textSize = 12f; setOnClickListener { rig.nextLens() } }
        zoomBtn = Button(this).apply { isAllCaps = false; textSize = 12f; setOnClickListener { rig.cycleZoom() } }
        rotateBtn = Button(this).apply {
            isAllCaps = false; textSize = 12f
            setOnClickListener {
                ServerConfig.setCameraOrientation(this@SetupCheckActivity,
                    CameraOptions.nextOrientation(ServerConfig.cameraOrientation(this@SetupCheckActivity)))
                applyOrientation()
            }
        }
        for (v in listOf(lensBtn, zoomBtn, rotateBtn)) camRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(camRow)
        if (landscape) {
            // side panel, scrollable, so the person stays in view
            val scroll = android.widget.ScrollView(this).apply { addView(panel) }
            root.addView(scroll, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.42f).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.END))
        } else {
            root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM))
        }
        setContentView(root)
        rig = CameraRig(this, preview, executor, ::analyze) { onCameraChanged() }
        rig.enablePinch()
        applyOrientation()
        render(PlacementCoach.Result(listOf(PlacementCoach.Item("person", false, "pc_fix_person"))))

        poseEngine = runCatching { PoseEngine(this) }.getOrElse {
            Toast.makeText(this, t("pose_unavailable"), Toast.LENGTH_LONG).show(); null
        }
        if (ServerConfig.audioCues(this)) tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = if (I18n.lang == "ro") Locale("ro", "RO") else Locale.US
        }
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        restored = savedInstanceState
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val sm = sensors ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() { super.onPause(); sensors?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        // device axes -> screen axes, so "level" and "upright" also hold in landscape
        val (sx, sy) = PlacementCoach.screenGravity(e.values[0].toDouble(), e.values[1].toDouble(), displayRotationDeg)
        val (p, r) = PlacementCoach.tiltFromGravity(sx, sy, e.values[2].toDouble())
        pitch = p; roll = r
    }

    @Suppress("DEPRECATION")
    private val displayRotationDeg: Int get() = windowManager.defaultDisplay.rotation * 90

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private var restored: Bundle? = null

    private fun startCamera() {
        val s = restored
        rig.start(s?.getBoolean("front", true) ?: true, s?.getString("camera_id"), s?.getFloat("zoom", Float.NaN) ?: Float.NaN)
    }

    private fun applyOrientation() {
        requestedOrientation = CameraRig.requestedOrientation(ServerConfig.cameraOrientation(this))
        rotateBtn.text = "⟳ " + t(CameraOptions.orientationKey(ServerConfig.cameraOrientation(this)))
    }

    private fun onCameraChanged() {
        overlay.mirror = rig.front
        lensBtn.text = rig.lensLabel()
        lensBtn.visibility = if (rig.lenses.size > 1) View.VISIBLE else View.GONE
        zoomBtn.text = rig.zoomLabel()
        zoomBtn.isEnabled = rig.maxZoom > rig.minZoom + 0.01f
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (!::rig.isInitialized) return
        out.putBoolean("front", rig.front)
        rig.lens?.let { out.putString("camera_id", it.cameraId) }
        out.putFloat("zoom", rig.zoom)
    }

    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val engine = poseEngine
        if (engine == null || now - lastFrame < 150) { proxy.close(); return }
        lastFrame = now
        try {
            var bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix(); m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val w = bmp.width; val h = bmp.height
            val named = engine.detect(bmp).landmarks?.let { Metrics.named(it, w, h) }
            val minConf = ServerConfig.minConfidence(this).toDouble()
            val result = PlacementCoach.assess(named, w.toDouble(), h.toDouble(), exercise, minConf, pitch, roll)
            val framing = Framing.check(named, exercise.requiredLandmarks, minConf, w.toDouble(), h.toDouble())
            overlay.show(SetupOverlayView.State(named, w, h, framing.missing, result.ready))
            if (now - lastUi > 250) { lastUi = now; runOnUiThread { render(result) } }
        } catch (_: Exception) {
            // keep analysing on transient frame errors
        } finally {
            proxy.close()
        }
    }

    private fun render(r: PlacementCoach.Result) {
        list.removeAllViews()
        for (item in r.items) list.addView(TextView(this).apply {
            text = if (item.ok) "✓ " + PlacementCoach.itemText(item)
                   else "✗ " + PlacementCoach.itemText(item) + ": " + PlacementCoach.fixText(item.fixKey)
            setTextColor(if (item.ok) Ui.OK else Ui.WARN); textSize = 13f
        })
        val now = System.currentTimeMillis()
        if (r.ready) {
            if (readySince == 0L) readySince = now
            if (now - readySince >= 1500) {
                status.text = t("sc_ready")
                if (!announcedReady) { announcedReady = true; speak(t("sc_ready"), force = true) }
            }
        } else {
            readySince = 0L; announcedReady = false; status.text = ""
            r.firstFix?.let { speak(PlacementCoach.fixText(it.fixKey)) }
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (tts == null || (!force && (text == lastSpoken && now - lastSpokenAt < 8000 || now - lastSpokenAt < 3500))) return
        lastSpoken = text; lastSpokenAt = now
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "setup")
    }

    override fun onDestroy() {
        executor.shutdown()
        poseEngine?.close()
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Skeleton (shared OverlayRenderer) plus a dashed target zone showing where
 *  the body should sit in the picture. Green when placement is ready. */
class SetupOverlayView(context: Context) : View(context) {
    data class State(val lms: Map<String, Metrics.P>?, val w: Int, val h: Int, val missing: List<String>, val ready: Boolean)

    @Volatile private var state = State(null, 0, 0, emptyList(), false)
    @Volatile var mirror = false
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }

    fun show(s: State) { state = s; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state
        zone.color = if (s.ready) Ui.OK else Color.argb(200, 251, 191, 36)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(w * 0.28f, h * 0.06f, w * 0.72f, h * 0.62f, 40f, 40f, zone)
        OverlayRenderer.draw(canvas, s.lms, s.w, s.h, s.missing, s.ready, "", "", "", mirror)
    }
}
