package com.spinecare.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
        val p = f.landmarks

        // the scene is drawn in the whole-body frame; the programme's zoom (head and
        // shoulders, waist up) and the crop that fills this view are one transform
        val z = GuideDemo.zoomFor(demo.exercise.program).toFloat()
        val cy = GuideDemo.ZOOM_CY.toFloat()
        c.drawColor(Ui.BG)
        c.save()
        c.translate(ox + s * fw / 2f * (1 - z), oy + s * cy * (1 - z))
        c.scale(s * z, s * z)
        // wall and floor, with a soft shadow under the chair
        fill.shader = LinearGradient(0f, 0f, 0f, 860f, 0xFF16213A.toInt(), Ui.BG, Shader.TileMode.CLAMP)
        c.drawRect(-2000f, -2000f, fw + 2000f, 860f, fill)
        fill.shader = null
        fill.color = FLOOR; c.drawRect(-2000f, 860f, fw + 2000f, fh + 2000f, fill)
        val body = GuideDemo.unzoom(p, demo.exercise.program)
        val hipX = body["lhip"]?.x?.toFloat() ?: (fw / 2f)
        fill.color = 0x66000000
        c.drawOval(RectF(hipX - 170f, 846f, hipX + 170f, 880f), fill)
        FigurePainter.draw(c, body, demo.exercise.view)
        c.restore()

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
        stroke.color = FigurePainter.SHIRT; stroke.strokeWidth = unit * 0.7f
        c.drawLine(pcx - fy * unit * 1.1f, personY + fx * unit * 1.1f, pcx + fy * unit * 1.1f, personY - fx * unit * 1.1f, stroke)
        fill.color = FigurePainter.HAIR      // seen from above: the top of the head
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
        // seated person in profile (the guide's figure, mirrored to face the phone)
        val px = w * 0.88f
        val chestY = tableTop - unit * 1.65f
        val k = (baseY - chestY) / (860f - 450f)
        c.save()
        c.translate(px, chestY); c.scale(-k, k); c.translate(-340f, -450f)
        FigurePainter.draw(c, GuideDemo.basePose("side"), "side")
        c.restore()
        // lens-to-chest line
        dashed.strokeWidth = 4f
        val lensY = tableTop - unit * 1.65f
        c.drawLine(phx + unit * 0.3f, lensY, px - 46f * k, chestY, dashed)
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

    private fun fmt(v: Double) = if (v < 1) "%.3f".format(v) else "%.0f".format(v)

    private fun ellipsize(s: String, maxW: Float): String {
        if (label.measureText(s) <= maxW) return s
        var cut = s
        while (cut.isNotEmpty() && label.measureText("$cut…") > maxW) cut = cut.dropLast(1)
        return "$cut…"
    }
}
