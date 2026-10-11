package com.spinecare.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.spinecare.app.I18n.t
import java.util.concurrent.Executors
import kotlin.math.min

/** Processes a video the user picked (a clinician's demonstration or their
 *  own recording): every 100 ms frame goes through on-device pose, the framing
 *  gate, the exercise tracker and the placement coach. Processed frames are
 *  shown with the session overlay while it runs; afterwards the user can scrub
 *  through them and read tips on camera placement and form. The video is read
 *  in place and never copied or uploaded. */
class VideoAnalysisActivity : AppCompatActivity() {
    private lateinit var exercise: Exercise
    private lateinit var image: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var summary: LinearLayout
    private lateinit var scrub: SeekBar
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    /** Per-frame result kept for scrubbing (landmarks are small; frames are re-read on demand). */
    private data class Processed(val tMs: Long, val lms: Map<String, Metrics.P>?, val w: Int, val h: Int,
                                 val missing: List<String>, val ready: Boolean, val metrics: String, val cue: String, val reps: Int)
    private val results = ArrayList<Processed>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val ex = Plan.byId(this, intent.getStringExtra("exercise_id") ?: "")
        val uri = intent.data
        if (ex == null || uri == null || !ex.cameraTracked) { finish(); return }
        exercise = ex
        title = t("va_title")
        val ui = Ui(this)
        setContentView(ui.root)
        ui.h(ex.name(I18n.lang))
        image = ui.add(ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(Ui.SURFACE) })
        progress = ui.add(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 })
        status = ui.p(t("va_processing").format(0), Ui.MUTED, 13f)
        scrub = ui.add(SeekBar(this).apply { isEnabled = false })
        summary = ui.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        worker.execute { analyze(uri) }
    }

    private fun analyze(uri: Uri) {
        val mmr = MediaMetadataRetriever()
        val engine = runCatching { PoseEngine(this) }.getOrNull()
        try {
            mmr.setDataSource(this, uri)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (engine == null || duration <= 0) { fail(); return }
            val end = min(duration, MAX_MS)
            val minConf = ServerConfig.minConfidence(this).toDouble()
            val tracker = ExerciseTracker(exercise, (Plan.bundle(this).calibrationS * 1000).toLong(), I18n.lang)
            val report = VideoReport(exercise)
            var tMs = 0L
            var lastCue = ""
            while (tMs <= end && !cancelled) {
                val frame = frameAt(mmr, tMs)
                if (frame != null) {
                    val w = frame.width; val h = frame.height
                    val named = engine.detect(frame).landmarks?.let { Metrics.named(it, w, h) }
                    val framing = Framing.check(named, exercise.requiredLandmarks, minConf, w.toDouble(), h.toDouble())
                    val metrics = named?.let { Metrics.compute(it) } ?: emptyMap()
                    val st = tracker.update(metrics, framing.ready, tMs)
                    val placement = PlacementCoach.assess(named, w.toDouble(), h.toDouble(), exercise, minConf)
                    report.add(tMs, placement, framing.ready, st)
                    val cue = when {
                        !placement.ready -> placement.firstFix?.let { PlacementCoach.fixText(it.fixKey) } ?: ""
                        st.calibrating -> t("calibrating")
                        st.cues.isNotEmpty() -> st.cues.first().text
                        st.repJustDone -> t("rep_done").format(st.reps, exercise.targetReps)
                        else -> lastCue
                    }
                    lastCue = cue
                    val line = "signal=${st.signal?.let { "%.3f".format(it) } ?: "-"}  t=${"%.1f".format(tMs / 1000.0)}s"
                    val p = Processed(tMs, named, w, h, framing.missing, framing.ready, line, cue, st.reps)
                    synchronized(results) { results.add(p) }
                    val shown = draw(frame, p)
                    val pct = (100 * tMs / end).toInt()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        image.setImageBitmap(shown); progress.progress = pct
                        status.text = t("va_processing").format(pct)
                    }
                }
                tMs += STEP_MS
            }
            runOnUiThread { if (!isDestroyed) finished(report, duration > MAX_MS) }
        } catch (e: Exception) {
            fail()
        } finally {
            runCatching { mmr.release() }
            engine?.close()
        }
    }

    private fun frameAt(mmr: MediaMetadataRetriever, tMs: Long): Bitmap? = runCatching {
        val us = tMs * 1000
        val raw = if (Build.VERSION.SDK_INT >= 27)
            mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, MAX_SIDE, MAX_SIDE)
        else mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        raw?.let { scaleDown(it) }
    }.getOrNull()

    private fun scaleDown(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        val c = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        if (m <= MAX_SIDE) return c
        val k = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(c, (c.width * k).toInt(), (c.height * k).toInt(), true)
    }

    private fun draw(frame: Bitmap, p: Processed): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        OverlayRenderer.draw(Canvas(out), p.lms, p.w, p.h, p.missing, p.ready, p.metrics, p.cue,
            "${p.reps}/${exercise.targetReps}", false)
        return out
    }

    private fun fail() = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        status.text = t("va_failed"); status.setTextColor(Ui.BAD); progress.progress = 0
    }

    private fun finished(report: VideoReport, truncated: Boolean) {
        progress.progress = 100
        status.text = t("va_done"); status.setTextColor(Ui.OK)
        val ui = Ui(this)
        fun line(s: String, color: Int = Ui.TEXT, size: Float = 14f) = ui.p(s, color, size, summary)
        line(t("va_frames").format(report.frames, (report.durationMs / 1000).toInt()), Ui.MUTED, 13f)
        if (truncated) line(t("va_too_long"), Ui.WARN, 13f)
        line(t("va_reps").format(report.reps, exercise.targetReps), Ui.OK, 16f)
        line(t("va_framed").format((report.framedFraction * 100).toInt()))
        ui.h(t("va_tips"), summary)
        for (tip in report.tipTexts()) line("• $tip")
        val n = synchronized(results) { results.size }
        if (n > 1) {
            line(t("va_scrub"), Ui.MUTED, 12f)
            scrub.max = n - 1; scrub.progress = n - 1; scrub.isEnabled = true
            scrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) showFrame(v) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    @Volatile private var wanted = -1
    private val scrubber = Executors.newSingleThreadExecutor()

    /** Re-reads one frame and redraws its stored result; skips stale requests while dragging. */
    private fun showFrame(i: Int) {
        wanted = i
        val uri = intent.data ?: return
        scrubber.execute {
            if (wanted != i) return@execute
            val p = synchronized(results) { results.getOrNull(i) } ?: return@execute
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(this, uri)
                val frame = frameAt(mmr, p.tMs) ?: return@execute
                val shown = draw(frame, p)
                runOnUiThread { if (!isDestroyed && wanted == i) image.setImageBitmap(shown) }
            } catch (_: Exception) {
            } finally { runCatching { mmr.release() } }
        }
    }

    override fun onDestroy() {
        cancelled = true
        worker.shutdown(); scrubber.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val STEP_MS = 100L
        private const val MAX_MS = 180_000L
        private const val MAX_SIDE = 720
    }
}
