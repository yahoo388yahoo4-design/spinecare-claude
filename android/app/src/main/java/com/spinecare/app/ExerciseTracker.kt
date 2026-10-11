package com.spinecare.app

import kotlin.math.abs

/** Hysteresis rep counter on a scalar signal, mirroring server RepEngine.
 *  A rep = signal rises above [high] (held >= [minHoldMs]) and returns below [low]. */
class RepEngine(private val low: Double, private val high: Double, private val minHoldMs: Long = 400) {
    enum class Phase { REST, OUT, IN }
    var phase = Phase.REST; private set
    var reps = 0; private set
    private var tEnter = 0L

    /** Returns true when a rep just completed. */
    fun update(value: Double?, nowMs: Long): Boolean {
        if (value == null || value.isNaN()) return false
        var done = false
        when (phase) {
            Phase.REST, Phase.IN -> if (value >= high) { phase = Phase.OUT; tEnter = nowMs }
            Phase.OUT -> if (value <= low) {
                if (nowMs - tEnter >= minHoldMs) { reps++; done = true }
                phase = Phase.IN; tEnter = nowMs
            }
        }
        return done
    }
}

/** Framing gate, mirroring server framing_check. Coordinates in pixels. */
object Framing {
    data class Result(val ready: Boolean, val issues: List<String>, val missing: List<String>)

    fun resolve(p: Map<String, Metrics.P>, name: String): String {
        if (!name.startsWith("any_")) return name
        val part = name.removePrefix("any_")
        return if ((p["l$part"]?.c ?: 0.0) >= (p["r$part"]?.c ?: 0.0)) "l$part" else "r$part"
    }

    fun check(p: Map<String, Metrics.P>?, required: List<String>, minConf: Double,
              w: Double, h: Double, margin: Double = 0.03): Result {
        if (p == null) return Result(false, listOf("no_person"), required)
        val issues = ArrayList<String>(); val missing = ArrayList<String>()
        for (req in required) {
            val lm = p[resolve(p, req)]
            if (lm == null || lm.c < minConf) { missing.add(req); issues.add("$req:low_conf") }
            else if (lm.x < margin * w || lm.x > (1 - margin) * w || lm.y < margin * h || lm.y > (1 - margin) * h) {
                missing.add(req); issues.add("$req:out_of_frame")
            }
        }
        return Result(missing.isEmpty(), issues, missing)
    }
}

/** Calibration + signal + reps + form cues for one exercise. Mirrors the
 *  server ExerciseTracker so on-device and server coaching behave the same. */
class ExerciseTracker(private val ex: Exercise, private val calibrationMs: Long, private val lang: String) {
    data class Cue(val metric: String, val value: Double, val text: String)
    data class State(val signal: Double?, val reps: Int, val phase: RepEngine.Phase,
                     val repJustDone: Boolean, val cues: List<Cue>, val calibrating: Boolean)

    val reps = RepEngine(ex.repLow, ex.repHigh)
    private val useAbs = ex.signal.startsWith("abs:")
    private val metric = ex.signal.removePrefix("abs:")
    private var calibStart = -1L
    private val samples = HashMap<String, MutableList<Double>>()
    var baseline: Map<String, Double>? = null; private set
    val calibrating get() = baseline == null

    fun recalibrate() { calibStart = -1L; samples.clear(); baseline = null }

    fun signal(m: Map<String, Double>): Double? {
        val raw = m[metric] ?: return null
        val b = baseline ?: return null
        val v = (raw - (b[metric] ?: 0.0)) * ex.direction
        return if (useAbs) abs(v) else v
    }

    fun update(m: Map<String, Double>, ready: Boolean, nowMs: Long): State {
        if (!ready) return State(null, reps.reps, reps.phase, false, emptyList(), calibrating)
        if (baseline == null) {
            if (calibStart < 0) calibStart = nowMs
            for ((k, v) in m) samples.getOrPut(k) { ArrayList() }.add(v)
            if (nowMs - calibStart >= calibrationMs && samples.containsKey(metric)) {
                baseline = samples.mapValues { median(it.value) }
            }
            return State(null, reps.reps, reps.phase, false, emptyList(), baseline == null)
        }
        val sig = signal(m)
        val done = reps.update(sig, nowMs)
        val cues = ArrayList<Cue>()
        for (rule in ex.formRules) {
            var v = m[rule.metric] ?: continue
            val b = baseline?.get(rule.metric)
            if (rule.relative && b != null) v -= b
            if (abs(v) > rule.maxAbs) cues.add(Cue(rule.metric, v, rule.cue[lang] ?: rule.cue["en"] ?: ""))
        }
        return State(sig, reps.reps, reps.phase, done, cues, false)
    }

    companion object {
        fun median(v: List<Double>): Double {
            val s = v.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}
