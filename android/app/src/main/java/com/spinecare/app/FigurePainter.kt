package com.spinecare.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Draws the guide's demonstration person from pose landmarks: a seated figure
 *  with a shaped head (hair, ears, eyes that follow head turns), neck, a shirt
 *  with sleeves over a tapered torso, trousers, hands and shoes, on a chair,
 *  seen from the front, the side or the back. Every part hangs off the
 *  landmarks, so the body moves exactly as the tracker sees it move.
 *  Coordinates are the demo's unzoomed 720x960 frame (GuideDemo.seated); the
 *  caller sets the canvas transform for the crop and the programme's zoom. */
object FigurePainter {
    const val SKIN = 0xFFF0C4A0.toInt()
    const val SKIN_SHADE = 0xFFD39B78.toInt()
    const val HAIR = 0xFF3B2A20.toInt()
    const val SHIRT = 0xFF3B82F6.toInt()
    const val SHIRT_SHADE = 0xFF1D4ED8.toInt()
    const val PANTS = 0xFF475569.toInt()
    const val SHOE = 0xFFE2E8F0.toInt()
    const val CHAIR = 0xFF334155.toInt()
    const val CHAIR_DARK = 0xFF1E293B.toInt()
    private const val FEATURE = 0xFF1F2937.toInt()
    private const val MOUTH = 0xFF9F4F45.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private data class V(val x: Float, val y: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(k: Float) = V(x * k, y * k)
        val len get() = hypot(x, y)
        fun norm(): V { val l = len; return if (l < 1e-4f) V(0f, 1f) else V(x / l, y / l) }
        /** Perpendicular, rotated 90° clockwise on screen (y down). */
        fun perp() = V(-y, x)
    }

    private fun lerp(a: V, b: V, k: Float) = V(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

    /** Darker copy of a colour, for the far-side limbs. */
    fun shade(color: Int, k: Float = 0.7f): Int {
        fun ch(shift: Int) = ((color shr shift and 0xFF) * k).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Synchronized: the on-screen demo and the MP4 export can draw at the same time. */
    @Synchronized
    fun draw(c: Canvas, p: Map<String, Metrics.P>, view: String) {
        if (view == "side") side(c, p) else frontBack(c, p, back = view == "back")
    }

    // ------------------------------------------------------------------ parts

    /** Tapered limb segment: a quad between two end circles. */
    private fun capsule(c: Canvas, a: V, b: V, ra: Float, rb: Float, color: Int) {
        val n = (b - a).norm().perp()
        path.reset()
        val p1 = a + n * ra; val p2 = b + n * rb; val p3 = b - n * rb; val p4 = a - n * ra
        path.moveTo(p1.x, p1.y); path.lineTo(p2.x, p2.y); path.lineTo(p3.x, p3.y); path.lineTo(p4.x, p4.y); path.close()
        fill.color = color
        c.drawPath(path, fill)
        c.drawCircle(a.x, a.y, ra, fill); c.drawCircle(b.x, b.y, rb, fill)
    }

    private fun oval(c: Canvas, center: V, rx: Float, ry: Float, angleDeg: Float, color: Int) {
        fill.color = color
        c.save(); c.rotate(angleDeg, center.x, center.y)
        c.drawOval(RectF(center.x - rx, center.y - ry, center.x + rx, center.y + ry), fill)
        c.restore()
    }

    private fun deg(v: V) = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()

    /** Upper arm with a short sleeve, forearm and hand. */
    private fun arm(c: Canvas, sho: V, elb: V, wri: V, dark: Boolean) {
        val k = if (dark) 0.72f else 1f
        val skin = if (dark) shade(SKIN, k) else SKIN
        capsule(c, sho, elb, 17f, 13.5f, skin)
        capsule(c, elb, wri, 13f, 10f, skin)
        capsule(c, sho, lerp(sho, elb, 0.55f), 20f, 17f, if (dark) shade(SHIRT, k) else SHIRT)
        // hand: an oval past the wrist along the forearm, with a thumb
        val d = (wri - elb).norm()
        val hc = wri + d * 13f
        oval(c, hc, 16f, 11.5f, deg(d), skin)
        val thumb = wri + d * 6f + d.perp() * 9f
        fill.color = if (dark) shade(SKIN_SHADE, k) else SKIN_SHADE
        c.drawCircle(thumb.x, thumb.y, 5.5f, fill)
    }

    private fun leg(c: Canvas, hip: V, knee: V, ank: V, dark: Boolean, side: Float) {
        val pants = if (dark) shade(PANTS, 0.72f) else PANTS
        capsule(c, hip, knee, 30f, 24f, pants)
        capsule(c, knee, ank, 23f, 17f, pants)
        val shoe = if (dark) shade(SHOE, 0.62f) else SHOE
        fill.color = shoe
        if (side != 0f) {           // profile: toe points the way the person faces
            val heel = ank + V(-side * 16f, 6f); val toe = ank + V(side * 52f, 18f)
            c.drawRoundRect(RectF(minOf(heel.x, toe.x), ank.y - 4f, maxOf(heel.x, toe.x), toe.y + 6f), 14f, 14f, fill)
        } else {
            c.drawRoundRect(RectF(ank.x - 24f, ank.y + 2f, ank.x + 24f, ank.y + 26f), 12f, 12f, fill)
        }
        // a hint of sock between trouser and shoe
        fill.color = if (dark) shade(SKIN_SHADE, 0.6f) else 0xFF64748B.toInt()
        c.drawRect(ank.x - 11f, ank.y - 4f, ank.x + 11f, ank.y + 3f, fill)
    }

    private fun chairLegs(c: Canvas, xs: List<Float>, top: Float) {
        pen.color = CHAIR; pen.strokeWidth = 13f
        for (x in xs) c.drawLine(x, top, x, 862f, pen)
    }

    // ------------------------------------------------------------------ front / back

    private fun frontBack(c: Canvas, p: Map<String, Metrics.P>, back: Boolean) {
        fun v(k: String) = p[k]?.let { V(it.x.toFloat(), it.y.toFloat()) }
        val ls = v("lsho") ?: return; val rs = v("rsho") ?: return
        val lh = v("lhip") ?: return; val rh = v("rhip") ?: return
        // image-left / image-right, so shapes are drawn the same from the front and from behind
        val (sL, sR) = if (ls.x <= rs.x) ls to rs else rs to ls
        val (hL, hR) = if (lh.x <= rh.x) lh to rh else rh to lh
        val shoMid = lerp(sL, sR, 0.5f); val hipMid = lerp(hL, hR, 0.5f)

        // chair: back rest behind the body, seat under the hips
        fill.color = CHAIR_DARK
        c.drawRoundRect(RectF(hipMid.x - 118f, shoMid.y + 4f, hipMid.x + 118f, hipMid.y + 30f), 20f, 20f, fill)
        fill.color = CHAIR
        c.drawRoundRect(RectF(hipMid.x - 140f, hipMid.y + 16f, hipMid.x + 140f, hipMid.y + 46f), 10f, 10f, fill)
        chairLegs(c, listOf(hipMid.x - 122f, hipMid.x + 122f), hipMid.y + 46f)

        for (s in listOf("l", "r")) {
            val hip = v(s + "hip") ?: continue; val knee = v(s + "knee") ?: continue; val ank = v(s + "ank") ?: continue
            leg(c, hip, knee, ank, false, 0f)
        }
        // seat of the trousers
        fill.color = PANTS
        val hu = (hR - hL).norm()
        path.reset()
        val a = hL - hu * 30f; val b = hR + hu * 30f
        path.moveTo(a.x, a.y - 26f); path.lineTo(b.x, b.y - 26f)
        path.quadTo(b.x + 4f, b.y + 26f, b.x - 14f, b.y + 30f); path.lineTo(a.x + 14f, a.y + 30f)
        path.quadTo(a.x - 4f, a.y + 26f, a.x, a.y - 26f); path.close()
        c.drawPath(path, fill)

        // head geometry (needed for the neck)
        val lear = v("lear"); val rear = v("rear"); val nose = v("nose")
        val (eL, eR) = when {
            lear != null && rear != null -> if (lear.x <= rear.x) lear to rear else rear to lear
            else -> V(shoMid.x - 35f, shoMid.y - 105f) to V(shoMid.x + 35f, shoMid.y - 105f)
        }
        val earHalf = ((eR - eL).len / 2f).coerceAtLeast(20f)
        val hc = lerp(eL, eR, 0.5f) + V(0f, -6f)
        val tilt = deg(eR - eL)
        val rx = earHalf * 1.43f; val ry = rx * 1.2f
        val up = (eR - eL).norm().perp() * -1f          // head's up direction
        val chin = hc - up * (ry * 0.78f)

        // neck
        capsule(c, shoMid + V(0f, -2f), chin, 21f, 19f, SKIN_SHADE)

        // torso: sloped shoulders, a waist, then the hem over the trousers
        val u = (sR - sL).norm(); val down = u.perp()
        val shoW = (sR - sL).len
        val nL = shoMid - u * (shoW * 0.17f) - down * 6f; val nR = shoMid + u * (shoW * 0.17f) - down * 6f
        val oL = sL - u * 16f + down * 10f; val oR = sR + u * 16f + down * 10f
        val wL = lerp(sL, hL, 0.64f) + u * (shoW * 0.07f); val wR = lerp(sR, hR, 0.64f) - u * (shoW * 0.07f)
        val hemL = hL - hu * 28f + V(0f, 8f); val hemR = hR + hu * 28f + V(0f, 8f)
        path.reset()
        path.moveTo(nL.x, nL.y)
        val c1 = sL - down * 8f
        path.quadTo(c1.x, c1.y, oL.x, oL.y)
        val c2 = oL + down * 60f; val c3 = wL - down * 40f
        path.cubicTo(c2.x, c2.y, c3.x, c3.y, wL.x, wL.y)
        val c4 = lerp(wL, hemL, 0.6f) - hu * 6f
        path.quadTo(c4.x, c4.y, hemL.x, hemL.y)
        val hemMid = lerp(hemL, hemR, 0.5f) + V(0f, 6f)
        path.quadTo(hemMid.x, hemMid.y, hemR.x, hemR.y)
        val c5 = lerp(wR, hemR, 0.6f) + hu * 6f
        path.quadTo(c5.x, c5.y, wR.x, wR.y)
        val c6 = wR - down * 40f; val c7 = oR + down * 60f
        path.cubicTo(c6.x, c6.y, c7.x, c7.y, oR.x, oR.y)
        val c8 = sR - down * 8f
        path.quadTo(c8.x, c8.y, nR.x, nR.y)
        val collar = shoMid + down * (if (back) 2f else 16f)
        path.quadTo(collar.x, collar.y, nL.x, nL.y)
        path.close()
        fill.shader = LinearGradient(oL.x, 0f, oR.x, 0f, intArrayOf(SHIRT_SHADE, SHIRT, SHIRT, SHIRT_SHADE),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        if (!back) {    // collar line
            pen.color = SHIRT_SHADE; pen.strokeWidth = 4f
            path.reset(); path.moveTo(nL.x, nL.y); path.quadTo(collar.x, collar.y, nR.x, nR.y)
            c.drawPath(path, pen)
        } else {        // back seam
            pen.color = SHIRT_SHADE; pen.strokeWidth = 3f
            val seamTop = shoMid + down * 10f; val seamBottom = lerp(hemL, hemR, 0.5f) - V(0f, 20f)
            c.drawLine(seamTop.x, seamTop.y, seamBottom.x, seamBottom.y, pen)
        }

        // head
        val yaw = if (back || nose == null) 0f else {
            val d = nose - hc
            // nose offset along the ear line, in half ear-widths: -1 (turned to image left) .. 1
            ((d.x * u.x + d.y * u.y) / earHalf).coerceIn(-1f, 1f)
        }
        c.save(); c.rotate(tilt, hc.x, hc.y)
        // ears sit behind the face; the one the face turns away from shows more
        oval(c, V(hc.x - rx * (0.96f + 0.05f * yaw), hc.y + ry * 0.06f), 10f, 16f, 0f, SKIN_SHADE)
        oval(c, V(hc.x + rx * (0.96f - 0.05f * yaw), hc.y + ry * 0.06f), 10f, 16f, 0f, SKIN_SHADE)
        val head = RectF(hc.x - rx, hc.y - ry, hc.x + rx, hc.y + ry)
        if (back) {
            fill.color = HAIR; c.drawOval(head, fill)
            fill.color = SKIN_SHADE      // nape below the hairline
            c.save(); path.reset(); path.addOval(head, Path.Direction.CW); c.clipPath(path)
            c.drawRect(hc.x - rx * 0.45f, hc.y + ry * 0.62f, hc.x + rx * 0.45f, hc.y + ry, fill)
            c.restore()
            pen.color = shade(HAIR, 1.5f); pen.strokeWidth = 3f
            c.drawArc(RectF(hc.x - rx * 0.6f, hc.y - ry * 0.85f, hc.x + rx * 0.3f, hc.y + ry * 0.3f), 200f, 70f, false, pen)
        } else {
            fill.color = SKIN; c.drawOval(head, fill)
            // hair: clipped to the head, a side-swept fringe that follows the turn
            c.save(); path.reset(); path.addOval(head, Path.Direction.CW); c.clipPath(path)
            fill.color = HAIR
            path.reset()
            path.moveTo(hc.x - rx - 4f, hc.y - ry * 0.05f)
            path.quadTo(hc.x - rx * 0.55f + yaw * rx * 0.3f, hc.y - ry * 0.72f, hc.x + rx * 0.15f + yaw * rx * 0.3f, hc.y - ry * 0.5f)
            path.quadTo(hc.x + rx * 0.75f, hc.y - ry * 0.38f, hc.x + rx + 4f, hc.y - ry * 0.02f)
            path.lineTo(hc.x + rx + 4f, hc.y - ry - 4f); path.lineTo(hc.x - rx - 4f, hc.y - ry - 4f); path.close()
            c.drawPath(path, fill)
            c.restore()
            // face: features slide toward the turn and the far eye narrows
            val fx = hc.x + yaw * rx * 0.5f
            val gap = rx * 0.36f * (1f - abs(yaw) * 0.3f)
            fill.color = FEATURE
            val eyeY = hc.y + ry * 0.04f
            c.drawOval(RectF(fx - gap - 5f, eyeY - 5.5f, fx - gap + 5f, eyeY + 5.5f), fill)
            c.drawOval(RectF(fx + gap - 5f, eyeY - 5.5f, fx + gap + 5f, eyeY + 5.5f), fill)
            pen.color = HAIR; pen.strokeWidth = 4f
            c.drawLine(fx - gap - 9f, eyeY - 13f, fx - gap + 8f, eyeY - 15f, pen)
            c.drawLine(fx + gap - 8f, eyeY - 15f, fx + gap + 9f, eyeY - 13f, pen)
            pen.color = SKIN_SHADE; pen.strokeWidth = 4f
            val nx = hc.x + yaw * rx * 0.62f
            path.reset(); path.moveTo(nx, hc.y + ry * 0.1f); path.lineTo(nx + yaw * 7f, hc.y + ry * 0.3f); path.lineTo(nx - 5f * (1 - abs(yaw)), hc.y + ry * 0.33f)
            c.drawPath(path, pen)
            pen.color = MOUTH; pen.strokeWidth = 4f
            val mx = hc.x + yaw * rx * 0.55f; val my = hc.y + ry * 0.52f; val mw = rx * 0.24f
            c.drawArc(RectF(mx - mw, my - 8f, mx + mw, my + 6f), 20f, 140f, false, pen)
        }
        c.restore()

        // arms in front of the body
        for (s in listOf("l", "r")) {
            val sho = v(s + "sho") ?: continue; val elb = v(s + "elb") ?: continue; val wri = v(s + "wri") ?: continue
            arm(c, sho, elb, wri, false)
        }
    }

    // ------------------------------------------------------------------ side

    private fun side(c: Canvas, p: Map<String, Metrics.P>) {
        fun v(k: String) = p[k]?.let { V(it.x.toFloat(), it.y.toFloat()) }
        val sho = v("lsho") ?: return; val hip = v("lhip") ?: return
        val lear = v("lear"); val nose = v("nose")
        val f = if (nose != null && lear != null && nose.x < lear.x) -1f else 1f     // facing image right (+1) or left

        // chair: seat under the hips, back rest behind the back
        fill.color = CHAIR_DARK
        c.drawRoundRect(RectF(minOf(hip.x - f * 92f, hip.x - f * 70f), sho.y - 6f, maxOf(hip.x - f * 92f, hip.x - f * 70f), hip.y + 34f), 8f, 8f, fill)
        fill.color = CHAIR
        c.drawRoundRect(RectF(minOf(hip.x - f * 92f, hip.x + f * 118f), hip.y + 22f, maxOf(hip.x - f * 92f, hip.x + f * 118f), hip.y + 46f), 8f, 8f, fill)
        chairLegs(c, listOf(hip.x - f * 76f, hip.x + f * 100f), hip.y + 46f)

        // far side, darker
        val rh = v("rhip"); val rk = v("rknee"); val ra = v("rank")
        if (rh != null && rk != null && ra != null) leg(c, rh, rk, ra, true, f)
        val rs = v("rsho"); val re = v("relb"); val rw = v("rwri")
        if (rs != null && re != null && rw != null) arm(c, rs, re, rw, true)

        // near leg
        val lk = v("lknee"); val la = v("lank")
        if (lk != null && la != null) leg(c, hip, lk, la, false, f)

        // head geometry
        val hc = if (lear != null && nose != null) lerp(lear, nose, 0.3f) + V(0f, -8f) else sho + V(f * 25f, -108f)
        val pitch = if (lear != null && nose != null) deg(nose - lear) - deg(V(53f * f, 12f)) else 0f
        val rx = 52f; val ry = 60f

        // neck, leaning with the head
        val chinBack = hc + rotate(V(-f * 8f, ry * 0.72f), pitch)
        capsule(c, sho + V(f * 10f, -8f), chinBack, 21f, 18f, SKIN_SHADE)

        // torso in profile: chest in front, a gentle curve down the back
        val d = (hip - sho).norm(); val len = (hip - sho).len
        var nf = d.perp(); if (nf.x * f < 0) nf = nf * -1f
        val top = sho - d * 14f
        val chest = sho + d * (len * 0.28f) + nf * 44f
        val waist = sho + d * (len * 0.7f) + nf * 37f
        val hipF = hip + nf * 42f + d * 14f
        val hipB = hip - nf * 46f + d * 14f
        val lowBack = sho + d * (len * 0.74f) - nf * 34f
        val upBack = sho + d * (len * 0.22f) - nf * 42f
        path.reset()
        val start = top + nf * 12f
        path.moveTo(start.x, start.y)
        val a1 = sho + nf * 42f - d * 8f; val a2 = chest - d * 34f
        path.cubicTo(a1.x, a1.y, a2.x, a2.y, chest.x, chest.y)
        val a3 = lerp(chest, waist, 0.55f) + nf * 2f
        path.quadTo(a3.x, a3.y, waist.x, waist.y)
        val a4 = hipF - d * 24f + nf * 4f
        path.quadTo(a4.x, a4.y, hipF.x, hipF.y)
        path.lineTo(hipB.x, hipB.y)
        val a5 = lowBack - nf * 8f + d * 30f
        path.quadTo(a5.x, a5.y, lowBack.x, lowBack.y)
        val a6 = lerp(lowBack, upBack, 0.5f) - nf * 2f
        path.quadTo(a6.x, a6.y, upBack.x, upBack.y)
        val a7 = sho - nf * 40f - d * 12f; val back = top - nf * 16f
        path.quadTo(a7.x, a7.y, back.x, back.y)
        path.quadTo(top.x, top.y - 4f, start.x, start.y)
        path.close()
        val g0 = sho - nf * 46f; val g1 = sho + nf * 46f
        fill.shader = LinearGradient(g0.x, g0.y, g1.x, g1.y, SHIRT_SHADE, SHIRT, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null

        // head in profile, rotated with the nod
        c.save(); c.rotate(pitch, hc.x, hc.y)
        val head = RectF(hc.x - rx, hc.y - ry, hc.x + rx, hc.y + ry)
        // nose and chin first so the head outline overlaps them cleanly
        fill.color = SKIN
        path.reset()
        path.moveTo(hc.x + f * rx * 0.86f, hc.y - ry * 0.06f)
        path.lineTo(hc.x + f * (rx + 13f), hc.y + ry * 0.2f)
        path.lineTo(hc.x + f * rx * 0.88f, hc.y + ry * 0.3f)
        path.close()
        c.drawPath(path, fill)
        c.drawOval(head, fill)
        c.save(); path.reset(); path.addOval(head, Path.Direction.CW); c.clipPath(path)
        fill.color = HAIR
        path.reset()
        path.moveTo(hc.x + f * rx * 0.6f, hc.y - ry - 6f)
        path.quadTo(hc.x + f * rx * 0.3f, hc.y - ry * 0.48f, hc.x - f * rx * 0.02f, hc.y - ry * 0.36f)
        path.quadTo(hc.x - f * rx * 0.24f, hc.y + ry * 0.12f, hc.x - f * rx * 0.36f, hc.y + ry * 0.58f)
        path.lineTo(hc.x - f * rx * 1.2f, hc.y + ry * 0.62f); path.lineTo(hc.x - f * rx * 1.2f, hc.y - ry - 6f); path.close()
        c.drawPath(path, fill)
        c.restore()
        oval(c, V(hc.x - f * rx * 0.1f, hc.y + ry * 0.06f), 10f, 15f, 0f, SKIN_SHADE)
        fill.color = FEATURE
        c.drawCircle(hc.x + f * rx * 0.56f, hc.y - ry * 0.04f, 5f, fill)
        pen.color = HAIR; pen.strokeWidth = 4f
        c.drawLine(hc.x + f * rx * 0.44f, hc.y - ry * 0.2f, hc.x + f * rx * 0.72f, hc.y - ry * 0.22f, pen)
        pen.color = MOUTH; pen.strokeWidth = 4f
        c.drawLine(hc.x + f * rx * 0.84f, hc.y + ry * 0.5f, hc.x + f * rx * 0.62f, hc.y + ry * 0.53f, pen)
        c.restore()

        // near arm in front
        val le = v("lelb"); val lw = v("lwri")
        if (le != null && lw != null) arm(c, sho, le, lw, false)
    }

    private fun rotate(v: V, degrees: Float): V {
        val r = Math.toRadians(degrees.toDouble())
        val cs = kotlin.math.cos(r).toFloat(); val sn = kotlin.math.sin(r).toFloat()
        return V(v.x * cs - v.y * sn, v.x * sn + v.y * cs)
    }
}
