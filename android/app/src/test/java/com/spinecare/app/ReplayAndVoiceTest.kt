package com.spinecare.app

import com.spinecare.app.VoiceCommands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM tests for the voice command vocabulary (shared/voice_commands.json)
 *  and the session timeline format used by the in-app replay. */
class ReplayAndVoiceTest {
    private val cmds = VoiceCommands(File("../../shared/voice_commands.json").readText())

    @Test fun englishCommands() {
        assertEquals(Cmd.START, cmds.parse("Start"))
        assertEquals(Cmd.START, cmds.parse("ok let's go"))
        assertEquals(Cmd.STOP, cmds.parse("stop please"))
        assertEquals(Cmd.STOP, cmds.parse("I'm done"))
        assertEquals(Cmd.PAUSE, cmds.parse("pause"))
        assertEquals(Cmd.RESUME, cmds.parse("go on"))          // resume outranks "go" (start)
        assertEquals(Cmd.FLIP, cmds.parse("switch camera"))
        assertEquals(Cmd.STATUS, cmds.parse("how many have I done"))
        assertEquals(Cmd.HELP, cmds.parse("what can I say?"))
        assertEquals(Cmd.SKIP, cmds.parse("skip"))
        assertEquals(Cmd.RECALIBRATE, cmds.parse("recalibrate"))
    }

    @Test fun romanianCommandsWithAndWithoutDiacritics() {
        assertEquals(Cmd.START, cmds.parse("Începe"))
        assertEquals(Cmd.START, cmds.parse("incepe"))
        assertEquals(Cmd.STOP, cmds.parse("Oprește"))
        assertEquals(Cmd.STOP, cmds.parse("opreşte"))           // cedilla variant
        assertEquals(Cmd.PAUSE, cmds.parse("pauză"))
        assertEquals(Cmd.RESUME, cmds.parse("continuă"))
        assertEquals(Cmd.FLIP, cmds.parse("schimbă camera"))
        assertEquals(Cmd.STATUS, cmds.parse("câte repetări am făcut"))
        assertEquals(Cmd.SKIP, cmds.parse("sari peste"))
        assertEquals(Cmd.RECALIBRATE, cmds.parse("recalibrează"))
        assertEquals(Cmd.HELP, cmds.parse("ajutor"))
    }

    @Test fun safetyCommandsWinAndWordsMustMatchWhole() {
        assertEquals("stop always beats start", Cmd.STOP, cmds.parse("start stop"))
        assertNull(cmds.parse("restart"))
        assertNull(cmds.parse("gone"))
        assertNull(cmds.parse(""))
        assertNull(cmds.parse("the weather is nice"))
        assertEquals(Cmd.STOP to "stop", cmds.parseAny(listOf("top", "stop")))
    }

    @Test fun timelineRoundTrip() {
        val f = File.createTempFile("timeline", ".jsonl")  // prefix needs 3+ chars
        try {
            val w = SessionTimeline.Writer(f, SessionTimeline.header("chin_tuck", "head_forward_proxy", 10, 1000L))
            for (i in 0 until 20) {
                w.add(SessionTimeline.Sample(
                    t = i * 150L, frameW = 480, frameH = 640, mirror = true,
                    landmarks = mapOf("nose" to Metrics.P(0.5, 0.25, 0.9), "lsho" to Metrics.P(0.4, 0.5, 0.8)),
                    metrics = mapOf("head_forward_proxy" to 0.2 - i * 0.001, "bad" to Double.NaN),
                    signal = if (i < 5) null else i.toDouble(),
                    reps = i / 6, cue = if (i == 10) "Keep the eyes level" else "", ready = i > 1,
                    video = i >= 3, head = if (i % 2 == 0) doubleArrayOf(1.0, 2.0, 3.0) else null, trunk = null,
                ))
            }
            assertEquals(20, w.close())
            val tl = SessionTimeline.read(f)
            assertNotNull(tl)
            tl!!
            assertEquals("chin_tuck", tl.exercise); assertEquals("head_forward_proxy", tl.metric); assertEquals(10, tl.target)
            assertEquals(20, tl.samples.size)
            assertEquals(19 * 150L, tl.durationMs)
            assertEquals(450L, tl.videoOffsetMs)
            assertEquals(listOf(6 * 150L, 12 * 150L, 18 * 150L), tl.repTimes)
            assertEquals(listOf(1500L), tl.cueTimes)
            assertTrue(tl.hasImu)
            val s = tl.at(700L)!!
            assertEquals(600L, s.t)                                   // latest sample at or before
            assertEquals(240.0, s.pixels().getValue("nose").x, 1e-6)  // denormalized to the frame
            assertTrue(s.mirror)
            assertFalse(s.metrics.containsKey("bad"))
            assertNull(tl.at(0L)!!.signal)
            assertEquals(0L, tl.at(-5L)!!.t)
            assertEquals(19 * 150L, tl.at(99_999L)!!.t)
            assertNotNull(SessionPreview.peak(tl))
            assertEquals(19.0, SessionPreview.peak(tl)!!.signal!!, 1e-9)
        } finally { f.delete() }
    }

    @Test fun timelineIgnoresBrokenLines() {
        val tl = SessionTimeline.parse(sequenceOf("not json", "{\"type\":\"header\",\"exercise\":\"x\"}", "{\"t\":5,\"r\":1}", ""))
        assertEquals(1, tl!!.samples.size)
        assertNull(SessionTimeline.parse(sequenceOf("{\"type\":\"header\"}")))
    }
}
