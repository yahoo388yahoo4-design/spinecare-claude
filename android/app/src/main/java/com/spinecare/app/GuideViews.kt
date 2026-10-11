package com.spinecare.app

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.spinecare.app.I18n.t

/** Runs the demo through the real framing gate + tracker at a fixed 10 Hz,
 *  so the numbers shown are what a live session would produce. */
class DemoPlayer(val demo: GuideDemo) {
    private var tracker = newTracker()
    private var fedUntil = -STEP_MS
    private var hud = GuideRenderer.Hud(null, 0, "")
    private var lastCue = ""

    private fun newTracker() = ExerciseTracker(demo.exercise, demo.calibrationMs, I18n.lang)

    fun hudAt(tMs: Long): GuideRenderer.Hud {
        if (tMs < fedUntil) { tracker = newTracker(); fedUntil = -STEP_MS; lastCue = "" }
        while (fedUntil + STEP_MS <= tMs) {
            fedUntil += STEP_MS
            val lms = demo.frameAt(fedUntil).landmarks
            val framing = Framing.check(lms, demo.exercise.requiredLandmarks, demo.exercise.minConf,
                GuideDemo.W.toDouble(), GuideDemo.H.toDouble())
            val st = tracker.update(Metrics.compute(lms), framing.ready, fedUntil)
            lastCue = when {
                st.calibrating -> t("calibrating")
                st.cues.isNotEmpty() -> st.cues.first().text
                st.repJustDone -> t("rep_done").format(st.reps, demo.reps)
                else -> if (lastCue == t("calibrating")) "" else lastCue
            }
            hud = GuideRenderer.Hud(st.signal, st.reps, lastCue)
        }
        return hud
    }

    companion object { const val STEP_MS = 100L }
}

/** Looping demonstration with play/pause, tracking overlay toggle and a
 *  correct/mistake switch. Height follows the demo's 3:4 frame. */
class GuideDemoView(context: Context) : View(context) {
    var demo: GuideDemo? = null
        set(v) { field = v; player = v?.let { DemoPlayer(it) }; position = 0L; startedAt = SystemClock.uptimeMillis(); invalidate() }
    var overlay = true
        set(v) { field = v; invalidate() }
    var playing = true
        private set
    var onFinished: (() -> Unit)? = null

    private var player: DemoPlayer? = null
    private var position = 0L
    private var startedAt = SystemClock.uptimeMillis()

    fun play() { if (!playing) { playing = true; startedAt = SystemClock.uptimeMillis() - position; invalidate() } }
    fun pause() { playing = false }
    fun restart() { position = 0L; startedAt = SystemClock.uptimeMillis(); playing = true; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * GuideDemo.H / GuideDemo.W)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = demo ?: return
        val pl = player ?: return
        if (playing) {
            position = SystemClock.uptimeMillis() - startedAt
            if (position > d.durationMs + 1500) {      // short pause on the last frame, then loop
                position = 0L; startedAt = SystemClock.uptimeMillis(); onFinished?.invoke()
            }
        }
        val t = position.coerceAtMost(d.durationMs)
        GuideRenderer.drawDemo(canvas, d, d.frameAt(t), pl.hudAt(t), overlay)
        if (playing && isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Animated camera-placement diagram for one exercise view. */
class PlacementDiagramView(context: Context, private val view: String) : View(context) {
    private val start = SystemClock.uptimeMillis()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        GuideRenderer.drawPlacement(canvas, view, SystemClock.uptimeMillis() - start)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
