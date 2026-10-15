package com.spinecare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The movable guide box keeps its place and shape, and the framing coach turns
 *  a badly placed body into the right instruction, in the person's own terms. */
class GuideBoxAndFrameCoachTest {
    private val bundle = Plan.parseBundle(File("../../shared/exercises.json").readText())
    private val camera = bundle.exercises.filter { it.cameraTracked }
    private fun ex(view: String, program: String = "full") = camera.first { it.view == view && it.program == program }
    private val w = GuideDemo.W.toDouble()
    private val h = GuideDemo.H.toDouble()

    private fun shifted(p: Map<String, Metrics.P>, dx: Double) = p.mapValues { (_, v) -> v.copy(x = v.x + dx) }
    private fun scaled(p: Map<String, Metrics.P>, k: Double) =
        p.mapValues { (_, v) -> v.copy(x = w / 2 + (v.x - w / 2) * k, y = h / 2 + (v.y - h / 2) * k) }

    // ------------------------------------------------------------------ guide box

    @Test fun guideBoxStaysInsideTheAreaWithTheDemoShape() {
        val density = 2.75f
        for (s in listOf(GuideBox.DEFAULT, GuideBox.State(1f, 1f, 5000f, GuideBox.MODE_SHOWN), GuideBox.State(-1f, 2f, 10f, GuideBox.MODE_AUTO))) {
            for ((aw, ah) in listOf(1080 to 2200, 2200 to 1080, 400 to 300)) {
                val (l, t, bw, bh) = GuideBox.layout(s, aw, ah, density)
                assertTrue("left $l in $aw", l >= 0 && l + bw <= aw)
                assertTrue("top $t in $ah", t >= 0 && t + bh <= ah)
                assertEquals(bw * GuideBox.ASPECT, bh.toFloat(), 1.5f)
            }
        }
    }

    @Test fun movedBoxComesBackWhereItWasLeft() {
        val density = 3f
        val aw = 1080; val ah = 2200
        val moved = GuideBox.adjusted(GuideBox.DEFAULT, 600f, 1500f, 450f, aw, ah, density)
        assertEquals(150f, moved.widthDp, 0.01f)
        val (l, t, bw, _) = GuideBox.layout(moved, aw, ah, density)
        assertEquals(600f, l.toFloat(), 1f); assertEquals(1500f, t.toFloat(), 1f); assertEquals(450f, bw.toFloat(), 1f)
        // the same corner of the free area after a rotation
        val (l2, t2, bw2, bh2) = GuideBox.layout(moved, ah, aw, density)
        assertEquals(moved.x, l2.toFloat() / (ah - bw2), 0.01f)
        assertEquals(moved.y, t2.toFloat() / (aw - bh2), 0.01f)
    }

    @Test fun showHideButtonPinsOrHidesTheBox() {
        val shown = GuideBox.toggled(GuideBox.DEFAULT, visibleNow = false)
        assertEquals(GuideBox.MODE_SHOWN, shown.mode)
        assertFalse(GuideBox.autoShows(shown))
        val hidden = GuideBox.toggled(shown, visibleNow = true)
        assertEquals(GuideBox.MODE_HIDDEN, hidden.mode)
        assertTrue(GuideBox.autoShows(GuideBox.DEFAULT))
    }

    // ------------------------------------------------------------------ demo figure geometry

    @Test fun unzoomUndoesTheProgrammeZoom() {
        for (view in listOf("front", "side", "back")) {
            val full = GuideDemo.basePose(view, "full")
            val back = GuideDemo.unzoom(GuideDemo.basePose(view, "neck"), "neck")
            for ((k, v) in full) {
                assertEquals("$view $k x", v.x, back.getValue(k).x, 1e-6)
                assertEquals("$view $k y", v.y, back.getValue(k).y, 1e-6)
            }
        }
    }

    // ------------------------------------------------------------------ framing coach

    @Test fun theGuidePoseIsAGoodPositionForEveryExercise() {
        for (e in camera) {
            val hint = FrameCoach.assess(GuideDemo.basePose(e.view, e.program), w, h, e)
            assertTrue("${e.id}: ${hint.key}", hint.ok)
            assertEquals(FrameCoach.KEY_OK, hint.key)
        }
    }

    @Test fun nobodyInViewAsksThePersonToSitInFront() {
        val hint = FrameCoach.assess(null, w, h, ex("front", "neck"))
        assertFalse(hint.ok)
        assertEquals("pc_fix_person", hint.key)
        assertNull(hint.center)
        assertTrue(hint.ghost.isNotEmpty())
    }

    @Test fun offCentreSaysWhichWayInThePersonsTerms() {
        // body on the picture's left: it has to move right in the picture
        val front = FrameCoach.assess(shifted(GuideDemo.basePose("front"), -180.0), w, h, ex("front", "upper_body"))
        assertEquals(FrameCoach.Kind.RIGHT, front.kind)
        assertEquals("fc_step_left", front.key)          // facing the phone, the picture's right is their left
        val back = FrameCoach.assess(shifted(GuideDemo.basePose("back"), -180.0), w, h, ex("back"))
        assertEquals("fc_step_right", back.key)          // from behind it is their right
        val side = FrameCoach.assess(shifted(GuideDemo.basePose("side"), -180.0), w, h, ex("side"))
        assertEquals("fc_move_forward", side.key)        // the figure faces the picture's right
        val sideOther = FrameCoach.assess(shifted(GuideDemo.basePose("side"), 200.0), w, h, ex("side"))
        assertEquals("fc_move_backward", sideOther.key)
        assertEquals(FrameCoach.Kind.LEFT, sideOther.kind)
    }

    @Test fun tooCloseAndTooFar() {
        val e = ex("side")
        val close = FrameCoach.assess(scaled(GuideDemo.basePose("side"), 2.0), w, h, e)
        assertEquals("fc_step_back", close.key); assertEquals(FrameCoach.Kind.BACK, close.kind)
        val far = FrameCoach.assess(scaled(GuideDemo.basePose("side"), 0.4), w, h, e)
        assertEquals("fc_come_closer", far.key); assertEquals(FrameCoach.Kind.CLOSER, far.kind)
    }

    @Test fun ghostFitsTheCameraFrame() {
        val g = FrameCoach.ghost("front", "full", 640.0, 480.0)
        val xs = g.values.map { it.x }; val ys = g.values.map { it.y }
        assertTrue(xs.all { it in 0.0..640.0 } && ys.all { it in 0.0..480.0 })
        assertEquals(320.0, (g.getValue("lsho").x + g.getValue("rsho").x) / 2, 1.0)
        assertTrue(FrameCoach.headRadius(g) > 10)
    }

    @Test fun everyCoachTextIsTranslated() {
        val keys = listOf("fc_good", "fc_step_back", "fc_come_closer", "fc_step_left", "fc_step_right", "fc_move_forward",
            "fc_move_backward", "fc_raise", "fc_lower", "fc_edge_top", "fc_edge_bottom", "gb_show", "gb_hide", "gb_reset",
            "set_guide_box", "set_frame_coach", "replay_fill", "replay_fit")
        for (k in keys) for (l in I18n.LANGS) assertTrue("$k [$l]", I18n.has(k, l))
        assertEquals(I18n.t("vg_framing_ok"), FrameCoach.text(FrameCoach.KEY_OK, beforeStart = true))
        assertEquals(I18n.t("fc_good"), FrameCoach.text(FrameCoach.KEY_OK, beforeStart = false))
    }

    @Test fun stabilizerIgnoresFlickerAndRepeatsWhileStillWrong() {
        val s = FrameCoach.Stabilizer(holdMs = 900, repeatMs = 7000)
        assertNull(s.update("fc_step_left", 0))
        assertNull(s.update("fc_raise", 300))           // a one-frame flicker is never said
        assertNull(s.update("fc_step_left", 400))
        assertNull(s.update("fc_step_left", 1000))
        assertEquals("fc_step_left", s.update("fc_step_left", 1400))
        assertNull(s.update("fc_step_left", 5000))
        assertEquals("fc_step_left", s.update("fc_step_left", 8500))   // still wrong: said again
        assertNull(s.update(FrameCoach.KEY_OK, 9000))
        assertEquals(FrameCoach.KEY_OK, s.update(FrameCoach.KEY_OK, 10000))
        assertNull(s.update(FrameCoach.KEY_OK, 30000))  // "good" only once
        s.reset()
        assertNull(s.stable)
    }
}
