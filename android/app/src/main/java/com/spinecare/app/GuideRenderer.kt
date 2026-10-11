package com.spinecare.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.spinecare.app.I18n.t
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the guide's animated scenes: the demonstration figure (with the same
 *  tracking overlay the live session uses, plus a signal meter), and the
 *  camera-placement diagram. Shared by the on-screen views and the MP4 export. */
object GuideRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.PRIMARY; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private const val BODY = 0xFF64748B.toInt()
    private const val BODY_FAR = 0xFF475569.toInt()
    private const val HAIR = 0xFF1F2937.toInt()
    private const val FLOOR = 0xFF111827.toInt()
    private const val FURNITURE = 0xFF334155.toInt()

    /** Live tracking numbers drawn on the demo: what the app "sees" in this frame. */
    data class Hud(val signal: Double?, val reps: Int, val cue: String)

    // ------------------------------------------------------------------ demo figure

    fun drawDemo(c: Canvas, demo: GuideDemo, f: GuideDemo.Frame, hud: Hud, overlay: Boolean) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        val fw = GuideDemo.W.toFloat(); val fh = GuideDemo.H.toFloat()
        val s = max(w / fw, h / fh)
        val ox = (w - fw * s) / 2f; val oy = (h - fh * s) / 2f
        fun x(v: Double) = ox + v.toFloat() * s
        fun y(v: Double) = oy + v.toFloat() * s
        val p = f.landmarks
        fun pt(k: String) = p[k]

        c.drawColor(Ui.BG)
        fill.color = FLOOR; c.drawRect(0f, y(860.0), w, h, fill)

        // seat
        val side = demo.exercise.view == "side"
        val hip = if (side) pt("lhip") else null
        fill.color = FURNITURE
        if (side && hip != null) {
            c.drawRoundRect(RectF(x(hip.x - 80), y(hip.y + 22), x(hip.x + 120), y(hip.y + 44)), 8f, 8f, fill)
            stroke.color = FURNITURE; stroke.strokeWidth = 14f * s
            c.drawLine(x(hip.x - 60), y(hip.y + 44), x(hip.x - 60), y(860.0), stroke)
            c.drawLine(x(hip.x + 100), y(hip.y + 44), x(hip.x + 100), y(860.0), stroke)
        } else {
            c.drawRoundRect(RectF(x(230.0), y(668.0), x(490.0), y(700.0)), 8f, 8f, fill)
            stroke.color = FURNITURE; stroke.strokeWidth = 14f * s
            c.drawLine(x(250.0), y(700.0), x(250.0), y(860.0), stroke)
            c.drawLine(x(470.0), y(700.0), x(470.0), y(860.0), stroke)
        }

        fun limb(color: Int, width: Float, vararg keys: String) {
            val pts = keys.mapNotNull { pt(it) }
            if (pts.size < 2) return
            stroke.color = color; stroke.strokeWidth = width * s
            val path = Path().apply { moveTo(x(pts[0].x), y(pts[0].y)); for (q in pts.drop(1)) lineTo(x(q.x), y(q.y)) }
            c.drawPath(path, stroke)
        }

        if (side) {
            limb(BODY_FAR, 44f, "rhip", "rknee", "rank")
            limb(BODY_FAR, 28f, "rsho", "relb", "rwri")
            limb(BODY, 46f, "lhip", "lknee", "lank")
            limb(BODY, 92f, "lsho", "lhip")
        } else {
            limb(BODY, 46f, "lhip", "lknee", "lank"); limb(BODY, 46f, "rhip", "rknee", "rank")
            val q = listOf("lsho", "rsho", "rhip", "lhip").mapNotNull { pt(it) }
            if (q.size == 4) {
                val torso = Path().apply { moveTo(x(q[0].x), y(q[0].y)); for (k in 1..3) lineTo(x(q[k].x), y(q[k].y)); close() }
                fill.color = BODY; c.drawPath(torso, fill)
                stroke.color = BODY; stroke.strokeWidth = 36f * s; c.drawPath(torso, stroke)
            }
        }

        // neck + head
        val lear = pt("lear"); val rear = pt("rear"); val nose = pt("nose")
        val shoMid = midOf(pt("lsho"), pt("rsho"), side)
        val (hx, hy) = when {
            side && lear != null && nose != null -> Pair(lear.x + (nose.x - lear.x) * 0.3, lear.y - 8)
            lear != null && rear != null -> Pair((lear.x + rear.x) / 2, (lear.y + rear.y) / 2 - 6)
            else -> Pair(360.0, 300.0)
        }
        if (shoMid != null) {
            stroke.color = BODY; stroke.strokeWidth = 40f * s
            c.drawLine(x(shoMid.first), y(shoMid.second - 10), x(hx), y(hy + 30), stroke)
        }
        val back = demo.exercise.view == "back"
        fill.color = if (back) HAIR else BODY
        c.drawCircle(x(hx), y(hy), 54f * s, fill)
        if (!back && nose != null) {
            fill.color = 0xFFCBD5E1.toInt()
            if (side) c.drawCircle(x(nose.x), y(nose.y), 9f * s, fill)
            else {   // eyes follow the nose, so head turns read clearly
                val ex = nose.x - hx
                c.drawCircle(x(hx + ex - 18), y(hy - 4), 6f * s, fill); c.drawCircle(x(hx + ex + 18), y(hy - 4), 6f * s, fill)
                c.drawCircle(x(nose.x), y(nose.y + 6), 5f * s, fill)
            }
        }
        if (side) {
            limb(BODY, 30f, "lsho", "lelb", "lwri")
        } else {
            limb(BODY, 30f, "lsho", "lelb", "lwri"); limb(BODY, 30f, "rsho", "relb", "rwri")
        }

        // tracking overlay: exactly what a live session draws
        val phaseText = phaseText(demo, f)
        val reps = "${hud.reps}/${demo.reps}"
        if (overlay) {
            val sig = hud.signal?.let { "%.3f".format(it) } ?: "-"
            val metrics = "signal=$sig  band ${fmt(demo.exercise.repLow)}–${fmt(demo.exercise.repHigh)}"
            OverlayRenderer.draw(c, p, GuideDemo.W, GuideDemo.H, emptyList(), true, metrics, hud.cue, reps, false)
            drawMeter(c, demo, hud.signal)
        }

        // phase banner
        label.textSize = max(26f, w / 26f); label.isFakeBoldText = true
        val banner = if (demo.mistake) t("gd_mistake") + " · " + phaseText else phaseText
        val bw = min(w * 0.7f, label.measureText(banner) + 32f)
        fill.color = if (demo.mistake) 0xE07F1D1D.toInt() else 0xE00B1220.toInt()
        c.drawRoundRect(RectF(16f, 16f, 16f + bw, 16f + label.textSize * 1.9f), 14f, 14f, fill)
        label.color = if (demo.mistake) Color.WHITE else Ui.OK
        c.drawText(ellipsize(banner, bw - 32f), 32f, 16f + label.textSize * 1.3f, label)
        if (f.rep > 0) {
            label.textSize = max(22f, w / 32f); label.isFakeBoldText = false; label.color = Ui.MUTED
            c.drawText(t("gd_rep").format(f.rep, demo.reps), 32f, 16f + label.textSize * 4.2f, label)
        }
        label.isFakeBoldText = false; label.color = Ui.TEXT
    }

    fun phaseText(demo: GuideDemo, f: GuideDemo.Frame): String {
        val secs = ((f.phaseLeftMs + 999) / 1000).toInt()
        return when (f.phase) {
            GuideDemo.Phase.CALIBRATE -> t("gd_phase_calibrate")
            GuideDemo.Phase.IN -> t("gd_phase_in").format(secs)
            GuideDemo.Phase.HOLD -> t("gd_phase_hold").format(secs)
            GuideDemo.Phase.OUT -> t("gd_phase_out").format(secs)
            GuideDemo.Phase.REST -> t("gd_phase_rest")
        }
    }

    /** Vertical signal meter with the rep band: above the green line counts as "out", below amber as back. */
    private fun drawMeter(c: Canvas, demo: GuideDemo, signal: Double?) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        val top = h * 0.16f; val bottom = h * 0.62f
        val left = w - 46f; val right = w - 22f
        val maxV = max(demo.exercise.repHigh * 1.6, 1e-6)
        fun yv(v: Double) = bottom - ((v / maxV).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
        fill.color = 0xCC0B1220.toInt(); c.drawRoundRect(RectF(left - 6, top - 6, right + 6, bottom + 6), 10f, 10f, fill)
        val v = signal ?: 0.0
        fill.color = if (v >= demo.exercise.repHigh) Ui.OK else if (v > demo.exercise.repLow) Ui.WARN else Ui.MUTED
        c.drawRect(left, yv(v), right, bottom, fill)
        stroke.strokeWidth = 4f
        stroke.color = Ui.OK; c.drawLine(left - 10, yv(demo.exercise.repHigh), right + 10, yv(demo.exercise.repHigh), stroke)
        stroke.color = Ui.WARN; c.drawLine(left - 10, yv(demo.exercise.repLow), right + 10, yv(demo.exercise.repLow), stroke)
    }

    // ------------------------------------------------------------------ placement diagram

    /** Two animated panels: a top-down plan (distance, which way to face) and a
     *  side elevation (phone upright on a table, lens at chest height). Loops every [LOOP_MS]. */
    fun drawPlacement(c: Canvas, view: String, program: String, tMs: Long) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        c.drawColor(Ui.SURFACE)
        val k = ((tMs % LOOP_MS).toFloat() / 1800f).coerceIn(0f, 1f)          // 0 -> 1 settling animation
        val e = k * k * (3 - 2 * k)
        val unit = min(w / 2f, h) / 10f
        label.textSize = max(22f, unit * 0.62f)

        // --- left: top-down plan
        val pcx = w * 0.25f
        val phoneY = h * 0.86f; val personY = h * 0.30f
        // field of view
        val cone = Path().apply {
            moveTo(pcx, phoneY); lineTo(pcx - unit * 3.2f, personY - unit * 1.6f); lineTo(pcx + unit * 3.2f, personY - unit * 1.6f); close()
        }
        val pulse = (0.5f + 0.5f * sin(tMs / 600.0 * PI).toFloat())
        fill.color = Color.argb((40 + 30 * pulse).toInt(), 56, 189, 248); c.drawPath(cone, fill)
        // phone (top view)
        fill.color = Ui.TEXT; c.drawRoundRect(RectF(pcx - unit * 0.6f, phoneY - unit * 0.18f, pcx + unit * 0.6f, phoneY + unit * 0.18f), 6f, 6f, fill)
        // person seen from above: shoulders + head + nose showing the facing direction
        val want = when (view) { "side" -> 90.0; "back" -> 180.0; else -> 0.0 }      // 0 = facing the phone
        val start = when (view) { "side" -> 0.0; "back" -> 0.0; else -> 90.0 }
        val ang = Math.toRadians(start + (want - start) * e)
        val fx = sin(ang).toFloat(); val fy = cos(ang).toFloat()              // facing vector (toward the phone = +y)
        stroke.color = BODY; stroke.strokeWidth = unit * 0.7f
        c.drawLine(pcx - fy * unit * 1.1f, personY + fx * unit * 1.1f, pcx + fy * unit * 1.1f, personY - fx * unit * 1.1f, stroke)
        fill.color = if (view == "back" && e > 0.5f) HAIR else 0xFF94A3B8.toInt()
        c.drawCircle(pcx, personY, unit * 0.62f, fill)
        fill.color = Ui.WARN
        val nose = Path().apply {
            moveTo(pcx + fx * unit * 1.05f, personY + fy * unit * 1.05f)
            lineTo(pcx + fx * unit * 0.5f - fy * unit * 0.3f, personY + fy * unit * 0.5f + fx * unit * 0.3f)
            lineTo(pcx + fx * unit * 0.5f + fy * unit * 0.3f, personY + fy * unit * 0.5f - fx * unit * 0.3f)
            close()
        }
        c.drawPath(nose, fill)
        // distance arrow
        val ax = pcx + unit * 3.4f
        stroke.color = Ui.PRIMARY; stroke.strokeWidth = 3f
        c.drawLine(ax, personY, ax, phoneY, stroke)
        c.drawLine(ax - 10, personY + 14, ax, personY, stroke); c.drawLine(ax + 10, personY + 14, ax, personY, stroke)
        c.drawLine(ax - 10, phoneY - 14, ax, phoneY, stroke); c.drawLine(ax + 10, phoneY - 14, ax, phoneY, stroke)
        label.color = Ui.PRIMARY
        val dist = t(when (program) { "neck" -> "gd_label_1m"; "upper_body" -> "gd_label_15m"; else -> "gd_label_2m" })
        c.drawText(dist, ax - label.measureText(dist) - 8, (personY + phoneY) / 2, label)
        label.color = Ui.MUTED
        c.drawText(t("gd_label_you"), pcx - label.measureText(t("gd_label_you")) / 2, personY - unit * 1.2f, label)
        c.drawText(t("gd_label_phone"), pcx - label.measureText(t("gd_label_phone")) / 2, phoneY + unit * 0.9f, label)
        if (e >= 1f) {
            stroke.color = Ui.OK; stroke.strokeWidth = 6f
            val cx = pcx + unit * 1.6f; val cy = personY + unit * 1.4f
            c.drawLine(cx - 14, cy, cx - 4, cy + 12, stroke); c.drawLine(cx - 4, cy + 12, cx + 18, cy - 14, stroke)
        }

        // --- right: side elevation
        val baseY = h * 0.88f
        val tableTop = h * 0.56f
        val tx = w * 0.56f
        fill.color = FURNITURE
        c.drawRect(tx, tableTop, tx + unit * 2.2f, tableTop + unit * 0.3f, fill)
        c.drawRect(tx + unit * 0.2f, tableTop, tx + unit * 0.45f, baseY, fill)
        c.drawRect(tx + unit * 1.75f, tableTop, tx + unit * 2.0f, baseY, fill)
        // phone, tilting upright
        val tilt = 28f * (1 - e)
        val phx = tx + unit * 1.1f
        c.save(); c.rotate(-tilt, phx, tableTop)
        fill.color = Ui.TEXT
        c.drawRoundRect(RectF(phx - unit * 0.28f, tableTop - unit * 1.9f, phx + unit * 0.28f, tableTop), 8f, 8f, fill)
        fill.color = Ui.BG; c.drawCircle(phx + unit * 0.12f, tableTop - unit * 1.65f, unit * 0.09f, fill)
        c.restore()
        // seated person in profile, facing the phone
        val px = w * 0.9f
        val chestY = tableTop - unit * 1.65f
        stroke.color = BODY; stroke.strokeWidth = unit * 0.55f
        c.drawLine(px, chestY - unit * 0.6f, px, chestY + unit * 2.4f, stroke)                                   // torso
        c.drawLine(px, chestY + unit * 2.4f, px - unit * 1.2f, chestY + unit * 2.5f, stroke)                       // thigh
        c.drawLine(px - unit * 1.2f, chestY + unit * 2.5f, px - unit * 1.2f, baseY, stroke)                        // shin
        fill.color = BODY; c.drawCircle(px - unit * 0.05f, chestY - unit * 1.35f, unit * 0.55f, fill)
        fill.color = FURNITURE; c.drawRect(px - unit * 0.4f, chestY + unit * 2.65f, px + unit * 0.6f, chestY + unit * 2.9f, fill)
        // lens-to-chest line
        dashed.strokeWidth = 4f
        val lensY = tableTop - unit * 1.65f
        c.drawLine(phx + unit * 0.3f, lensY, px - unit * 0.4f, chestY, dashed)
        label.color = if (e >= 1f) Ui.OK else Ui.MUTED
        val l1 = (if (e >= 1f) "✓ " else "") + t("gd_label_upright")
        val l2 = (if (e >= 1f) "✓ " else "") + t(if (program == "neck" || view == "front") "gd_label_face" else "gd_label_chest")
        c.drawText(ellipsize(l1, w * 0.44f), w * 0.54f, h * 0.14f, label)
        c.drawText(ellipsize(l2, w * 0.44f), w * 0.54f, h * 0.14f + label.textSize * 1.4f, label)
        // divider
        stroke.color = Ui.BG; stroke.strokeWidth = 4f; c.drawLine(w * 0.5f, 0f, w * 0.5f, h, stroke)
        label.color = Ui.TEXT
    }

    const val LOOP_MS = 6000L

    private fun midOf(a: Metrics.P?, b: Metrics.P?, side: Boolean): Pair<Double, Double>? = when {
        side && a != null -> Pair(a.x, a.y)
        a != null && b != null -> Pair((a.x + b.x) / 2, (a.y + b.y) / 2)
        else -> null
    }

    private fun fmt(v: Double) = if (v < 1) "%.3f".format(v) else "%.0f".format(v)

    private fun ellipsize(s: String, maxW: Float): String {
        if (label.measureText(s) <= maxW) return s
        var cut = s
        while (cut.isNotEmpty() && label.measureText("$cut…") > maxW) cut = cut.dropLast(1)
        return "$cut…"
    }
}
