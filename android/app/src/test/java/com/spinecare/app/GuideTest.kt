package com.spinecare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The guide's demo video is processed by the real framing gate and tracker,
 *  so these tests prove the demo teaches movements the app actually counts. */
class GuideTest {
    private val bundle = Plan.parseBundle(File("../../shared/exercises.json").readText())
    private val calibMs = (bundle.calibrationS * 1000).toLong()
    private val camera = bundle.exercises.filter { it.cameraTracked }

    /** Plays a demo at 10 fps through framing + tracker, like video analysis does. */
    private fun process(demo: GuideDemo): Pair<ExerciseTracker.State?, List<String>> {
        val tr = ExerciseTracker(demo.exercise, demo.calibrationMs, "en")
        var last: ExerciseTracker.State? = null
        val cues = ArrayList<String>()
        var t = 0L
        while (t <= demo.durationMs) {
            val f = demo.frameAt(t)
            val framing = Framing.check(f.landmarks, demo.exercise.requiredLandmarks, demo.exercise.minConf,
                GuideDemo.W.toDouble(), GuideDemo.H.toDouble())
            assertTrue("${demo.exercise.id} framed at $t: ${framing.issues}", framing.ready)
            last = tr.update(Metrics.compute(f.landmarks), framing.ready, t)
            last.cues.forEach { cues.add(it.text) }
            t += 100
        }
        return Pair(last, cues)
    }

    @Test fun everyCameraExerciseDemoCountsItsReps() {
        assertTrue(camera.isNotEmpty())
        for (ex in camera) {
            val demo = GuideDemo(ex, calibMs)
            assertTrue("${ex.id} has a movement", demo.countable)
            val (state, cues) = process(demo)
            assertEquals("${ex.id} reps", demo.reps, state!!.reps)
            assertTrue("${ex.id} correct demo raises no form cue: $cues", cues.isEmpty())
        }
    }

    @Test fun mistakeDemoTriggersTheFormCue() {
        for (ex in camera.filter { it.formRules.isNotEmpty() }) {
            val demo = GuideDemo(ex, calibMs, mistake = true)
            val rule = demo.mistakeRule
            assertNotNull("${ex.id} has a demonstrable mistake", rule)
            val (_, cues) = process(demo)
            assertTrue("${ex.id} mistake cue shown", cues.contains(rule!!.cue["en"]))
        }
    }

    @Test fun rotationDemoTurnsBothWays() {
        val demo = GuideDemo(camera.first { it.signal.startsWith("abs:") }, calibMs)
        val amounts = demo.motion!!.amounts
        assertEquals(2, amounts.size)
        assertTrue(amounts[0] * amounts[1] < 0)
    }

    @Test fun demoTimelineFollowsTempo() {
        val ex = camera.first { it.id == "chin_tuck" }
        val demo = GuideDemo(ex, calibMs)
        assertEquals(GuideDemo.Phase.CALIBRATE, demo.frameAt(0).phase)
        val start = demo.leadMs
        assertEquals(GuideDemo.Phase.IN, demo.frameAt(start + 10).phase)
        assertEquals(GuideDemo.Phase.HOLD, demo.frameAt(start + (ex.tempoIn * 1000).toLong() + 10).phase)
        assertEquals(1, demo.frameAt(start + 10).rep)
        assertEquals(demo.reps, demo.frameAt(demo.durationMs).rep)
    }

    private fun w() = GuideDemo.W.toDouble()
    private fun h() = GuideDemo.H.toDouble()

    @Test fun placementAcceptsTheDemoPose() {
        for (ex in camera) {
            val r = PlacementCoach.assess(GuideDemo.basePose(ex.view), w(), h(), ex, pitch = 2.0, roll = -1.0)
            assertTrue("${ex.id}: ${r.items.filter { !it.ok }}", r.ready)
        }
    }

    @Test fun placementAsksToTurnForTheWrongView() {
        val side = camera.first { it.view == "side" }
        val front = camera.first { it.view == "front" }
        assertEquals("pc_fix_turn_side", PlacementCoach.assess(GuideDemo.basePose("front"), w(), h(), side).firstFix?.fixKey)
        assertEquals("pc_fix_face", PlacementCoach.assess(GuideDemo.basePose("side"), w(), h(), front).firstFix?.fixKey)
        camera.firstOrNull { it.view == "back" }?.let { back ->
            assertEquals("pc_fix_turn_back", PlacementCoach.assess(GuideDemo.basePose("front"), w(), h(), back).firstFix?.fixKey)
        }
    }

    private fun scaled(p: Map<String, Metrics.P>, k: Double, dy: Double = 0.0) = p.mapValues { (_, v) ->
        v.copy(x = GuideDemo.W / 2 + (v.x - GuideDemo.W / 2) * k, y = GuideDemo.H / 2 + (v.y - GuideDemo.H / 2) * k + dy)
    }

    @Test fun placementDistanceHeightAndTilt() {
        val ex = camera.first { it.view == "side" }
        val base = GuideDemo.basePose("side")
        fun fix(p: Map<String, Metrics.P>, pitch: Double? = null, roll: Double? = null) =
            PlacementCoach.assess(p, w(), h(), ex, pitch = pitch, roll = roll).items.filter { !it.ok }.map { it.fixKey }
        assertTrue(fix(scaled(base, 0.4)).contains("pc_fix_come_closer"))
        assertTrue(fix(scaled(base, 1.9)).contains("pc_fix_step_back"))
        assertTrue(fix(scaled(base, 1.0, dy = 260.0)).contains("pc_fix_lower"))
        assertTrue(fix(scaled(base, 1.0, dy = -270.0)).contains("pc_fix_raise"))
        assertEquals(listOf("pc_fix_upright"), fix(base, pitch = 25.0, roll = 0.0))
        assertEquals(listOf("pc_fix_level"), fix(base, pitch = 0.0, roll = 15.0))
        assertEquals(listOf("pc_fix_person"), fix(emptyMap()))
    }

    @Test fun tiltFromGravity() {
        val (p0, r0) = PlacementCoach.tiltFromGravity(0.0, 9.81, 0.0)
        assertEquals(0.0, p0, 1e-6); assertEquals(0.0, r0, 1e-6)
        val (p1, _) = PlacementCoach.tiltFromGravity(0.0, 9.81 * Math.cos(Math.toRadians(30.0)), 9.81 * Math.sin(Math.toRadians(30.0)))
        assertEquals(30.0, p1, 1e-6)
        val (_, r2) = PlacementCoach.tiltFromGravity(9.81 * Math.sin(Math.toRadians(10.0)), 9.81 * Math.cos(Math.toRadians(10.0)), 0.0)
        assertEquals(10.0, r2, 1e-6)
    }

    @Test fun videoReportTips() {
        val ex = camera.first { it.id == "chin_tuck" }
        val demo = GuideDemo(ex, calibMs)
        val tr = ExerciseTracker(ex, calibMs, "en")
        val report = VideoReport(ex)
        var t = 0L
        while (t <= demo.durationMs) {
            val lms = demo.frameAt(t).landmarks
            val framing = Framing.check(lms, ex.requiredLandmarks, ex.minConf, w(), h())
            report.add(t, PlacementCoach.assess(lms, w(), h(), ex), framing.ready, tr.update(Metrics.compute(lms), framing.ready, t))
            t += 100
        }
        assertEquals(demo.reps, report.reps)
        assertEquals(1.0, report.framedFraction, 1e-9)
        assertEquals(listOf("va_tip_good" to ""), report.tips())

        // A video filmed from the front for a side exercise: coach says turn sideways.
        val wrong = VideoReport(ex)
        val front = GuideDemo.basePose("front")
        val tr2 = ExerciseTracker(ex, calibMs, "en")
        repeat(30) { i ->
            val framing = Framing.check(front, ex.requiredLandmarks, ex.minConf, w(), h())
            wrong.add(i * 100L, PlacementCoach.assess(front, w(), h(), ex), framing.ready, tr2.update(Metrics.compute(front), framing.ready, i * 100L))
        }
        assertEquals("va_tip_placement", wrong.tips().first().first)
        assertTrue(wrong.tips().first().second.startsWith("pc_fix_turn_side|"))
        assertFalse(wrong.tips().any { it.first == "va_tip_good" })
    }

    @Test fun guideStringsTranslated() {
        val keys = listOf("pc_fix_person", "pc_fix_turn_side", "pc_fix_face", "pc_fix_turn_back", "pc_fix_step_back",
            "pc_fix_come_closer", "pc_fix_center", "pc_fix_raise", "pc_fix_lower", "pc_fix_upright", "pc_fix_level",
            "pc_fix_light", "va_tip_no_frames", "va_tip_placement", "va_tip_calibrate", "va_tip_no_reps", "va_tip_cue", "va_tip_good")
        for (k in keys) { assertTrue(k, I18n.has(k, "en")); assertTrue(k, I18n.has(k, "ro")) }
        for (item in listOf("person", "view", "distance", "center", "height", "upright", "level", "visible", "light"))
            assertTrue(item, I18n.has("pc_item_$item", "ro"))
        for (ph in GuideDemo.Phase.values()) assertTrue(ph.name, I18n.has("gd_phase_" + ph.name.lowercase(), "ro"))
    }
}
