package com.spinecare.app

import com.spinecare.app.I18n.t
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Camera-placement coach: turns one pose (plus the phone's tilt, when known)
 *  into a checklist of concrete fixes, e.g. "turn sideways", "step back",
 *  "lower the phone to chest height", "stand the phone upright". Used by the
 *  live setup check and by video analysis. Pure Kotlin; texts come from I18n. */
object PlacementCoach {
    /** One checklist line. [key] is stable (person, view, distance, center, height, upright, level, visible, light). */
    data class Item(val key: String, val ok: Boolean, val fixKey: String = "", val fixArg: String = "")
    data class Result(val items: List<Item>) {
        val ready get() = items.all { it.ok }
        val firstFix get() = items.firstOrNull { !it.ok }
    }

    const val MAX_TILT_DEG = 12.0
    const val MAX_ROLL_DEG = 8.0

    /** Phone tilt from the gravity sensor (device axes, m/s²) for a phone held in portrait.
     *  pitch: leaning back (+) or forward (-) from upright; roll: sideways. Degrees. */
    fun tiltFromGravity(gx: Double, gy: Double, gz: Double): Pair<Double, Double> {
        val pitch = Math.toDegrees(atan2(gz, hypot(gx, gy)))
        val roll = Math.toDegrees(atan2(gx, gy))
        return Pair(pitch, roll)
    }

    /** [p] in pixels of a [w]x[h] upright frame; [pitch]/[roll] null when no sensor (e.g. a video). */
    fun assess(p: Map<String, Metrics.P>?, w: Double, h: Double, ex: Exercise, minConf: Double = 0.5,
               pitch: Double? = null, roll: Double? = null): Result {
        val items = ArrayList<Item>()
        val tiltOk = pitch == null || abs(pitch) <= MAX_TILT_DEG
        val levelOk = roll == null || abs(roll) <= MAX_ROLL_DEG
        fun phoneItems() {
            if (pitch != null) items.add(Item("upright", tiltOk, "pc_fix_upright"))
            if (roll != null) items.add(Item("level", levelOk, "pc_fix_level"))
        }
        fun vis(k: String) = (p?.get(k)?.c ?: 0.0) >= minConf
        val anyBody = p != null && (vis("lsho") || vis("rsho") || vis("nose"))
        items.add(Item("person", anyBody, "pc_fix_person"))
        if (p == null || !anyBody) { phoneItems(); return Result(items) }

        // Which way the body faces: shoulders look narrow in profile, wide from the front or back.
        // Measured against the torso, or against the ear-to-shoulder distance when the hips are out of view.
        val shoMid = mid(p["lsho"], p["rsho"])
        val hipMid = mid(p["lhip"], p["rhip"])
        val torso = if (shoMid != null && hipMid != null && (vis("lhip") || vis("rhip"))) hypot(shoMid.first - hipMid.first, shoMid.second - hipMid.second) else null
        val upSide = Metrics.bestSideUpper(p)
        val neck = upSide?.let { s -> val e = p.getValue(s + "ear"); val q = p.getValue(s + "sho"); hypot(e.x - q.x, e.y - q.y) }
        val shoW = if (p["lsho"] != null && p["rsho"] != null) abs(p.getValue("lsho").x - p.getValue("rsho").x) else null
        val ratio = when {
            shoW == null -> null
            torso != null && torso > 1e-6 -> shoW / torso
            neck != null && neck > 1e-6 -> shoW / neck * 0.45      // ear-shoulder ≈ 0.45 torso
            else -> null
        }
        val faceSeen = (p["nose"]?.c ?: 0.0) >= 0.5
        val view = when {
            ratio == null -> null
            ratio < 0.45 -> "side"
            faceSeen -> "front"
            else -> "back"
        }
        val viewFix = when (ex.view) {
            "side" -> if (view != null && view != "side") "pc_fix_turn_side" else null
            "front" -> if (view == "side" || view == "back") "pc_fix_face" else null
            "back" -> if (view == "side" || view == "front") "pc_fix_turn_back" else null
            else -> null
        }
        items.add(Item("view", viewFix == null, viewFix ?: ""))

        // Distance: body size in the picture. Full programme: torso about 1/4 of the frame height
        // (seated ~2 m away). Waist-up and head-and-shoulders programmes sit closer, judged by the
        // ear-to-shoulder distance.
        val headTopY = listOfNotNull(p["nose"], p["lear"], p["rear"]).filter { it.c >= minConf }.minOfOrNull { it.y }
        val (lo, hi) = when (ex.program) { "neck" -> Pair(0.14, 0.40); "upper_body" -> Pair(0.09, 0.30); else -> Pair(0.12, 0.45) }
        val size = if (ex.program == "neck" || ex.program == "upper_body") neck?.let { it / h } else torso?.let { it / h }
        val tooClose = (size != null && size > hi) || (size == null && shoW != null && shoW / w > 0.75)
        val tooFar = size != null && size < lo
        items.add(Item("distance", !tooClose && !tooFar, if (tooClose) "pc_fix_step_back" else if (tooFar) "pc_fix_come_closer" else ""))

        // Centre: the body midline in the middle 40 % of the picture.
        val cx = shoMid?.first ?: p["nose"]?.x
        val centred = cx == null || (cx >= 0.3 * w && cx <= 0.7 * w)
        items.add(Item("center", centred, "pc_fix_center"))

        // Height: a level camera at chest height puts the shoulders a little above the middle.
        val shoY = shoMid?.second
        val heightFix = when {
            headTopY != null && headTopY < 0.06 * h -> "pc_fix_raise"
            shoY != null && shoY > 0.62 * h -> "pc_fix_lower"
            else -> null
        }
        items.add(Item("height", heightFix == null, heightFix ?: ""))
        phoneItems()

        // The exercise's own landmarks must be clearly inside the frame.
        val framing = Framing.check(p, ex.requiredLandmarks, minConf, w, h)
        val issue = framing.issues.firstOrNull()
        items.add(Item("visible", framing.ready, if (issue != null) "fix:$issue" else ""))

        // Light: visible but uncertain landmarks usually mean dim or back-lit scenes.
        val confs = ex.requiredLandmarks.mapNotNull { p[Framing.resolve(p, it)]?.c }.filter { it >= minConf }
        val lightOk = confs.isEmpty() || confs.average() >= 0.65
        items.add(Item("light", lightOk, "pc_fix_light"))
        return Result(items)
    }

    /** Localized fix for a checklist item, or for a fix key stored in a video report. */
    fun fixText(fixKey: String): String =
        if (fixKey.startsWith("fix:")) FramingGuide.fixFor(fixKey.removePrefix("fix:")) else t(fixKey)

    fun itemText(item: Item): String = t("pc_item_" + item.key)

    private fun mid(a: Metrics.P?, b: Metrics.P?): Pair<Double, Double>? = when {
        a != null && b != null -> Pair((a.x + b.x) / 2, (a.y + b.y) / 2)
        a != null -> Pair(a.x, a.y)
        b != null -> Pair(b.x, b.y)
        else -> null
    }
}

/** Running summary of a processed video: framing, placement problems, reps
 *  and form cues, turned into a short list of tips at the end. */
class VideoReport(val exercise: Exercise) {
    var frames = 0; private set
    var framedFrames = 0; private set
    var trackedFrames = 0; private set
    var reps = 0; private set
    var calibrated = false; private set
    var durationMs = 0L; private set
    val placementCounts = LinkedHashMap<String, Int>()     // fix key -> frames
    val cueCounts = LinkedHashMap<String, Int>()           // cue text -> frames

    fun add(tMs: Long, placement: PlacementCoach.Result, ready: Boolean, state: ExerciseTracker.State?) {
        frames++
        durationMs = maxOf(durationMs, tMs)
        if (ready) framedFrames++
        placement.firstFix?.let { val k = it.fixKey.ifEmpty { it.key }; placementCounts[k] = (placementCounts[k] ?: 0) + 1 }
        if (state != null) {
            reps = state.reps
            if (!state.calibrating) calibrated = true
            if (ready && !state.calibrating) {
                trackedFrames++
                for (c in state.cues) cueCounts[c.text] = (cueCounts[c.text] ?: 0) + 1
            }
        }
    }

    val framedFraction get() = if (frames == 0) 0.0 else framedFrames.toDouble() / frames

    /** Localized tips, most important first. */
    fun tipTexts(): List<String> = tips().map { (key, arg) ->
        val what = arg.substringBeforeLast('|'); val pct = arg.substringAfterLast('|', "")
        when (key) {
            "va_tip_placement" -> t(key).format(PlacementCoach.fixText(what), pct)
            "va_tip_cue" -> t(key).format(what, pct)
            else -> t(key)
        }
    }

    /** Tip keys with their format argument, most important first. */
    fun tips(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (frames == 0) return listOf("va_tip_no_frames" to "")
        val pct = { n: Int -> (100.0 * n / frames).toInt().toString() }
        placementCounts.entries.sortedByDescending { it.value }.firstOrNull()?.let { (k, n) ->
            if (n >= frames * 0.15) out.add("va_tip_placement" to "$k|${pct(n)}")
        }
        if (!calibrated) out.add("va_tip_calibrate" to "")
        else if (reps == 0 && framedFraction >= 0.6) out.add("va_tip_no_reps" to "")
        cueCounts.entries.sortedByDescending { it.value }.firstOrNull()?.let { (text, n) ->
            if (trackedFrames > 0 && n >= trackedFrames * 0.1) out.add("va_tip_cue" to "$text|${(100.0 * n / trackedFrames).toInt()}")
        }
        if (out.isEmpty()) out.add("va_tip_good" to "")
        return out
    }
}
