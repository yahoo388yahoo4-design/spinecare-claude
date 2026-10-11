package com.spinecare.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spinecare.app.I18n.t
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injects demo videos into the app's on-device coaching pipeline, frame by frame:
 *  MediaPipe PoseEngine -> Metrics -> Framing gate -> ExerciseTracker (calibration,
 *  reps, form cues) -> OverlayRenderer, and the app's own SessionRecorder (MP4).
 *  Cue selection mirrors ExerciseActivity.analyze for the on-device path.
 *
 *  Videos come from androidTest assets/videos (sources.json + clips), placed there
 *  by CI from scripts/fetch_demo_videos.py; without them the test is skipped.
 *  Output goes to <external files>/replay/<id>/ (annotated frames, recorder MP4,
 *  per-frame log) plus replay/summary.json, and CI pulls it with adb. */
@RunWith(AndroidJUnit4::class)
class VideoReplayTest {
    @Test
    fun replayDemoVideosThroughOnDevicePipeline() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val assets = inst.context.assets
        val sourcesJson = runCatching { assets.open("videos/sources.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("no demo videos bundled (run scripts/fetch_demo_videos.py first)", sourcesJson != null)
        val videos = JSONObject(sourcesJson!!).getJSONArray("videos")
        val maxFrames = InstrumentationRegistry.getArguments().getString("maxFrames")?.toIntOrNull() ?: 270

        I18n.init(app)
        val outRoot = File(app.getExternalFilesDir(null), "replay").apply { deleteRecursively(); mkdirs() }
        val engine = PoseEngine(app)
        val bundle = Plan.bundle(app)
        val minConf = ServerConfig.minConfidence(app).toDouble()
        val summary = JSONArray()
        try {
            for (i in 0 until videos.length()) {
                val v = videos.getJSONObject(i)
                val ex = bundle.exercises.firstOrNull { it.id == v.getString("exercise_id") }
                if (ex == null) { Log.w(TAG, "unknown exercise ${v.getString("exercise_id")}"); continue }
                val clip = File(app.cacheDir, v.getString("file"))
                assets.open("videos/" + v.getString("file")).use { inp -> clip.outputStream().use { inp.copyTo(it) } }
                val res = replay(engine, clip, ex, File(outRoot, v.getString("id")), bundle.calibrationS, minConf, maxFrames)
                res.put("id", v.getString("id")).put("exercise_id", ex.id).put("title", v.optString("title"))
                    .put("license", v.optString("license"))
                summary.put(res)
                Log.i(TAG, "${v.getString("id")}: $res")
                clip.delete()
            }
        } finally {
            engine.close()
            File(outRoot, "summary.json").writeText(JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.joinToString()}")
                .put("videos", summary).toString(1))
        }

        assertTrue("no video was replayed", summary.length() > 0)
        for (i in 0 until summary.length()) {
            val s = summary.getJSONObject(i)
            assertTrue("${s.getString("id")}: no frames decoded", s.getInt("frames") > 0)
        }
        // The detector must find people in demo footage; per-clip rates are reported, not gated.
        val best = (0 until summary.length()).maxOf { summary.getJSONObject(it).getDouble("detection_rate") }
        assertTrue("pose detected in under half the frames of every clip (best ${"%.2f".format(best)})", best >= 0.5)
    }

    private fun replay(engine: PoseEngine, clip: File, ex: Exercise, out: File, calibrationS: Double,
                       minConf: Double, maxFrames: Int): JSONObject {
        out.mkdirs()
        val framesDir = File(out, "frames").apply { mkdirs() }
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(clip.absolutePath)
        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val count = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            ?: (durMs / 1000.0 * 6).toInt()
        val fps = if (durMs > 0) count * 1000.0 / durMs else 6.0

        val tracker = ExerciseTracker(ex, (calibrationS * 1000).toLong(), I18n.lang)
        var recorder: SessionRecorder? = null
        val recFile = File(out, "app_recorder.mp4")
        val log = JSONArray()
        val latencies = ArrayList<Long>()
        val cueCounts = HashMap<String, Int>()
        var detected = 0; var ready = 0; var frames = 0; var firstRepFrame = -1
        var lastCue = ""

        for (idx in 0 until minOf(count, maxFrames)) {
            val tMs = (idx * 1000.0 / fps).toLong()
            val raw = (if (Build.VERSION.SDK_INT >= 28) mmr.getFrameAtIndex(idx)
                       else mmr.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue
            val bmp = if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false)
            val w = bmp.width; val h = bmp.height
            frames++

            val det = engine.detect(bmp)
            latencies.add(det.latencyMs)
            val named = det.landmarks?.let { Metrics.named(it, w, h) }
            if (named != null) detected++
            val framing = Framing.check(named, ex.requiredLandmarks, minConf, w.toDouble(), h.toDouble())
            if (framing.ready) ready++
            val metrics = named?.let { Metrics.compute(it) } ?: emptyMap()
            val st = tracker.update(metrics, framing.ready, tMs)
            if (st.repJustDone && firstRepFrame < 0) firstRepFrame = idx
            st.cues.forEach { c -> cueCounts[c.metric] = (cueCounts[c.metric] ?: 0) + 1 }

            // same precedence as ExerciseActivity.analyze (on-device branch)
            val cue = when {
                !framing.ready -> framing.issues.distinct().joinToString(" ") { FramingGuide.fixFor(it) }
                st.calibrating -> t("calibrating")
                st.cues.isNotEmpty() -> st.cues.first().text
                st.repJustDone -> t("rep_done").format(st.reps, ex.targetReps)
                else -> ""
            }
            if (cue.isNotEmpty()) lastCue = cue
            val metricsLine = metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" } +
                "\nsignal=${st.signal?.let { "%.2f".format(it) } ?: "-"} on-device ${det.latencyMs}ms frame=$idx ${ex.id}"
            val repsText = "${st.reps}/${ex.targetReps}"

            val annotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(annotated)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            OverlayRenderer.draw(canvas, named, w, h, framing.missing, framing.ready, metricsLine, lastCue, repsText, false)
            File(framesDir, "f_%05d.jpg".format(idx)).outputStream().use { annotated.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            annotated.recycle()

            if (recorder == null) recorder = runCatching { SessionRecorder(recFile, w, h) }
                .onFailure { Log.w(TAG, "SessionRecorder unavailable: $it") }.getOrNull()
            recorder?.drawFrame(bmp, named, w, h, framing.missing, framing.ready, metricsLine, lastCue, repsText, false)

            log.put(JSONObject().put("frame", idx).put("t_ms", tMs).put("detected", named != null)
                .put("ready", framing.ready).put("missing", JSONArray(framing.missing))
                .put("metrics", JSONObject(metrics.filterValues { it.isFinite() } as Map<*, *>))
                .put("signal", st.signal?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("reps", st.reps).put("rep_done", st.repJustDone).put("calibrating", st.calibrating)
                .put("cue", cue).put("latency_ms", det.latencyMs))
            if (bmp !== raw) bmp.recycle()
            raw.recycle()
        }
        mmr.release()
        val recFrames = recorder?.framesWritten ?: 0
        recorder?.let { runCatching { it.stop() } }

        val sorted = latencies.sorted()
        val res = JSONObject()
            .put("frames", frames).put("fps", fps)
            .put("detected_frames", detected).put("detection_rate", if (frames > 0) detected.toDouble() / frames else 0.0)
            .put("ready_frames", ready).put("ready_rate", if (frames > 0) ready.toDouble() / frames else 0.0)
            .put("calibrated", !tracker.calibrating).put("reps", tracker.reps.reps).put("first_rep_frame", firstRepFrame)
            .put("cue_counts", JSONObject(cueCounts as Map<*, *>))
            .put("latency_p50_ms", sorted.getOrElse(sorted.size / 2) { 0L })
            .put("latency_p95_ms", sorted.getOrElse((sorted.size * 95) / 100) { 0L })
            .put("recorder_frames", recFrames)
            .put("recorder_bytes", if (recFile.exists()) recFile.length() else 0L)
        File(out, "log.json").writeText(JSONObject().put("summary", res).put("frames", log).toString())
        return res
    }

    companion object { private const val TAG = "VideoReplayTest" }
}
