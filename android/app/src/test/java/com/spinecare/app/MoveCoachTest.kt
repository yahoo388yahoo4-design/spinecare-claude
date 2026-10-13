package com.spinecare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realtime movement coaching: tempo prompts and the per-rep precision/speed verdict. */
class MoveCoachTest {
    private fun coach() = MoveCoach(0.015, 0.05, 2.0, 3.0, 2.0)

    /** Feeds a signal at 10 Hz; the rep counter "counts" when the signal is back at rest after the position. */
    private fun run(c: MoveCoach, endMs: Long, f: (Long) -> Double): List<Pair<Long, MoveCoach.Update>> {
        val out = ArrayList<Pair<Long, MoveCoach.Update>>()
        var reached = false
        var t = 0L
        while (t <= endMs) {
            val v = f(t)
            if (v >= 0.05) reached = true
            val counted = reached && v <= 0.015
            if (counted) reached = false
            val u = c.update(v, t, formCue = false, repJustDone = counted)
            if (u.prompt != null || u.feedback != null) out.add(t to u)
            t += 100
        }
        return out
    }

    @Test fun steadyRepGetsHoldReleaseAndGoodVerdict() {
        val ev = run(coach(), 9000) { t ->
            when { t < 1000 -> 0.0; t < 3000 -> 0.07 * (t - 1000) / 2000.0; t < 6000 -> 0.07; t < 8000 -> 0.07 * (1 - (t - 6000) / 2000.0); else -> 0.0 }
        }
        assertEquals(MoveCoach.Prompt.HOLD, ev[0].second.prompt)
        assertEquals(MoveCoach.Prompt.RELEASE, ev[1].second.prompt)
        assertTrue("release after the 3 s hold", ev[1].first - ev[0].first >= 3000)
        val fb = ev.last().second.feedback
        assertNotNull(fb); fb!!
        assertTrue(fb.counted)
        assertEquals(100, fb.score)
        assertEquals(listOf("fb_great"), fb.keys)
        assertEquals(140, fb.rangePct)
    }

    @Test fun rushedRepAsksToHoldAndSlowDown() {
        val ev = run(coach(), 2000) { t ->
            when { t < 300 -> 0.07 * t / 300.0; t < 1000 -> 0.07; t < 1300 -> 0.07 * (1 - (t - 1000) / 300.0); else -> 0.0 }
        }
        val fb = ev.mapNotNull { it.second.feedback }.single()
        assertTrue(fb.keys.containsAll(listOf("fb_hold_longer", "fb_slower")))
        assertTrue(fb.score < 80)
    }

    @Test fun shortMovementAsksToGoFurtherAndWobblesAreIgnored() {
        // never reaches the position: verdict, not counted
        val partial = run(coach(), 4000) { t -> if (t in 500..2500) 0.04 else 0.0 }
        val fb = partial.mapNotNull { it.second.feedback }.single()
        assertFalse(fb.counted)
        assertEquals("fb_further", fb.keys.first())
        assertTrue(partial.none { it.second.prompt != null })
        // tiny wobble just above rest: no verdict at all
        assertTrue(run(coach(), 3000) { t -> if (t in 500..800) 0.02 else 0.0 }.isEmpty())
    }

    @Test fun formCueDuringRepIsReported() {
        val c = coach()
        var t = 0L
        var last: MoveCoach.Feedback? = null
        for (v in listOf(0.0, 0.03, 0.06, 0.06, 0.06, 0.06, 0.06, 0.06, 0.03, 0.0)) {
            c.update(v, t, formCue = v > 0.05, repJustDone = v == 0.0 && t > 0).feedback?.let { last = it }
            t += 500
        }
        assertTrue(last!!.keys.contains("fb_form"))
    }

    @Test fun coachingPhrasesTranslated() {
        val keys = listOf("fb_great", "fb_good", "fb_further", "fb_gentler", "fb_hold_longer", "fb_slower", "fb_quicker", "fb_form",
            "fb_detail", "pr_hold", "pr_release", "mv_now", "mv_again", "mv_tempo", "tts_no_engine", "tts_lang_missing", "tts_error")
        for (k in keys) { assertTrue(k, I18n.has(k, "en")); assertTrue(k, I18n.has(k, "ro")) }
    }

    @Test fun timelineRecordsWhetherVideoHasText() {
        val sample = """{"t":0,"fw":10,"fh":10,"lm":{},"m":{},"r":0,"ok":true}"""
        val clean = SessionTimeline.parse(sequenceOf(SessionTimeline.header("x", "m", 1, 0L, videoText = false), sample))
        assertEquals(false, clean?.videoText)
        val old = SessionTimeline.parse(sequenceOf("""{"type":"header","v":1,"exercise":"x"}""", sample))
        assertEquals(true, old?.videoText)
    }
}
