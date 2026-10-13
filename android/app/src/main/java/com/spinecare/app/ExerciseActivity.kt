package com.spinecare.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
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
 *  voice control takes hands-free commands (start, pause, stop, ...).
 *
 *  Movement coaching (each part switchable in Settings): when the coach explains
 *  the next move, a small animated figure shows how the head/body/arms travel;
 *  while moving, realtime "hold" / "and back" prompts follow the plan's tempo;
 *  after each rep a short precision and speed verdict is shown and spoken.
 *  Tapping the camera picture hides the text and controls so the whole body and
 *  its tracking points are visible; tap again to bring them back.
 *
 *  The screen works in portrait and landscape (Rotate button: auto, portrait,
 *  landscape). The orientation is held while a session or check-in is open so
 *  calibration and the recording keep one frame shape; lens and zoom (pinch or
 *  the zoom button) come from CameraRig. */
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

    // movement coaching
    @Volatile private var moveCoach: MoveCoach? = null
    private var demo: GuideDemo? = null
    private var tempoPromptsOn = true
    private var moveDemoOn = true
    private var repFeedbackOn = true
    private var cleanRecording = true
    @Volatile private var pendingFeedback: Pair<Long, String>? = null
    @Volatile private var lastExplainAt = 0L
    private var chromeHidden = false
    private val hideGuide = Runnable { b.moveGuide.visibility = View.GONE }

    // camera + recording
    private lateinit var rig: CameraRig
    @Volatile private var frontCamera = true
    private var recorder: SessionRecorder? = null
    private var recorderFailed = false
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

        rig = CameraRig(this, b.preview, analysisExecutor, ::analyze) { onCameraChanged() }
        rig.enablePinch(if (ServerConfig.tapHidesText(this)) ::toggleChrome else null)
        applyOrientation()

        tempoPromptsOn = ServerConfig.tempoPrompts(this)
        moveDemoOn = ServerConfig.moveDemo(this)
        repFeedbackOn = ServerConfig.repFeedback(this)
        cleanRecording = ServerConfig.cleanRecordings(this)
        if (moveDemoOn) demo = runCatching { GuideDemo(exercise) }.getOrNull()?.takeIf { it.countable }
        b.moveGuide.demo = demo
        b.moveGuide.setOnClickListener { b.moveGuide.visibility = View.GONE }

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
        b.btnLens.setOnClickListener {
            if (running) { Toast.makeText(this, t("lens_locked"), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            rig.nextLens()
        }
        b.btnZoom.setOnClickListener { rig.cycleZoom() }
        b.btnRotate.setOnClickListener {
            if (running) return@setOnClickListener
            ServerConfig.setCameraOrientation(this, CameraOptions.nextOrientation(ServerConfig.cameraOrientation(this)))
            labelButtons(); applyOrientation()
        }
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
        if (savedInstanceState?.getBoolean(KEY_CHROME_HIDDEN) == true) toggleChrome()
        // spoken once per screen, not again after a rotation
        if (savedInstanceState == null) coach?.guide("${exercise.name(I18n.lang)}. ${guide.headline}. ${t("vg_setup")}")
        postCue(t("position_camera").format(t("view_" + exercise.view)))
        b.tvVoice.setOnClickListener { toggleVoice() }
        labelVoice()

        // after a rotation, or the camera the setup check used
        val src = savedInstanceState ?: intent.extras
        frontCamera = src?.getBoolean(KEY_FRONT, true) ?: true
        restoreCameraId = src?.getString(KEY_CAMERA)
        restoreZoom = src?.getFloat(KEY_ZOOM, Float.NaN) ?: Float.NaN
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(); requestMicIfNeeded()
        } else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------ voice

    private fun ensureCoach(): VoiceCoach =
        coach ?: VoiceCoach(this, ServerConfig.audioCues(this),
            onProblem = { key -> if (!isFinishing) Toast.makeText(this, t(key), Toast.LENGTH_LONG).show() },
            onSpeaking = { speaking -> voice?.hold(speaking) }).also { coach = it }

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
        if (p) { b.moveGuide.removeCallbacks(hideGuide); b.moveGuide.visibility = View.GONE }
        moveCoach?.reset(System.currentTimeMillis())
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
        b.btnRotate.text = "⟳ " + t(CameraOptions.orientationKey(ServerConfig.cameraOrientation(this)))
        b.btnRotate.isEnabled = !running
        b.btnLens.isEnabled = !running
    }

    /** Applies the user's orientation choice, or holds the current one while a
     *  session or symptom check-in is open (a rotation must not restart them). */
    private fun applyOrientation() {
        requestedOrientation = if (running || pendingDialog != null) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else CameraRig.requestedOrientation(ServerConfig.cameraOrientation(this))
    }

    private fun onCameraChanged() {
        frontCamera = rig.front
        b.overlay.mirror = rig.front
        b.btnLens.text = rig.lensLabel()
        b.btnLens.visibility = if (rig.lenses.size > 1) android.view.View.VISIBLE else android.view.View.GONE
        b.btnZoom.text = rig.zoomLabel()
        b.btnZoom.isEnabled = rig.maxZoom > rig.minZoom + 0.01f
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (!::rig.isInitialized) return
        out.putBoolean(KEY_FRONT, rig.front)
        rig.lens?.let { out.putString(KEY_CAMERA, it.cameraId) }
        out.putFloat(KEY_ZOOM, rig.zoom)
        out.putBoolean(KEY_CHROME_HIDDEN, chromeHidden)
    }

    // ------------------------------------------------------------------ clean view

    /** Tap on the camera picture: hide (or bring back) every text overlay and the
     *  controls, so the whole body and its tracking points are visible. */
    private fun toggleChrome() {
        chromeHidden = !chromeHidden
        val vis = if (chromeHidden) View.INVISIBLE else View.VISIBLE
        for (v in listOf(b.tvExercise, b.tvCue, b.tvMetrics, b.tvVoice, b.tvReps, b.btnRow, b.btnRow2, b.btnRow3)) v.visibility = vis
        b.panel?.visibility = vis
        b.tvFeedback.visibility = if (chromeHidden || b.tvFeedback.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (chromeHidden) Toast.makeText(this, t("tap_to_show"), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ movement coaching (main thread)

    /** The coach explains the next move: spoken (with voice guidance on) and, with
     *  the movement demo on, shown by the animated figure for [cycles] demo loops. */
    private fun explainMove(first: Boolean, cycles: Int = 1) {
        if (!running || paused) return
        lastExplainAt = System.currentTimeMillis()
        val desc = exercise.description(I18n.lang)
        val what = if (first) t("mv_now").format(desc) else t("mv_again").format(firstSentence(desc))
        val tempo = t("mv_tempo").format(secs(exercise.tempoIn), secs(exercise.tempoHold), secs(exercise.tempoOut))
        coach?.guide("$what $tempo")
        showMoveGuide(cycles)
    }

    private fun showMoveGuide(cycles: Int) {
        val d = demo ?: return
        if (!moveDemoOn) return
        b.moveGuide.removeCallbacks(hideGuide)
        if (b.moveGuide.visibility != View.VISIBLE) { b.moveGuide.visibility = View.VISIBLE; b.moveGuide.restart() }
        b.moveGuide.postDelayed(hideGuide, d.repMs * minOf(2, d.reps) * cycles.coerceAtLeast(1))
    }

    /** A movement ended: show the verdict now; a counted rep's verdict is spoken
     *  with the rep count, a movement that did not count is spoken right away. */
    private fun onMoveFeedback(fb: MoveCoach.Feedback) {
        if (!repFeedbackOn) return
        val phrase = fb.keys.take(2).joinToString(" ") { t(it) }
        val detail = t("fb_detail").format(fb.score, fb.rangePct, secs(fb.inMs / 1000.0), secs(fb.holdMs / 1000.0), secs(fb.outMs / 1000.0))
        if (fb.counted) pendingFeedback = System.currentTimeMillis() to phrase
        runOnUiThread {
            b.tvFeedback.text = "$phrase\n$detail"
            b.tvFeedback.setTextColor(if (fb.score >= 80) Ui.OK else Ui.WARN)
            if (!chromeHidden) b.tvFeedback.visibility = View.VISIBLE
            if (!fb.counted) coach?.cue(phrase)
            // a movement that needs correcting: show how it should look again
            if (fb.score < 80) showMoveGuide(1)
        }
    }

    private fun takeFeedback(): String {
        val p = pendingFeedback ?: return ""
        pendingFeedback = null
        return if (System.currentTimeMillis() - p.first < 2500) p.second else ""
    }

    private fun firstSentence(s: String): String {
        val i = s.indexOfFirst { it == '.' || it == '!' || it == '?' }
        return if (i in 1 until s.length - 1) s.substring(0, i + 1) else s
    }

    private fun secs(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

    // ------------------------------------------------------------------ camera

    private var restoreCameraId: String? = null
    private var restoreZoom = Float.NaN

    private fun startCamera() = rig.start(frontCamera, restoreCameraId, restoreZoom)

    private fun flipCamera() { rig.flip() }

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
            for ((k, v) in metrics) if (!v.isNaN() && !v.isInfinite()) metricSums.getOrPut(k) { DoubleArray(2) }.let { it[0] += v; it[1] += 1.0 }
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
            // movement coaching on the on-device signal: tempo prompts, verdict per movement,
            // and a reminder of the move when nothing has happened for a while
            val mc = moveCoach
            if (mc != null && local != null && !local.calibrating) {
                val upd = mc.update(local.signal, now, local.cues.isNotEmpty(), local.repJustDone)
                upd.prompt?.let { pr ->
                    if (tempoPromptsOn) runOnUiThread { coach?.prompt(t(if (pr == MoveCoach.Prompt.HOLD) "pr_hold" else "pr_release")) }
                }
                upd.feedback?.let { onMoveFeedback(it) }
                if (ready && !mc.isMoving && repsNow < exercise.targetReps &&
                    now - mc.lastActivityMs > REMIND_MS && now - lastExplainAt > REMIND_MS) {
                    lastExplainAt = now
                    runOnUiThread { explainMove(first = false) }
                }
            }
            // voice guidance: calibration finished (then the first move is explained), then every new rep
            val calibratingNow = if (sp != null) sp.optBoolean("calibrating") else (local == null || local.calibrating)
            if (wasCalibrating && !calibratingNow && ready) {
                moveCoach?.reset(now)
                runOnUiThread { coach?.guide(t("vg_calibrated"), flush = true); explainMove(first = true, cycles = 2) }
            }
            wasCalibrating = calibratingNow
            if (repsNow > lastRepsAnnounced) {
                lastRepsAnnounced = repsNow
                val fb = takeFeedback()
                runOnUiThread { coach?.rep(repsNow, exercise.targetReps, fb) }
            }
            if (cue.isNotEmpty()) postCue(cue)
            val source = if (sp != null) "server ${sp.optInt("latency_ms")}ms" else "on-device ${res.latencyMs}ms"
            val metricsLine = metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" } +
                "\nsignal=${fmt(local?.signal)} $source frames=$framesProcessed"
            val repsText = "$repsNow/${exercise.targetReps}"
            b.overlay.show(OverlayView.Frame(named, w, h, missing, ready))
            runOnUiThread { b.tvReps.text = repsText; b.tvMetrics.text = metricsLine }

            var wroteVideo = false
            if (recordEnabled && !recorderFailed) {
                val r = recorder ?: runCatching { newRecorder(w, h) }.getOrElse {
                    // don't retry (and leak an encoder) on every frame
                    recorderFailed = true; recordFile?.delete(); recordFile = null; null
                }
                val before = r?.framesWritten ?: 0
                // clean recordings keep only the skeleton; the replay draws the text as a layer it can hide
                if (cleanRecording) r?.drawFrame(bmp, named, w, h, missing, ready, "", "", "", frontCamera)
                else r?.drawFrame(bmp, named, w, h, missing, ready, metricsLine, lastCue, repsText, frontCamera)
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
        recordFile = f
        return SessionRecorder(f, w, h).also { recorder = it }
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
                SessionTimeline.header(exercise.id, exercise.signal.removePrefix("abs:"), exercise.targetReps, startedAt,
                    videoText = !cleanRecording))
        }.getOrNull()
        paused = false; wasCalibrating = true; lastRepsAnnounced = 0; recorderFailed = false
        moveCoach = MoveCoach(exercise); pendingFeedback = null; lastExplainAt = startedAt
        b.tvFeedback.text = ""; b.tvFeedback.visibility = View.GONE
        connectServerIfConfigured()
        running = true
        labelButtons(); applyOrientation()
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
        moveCoach = null
        b.moveGuide.removeCallbacks(hideGuide); b.moveGuide.visibility = View.GONE
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
        if (isDestroyed || isFinishing) return
        labelButtons(); applyOrientation()
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
        applyOrientation()
        dlg.setOnDismissListener { if (pendingDialog === dlg) { pendingDialog = null; applyOrientation() } }
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
        // Leaving mid-session (back, or the system recreating the screen) keeps the data.
        // Before, only isFinishing saved, so a recreation dropped the session.
        if (running) stopAndSave(askAfter = false)
        pendingDialog?.setOnDismissListener(null); pendingDialog?.dismiss(); pendingDialog = null
        running = false
        analysisExecutor.shutdown()
        poseEngine?.close()
        server?.close()
        recorder?.let { runCatching { it.stop() } }
        timeline?.close()
        voice?.stop()
        if (::b.isInitialized) b.moveGuide.removeCallbacks(hideGuide)
        coach?.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val KEY_FRONT = "front"
        const val KEY_CAMERA = "camera_id"
        const val KEY_ZOOM = "zoom"
        const val KEY_CHROME_HIDDEN = "chrome_hidden"
        /** Re-explain the move after this long without any movement. */
        const val REMIND_MS = 15_000L
    }
}
