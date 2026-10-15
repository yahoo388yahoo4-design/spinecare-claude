package com.spinecare.app

import kotlin.math.hypot

/** Live help getting into position on the session screen. Each analysed frame
 *  becomes one hint: the most important thing to fix (from PlacementCoach),
 *  said in the person's own terms ("shift a little to your left", "slide your
 *  chair forward", "bring the phone closer"), which way to move (drawn as an
 *  arrow), and a ghost outline of where the body should be. A [Stabilizer]
 *  keeps the spoken hint from flickering and repeats it while it is still
 *  wrong. Pure Kotlin; texts come from I18n. */
object FrameCoach {
    /** What the drawn arrow shows. LEFT / RIGHT / UP / DOWN are the way the body
     *  has to move in the camera picture (UP: raise the phone, so the body sits lower). */
    enum class Kind { NONE, PERSON, VIEW, CLOSER, BACK, LEFT, RIGHT, UP, DOWN, LIGHT, VISIBLE }

    data class Hint(
        val ok: Boolean,
        /** I18n key, or "fix:<framing issue>" (see PlacementCoach.fixText). */
        val key: String,
        val kind: Kind,
        /** Middle of the body in frame pixels, or null when nobody is seen. */
        val center: Pair<Double, Double>?,
        /** Outline of where the body should be, in frame pixels. */
        val ghost: Map<String, Metrics.P>,
    )

    const val KEY_OK = "fc_good"

    fun assess(p: Map<String, Metrics.P>?, w: Double, h: Double, ex: Exercise, minConf: Double = 0.5): Hint {
        val ghost = ghost(ex.view, ex.program, w, h)
        val center = p?.let { bodyCenter(it, minConf) }
        val fix = PlacementCoach.assess(p, w, h, ex, minConf).firstFix
            ?: return Hint(true, KEY_OK, Kind.NONE, center, ghost)
        fun hint(key: String, kind: Kind) = Hint(false, key, kind, center, ghost)
        return when (fix.key) {
            "person" -> hint("pc_fix_person", Kind.PERSON)
            "view" -> hint(fix.fixKey, Kind.VIEW)
            "distance" -> if (fix.fixKey == "pc_fix_step_back") hint("fc_step_back", Kind.BACK) else hint("fc_come_closer", Kind.CLOSER)
            "center" -> {
                val right = (center?.first ?: (w / 2)) < w / 2      // body left of the middle: it has to move right in the picture
                hint(sideways(ex.view, right, p), if (right) Kind.RIGHT else Kind.LEFT)
            }
            "height" -> if (fix.fixKey == "pc_fix_raise") hint("fc_raise", Kind.UP) else hint("fc_lower", Kind.DOWN)
            "visible" -> edgeHint(p, w, h, ex, fix.fixKey, ::hint)
            "light" -> hint("pc_fix_light", Kind.LIGHT)
            else -> hint(fix.fixKey.ifEmpty { "fix_generic" }, Kind.NONE)
        }
    }

    /** A required body part outside the picture: which edge it left by says how to move. */
    private fun edgeHint(p: Map<String, Metrics.P>?, w: Double, h: Double, ex: Exercise, fixKey: String,
                         hint: (String, Kind) -> Hint): Hint {
        val issue = fixKey.removePrefix("fix:")
        if (p == null || !issue.endsWith(":out_of_frame")) return hint(fixKey, Kind.VISIBLE)
        val lm = p[Framing.resolve(p, issue.substringBefore(':'))] ?: return hint(fixKey, Kind.VISIBLE)
        val m = 0.03
        return when {
            lm.y < m * h -> hint("fc_edge_top", Kind.UP)
            lm.y > (1 - m) * h -> hint("fc_edge_bottom", Kind.DOWN)
            lm.x < m * w -> hint(sideways(ex.view, true, p), Kind.RIGHT)
            lm.x > (1 - m) * w -> hint(sideways(ex.view, false, p), Kind.LEFT)
            else -> hint(fixKey, Kind.VISIBLE)
        }
    }

    /** The way to move sideways, in the person's terms, for a body that has to move
     *  toward the picture's right ([right]) or left. Facing the camera, the picture's
     *  right is the person's left; from behind it is their right; in profile it is
     *  forward or back depending on which way they face. */
    fun sideways(view: String, right: Boolean, p: Map<String, Metrics.P>?): String = when (view) {
        "front" -> if (right) "fc_step_left" else "fc_step_right"
        "back" -> if (right) "fc_step_right" else "fc_step_left"
        "side" -> {
            val nose = p?.get("nose"); val ear = p?.get(Metrics.bestSideUpper(p ?: emptyMap())?.let { it + "ear" } ?: "lear")
            if (nose == null || ear == null) "pc_fix_center"
            else if ((nose.x > ear.x) == right) "fc_move_forward" else "fc_move_backward"
        }
        else -> "pc_fix_center"
    }

    /** Middle of the visible shoulders and hips (or the nose). */
    fun bodyCenter(p: Map<String, Metrics.P>, minConf: Double): Pair<Double, Double>? {
        val pts = listOf("lsho", "rsho", "lhip", "rhip").mapNotNull { p[it] }.filter { it.c >= minConf }
            .ifEmpty { listOfNotNull(p["nose"]?.takeIf { it.c >= minConf }) }
        if (pts.isEmpty()) return null
        return Pair(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)
    }

    /** The guide's seated figure for this view and programme, scaled to a [w]x[h]
     *  camera frame (fitted by height, centred): where the body should sit. */
    fun ghost(view: String, program: String, w: Double, h: Double): Map<String, Metrics.P> {
        val k = h / GuideDemo.H
        val ox = (w - GuideDemo.W * k) / 2
        return GuideDemo.basePose(view, program).mapValues { (_, v) -> Metrics.P(ox + v.x * k, v.y * k, v.c) }
    }

    /** Head radius of a ghost, in its pixels. */
    fun headRadius(g: Map<String, Metrics.P>): Double {
        val l = g["lear"]; val r = g["rear"]; val n = g["nose"]
        val ears = if (l != null && r != null) hypot(l.x - r.x, l.y - r.y) * 0.75 else 0.0
        val side = if (l != null && n != null) hypot(l.x - n.x, l.y - n.y) * 0.95 else 0.0
        return maxOf(ears, side, 8.0)
    }

    /** Spoken text for a hint key; [beforeStart] uses the "all good, tap Start" wording. */
    fun text(key: String, beforeStart: Boolean): String =
        if (key == KEY_OK) I18n.t(if (beforeStart) "vg_framing_ok" else KEY_OK) else PlacementCoach.fixText(key)

    /** Decides when a hint is spoken: once it has held for [holdMs] (so a hint that
     *  flickers for a frame is never said), again every [repeatMs] while it is still
     *  a problem, and "good position" once when the problems are gone. */
    class Stabilizer(private val holdMs: Long = 900, private val repeatMs: Long = 7000) {
        private var candidate: String? = null
        private var since = 0L
        private var spokenAt = 0L
        /** The hint that has held long enough, or null before the first one. */
        var stable: String? = null; private set

        /** Feeds this frame's hint key; returns the key to say now, or null. */
        fun update(key: String, nowMs: Long): String? {
            if (key != candidate) { candidate = key; since = nowMs }
            if (nowMs - since < holdMs) return null
            if (key != stable) { stable = key; spokenAt = nowMs; return key }
            if (key != KEY_OK && nowMs - spokenAt >= repeatMs) { spokenAt = nowMs; return key }
            return null
        }

        fun reset() { candidate = null; stable = null; since = 0L; spokenAt = 0L }
    }
}
