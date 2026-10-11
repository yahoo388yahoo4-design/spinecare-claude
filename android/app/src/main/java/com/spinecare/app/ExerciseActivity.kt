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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Guided camera session: on-device MediaPipe pose -> framing gate ->
 *  calibrated signal -> reps + form cues, always running so coaching never
 *  depends on the network. When a coaching server is configured and reachable
 *  its results take over the display (landmarks-only by default; frames only
 *  if the user chose that), and on-device coaching resumes instantly if it drops.
 *
 *  Every session also saves a sensor timeline for in-app replay. Optional
 *  voice guidance speaks setup, calibration, cues and rep counts; optional
 *  voice control takes hands-free commands (start, pause, stop, ...). */
class ExerciseActivity : AppCompatActivity() {
    private lateinit var b: ActivityExerciseBinding
    private lateinit var exercise: Exercise
    private var poseEngine: PoseEngine? = null
    private lateinit var store: LocalStore
    private var tracker: ExerciseTracker? = null
    private var server: ServerClient? = null
    private var coach: VoiceCoach? = null
    private var voice: VoiceControl? = null
    private var voiceOn = false
    private var voiceAnnounced = false
    private var voiceHeard: String? = null
    private var voiceListening = false
    private var pendingDialog: AlertDialog? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var serverPose: JSONObject? = null
    @Volatile private var lastCue = ""
    private var lastReadyAnnounce = 0L
    private var wasCalibrating = true
    private var lastRepsAnnounced = 0
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
    private var sessionBase = ""
    private var timeline: SessionTimeline.Writer? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, t("camera_needed"), Toast.LENGTH_LONG).show()
        requestMicIfNeeded()
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else { Toast.makeText(this, t("mic_needed"), Toast.LENGTH_LONG).show(); labelVoice() }
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
        b.btnStart.setOnClickListener { if (running) setPaused(!paused) else askSymptoms("before") { startSession() } }
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
        voiceOn = ServerConfig.voiceControl(this)
        if (ServerConfig.audioCues(this) || voiceOn) ensureCoach()
        coach?.guide("${exercise.name(I18n.lang)}. ${guide.headline}. ${t("vg_setup")}")
        postCue(t("position_camera").format(t("view_" + exercise.view)))
        b.tvVoice.setOnClickListener { toggleVoice() }
        labelVoice()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(); requestMicIfNeeded()
        } else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------ voice

    private fun ensureCoach(): VoiceCoach =
        coach ?: VoiceCoach(this, ServerConfig.audioCues(this)) { speaking -> voice?.hold(speaking) }.also { coach = it }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicIfNeeded() {
        if (!voiceOn) return
        if (micGranted()) startVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoice() {
        if (!voiceOn || !micGranted()) return
        val vc = voice ?: VoiceControl(this, VoiceCommands.load(this), ::onVoiceCommand) { listening, heard ->
            voiceListening = listening
            if (!heard.isNullOrBlank()) voiceHeard = heard
            labelVoice()
        }.also { voice = it }
        if (!vc.available) {
            Toast.makeText(this, t("voice_unavailable"), Toast.LENGTH_LONG).show()
            voiceOn = false; labelVoice(); return
        }
        vc.start()
        if (!voiceAnnounced) { voiceAnnounced = true; ensureCoach().speak(t("vg_listening")) }
        labelVoice()
    }

    private fun toggleVoice() {
        voiceOn = !voiceOn
        ServerConfig.setVoiceControl(this, voiceOn)
        if (voiceOn) { ensureCoach(); voiceAnnounced = false; requestMicIfNeeded() }
        else { voice?.stop(); voiceListening = false }
        labelVoice()
    }

    private fun labelVoice() {
        b.tvVoice.text = when {
            !voiceOn -> "🎤 " + t("voice_off")
            else -> "🎤 " + (if (voiceListening) t("voice_listening") else t("voice_on")) +
                (voiceHeard?.let { "\n" + t("voice_heard").format(it) } ?: "")
        }
        b.tvVoice.setTextColor(if (voiceOn) Ui.OK else Ui.MUTED)
    }

    private fun onVoiceCommand(cmd: VoiceCommands.Cmd, heard: String) {
        voiceHeard = heard; labelVoice()
        val c = ensureCoach()
        when (cmd) {
            VoiceCommands.Cmd.START -> when {
                pendingDialog != null -> {}
                running && paused -> setPaused(false)
                !running -> b.btnStart.performClick()
            }
            VoiceCommands.Cmd.SKIP -> pendingDialog?.cancel()
            VoiceCommands.Cmd.STOP -> if (running) stopAndSave(askAfter = true) else pendingDialog?.cancel()
            VoiceCommands.Cmd.PAUSE -> if (running) setPaused(true)
            VoiceCommands.Cmd.RESUME -> if (running) setPaused(false)
            VoiceCommands.Cmd.RECALIBRATE -> if (running) b.btnCalib.performClick()
            VoiceCommands.Cmd.FLIP -> if (!running) flipCamera() else c.speak(t("vg_flip_running"), flush = true)
            VoiceCommands.Cmd.STATUS -> c.speak(
                if (running) t("vg_status").format(currentReps(), exercise.targetReps) else t("vg_not_started"), flush = true)
            VoiceCommands.Cmd.REPEAT -> c.speak(c.lastSpoken.ifEmpty { lastCue }, flush = true)
            VoiceCommands.Cmd.HELP -> c.speak(t("vg_help"), flush = true)
        }
    }

    private fun currentReps(): Int {
        val sp = serverPose
        return if (server?.connected == true && sp != null) sp.optInt("reps") else tracker?.reps?.reps ?: 0
    }

    private fun setPaused(p: Boolean) {
        if (!running || paused == p) return
        paused = p
        labelButtons()
        val msg = if (p) t("vg_paused") else t("vg_resumed")
        lastCue = msg; b.tvCue.text = msg
        coach?.speak(msg, flush = true)
    }

    private fun labelButtons() {
        b.btnMode.text = when (ServerConfig.mode(this)) {
            ServerConfig.MODE_ON_DEVICE -> t("mode_btn_device")
            ServerConfig.MODE_SERVER -> t("mode_btn_server")
            else -> t("mode_btn_auto")
        }
        b.btnRec.text = if (recordEnabled) t("rec_on") else t("rec_off")
        b.btnStart.text = if (!running) t("start") else if (paused) t("resume") else t("pause")
        b.btnStop.isEnabled = running
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
            if (!running || paused) {
                b.overlay.show(OverlayView.Frame(named, w, h, framing.missing, framing.ready))
                if (!running && framing.ready && now - lastReadyAnnounce > 15_000) {
                    lastReadyAnnounce = now
                    runOnUiThread { coach?.guide(t("vg_framing_ok")) }
                }
                return
            }
            val imu = BlePostureClient.snapshot()
            framesProcessed++
            for ((k, v) in metrics) metricSums.getOrPut(k) { DoubleArray(2) }.let { it[0] += v; it[1] += 1.0 }
            val local = tracker?.update(metrics, framing.ready, now)

            // server enhancement (never blocks on-device coaching)
            val srv = server
            if (srv != null && srv.connected && now - lastServerSend >= serverIntervalMs) {
                lastServerSend = now
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
            // voice guidance: calibration finished, then every new rep
            val calibratingNow = if (sp != null) sp.optBoolean("calibrating") else (local == null || local.calibrating)
            if (wasCalibrating && !calibratingNow && ready) runOnUiThread { coach?.guide(t("vg_calibrated"), flush = true) }
            wasCalibrating = calibratingNow
            if (repsNow > lastRepsAnnounced) {
                lastRepsAnnounced = repsNow
                runOnUiThread { coach?.rep(repsNow, exercise.targetReps) }
            }
            if (cue.isNotEmpty()) postCue(cue)
            val source = if (sp != null) "server ${sp.optInt("latency_ms")}ms" else "on-device ${res.latencyMs}ms"
            val metricsLine = metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" } +
                "\nsignal=${fmt(local?.signal)} $source frames=$framesProcessed"
            val repsText = "$repsNow/${exercise.targetReps}"
            b.overlay.show(OverlayView.Frame(named, w, h, missing, ready))
            runOnUiThread { b.tvReps.text = repsText; b.tvMetrics.text = metricsLine }

            var wroteVideo = false
            if (recordEnabled) {
                val r = recorder ?: runCatching { newRecorder(w, h) }.getOrNull()
                val before = r?.framesWritten ?: 0
                r?.drawFrame(bmp, named, w, h, missing, ready, metricsLine, lastCue, repsText, frontCamera)
                wroteVideo = (r?.framesWritten ?: 0) > before
            }
            timeline?.add(SessionTimeline.Sample(
                t = now - startedAt, frameW = w, frameH = h, mirror = frontCamera,
                landmarks = named?.mapValues { Metrics.P(it.value.x / w, it.value.y / h, it.value.c) } ?: emptyMap(),
                metrics = metrics, signal = local?.signal, reps = repsNow, cue = lastCue, ready = ready, video = wroteVideo,
                head = SessionTimeline.imuTriple(imu?.optJSONObject("head")),
                trunk = SessionTimeline.imuTriple(imu?.optJSONObject("trunk")),
            ))
        } catch (e: Exception) {
            // keep the loop alive on transient frame errors
        } finally {
            proxy.close()
        }
    }

    private fun newRecorder(w: Int, h: Int): SessionRecorder {
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "$sessionBase.mp4")
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
        sessionBase = "session_${exercise.id}_$startedAt"
        timeline = runCatching {
            SessionTimeline.Writer(store.newTimelineFile(sessionBase),
                SessionTimeline.header(exercise.id, exercise.signal.removePrefix("abs:"), exercise.targetReps, startedAt))
        }.getOrNull()
        paused = false; wasCalibrating = true; lastRepsAnnounced = 0
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
                override fun onError(error: String) { if (error.contains("exercise")) runOnUiThread { postCue(t("err_unknown_exercise")) } }
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
        val tlWriter = timeline
        val recFuture = analysisExecutor.submit<Pair<Int, Int>> {
            val r = recorder; recorder = null
            val n = r?.framesWritten ?: 0
            runCatching { r?.stop() }
            timeline = null
            n to (tlWriter?.close() ?: 0)
        }
        val (recordedFrames, timelineSamples) = runCatching { recFuture.get() }.getOrElse { 0 to (tlWriter?.count ?: 0) }
        val timelinePath = tlWriter?.file?.takeIf { timelineSamples > 0 && it.exists() }?.absolutePath
        if (timelineSamples == 0) tlWriter?.file?.delete()
        paused = false
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
            if (serverUsed) "server+on-device" else "on-device", videoPath, recordedFrames, timelinePath, timelineSamples)
        labelButtons()
        coach?.guide(t("vg_saved").format(reps), flush = true)
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
        val dlg = AlertDialog.Builder(this).setTitle(t("phase_$phase")).setView(ui.root)
            .setPositiveButton(t("save_checkin")) { _, _ -> store.saveCheckin(phase, neck.progress, arm.progress, null, ""); then() }
            .setNegativeButton(t("skip")) { _, _ -> then() }
            .setOnCancelListener { then() }
            .show()
        pendingDialog = dlg
        dlg.setOnDismissListener { if (pendingDialog === dlg) pendingDialog = null }
        if (voiceOn) coach?.speak(t("vg_checkin"))
    }

    private fun postCue(text: String) {
        if (text == lastCue) return
        lastCue = text
        runOnUiThread { b.tvCue.text = text; coach?.cue(text) }
    }

    private fun vibrate() {
        if (!ServerConfig.haptics(this)) return
        val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun fmt(v: Double?) = if (v == null || v.isNaN()) "-" else "%.2f".format(v)

    override fun onResume() { super.onResume(); if (voiceOn && micGranted() && voice != null) startVoice() }

    // never listen in the background
    override fun onPause() { voice?.stop(); voiceListening = false; super.onPause() }

    override fun onDestroy() {
        if (running && isFinishing) stopAndSave(askAfter = false)   // back pressed mid-session: keep the data
        running = false
        analysisExecutor.shutdown()
        poseEngine?.close()
        server?.close()
        recorder?.let { runCatching { it.stop() } }
        timeline?.close()
        voice?.stop()
        coach?.shutdown()
        super.onDestroy()
    }
}
