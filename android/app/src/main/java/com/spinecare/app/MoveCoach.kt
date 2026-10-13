package com.spinecare.app

import kotlin.math.roundToInt

/** Realtime movement coaching on the tracker's calibrated signal, per rep:
 *  tempo prompts while moving (reached the position -> "hold", hold time done
 *  -> "and back slowly") and, once the rep is counted, a short precision and
 *  speed verdict against the plan's rep band and tempo. Pure Kotlin so the JVM
 *  tests can drive it with synthetic signals.
 *
 *  A movement starts when the signal leaves the rest zone (above the band's
 *  low edge), reaches the position at the band's high edge, and ends back at
 *  rest; the rep counter decides whether it counted. */
class MoveCoach(private val low: Double, private val high: Double,
                tempoInS: Double, tempoHoldS: Double, tempoOutS: Double) {

    constructor(ex: Exercise) : this(ex.repLow, ex.repHigh, ex.tempoIn, ex.tempoHold, ex.tempoOut)

    enum class Prompt { HOLD, RELEASE }

    /** Verdict on one movement. [keys] are I18n keys of the short phrases, most important first. */
    data class Feedback(
        val score: Int,             // 0..100
        val rangePct: Int,          // peak signal as % of the band's high edge
        val inMs: Long, val holdMs: Long, val outMs: Long,
        val counted: Boolean,
        val keys: List<String>,
    )

    data class Update(val prompt: Prompt? = null, val feedback: Feedback? = null)

    val targetInMs = (tempoInS * 1000).toLong().coerceAtLeast(300)
    val targetHoldMs = (tempoHoldS * 1000).toLong().coerceAtLeast(400)
    val targetOutMs = (tempoOutS * 1000).toLong().coerceAtLeast(300)

    private var moving = false
    private var tStart = 0L
    private var tReached = -1L
    private var tLastAbove = -1L
    private var peak = 0.0
    private var releaseSent = false
    private var formIssue = false
    /** Time of the last movement end (or of [reset]); for "you have not started yet" reminders. */
    var lastActivityMs = -1L; private set
    val isMoving get() = moving

    fun reset(nowMs: Long) { moving = false; tReached = -1L; lastActivityMs = nowMs }

    /** Feed one frame: calibrated [signal] (null while calibrating or not ready),
     *  whether a form cue is active, and whether the rep counter just counted. */
    fun update(signal: Double?, nowMs: Long, formCue: Boolean, repJustDone: Boolean): Update {
        if (lastActivityMs < 0) lastActivityMs = nowMs
        if (signal == null || signal.isNaN()) return Update()
        var prompt: Prompt? = null
        if (!moving) {
            if (signal <= low) return Update()
            moving = true; tStart = nowMs; tReached = -1L; tLastAbove = -1L
            peak = signal; releaseSent = false; formIssue = false
        }
        if (signal > peak) peak = signal
        if (formCue) formIssue = true
        if (signal >= high) {
            if (tReached < 0) { tReached = nowMs; prompt = Prompt.HOLD }
            tLastAbove = nowMs
            if (!releaseSent && nowMs - tReached >= targetHoldMs) { releaseSent = true; prompt = Prompt.RELEASE }
        }
        if (signal <= low || repJustDone) {
            moving = false; lastActivityMs = nowMs
            // Ignore small wobbles that never got near the position.
            if (!repJustDone && peak < (low + high) / 2) return Update(prompt)
            return Update(prompt, assess(nowMs, repJustDone))
        }
        return Update(prompt)
    }

    private fun assess(nowMs: Long, counted: Boolean): Feedback {
        val reached = tReached >= 0
        val inMs = (if (reached) tReached else nowMs) - tStart
        val holdMs = if (reached) tLastAbove - tReached else 0L
        val outMs = if (reached) nowMs - tLastAbove else 0L
        val range = if (high > 0) peak / high else 0.0
        val keys = ArrayList<String>()
        var score = 100
        when {
            !reached -> { keys.add("fb_further"); score -= 40 }
            range > 2.5 -> { keys.add("fb_gentler"); score -= 20 }
        }
        if (reached && holdMs < targetHoldMs * 0.6) { keys.add("fb_hold_longer"); score -= 20 }
        val moveMs = inMs + outMs
        val targetMove = targetInMs + targetOutMs
        if (reached) when {
            moveMs < targetMove * 0.5 -> { keys.add("fb_slower"); score -= 20 }
            moveMs > targetMove * 3.0 -> { keys.add("fb_quicker"); score -= 10 }
        }
        if (formIssue) { keys.add("fb_form"); score -= 20 }
        if (keys.isEmpty()) keys.add(if (range >= 1.2) "fb_great" else "fb_good")
        return Feedback(score.coerceIn(0, 100), (range * 100).roundToInt(), inMs, holdMs, outMs, counted, keys)
    }

    companion object {
        /** I18n key of the spoken/shown instruction for a demo movement. */
        fun instructionKey(move: GuideDemo.Move?): String = when (move) {
            null -> "mv_generic"
            else -> "mv_" + move.name.lowercase()
        }
    }
}
