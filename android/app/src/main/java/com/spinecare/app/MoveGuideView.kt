package com.spinecare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Small picture-in-picture of the next movement, shown on the exercise screen
 *  while the coach explains it: the guide's demonstration figure loops one rep
 *  (both sides for side-to-side exercises) with the plan's tempo, and arrows
 *  show which way the head, shoulders or arms travel.
 *
 *  When [adjustable], the user drags it anywhere, resizes it with the corner
 *  handle or a pinch (keeping the 3:4 frame, so the figure is never cut), and
 *  closes it with the ✕; a tap replays the loop. [onAdjusted] fires when a move
 *  or resize ends so the screen can save the new place. */
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

    var adjustable = false
        set(v) { field = v; invalidate() }
    var onClose: (() -> Unit)? = null
    var onAdjusted: (() -> Unit)? = null

    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xCC0B1220.toInt() }
    private val chromeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Ui.TEXT
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var gesture = NONE
    private var downX = 0f; private var downY = 0f
    private var startX = 0f; private var startY = 0f; private var startW = 0
    private var moved = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { resizeTo(width * d.scaleFactor, keepCenter = true); return true }
    })

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
        if (adjustable) drawChrome(canvas)
        if (visibility == VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** ✕ in the top-right corner, resize grip in the bottom-right one. */
    private fun drawChrome(c: Canvas) {
        val br = BUTTON_DP * dp / 2f
        val cx = width - br - 3f * dp; val cy = br + 3f * dp
        c.drawCircle(cx, cy, br, chrome)
        chromeLine.strokeWidth = 2.2f * dp
        val k = br * 0.38f
        c.drawLine(cx - k, cy - k, cx + k, cy + k, chromeLine); c.drawLine(cx - k, cy + k, cx + k, cy - k, chromeLine)
        val gx = width - 3f * dp; val gy = height - 3f * dp
        c.drawCircle(gx - br + 2f * dp, gy - br + 2f * dp, br, chrome)
        for (i in 1..3) {
            val o = i * br * 0.42f
            c.drawLine(gx - o - 3f * dp, gy - 3f * dp, gx - 3f * dp, gy - o - 3f * dp, chromeLine)
        }
    }

    private fun inClose(x: Float, y: Float) = x > width - BUTTON_DP * 1.4f * dp && y < BUTTON_DP * 1.4f * dp
    private fun inGrip(x: Float, y: Float) = x > width - BUTTON_DP * 1.5f * dp && y > height - BUTTON_DP * 1.5f * dp

    private fun area(): Pair<Int, Int> {
        val p = parent as? View ?: return Pair(width, height)
        return Pair(areaWidth?.invoke() ?: p.width, p.height)
    }

    /** Width of the free area (the screen minus a side panel), when it differs from the parent's. */
    var areaWidth: (() -> Int)? = null

    private fun resizeTo(wIn: Float, keepCenter: Boolean) {
        val (aw, ah) = area()
        val w = GuideBox.clampWidth(wIn, aw, ah, dp)
        val h = w * GuideBox.ASPECT
        val lp = layoutParams ?: return
        val oldW = width.toFloat(); val oldH = height.toFloat()
        if (lp.width == w.toInt() && lp.height == h.toInt()) return
        lp.width = w.toInt(); lp.height = h.toInt(); layoutParams = lp
        val nx = if (keepCenter) x + (oldW - w) / 2f else x
        val ny = if (keepCenter) y + (oldH - h) / 2f else y
        x = GuideBox.clampLeft(nx, w, aw); y = GuideBox.clampTop(ny, h, ah)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!adjustable) return super.onTouchEvent(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.rawX; downY = e.rawY; startX = x; startY = y; startW = width; moved = false
                gesture = when { inClose(e.x, e.y) -> CLOSE; inGrip(e.x, e.y) -> RESIZE; else -> DRAG }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { gesture = PINCH; moved = true }
            MotionEvent.ACTION_MOVE -> if (gesture != PINCH && !scaler.isInProgress) {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                if (moved) when (gesture) {
                    DRAG, CLOSE -> {
                        gesture = DRAG
                        val (aw, ah) = area()
                        x = GuideBox.clampLeft(startX + dx, width.toFloat(), aw)
                        y = GuideBox.clampTop(startY + dy, height.toFloat(), ah)
                    }
                    RESIZE -> resizeTo(startW + maxOf(dx, dy / GuideBox.ASPECT), keepCenter = false)
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    moved -> onAdjusted?.invoke()
                    gesture == CLOSE -> onClose?.invoke()
                    else -> { restart(); performClick() }
                }
                gesture = NONE
            }
            MotionEvent.ACTION_CANCEL -> { if (moved) onAdjusted?.invoke(); gesture = NONE }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

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
        const val NONE = 0; const val DRAG = 1; const val RESIZE = 2; const val PINCH = 3; const val CLOSE = 4
        const val BUTTON_DP = 26f
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
