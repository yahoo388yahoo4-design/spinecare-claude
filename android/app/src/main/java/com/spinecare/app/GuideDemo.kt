package com.spinecare.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic demonstration "video" for one exercise, generated from its plan
 *  entry instead of shipped as footage: a seated figure in the camera view the
 *  exercise needs moves through calibration, then in / hold / out for each rep
 *  using the plan's tempo. The movement amplitude is solved so the app's own
 *  signal (Metrics + ExerciseTracker) crosses the rep band, so playing the demo
 *  through the real tracker shows exactly what the app counts. The "mistake"
 *  variant adds the movement that one form rule warns about, so the user sees
 *  and hears that cue. Pure Kotlin (no Android types), shared with iOS logic. */
class GuideDemo(val exercise: Exercise, val calibrationMs: Long = 1500, val mistake: Boolean = false) {

    enum class Phase { CALIBRATE, IN, HOLD, OUT, REST }

    data class Frame(
        val tMs: Long,
        val landmarks: Map<String, Metrics.P>,
        val phase: Phase,
        val phaseLeftMs: Long,
        val rep: Int,              // 1-based rep being shown (0 during calibration)
    )

    /** Body movements the demo can perform; [amount] is pixels or degrees. */
    enum class Move { HEAD_SHIFT, HEAD_NOD, HEAD_YAW, HEAD_TILT, SHOULDER_HIKE, SHRUG, TRUNK_ROTATE, SHOULDER_SQUEEZE, ARMS_OUT, ARM_SWING }

    data class Motion(val move: Move, val amounts: List<Double>)   // one amount per rep (alternates for abs signals)

    val base: Map<String, Metrics.P> = basePose(exercise.view, exercise.program)
    private val zoom = zoomFor(exercise.program)
    val reps = exercise.targetReps.coerceIn(1, DEMO_REPS)
    val inMs = (exercise.tempoIn * 1000).toLong().coerceAtLeast(500)
    val holdMs = (exercise.tempoHold * 1000).toLong().coerceAtLeast(600)   // RepEngine needs >= 400 ms above the band
    val outMs = (exercise.tempoOut * 1000).toLong().coerceAtLeast(500)
    val restMs = 1000L
    val leadMs = calibrationMs + 1000L
    val repMs = inMs + holdMs + outMs + restMs
    val durationMs = leadMs + reps * repMs

    private val useAbs = exercise.signal.startsWith("abs:")
    private val metric = exercise.signal.removePrefix("abs:")
    private val baseMetrics = Metrics.compute(base)

    /** Movement that drives the rep signal, or null if no movement can (unknown metric). */
    val motion: Motion? = solveSignal()

    /** Form rule shown in the mistake variant, and the movement that breaks it. */
    val mistakeRule: FormRule? = if (mistake) exercise.formRules.firstOrNull { solveForm(it) != null } else null
    private val mistakeMotion: Motion? = mistakeRule?.let { solveForm(it) }

    /** True when the demo really reaches the counting band. */
    val countable get() = motion != null

    fun frameAt(tMsIn: Long): Frame {
        val t = tMsIn.coerceIn(0, durationMs)
        if (t < leadMs) return Frame(t, breathe(base, t), Phase.CALIBRATE, leadMs - t, 0)
        val i = ((t - leadMs) / repMs).toInt().coerceAtMost(reps - 1)
        val r = t - leadMs - i * repMs
        val (phase, left, env) = when {
            r < inMs -> Triple(Phase.IN, inMs - r, ease(r.toDouble() / inMs))
            r < inMs + holdMs -> Triple(Phase.HOLD, inMs + holdMs - r, 1.0)
            r < inMs + holdMs + outMs -> Triple(Phase.OUT, inMs + holdMs + outMs - r, 1.0 - ease((r - inMs - holdMs).toDouble() / outMs))
            else -> Triple(Phase.REST, repMs - r, 0.0)
        }
        var p = base
        motion?.let { p = apply(p, it.move, it.amounts[i % it.amounts.size] * env) }
        mistakeMotion?.let { p = apply(p, it.move, it.amounts[0] * env) }
        return Frame(t, breathe(p, t), phase, left, i + 1)
    }

    /** Signal the tracker would compute for [p] against the neutral pose. */
    fun signalOf(p: Map<String, Metrics.P>): Double? {
        val v = Metrics.compute(p)[metric] ?: return null
        val d = (v - (baseMetrics[metric] ?: return null)) * exercise.direction
        return if (useAbs) abs(d) else d
    }

    // ------------------------------------------------------------------ solving

    private fun solveSignal(): Motion? {
        if (metric.isEmpty() || baseMetrics[metric] == null) return null
        val target = exercise.repHigh * 1.4
        val candidates = listOfNotNull(PREFERRED[metric]) + Move.values().filter { it != PREFERRED[metric] }
        for (move in candidates) {
            val pos = solve(move, target) { signalOf(it) }
            val neg = solve(move, -target) { signalOf(it) }
            val found = listOfNotNull(pos, neg)
            if (found.isEmpty()) continue
            // abs signals: alternate both sides (e.g. turn left, then right); otherwise the smallest movement
            return if (useAbs && found.size == 2) Motion(move, found) else Motion(move, listOf(found.minBy { abs(it) }))
        }
        return null
    }

    private fun solveForm(rule: FormRule): Motion? {
        val b = baseMetrics[rule.metric] ?: return null
        val target = rule.maxAbs * 1.6
        fun value(p: Map<String, Metrics.P>): Double? {
            val v = Metrics.compute(p)[rule.metric] ?: return null
            return abs(if (rule.relative) v - b else v)
        }
        val signalMove = motion?.move
        val candidates = listOfNotNull(PREFERRED[rule.metric]) + Move.values().filter { it != PREFERRED[rule.metric] }
        for (move in candidates.filter { it != signalMove }) {
            val u = solve(move, target) { value(it) } ?: solve(move, -target) { value(it) } ?: continue
            return Motion(move, listOf(u))
        }
        return null
    }

    /** Smallest amount (sign of [target]) at which [f] reaches |target|, by scan + bisection. */
    private fun solve(move: Move, target: Double, f: (Map<String, Metrics.P>) -> Double?): Double? {
        val sign = if (target < 0) -1.0 else 1.0
        val goal = abs(target)
        val max = LIMIT.getValue(move) * if (move in DEGREES) 1.0 else zoom
        val steps = 60
        var prev = 0.0
        for (k in 1..steps) {
            val u = sign * max * k / steps
            val v = f(apply(base, move, u)) ?: continue
            if (v >= goal) {
                var lo = prev; var hi = u
                repeat(30) {
                    val mid = (lo + hi) / 2
                    val mv = f(apply(base, move, mid))
                    if (mv != null && mv >= goal) hi = mid else lo = mid
                }
                return hi
            }
            prev = u
        }
        return null
    }

    companion object {
        const val W = 720
        const val H = 960
        const val DEMO_REPS = 3

        private val PREFERRED = mapOf(
            "head_forward_proxy" to Move.HEAD_SHIFT,
            "cervical_sag_proxy" to Move.HEAD_NOD,
            "neck_yaw_est" to Move.HEAD_YAW,
            "cervical_tilt" to Move.HEAD_TILT,
            "shoulder_line_tilt" to Move.SHOULDER_HIKE,
            "trunk_lean_sagittal" to Move.TRUNK_ROTATE,
            "trunk_tilt_frontal" to Move.TRUNK_ROTATE,
            "shoulder_width_ratio" to Move.SHOULDER_SQUEEZE,
            "head_forward_neck" to Move.HEAD_SHIFT,
            "head_pitch_side" to Move.HEAD_NOD,
            "head_roll" to Move.HEAD_TILT,
            "shoulder_elev" to Move.SHRUG,
            "arm_elevation" to Move.ARMS_OUT,
            "arm_elev_asym" to Move.ARM_SWING,
            "arm_ext_side" to Move.ARM_SWING,
        )

        private val LIMIT = mapOf(
            Move.HEAD_SHIFT to 80.0, Move.HEAD_NOD to 45.0, Move.HEAD_YAW to 60.0, Move.HEAD_TILT to 40.0,
            Move.SHOULDER_HIKE to 60.0, Move.SHRUG to 60.0, Move.TRUNK_ROTATE to 35.0, Move.SHOULDER_SQUEEZE to 60.0,
            Move.ARMS_OUT to 110.0, Move.ARM_SWING to 60.0,
        )

        private val DEGREES = setOf(Move.HEAD_NOD, Move.HEAD_TILT, Move.TRUNK_ROTATE, Move.ARMS_OUT, Move.ARM_SWING)

        fun zoomFor(program: String) = when (program) { "neck" -> 2.2; "upper_body" -> 1.25; else -> 1.0 }

        private val HEAD = setOf("nose", "lear", "rear")
        private val UPPER = setOf("nose", "lear", "rear", "lsho", "rsho", "lelb", "relb", "lwri", "rwri")

        private fun p(x: Double, y: Double, c: Double = 0.95) = Metrics.P(x, y, c)

        /** Seated figure in a [W]x[H] portrait frame, seen from the side (facing right),
         *  the front, or the back, framed for the exercise's programme: whole seated
         *  body (full), waist up (upper_body) or head and shoulders (neck). Far-side
         *  and out-of-frame points have low visibility, as a real pose model reports. */
        fun basePose(view: String, program: String = "full"): Map<String, Metrics.P> {
            val zoom = zoomFor(program)
            val body = seated(view)
            if (zoom == 1.0) return body
            val out = LinkedHashMap<String, Metrics.P>()
            for ((k, v) in body) {
                val x = W / 2.0 + (v.x - W / 2.0) * zoom; val y = 330.0 + (v.y - 330.0) * zoom
                val inside = x > 0 && x < W && y > 0 && y < H
                out[k] = Metrics.P(x, y, if (inside) v.c else 0.0)
            }
            return out
        }

        private fun seated(view: String): Map<String, Metrics.P> = when (view) {
            "side" -> linkedMapOf(
                "nose" to p(405.0, 312.0),
                "lear" to p(352.0, 300.0), "rear" to p(346.0, 298.0, 0.2),
                "lsho" to p(340.0, 400.0), "rsho" to p(334.0, 398.0, 0.3),
                "lelb" to p(362.0, 530.0), "relb" to p(356.0, 528.0, 0.3),
                "lwri" to p(440.0, 600.0), "rwri" to p(434.0, 598.0, 0.3),
                "lhip" to p(330.0, 640.0), "rhip" to p(324.0, 638.0, 0.3),
                "lknee" to p(480.0, 650.0), "rknee" to p(474.0, 648.0, 0.3),
                "lank" to p(480.0, 830.0), "rank" to p(474.0, 828.0, 0.3),
            )
            "back" -> frontPose(mirror = true, faceVisible = false)
            else -> frontPose(mirror = false, faceVisible = true)
        }

        private fun frontPose(mirror: Boolean, faceVisible: Boolean): Map<String, Metrics.P> {
            // The subject's left appears on the image right when facing the camera, and on the left from behind.
            fun x(v: Double) = if (mirror) W - v else v
            return linkedMapOf(
                "nose" to p(360.0, 305.0, if (faceVisible) 0.95 else 0.05),
                "lear" to p(x(395.0), 295.0, if (faceVisible) 0.9 else 0.7), "rear" to p(x(325.0), 295.0, if (faceVisible) 0.9 else 0.7),
                "lsho" to p(x(465.0), 400.0), "rsho" to p(x(255.0), 400.0),
                "lelb" to p(x(490.0), 540.0), "relb" to p(x(230.0), 540.0),
                "lwri" to p(x(430.0), 610.0), "rwri" to p(x(290.0), 610.0),
                "lhip" to p(x(420.0), 650.0), "rhip" to p(x(300.0), 650.0),
                "lknee" to p(x(440.0), 720.0), "rknee" to p(x(280.0), 720.0),
                "lank" to p(x(440.0), 880.0), "rank" to p(x(280.0), 880.0),
            )
        }

        private fun mid(a: Metrics.P?, b: Metrics.P?): Pair<Double, Double> =
            if (a == null || b == null) Pair(W / 2.0, H / 2.0) else Pair((a.x + b.x) / 2, (a.y + b.y) / 2)

        private fun rotate(q: Metrics.P, cx: Double, cy: Double, deg: Double): Metrics.P {
            val r = deg * PI / 180; val dx = q.x - cx; val dy = q.y - cy
            return Metrics.P(cx + dx * cos(r) - dy * sin(r), cy + dx * sin(r) + dy * cos(r), q.c)
        }

        /** Applies one movement of [u] (px or degrees) to a pose. */
        fun apply(p: Map<String, Metrics.P>, move: Move, u: Double): Map<String, Metrics.P> {
            if (u == 0.0) return p
            val out = LinkedHashMap(p)
            val nose = p["nose"]; val lear = p["lear"]
            // side view: facing direction from nose vs ear
            val facing = if (nose != null && lear != null && nose.x < lear.x) -1.0 else 1.0
            val (smx, _) = mid(p["lsho"], p["rsho"])
            val (hmx, hmy) = mid(p["lhip"], p["rhip"])
            when (move) {
                Move.HEAD_SHIFT -> for (k in HEAD) p[k]?.let { out[k] = it.copy(x = it.x + u * facing) }
                Move.HEAD_NOD -> {
                    val pivot = p["lear"] ?: return p
                    p["nose"]?.let { out["nose"] = rotate(it, pivot.x, pivot.y, u) }
                }
                Move.HEAD_YAW -> {
                    p["nose"]?.let { out["nose"] = it.copy(x = it.x + u) }
                    for (k in listOf("lear", "rear")) p[k]?.let { out[k] = it.copy(x = it.x + u * 0.35) }
                }
                Move.HEAD_TILT -> {      // side bend about the middle of the head, so the face stays forward
                    val (ex, ey) = mid(p["lear"], p["rear"])
                    for (k in HEAD) p[k]?.let { out[k] = rotate(it, ex, ey, u) }
                }
                Move.SHOULDER_HIKE -> {
                    p["rsho"]?.let { out["rsho"] = it.copy(y = it.y - u) }
                    p["relb"]?.let { out["relb"] = it.copy(y = it.y - u * 0.7) }
                    p["rwri"]?.let { out["rwri"] = it.copy(y = it.y - u * 0.3) }
                }
                Move.SHRUG -> for (s in listOf("l", "r")) {
                    p[s + "sho"]?.let { out[s + "sho"] = it.copy(y = it.y - u) }
                    p[s + "elb"]?.let { out[s + "elb"] = it.copy(y = it.y - u * 0.8) }
                    p[s + "wri"]?.let { out[s + "wri"] = it.copy(y = it.y - u * 0.6) }
                }
                Move.ARMS_OUT -> for (s in listOf("l", "r")) {      // both arms raised out to the side
                    val sho = p[s + "sho"] ?: continue
                    val outward = if (sho.x > smx) -1.0 else 1.0
                    for (k in listOf(s + "elb", s + "wri")) p[k]?.let { out[k] = rotate(it, sho.x, sho.y, u * outward) }
                }
                Move.ARM_SWING -> for (s in listOf("l", "r")) {     // both arms swing the same way (back in profile)
                    val sho = p[s + "sho"] ?: continue
                    for (k in listOf(s + "elb", s + "wri")) p[k]?.let { out[k] = rotate(it, sho.x, sho.y, u) }
                }
                Move.TRUNK_ROTATE -> for (k in UPPER) p[k]?.let { out[k] = rotate(it, hmx, hmy, u) }
                Move.SHOULDER_SQUEEZE -> for (k in listOf("lsho", "rsho", "lelb", "relb")) p[k]?.let {
                    val toward = if (it.x > smx) -1.0 else 1.0
                    out[k] = it.copy(x = it.x + toward * u * if (k.endsWith("elb")) 0.8 else 1.0)
                }
            }
            return out
        }

        /** Gentle breathing so the figure looks alive; small enough to stay inside every rep band. */
        fun breathe(p: Map<String, Metrics.P>, tMs: Long): Map<String, Metrics.P> {
            val dy = sin(tMs / 4000.0 * 2 * PI) * 1.0
            return p.mapValues { (k, v) -> if (k.endsWith("sho") || k.endsWith("elb")) v.copy(y = v.y + dy) else v }
        }

        private fun ease(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    }
}
