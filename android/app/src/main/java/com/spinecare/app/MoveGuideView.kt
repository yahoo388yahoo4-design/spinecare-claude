package com.spinecare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Small picture-in-picture of the next movement, shown on the exercise screen
 *  while the coach explains it: the guide's demonstration figure loops one rep
 *  (both sides for side-to-side exercises) with the plan's tempo, and arrows
 *  show which way the head, shoulders or arms travel. */
class MoveGuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var demo: GuideDemo? = null
        set(v) { field = v; arrows = v?.let { computeArrows(it) } ?: emptyList(); invalidate() }

    private var startedAt = SystemClock.uptimeMillis()
    private var arrows: List<List<Pair<Metrics.P, Metrics.P>>> = emptyList()   // per demo rep shown
    private val dp = context.resources.displayMetrics.density
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ui.OK
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ui.OK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.PRIMARY }
    private val clip = Path()

    /** Restarts the loop at the beginning of a rep. */
    fun restart() { startedAt = SystemClock.uptimeMillis(); invalidate() }

    private fun shownReps(d: GuideDemo) = minOf(2, d.reps)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = demo ?: return
        val loop = d.repMs * shownReps(d)
        val t = d.leadMs + (SystemClock.uptimeMillis() - startedAt) % loop
        val f = d.frameAt(t)
        val r = 12f * dp
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        GuideRenderer.drawDemo(canvas, d, f, GuideRenderer.Hud(null, 0, ""), overlay = false)
        drawArrows(canvas, arrows.getOrNull((f.rep - 1).coerceAtLeast(0) % max(1, arrows.size)) ?: emptyList())
        canvas.restore()
        border.strokeWidth = 2f * dp
        canvas.drawRoundRect(RectF(dp, dp, width - dp, height - dp), r, r, border)
        if (visibility == VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** Same center-crop as GuideRenderer.drawDemo, so arrows sit on the figure. */
    private fun drawArrows(c: Canvas, list: List<Pair<Metrics.P, Metrics.P>>) {
        val fw = GuideDemo.W.toFloat(); val fh = GuideDemo.H.toFloat()
        val s = max(width / fw, height / fh)
        val ox = (width - fw * s) / 2f; val oy = (height - fh * s) / 2f
        arrowPaint.strokeWidth = 3.5f * dp
        for ((a, b) in list) {
            val x0 = ox + a.x.toFloat() * s; val y0 = oy + a.y.toFloat() * s
            val x1 = ox + b.x.toFloat() * s; val y1 = oy + b.y.toFloat() * s
            c.drawLine(x0, y0, x1, y1, arrowPaint)
            val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
            val hl = 9f * dp
            val p = Path().apply {
                moveTo(x1, y1)
                lineTo((x1 - hl * cos(ang - 0.5)).toFloat(), (y1 - hl * sin(ang - 0.5)).toFloat())
                lineTo((x1 - hl * cos(ang + 0.5)).toFloat(), (y1 - hl * sin(ang + 0.5)).toFloat())
                close()
            }
            c.drawPath(p, head)
        }
    }

    private companion object {
        val KEYS = listOf("nose", "lwri", "rwri", "lelb", "relb", "lsho", "rsho", "lear", "rear")

        /** For each shown rep: up to three arrows from the neutral pose to the held
         *  position, for the landmarks that move most, lengthened so small
         *  movements (a chin tuck) are still readable. */
        fun computeArrows(d: GuideDemo): List<List<Pair<Metrics.P, Metrics.P>>> =
            (0 until minOf(2, d.reps)).map { i ->
                val held = d.frameAt(d.leadMs + i * d.repMs + d.inMs + 1).landmarks
                val moved = KEYS.mapNotNull { k ->
                    val a = d.base[k] ?: return@mapNotNull null
                    val b = held[k] ?: return@mapNotNull null
                    val len = hypot(b.x - a.x, b.y - a.y)
                    if (len < 4.0) null else Triple(a, b, len)
                }.sortedByDescending { it.third }
                val out = ArrayList<Pair<Metrics.P, Metrics.P>>()
                for ((a, b, len) in moved) {
                    if (out.size == 3) break
                    if (out.any { hypot(it.first.x - a.x, it.first.y - a.y) < 60.0 }) continue
                    val k = max(1.0, 70.0 / len)
                    out.add(a to Metrics.P(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, 1.0))
                }
                out
            }
    }
}
