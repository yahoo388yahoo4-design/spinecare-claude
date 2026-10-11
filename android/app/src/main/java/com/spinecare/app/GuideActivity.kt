package com.spinecare.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.spinecare.app.I18n.t
import java.io.File

/** Video guide for one camera-tracked exercise:
 *  1. where to put the phone (animated diagram + the view's placement steps),
 *  2. an animated demonstration processed by the real tracker, with a
 *     "common mistake" variant, exportable as an MP4,
 *  3. a live camera setup check, and
 *  4. analysis of any video the user picks (clinician demo or their own). */
class GuideActivity : AppCompatActivity() {
    private lateinit var exercise: Exercise
    private lateinit var demoView: GuideDemoView
    private var mistake = false
    @Volatile private var exporting = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) startActivity(Intent(this, VideoAnalysisActivity::class.java)
            .setData(uri).putExtra("exercise_id", exercise.id).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val ex = Plan.byId(this, intent.getStringExtra("exercise_id") ?: "")
        if (ex == null || !ex.cameraTracked) { finish(); return }
        exercise = ex
        title = t("gd_title")
        val calibMs = (Plan.bundle(this).calibrationS * 1000).toLong()
        val lang = I18n.lang
        val ui = Ui(this)
        setContentView(ui.root)

        ui.h(ex.name(lang))
        ui.muted(t("gd_approval_note"))

        // 1. camera placement
        ui.h(t("gd_step1"))
        ui.add(PlacementDiagramView(this, ex.view))
        val guide = FramingGuide.forView(ex.view)
        ui.p(guide.headline, Ui.TEXT, 15f)
        for (s in guide.steps) ui.p("• $s", Ui.MUTED, 13f)

        // 2. demonstration
        ui.h(t("gd_step2"))
        ui.p(ex.description(lang), Ui.TEXT, 14f)
        demoView = ui.add(GuideDemoView(this))
        demoView.demo = GuideDemo(ex, calibMs)
        val controls = ui.row()
        val playBtn = ui.button(t("gd_pause"), controls) { b ->
            if (demoView.playing) { demoView.pause(); b.text = t("gd_play") } else { demoView.play(); b.text = t("gd_pause") }
        }
        ui.button(t("gd_replay"), controls) { demoView.restart(); playBtn.text = t("gd_pause") }
        val overlayBtn = ui.button(t("gd_overlay_on"), controls) { b ->
            demoView.overlay = !demoView.overlay
            b.text = if (demoView.overlay) t("gd_overlay_on") else t("gd_overlay_off")
        }
        for (b in listOf(playBtn, overlayBtn)) b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val mistakeNote = ui.p("", Ui.WARN, 13f)
        val formRow = ui.row()
        val correctBtn = ui.button(t("gd_correct"), formRow) {}
        val mistakeBtn = ui.button(t("gd_mistake"), formRow) {}
        fun setMistake(m: Boolean) {
            val d = GuideDemo(ex, calibMs, mistake = m)
            if (m && d.mistakeRule == null) { Toast.makeText(this, t("gd_no_mistake"), Toast.LENGTH_SHORT).show(); return }
            mistake = m
            demoView.demo = d; playBtn.text = t("gd_pause"); demoView.play()
            correctBtn.isEnabled = m; mistakeBtn.isEnabled = !m
            mistakeNote.text = d.mistakeRule?.let { t("gd_mistake_note").format(it.cue[lang] ?: it.cue["en"] ?: "") } ?: ""
        }
        correctBtn.setOnClickListener { setMistake(false) }
        mistakeBtn.setOnClickListener { setMistake(true) }
        correctBtn.isEnabled = false
        mistakeBtn.isEnabled = ex.formRules.isNotEmpty()
        if (!demoView.demo!!.countable) ui.warn(t("gd_not_countable"))
        ui.muted(t("gd_tempo").format(secs(ex.tempoIn), secs(ex.tempoHold), secs(ex.tempoOut)))
        val metric = ex.signal.removePrefix("abs:")
        ui.muted(t("gd_measures").format(if (I18n.has("gd_sig_$metric", "en")) t("gd_sig_$metric") else metric))
        ui.muted(t("gd_demo_body"))
        ui.button(t("gd_save_video")) { b -> exportVideo(b) }
        if (ex.videoUrl.isNotBlank()) ui.button(t("open_video")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ex.videoUrl))) }
                .onFailure { Toast.makeText(this, t("cannot_open"), Toast.LENGTH_SHORT).show() }
        }
        if (ex.stops(lang).isNotEmpty()) ui.p(t("stop_if") + " " + ex.stops(lang).joinToString(), Ui.WARN, 12f)

        // 3. live setup check
        ui.h(t("gd_step3"))
        ui.muted(t("gd_setup_body"))
        ui.button(t("gd_setup_open")) {
            startActivity(Intent(this, SetupCheckActivity::class.java).putExtra("exercise_id", ex.id))
        }

        // 4. analyze a video
        ui.h(t("gd_step4"))
        ui.muted(t("gd_video_body"))
        ui.button(t("gd_video_pick")) { runCatching { pickVideo.launch("video/*") } }
    }

    private fun secs(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

    override fun onPause() { super.onPause(); if (::demoView.isInitialized) demoView.pause() }

    /** Renders the current demo to MP4 in real time (the encoder timestamps frames as they are drawn). */
    private fun exportVideo(button: Button) {
        if (exporting) return
        exporting = true
        button.isEnabled = false; button.text = t("gd_saving")
        val demo = GuideDemo(exercise, (Plan.bundle(this).calibrationS * 1000).toLong(), mistake)
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "guide_${exercise.id}${if (mistake) "_mistake" else ""}.mp4")
        Thread({
            val ok = runCatching {
                val rec = SessionRecorder(file, GuideDemo.W, GuideDemo.H)
                val player = DemoPlayer(demo)
                val bmp = Bitmap.createBitmap(GuideDemo.W, GuideDemo.H, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val t0 = SystemClock.uptimeMillis()
                var t = 0L
                while (t <= demo.durationMs + 1000 && !isDestroyed) {
                    val ft = t.coerceAtMost(demo.durationMs)
                    GuideRenderer.drawDemo(canvas, demo, demo.frameAt(ft), player.hudAt(ft), overlay = true)
                    rec.drawFrame(bmp, null, GuideDemo.W, GuideDemo.H, emptyList(), true, "", "", "", false)
                    t += FRAME_MS
                    val wait = t0 + t - SystemClock.uptimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                }
                val n = rec.framesWritten
                rec.stop()
                n > 0 && file.exists()
            }.getOrDefault(false)
            runOnUiThread {
                exporting = false
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true; button.text = t("gd_save_video")
                Toast.makeText(this, if (ok) t("gd_saved_video") else t("gd_save_failed"), Toast.LENGTH_SHORT).show()
                if (ok) openVideo(file)
            }
        }, "guide-export").start()
    }

    private fun openVideo(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(view) }.onFailure {
            val share = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(share, t("gd_title"))) }
        }
    }

    companion object { private const val FRAME_MS = 66L }
}
