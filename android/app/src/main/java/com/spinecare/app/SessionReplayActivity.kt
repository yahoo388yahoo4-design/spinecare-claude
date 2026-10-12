package com.spinecare.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.spinecare.app.I18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** In-app preview of a saved session. Plays the recorded video (overlay
 *  burned in) or, with no video, re-draws the skeleton and HUD from the saved
 *  sensor timeline; underneath, a metric chart with rep and cue marks, IMU
 *  lines from the BLE posture nodes, and a readout at the playhead.
 *  Everything stays on the device. Works in portrait and landscape (side-by-side
 *  layout); the playhead, speed and view survive a rotation. */
class SessionReplayActivity : AppCompatActivity() {
    private lateinit var stage: ReplayStageView
    private lateinit var video: VideoView
    private lateinit var chart: TimelineChartView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var readout: TextView
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var modeRow: LinearLayout
    private lateinit var videoBtn: Button
    private lateinit var sensorBtn: Button
    private lateinit var status: TextView

    private var tl: SessionTimeline.Timeline? = null
    private var videoFile: File? = null
    private var player: MediaPlayer? = null
    private var useVideo = false
    private var playing = false
    private var clock = 0L
    private var lastTick = 0L
    private var speedIdx = 1
    private var preferVideo = true
    private var target = 0
    private val speeds = floatArrayOf(0.5f, 1f, 2f)
    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() { tick(); if (playing) main.postDelayed(this, 33) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val store = LocalStore(this)
        val ts = intent.getLongExtra("ts", -1L)
        val row = LocalStore.findSession(store.allSessions(), ts) ?: run { finish(); return }
        val ex = Plan.byId(this, row.optString("exercise"))
        target = ex?.targetReps ?: 0
        videoFile = store.videoFile(row)?.takeIf { it.exists() }
        buildUi(row, ex?.name(I18n.lang) ?: row.optString("exercise"))
        savedInstanceState?.let {
            clock = it.getLong(KEY_CLOCK, 0L)
            speedIdx = it.getInt(KEY_SPEED, 1).coerceIn(0, speeds.size - 1)
            speedBtn.text = speedLabel()
            preferVideo = it.getBoolean(KEY_VIDEO, true)
        }

        val tlFile = store.timelineFile(row)?.takeIf { it.exists() }
        if (tlFile == null) { onTimeline(null); return }
        status.text = t("replay_loading")
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { SessionTimeline.read(tlFile) }
            onTimeline(parsed)
        }
    }

    private fun buildUi(row: JSONObject, title: String) {
        val dp = resources.displayMetrics.density
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(12, 12, 12, 12) }
        col.addView(TextView(this).apply {
            text = "${row.optString("date")}  $title"; setTextColor(Ui.TEXT); textSize = 16f
        })
        status = TextView(this).apply { setTextColor(Ui.MUTED); textSize = 12f }
        col.addView(status)

        val box = FrameLayout(this)
        video = VideoView(this)
        box.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stage = ReplayStageView(this).also { it.target = target }
        box.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!landscape) col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        videoBtn = btn(t("replay_video")) { setMode(true) }
        sensorBtn = btn(t("replay_sensors")) { setMode(false) }
        modeRow.addView(videoBtn); modeRow.addView(sensorBtn)
        col.addView(modeRow)

        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) seekTo(duration() * v / 1000)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeText = TextView(this).apply { setTextColor(Ui.MUTED); textSize = 12f }
        seekRow.addView(timeText)
        col.addView(seekRow)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(btn(t("replay_prev_rep")) { jumpRep(-1) })
        playBtn = btn(t("replay_play")) { if (playing) pause() else play() }
        controls.addView(playBtn)
        speedBtn = btn("1×") { cycleSpeed() }
        controls.addView(speedBtn)
        controls.addView(btn(t("replay_next_rep")) { jumpRep(1) })
        col.addView(controls)

        chart = TimelineChartView(this).apply { onSeek = { seekTo(it) } }
        col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (110 * dp).toInt()))
        col.addView(TextView(this).apply { text = t("replay_legend"); setTextColor(Ui.MUTED); textSize = 11f })

        readout = TextView(this).apply {
            setTextColor(Ui.TEXT); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            minLines = 3; setPadding(0, 8, 0, 8)
        }
        col.addView(readout)

        videoFile?.let { f ->
            col.addView(Button(this).apply {
                text = t("replay_external"); isAllCaps = false
                setOnClickListener { openExternal(f) }
            })
        }
        if (landscape) {
            // picture on the left, controls and chart in a scrolling column on the right
            val rowRoot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BG) }
            rowRoot.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
            rowRoot.addView(android.widget.ScrollView(this).apply { addView(col) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(rowRoot)
        } else setContentView(col)
    }

    // ------------------------------------------------------------------ setup

    private fun onTimeline(parsed: SessionTimeline.Timeline?) {
        tl = parsed
        val vf = videoFile
        if (parsed == null && vf == null) {
            status.text = t("replay_no_data")
            stage.visibility = View.GONE; video.visibility = View.GONE
            modeRow.visibility = View.GONE; chart.visibility = View.GONE
            return
        }
        status.text = when {
            parsed == null -> t("replay_video_only")
            vf == null -> t("replay_sensor_only")
            else -> ""
        }
        if (parsed != null) {
            if (target == 0) target = parsed.target
            stage.target = target
            val metric = parsed.metric
            val series = ArrayList<TimelineChartView.Series>()
            if (metric.isNotEmpty()) series.add(TimelineChartView.Series(metric, Ui.PRIMARY,
                FloatArray(parsed.samples.size) { parsed.samples[it].metrics[metric]?.toFloat() ?: Float.NaN }))
            if (parsed.samples.any { it.head != null }) series.add(TimelineChartView.Series(t("replay_head_pitch"), 0xFFC084FC.toInt(),
                FloatArray(parsed.samples.size) { parsed.samples[it].head?.get(0)?.toFloat() ?: Float.NaN }))
            if (parsed.samples.any { it.trunk != null }) series.add(TimelineChartView.Series(t("replay_trunk_pitch"), 0xFFF472B6.toInt(),
                FloatArray(parsed.samples.size) { parsed.samples[it].trunk?.get(0)?.toFloat() ?: Float.NaN }))
            chart.setData(parsed, series)
        } else chart.visibility = View.GONE

        if (vf != null) {
            video.setVideoPath(vf.absolutePath)
            video.setOnPreparedListener { mp -> player = mp; mp.setOnCompletionListener { pause() }; if (useVideo) seekTo(clock) }
            video.setOnErrorListener { _, _, _ -> player = null; if (useVideo) setMode(false); true }
        }
        modeRow.visibility = if (vf != null && parsed != null) View.VISIBLE else View.GONE
        setMode(vf != null && preferVideo)
        render()
    }

    private fun setMode(videoMode: Boolean) {
        val wasPlaying = playing
        if (wasPlaying) pause()
        useVideo = videoMode && videoFile != null
        if (!useVideo && tl == null) useVideo = videoFile != null
        // A hidden VideoView releases its player; onPrepared hands over a new one.
        if (!useVideo) player = null
        video.visibility = if (useVideo) View.VISIBLE else View.INVISIBLE
        stage.visibility = if (useVideo) View.GONE else View.VISIBLE
        videoBtn.isEnabled = !useVideo; sensorBtn.isEnabled = useVideo
        if (useVideo && player != null) seekTo(clock)
        if (wasPlaying) play()
        render()
    }

    // ------------------------------------------------------------------ transport

    private fun videoOffset() = tl?.videoOffsetMs ?: 0L

    private fun duration(): Long {
        val p = player
        val videoEnd = if (p != null && videoFile != null) runCatching { p.duration.toLong() }.getOrDefault(0L) + videoOffset() else 0L
        return maxOf(tl?.durationMs ?: 0L, videoEnd, 1L)
    }

    private fun play() {
        if (clock >= duration() - 50) seekTo(0)
        playing = true
        playBtn.text = t("replay_pause")
        if (useVideo) {
            video.start()
            applySpeed()
        }
        lastTick = System.currentTimeMillis()
        main.removeCallbacks(ticker); main.post(ticker)
    }

    private fun pause() {
        playing = false
        playBtn.text = t("replay_play")
        if (video.isPlaying) video.pause()
        main.removeCallbacks(ticker)
        render()
    }

    private fun applySpeed() {
        val p = player ?: return
        if (!video.isPlaying) return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speeds[speedIdx]) }
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        speedBtn.text = speedLabel()
        applySpeed()
    }

    private fun speedLabel() = when (speedIdx) { 0 -> "0.5×"; 1 -> "1×"; else -> "2×" }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong(KEY_CLOCK, clock); out.putInt(KEY_SPEED, speedIdx); out.putBoolean(KEY_VIDEO, useVideo || tl == null)
    }

    private fun seekTo(t: Long) {
        clock = t.coerceIn(0L, duration())
        if (useVideo && player != null) video.seekTo((clock - videoOffset()).coerceAtLeast(0L).toInt())
        render()
    }

    private fun jumpRep(dir: Int) {
        val reps = tl?.repTimes ?: return
        if (reps.isEmpty()) return
        // land 2 s before the rep completes, so the whole movement is visible
        val marks = reps.map { (it - 2000).coerceAtLeast(0L) }
        val next = if (dir > 0) marks.firstOrNull { it > clock + 300 } else marks.lastOrNull { it < clock - 300 }
        seekTo(next ?: if (dir > 0) marks.last() else 0L)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = now - lastTick; lastTick = now
        if (useVideo && player != null) {
            clock = video.currentPosition.toLong() + videoOffset()
        } else {
            clock += (dt * speeds[speedIdx]).toLong()
            if (clock >= duration()) { clock = duration(); pause() }
        }
        render()
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun render() {
        val d = duration()
        seek.progress = (clock * 1000 / d).toInt()
        timeText.text = " ${fmt(clock)} / ${fmt(d)}"
        val s = tl?.at(clock)
        stage.sample = s
        chart.playhead = clock
        if (s == null) { readout.text = ""; return }
        val sb = StringBuilder()
        sb.append(t("replay_reps_at").format(s.reps, target))
        if (s.cue.isNotEmpty()) sb.append("\n").append(s.cue)
        if (s.metrics.isNotEmpty())
            sb.append("\n").append(s.metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" })
        s.head?.let { sb.append("\n").append(t("replay_imu_head").format(it[0], it[1], it[2])) }
        s.trunk?.let { sb.append("\n").append(t("replay_imu_trunk").format(it[0], it[1], it[2])) }
        readout.text = sb.toString()
    }

    private fun openExternal(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(view) }.onFailure {
            val share = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(share, t("review"))) }
        }
    }

    override fun onPause() { super.onPause(); if (playing) pause() }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        runCatching { video.stopPlayback() }
        super.onDestroy()
    }

    private companion object {
        const val KEY_CLOCK = "clock"
        const val KEY_SPEED = "speed"
        const val KEY_VIDEO = "video"
    }
}
