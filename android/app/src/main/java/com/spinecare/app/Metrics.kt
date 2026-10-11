package com.spinecare.app

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Body-segment metrics, mirroring server/pose.py (checked against
 *  shared/metric_vectors.json in MetricsTest). Landmarks use the shared name
 *  space (nose, lear, rear, lsho, rsho, ...). All angles in degrees. ESTIMATES only. */
object Metrics {
    data class LM(val x: Float, val y: Float, val z: Float, val visibility: Float)

    /** Named landmark with coordinates in an isotropic unit (pixels). */
    data class P(val x: Double, val y: Double, val c: Double)

    const val VIS = 0.5

    /** MediaPipe BlazePose 33 indices for the shared names. */
    val MEDIAPIPE33 = linkedMapOf(
        "nose" to 0, "lear" to 7, "rear" to 8, "lsho" to 11, "rsho" to 12,
        "lelb" to 13, "relb" to 14, "lwri" to 15, "rwri" to 16,
        "lhip" to 23, "rhip" to 24, "lknee" to 25, "rknee" to 26, "lank" to 27, "rank" to 28,
    )

    /** Converts normalized MediaPipe landmarks to named points scaled by the
     *  frame size, so angles are not distorted by the image aspect ratio. */
    fun named(lms: List<LM>, w: Int, h: Int): Map<String, P> {
        val out = LinkedHashMap<String, P>()
        for ((name, i) in MEDIAPIPE33) if (i < lms.size) {
            val l = lms[i]
            out[name] = P(l.x.toDouble() * w, l.y.toDouble() * h, l.visibility.toDouble())
        }
        return out
    }

    private val ZERO = P(0.0, 0.0, 0.0)
    private fun mid(a: P, b: P) = P((a.x + b.x) / 2, (a.y + b.y) / 2, min(a.c, b.c))
    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    private fun angBetween(x1: Double, y1: Double, x2: Double, y2: Double): Double? {
        val n1 = hypot(x1, y1); val n2 = hypot(x2, y2)
        if (n1 < 1e-6 || n2 < 1e-6) return null
        return Math.toDegrees(acos(max(-1.0, min(1.0, (x1 * x2 + y1 * y2) / (n1 * n2)))))
    }

    private fun angFromVertical(a: P, b: P) = angBetween(b.x - a.x, b.y - a.y, 0.0, 1.0)

    /** 'l' or 'r': the side whose ear/shoulder/hip chain is most visible, or null. */
    fun bestSide(p: Map<String, P>): String? {
        fun score(s: String) = minOf(p[s + "ear"]?.c ?: 0.0, p[s + "sho"]?.c ?: 0.0, p[s + "hip"]?.c ?: 0.0)
        val s = if (score("l") >= score("r")) "l" else "r"
        return if (score(s) >= VIS) s else null
    }

    fun compute(p: Map<String, P>): Map<String, Double> {
        val m = HashMap<String, Double>()
        fun g(k: String) = p[k] ?: ZERO
        fun v(k: String) = (p[k]?.c ?: 0.0) >= VIS
        val lsho = g("lsho"); val rsho = g("rsho"); val lhip = g("lhip"); val rhip = g("rhip")
        val lear = g("lear"); val rear = g("rear"); val nose = g("nose")
        val bothSho = v("lsho") && v("rsho")
        val bothHip = v("lhip") && v("rhip")
        val shoMid = mid(lsho, rsho); val hipMid = mid(lhip, rhip)
        val shoW = dist(lsho, rsho)

        if (bothSho && bothHip) {
            val torso = dist(shoMid, hipMid)
            angFromVertical(shoMid, hipMid)?.let { m["trunk_tilt_frontal"] = it }
            if (torso > 1e-6) m["shoulder_width_ratio"] = shoW / torso
        }
        if (v("lear") && v("rear") && bothSho) {
            angFromVertical(mid(lear, rear), shoMid)?.let { m["cervical_tilt"] = it }
        }
        // Signed lateral nose offset vs shoulder midline (front view). Ear-separation
        // proxies collapse when an ear occludes during rotation; the nose stays visible.
        if (v("nose") && bothSho && shoW > 1e-6) {
            val ratio = max(-1.0, min(1.0, (nose.x - shoMid.x) / (0.28 * shoW)))
            m["neck_yaw_est"] = Math.toDegrees(asin(ratio))
        }
        if (bothSho) {
            val deg = Math.toDegrees(atan2(rsho.y - lsho.y, rsho.x - lsho.x))
            m["shoulder_line_tilt"] = wrap(deg + 90.0, 180.0) - 90.0
        }
        // Side view: most visible ear/shoulder/hip chain, oriented by facing direction.
        val side = bestSide(p)
        if (side != null && v("nose")) {
            val ear = g(side + "ear"); val sho = g(side + "sho"); val hip = g(side + "hip")
            val facing = if (nose.x >= ear.x) 1.0 else -1.0
            val torso = dist(sho, hip)
            if (torso > 1e-6) {
                m["head_forward_proxy"] = (ear.x - sho.x) * facing / torso
                m["trunk_lean_sagittal"] = Math.toDegrees(atan2((sho.x - hip.x) * -facing, hip.y - sho.y))
            }
            angBetween(nose.x - ear.x, nose.y - ear.y, sho.x - ear.x, sho.y - ear.y)?.let { m["cervical_sag_proxy"] = it }
        }
        return m
    }

    private fun wrap(a: Double, b: Double): Double = ((a % b) + b) % b
}
