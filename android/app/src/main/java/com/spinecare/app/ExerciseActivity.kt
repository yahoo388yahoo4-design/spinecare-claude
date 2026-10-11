package com.spinecare.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.spinecare.app.I18n.t
import com.spinecare.app.databinding.ActivityExerciseBinding
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Guided camera session: on-device MediaPipe pose -> framing gate ->
 *  calibrated signal -> reps + form cues, always running so coaching never
 *  depends on the network. When a coaching server is configured and reachable
 *  its results take over the display (landmarks-only by default; frames only
 *  if the user chose that), and on-device coaching resumes instantly if it drops. */
class ExerciseActivity : AppCompatActivity() {
    private lateinit var b: ActivityExerciseBinding
    private lateinit var exercise: Exercise
    private var poseEngine: PoseEngine? = null
    private lateinit var store: LocalStore
    private var tracker: ExerciseTracker? = null
    private var server: ServerClient? = null
    private var tts: TextToSpeech? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var serverPose: JSONObject? = null
    @Volatile private var lastCue = ""
    private var lastSpokenAt = 0L
    private var lastFrameT = 0L
    private var lastServerSend = 0L
    private var startedAt = 0L
    private var framesProcessed = 0
    private var serverUsed = false
    private val metricSums = HashMap<String, DoubleArray>()
    private val cueCounts = HashMap<String, Int>()
    private var frameIntervalMs = 150L
    private var serverIntervalMs = 150L
    private var minConf = 0.5

    // camera + recording
    private var frontCamera = true
    private var provider: ProcessCameraProvider? = null
    private var recorder: SessionRecorder? = null
    private var recordEnabled = false
    private var recordFile: File? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, t("camera_needed"), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        b = ActivityExerciseBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = LocalStore(this)

        val ex = Plan.byId(this, intent.getStringExtra("exercise_id") ?: "")
        if (ex == null || !ex.cameraTracked) { finish(); return }
        exercise = ex
        poseEngine = runCatching { PoseEngine(this) }.getOrElse {
            Toast.makeText(this, t("pose_unavailable"), Toast.LENGTH_LONG).show(); null
        }

        val guide = FramingGuide.forView(exercise.view, exercise.program)
        b.tvExercise.text = "${exercise.name(I18n.lang)}\n${guide.headline}\n• " + guide.steps.joinToString("\n• ")
        b.tvReps.text = "0/${exercise.targetReps}"
        b.btnStart.text = t("start"); b.btnStop.text = t("stop_save"); b.btnFlip.text = t("flip")
        b.btnCalib.text = t("recalibrate")
        b.btnStart.setOnClickListener { askSymptoms("before") { startSession() } }
        b.btnStop.setOnClickListener { stopAndSave(askAfter = true) }
        b.btnMode.setOnClickListener {
            if (running) return@setOnClickListener
            ServerConfig.setMode(this, (ServerConfig.mode(this) + 1) % 3); labelButtons()
        }
        b.btnFlip.setOnClickListener { flipCamera() }
        b.btnRec.setOnClickListener {
            if (running) return@setOnClickListener
            recordEnabled = !recordEnabled; ServerConfig.setRecordSessions(this, recordEnabled); labelButtons()
        }
        b.btnCalib.setOnClickListener {
            tracker?.recalibrate(); server?.recalibrate(); BlePostureClient.calibrateNeutral()
            postCue(t("calibrating"))
        }
        recordEnabled = ServerConfig.recordSessions(this)
        labelButtons()
        postCue(t("position_camera").format(t("view_" + exercise.view)))

        if (ServerConfig.audioCues(this)) tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = if (I18n.lang == "ro") Locale("ro", "RO") else Locale.US
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun labelButtons() {
        b.btnMode.text = when (ServerConfig.mode(this)) {
            ServerConfig.MODE_ON_DEVICE -> t("mode_btn_device")
            ServerConfig.MODE_SERVER -> t("mode_btn_server")
            else -> t("mode_btn_auto")
        }
        b.btnRec.text = if (recordEnabled) t("rec_on") else t("rec_off")
        b.btnStart.isEnabled = !running; b.btnStop.isEnabled = running
        b.btnMode.isEnabled = !running; b.btnRec.isEnabled = !running
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            if (provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) != true) frontCamera = false
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
        try {
            p.unbindAll()
            p.bindToLifecycle(this, selector, preview, analysis)
            b.overlay.mirror = frontCamera
        } catch (e: Exception) {
            Toast.makeText(this, t("no_camera"), Toast.LENGTH_LONG).show()
        }
    }

    private fun flipCamera() {
        val p = provider ?: return
        val target = if (frontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!p.hasCamera(target)) { Toast.makeText(this, t("no_other_camera"), Toast.LENGTH_SHORT).show(); return }
        frontCamera = !frontCamera
        bindCamera()
    }

    // ------------------------------------------------------------------ analysis (camera thread)

    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val engine = poseEngine
        if (engine == null || now - lastFrameT < frameIntervalMs) { proxy.close(); return }
        lastFrameT = now
        try {
            var bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix(); m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val w = bmp.width; val h = bmp.height
            val res = engine.detect(bmp)
            val raw = res.landmarks
            val named = raw?.let { Metrics.named(it, w, h) }
            val framing = Framing.check(named, exercise.requiredLandmarks, minConf, w.toDouble(), h.toDouble())
            val metrics = named?.let { Metrics.compute(it) } ?: emptyMap()
            if (!running) {
                b.overlay.show(OverlayView.Frame(named, w, h, framing.missing, framing.ready))
                return
            }
            framesProcessed++
            for ((k, v) in metrics) metricSums.getOrPut(k) { DoubleArray(2) }.let { it[0] += v; it[1] += 1.0 }
            val local = tracker?.update(metrics, framing.ready, now)

            // server enhancement (never blocks on-device coaching)
            val srv = server
            if (srv != null && srv.connected && now - lastServerSend >= serverIntervalMs) {
                lastServerSend = now
                val imu = BlePostureClient.snapshot()
                if (ServerConfig.streamMode(this) == ServerConfig.STREAM_FRAMES) srv.sendFrame(bmp, now, imu)
                else if (raw != null) srv.sendLandmarks(raw, w, h, now, imu)
            }
            val sp = serverPose?.takeIf { srv != null && srv.connected && now - srv.lastPoseAtMs < 1000 }
            if (sp != null) serverUsed = true

            val repsNow: Int
            val cue: String
            val ready: Boolean
            val missing: List<String>
            if (sp != null) {
                val f = sp.optJSONObject("framing")
                ready = f?.optBoolean("ready") ?: false
                missing = f?.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                repsNow = sp.optInt("reps")
                cue = when {
                    !ready -> f?.optJSONArray("issues")?.let { a -> (0 until a.length()).joinToString(" ") { FramingGuide.fixFor(a.getString(it)) } } ?: ""
                    sp.optBoolean("calibrating") -> t("calibrating")
                    (sp.optJSONArray("cues")?.length() ?: 0) > 0 -> sp.getJSONArray("cues").getJSONObject(0).optString("cue")
                    sp.optBoolean("rep_just_done") -> t("rep_done").format(repsNow, exercise.targetReps)
                    else -> ""
                }
                if (sp.optBoolean("rep_just_done")) { serverPose = JSONObject(sp.toString()).put("rep_just_done", false); vibrate() }
            } else {
                ready = framing.ready; missing = framing.missing
                repsNow = local?.reps ?: 0
                cue = when {
                    !framing.ready -> framing.issues.distinct().joinToString(" ") { FramingGuide.fixFor(it) }
                    local == null || local.calibrating -> t("calibrating")
                    local.cues.isNotEmpty() -> local.cues.first().text
                    local.repJustDone -> t("rep_done").format(repsNow, exercise.targetReps)
                    else -> ""
                }
                if (local?.repJustDone == true) vibrate()
                local?.cues?.forEach { c -> cueCounts[c.metric] = (cueCounts[c.metric] ?: 0) + 1 }
            }
            if (cue.isNotEmpty()) postCue(cue)
            val source = if (sp != null) "server ${sp.optInt("latency_ms")}ms" else "on-device ${res.latencyMs}ms"
            val metricsLine = metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" } +
                "\nsignal=${fmt(local?.signal)} $source frames=$framesProcessed"
            val repsText = "$repsNow/${exercise.targetReps}"
            b.overlay.show(OverlayView.Frame(named, w, h, missing, ready))
            runOnUiThread { b.tvReps.text = repsText; b.tvMetrics.text = metricsLine }

            if (recordEnabled) {
                val r = recorder ?: runCatching { newRecorder(w, h) }.getOrNull()
                r?.drawFrame(bmp, named, w, h, missing, ready, metricsLine, lastCue, repsText, frontCamera)
            }
        } catch (e: Exception) {
            // keep the loop alive on transient frame errors
        } finally {
            proxy.close()
        }
    }

    private fun newRecorder(w: Int, h: Int): SessionRecorder {
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "session_${exercise.id}_${System.currentTimeMillis()}.mp4")
        return SessionRecorder(f, w, h).also { recorder = it; recordFile = f }
    }

    // ------------------------------------------------------------------ session

    private fun startSession() {
        if (running) return
        val bundle = Plan.bundle(this)
        tracker = ExerciseTracker(exercise, (bundle.calibrationS * 1000).toLong(), I18n.lang)
        frameIntervalMs = ServerConfig.frameIntervalMs(this).toLong()
        serverIntervalMs = ServerConfig.serverFrameIntervalMs(this).toLong()
        minConf = ServerConfig.minConfidence(this).toDouble()
        framesProcessed = 0; metricSums.clear(); cueCounts.clear(); serverUsed = false; serverPose = null
        startedAt = System.currentTimeMillis()
        connectServerIfConfigured()
        running = true
        labelButtons()
        postCue(t("calibrate_hint").format(exercise.stops(I18n.lang).joinToString()))
    }

    private fun connectServerIfConfigured() {
        server?.close(); server = null
        val mode = ServerConfig.mode(this)
        if (mode == ServerConfig.MODE_ON_DEVICE) return
        if (!ServerConfig.serverUsable(this)) {
            if (mode == ServerConfig.MODE_SERVER) {
                val why = ServerConfig.urlProblem(ServerConfig.url(this), ServerConfig.allowInsecureLan(this))
                Toast.makeText(this, t(why ?: "server_offline") + " — " + t("using_device"), Toast.LENGTH_LONG).show()
            }
            return
        }
        server = ServerClient(ServerConfig.url(this), ServerConfig.user(this), ServerConfig.pass(this)).apply {
            connect(object : ServerClient.Listener {
                override fun onPose(msg: JSONObject) { serverPose = msg }
                override fun onError(error: String) { if (error.contains("exercise")) runOnUiThread { postCue(error) } }
                override fun onStatus(connected: Boolean) {
                    runOnUiThread {
                        if (!connected && running) Toast.makeText(this@ExerciseActivity, t("server_dropped"), Toast.LENGTH_SHORT).show()
                    }
                }
            })
            startSession(exercise.id, I18n.lang)
        }
    }

    private fun stopAndSave(askAfter: Boolean) {
        if (!running) return
        running = false
        val t0 = System.currentTimeMillis()
        // finish recording on the analysis thread so no frame is drawn mid-stop
        val recFuture = analysisExecutor.submit<Int> {
            val r = recorder; recorder = null
            val n = r?.framesWritten ?: 0
            runCatching { r?.stop() }
            n
        }
        val recordedFrames = runCatching { recFuture.get() }.getOrDefault(0)
        val videoPath = recordFile?.takeIf { recordedFrames > 0 && it.exists() }?.absolutePath
        if (recordedFrames == 0) recordFile?.delete()
        recordFile = null

        val srv = server
        val sp = serverPose
        val useServer = srv != null && srv.connected && sp != null && t0 - srv.lastPoseAtMs < 2000
        val reps = if (useServer) sp!!.optInt("reps") else tracker?.reps?.reps ?: 0
        srv?.endSession(); srv?.close(); server = null

        val avg = metricSums.mapValues { it.value[0] / it.value[1].coerceAtLeast(1.0) }
        store.saveSession(exercise.id, reps, 1, (t0 - startedAt) / 1000, avg, cueCounts, framesProcessed,
            if (serverUsed) "server+on-device" else "on-device", videoPath, recordedFrames)
        labelButtons()
        Toast.makeText(this, t("session_saved").format(reps) + if (videoPath != null) " · " + t("video_saved") else "",
            Toast.LENGTH_SHORT).show()
        if (askAfter) askSymptoms("after") {}
    }

    /** Optional before/after symptom check-in (0-10). Skipping never blocks the session. */
    private fun askSymptoms(phase: String, then: () -> Unit) {
        val ui = Ui(this)
        ui.muted(t("symptom_question"))
        val neck = ui.scale(t("neck_pain"), 0)
        val arm = ui.scale(t("arm_symptoms"), 0)
        ui.warn(t("pain_warning"))
        AlertDialog.Builder(this).setTitle(t("phase_$phase")).setView(ui.root)
            .setPositiveButton(t("save_checkin")) { _, _ -> store.saveCheckin(phase, neck.progress, arm.progress, null, ""); then() }
            .setNegativeButton(t("skip")) { _, _ -> then() }
            .setOnCancelListener { then() }
            .show()
    }

    private fun postCue(text: String) {
        if (text == lastCue) return
        lastCue = text
        runOnUiThread { b.tvCue.text = text }
        val now = System.currentTimeMillis()
        if (tts != null && now - lastSpokenAt > 2500) {
            lastSpokenAt = now
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
        }
    }

    private fun vibrate() {
        if (!ServerConfig.haptics(this)) return
        val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun fmt(v: Double?) = if (v == null || v.isNaN()) "-" else "%.2f".format(v)

    override fun onDestroy() {
        if (running && isFinishing) stopAndSave(askAfter = false)   // back pressed mid-session: keep the data
        running = false
        analysisExecutor.shutdown()
        poseEngine?.close()
        server?.close()
        recorder?.let { runCatching { it.stop() } }
        tts?.shutdown()
        super.onDestroy()
    }
}
