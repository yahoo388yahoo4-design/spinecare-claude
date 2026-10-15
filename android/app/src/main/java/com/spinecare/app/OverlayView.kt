package com.spinecare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/** Draws pose skeleton + framing status + cue highlights over the camera preview.
 *  Delegates all drawing to OverlayRenderer so recordings match the live view.
 *  State is replaced as one immutable snapshot from the analysis thread.
 *  With a framing-coach hint it also draws, under the skeleton, a ghost outline
 *  of where to sit and a pulsing arrow showing which way to move (live view
 *  only; recordings never show it). */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Frame(
        val landmarks: Map<String, Metrics.P>? = null,
        val frameW: Int = 0, val frameH: Int = 0,
        val missing: List<String> = emptyList(), val ready: Boolean = false,
        val metricsLine: String = "", val cue: String = "", val repsText: String = "",
        val coach: FrameCoach.Hint? = null,
    )

    @Volatile var frame = Frame()
    @Volatile var mirror = false

    private val dp = context.resources.displayMetrics.density
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val okFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun show(f: Frame) { frame = f; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame
        f.coach?.let { if (f.frameW > 0 && f.frameH > 0) drawCoach(canvas, it, f.frameW, f.frameH) }
        OverlayRenderer.draw(canvas, f.landmarks, f.frameW, f.frameH, f.missing, f.ready, f.metricsLine, f.cue, f.repsText, mirror)
        // keep the arrow pulsing between analysed frames
        if (f.coach?.ok == false && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawCoach(c: Canvas, hint: FrameCoach.Hint, fw: Int, fh: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = max(w / fw, h / fh)
        val ox = (w - fw * s) / 2f; val oy = (h - fh * s) / 2f
        fun px(x: Double): Float { val v = ox + x.toFloat() * s; return if (mirror) w - v else v }
        fun py(y: Double): Float = oy + y.toFloat() * s

        // ghost: where the body should be
        val g = hint.ghost
        ghostPaint.color = if (hint.ok) Color.argb(150, 52, 211, 153) else Color.argb(110, 255, 255, 255)
        ghostPaint.strokeWidth = 5f * dp
        for ((a, b) in GHOST_LINES) {
            val pa = g[a] ?: continue; val pb = g[b] ?: continue
            if (pa.c < 0.5 || pb.c < 0.5) continue      // far side in profile, or outside a close-up
            c.drawLine(px(pa.x), py(pa.y), px(pb.x), py(pb.y), ghostPaint)
        }
        val le = g["lear"]; val re = g["rear"]; val nose = g["nose"]
        val head = when {
            le != null && re != null && le.c > 0.5 && re.c > 0.5 -> Pair((le.x + re.x) / 2, (le.y + re.y) / 2 - 6 * fh / 960.0)
            le != null && nose != null -> Pair(le.x + (nose.x - le.x) * 0.3, le.y)
            else -> null
        }
        head?.let { (hx, hy) -> c.drawCircle(px(hx), py(hy), FrameCoach.headRadius(g).toFloat() * s, ghostPaint) }

        if (hint.ok) {
            // a green tick by the head
            val (hx, hy) = head ?: Pair(fw / 2.0, fh * 0.3)
            val cx = px(hx) + 70f * dp * (if (mirror) -1 else 1); val cy = py(hy)
            okFill.color = Color.argb(220, 52, 211, 153)
            c.drawCircle(cx, cy, 22f * dp, okFill)
            arrowPaint.color = Color.WHITE; arrowPaint.strokeWidth = 4.5f * dp
            c.drawLine(cx - 9f * dp, cy, cx - 2f * dp, cy + 8f * dp, arrowPaint)
            c.drawLine(cx - 2f * dp, cy + 8f * dp, cx + 11f * dp, cy - 8f * dp, arrowPaint)
            return
        }

        val pulse = (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 260.0).toFloat())
        val color = Color.argb((170 + 80 * pulse).toInt(), 251, 191, 36)
        arrowPaint.color = color; arrowHead.color = color
        arrowPaint.strokeWidth = 8f * dp
        val ctr = hint.center
        val cx = ctr?.let { px(it.first) } ?: (w / 2f)
        val cy = ctr?.let { py(it.second) } ?: (h / 2f)
        val len = 70f * dp + 14f * dp * pulse
        when (hint.kind) {
            FrameCoach.Kind.LEFT, FrameCoach.Kind.RIGHT -> {
                // the way the body moves in the picture, flipped like the preview when mirrored
                var dir = if (hint.kind == FrameCoach.Kind.RIGHT) 1f else -1f
                if (mirror) dir = -dir
                arrow(c, cx, cy, cx + dir * len * 1.4f, cy)
            }
            FrameCoach.Kind.CLOSER -> { arrow(c, cx - 30f * dp, cy, cx - 30f * dp - len, cy); arrow(c, cx + 30f * dp, cy, cx + 30f * dp + len, cy) }
            FrameCoach.Kind.BACK -> { arrow(c, cx - 30f * dp - len, cy, cx - 30f * dp, cy); arrow(c, cx + 30f * dp + len, cy, cx + 30f * dp, cy) }
            FrameCoach.Kind.UP, FrameCoach.Kind.DOWN -> {
                // phone height: an arrow at the side of the screen (up = raise or tilt the phone up)
                val x = w - 44f * dp; val mid = h * 0.45f
                val d = if (hint.kind == FrameCoach.Kind.UP) -1f else 1f
                arrow(c, x, mid - d * len * 0.7f, x, mid + d * len * 0.7f)
            }
            else -> {}
        }
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.drawLine(x0, y0, x1, y1, arrowPaint)
        val ang = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val hl = 22f * dp
        val p = Path().apply {
            moveTo(x1 + (Math.cos(ang) * hl * 0.4).toFloat(), y1 + (Math.sin(ang) * hl * 0.4).toFloat())
            lineTo((x1 - hl * Math.cos(ang - 0.6)).toFloat(), (y1 - hl * Math.sin(ang - 0.6)).toFloat())
            lineTo((x1 - hl * Math.cos(ang + 0.6)).toFloat(), (y1 - hl * Math.sin(ang + 0.6)).toFloat())
            close()
        }
        c.drawPath(p, arrowHead)
    }

    private companion object {
        val GHOST_LINES = listOf(
            "lsho" to "rsho", "lsho" to "lhip", "rsho" to "rhip", "lhip" to "rhip",
            "lsho" to "lelb", "rsho" to "relb", "lelb" to "lwri", "relb" to "rwri",
            "lhip" to "lknee", "rhip" to "rknee", "lknee" to "lank", "rknee" to "rank",
        )
    }
}
