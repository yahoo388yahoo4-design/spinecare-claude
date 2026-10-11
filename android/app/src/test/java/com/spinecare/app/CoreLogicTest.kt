package com.spinecare.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM tests for the coaching logic. Metric definitions are checked against
 *  shared/metric_vectors.json, generated from the server implementation. */
class CoreLogicTest {
    private val shared = File("../../shared")
    private val bundle = Plan.parseBundle(File(shared, "exercises.json").readText())
    private fun ex(id: String) = bundle.exercises.first { it.id == id }

    @Test fun metricsMatchServerVectors() {
        val v = JSONObject(File(shared, "metric_vectors.json").readText())
        val tol = v.getDouble("tolerance")
        val cases = v.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val lmsJ = c.getJSONObject("landmarks")
            val lms = lmsJ.keys().asSequence().associateWith { k ->
                val o = lmsJ.getJSONObject(k); Metrics.P(o.getDouble("x"), o.getDouble("y"), o.getDouble("conf"))
            }
            val got = Metrics.compute(lms)
            val exp = c.getJSONObject("expected")
            assertEquals(c.getString("name"), exp.keys().asSequence().toSet(), got.keys)
            for (k in exp.keys()) assertEquals("${c.getString("name")}.$k", exp.getDouble(k), got.getValue(k), tol)
        }
    }

    @Test fun planLoadsAllExercisesBilingual() {
        assertEquals(4, bundle.exercises.size)
        for (e in bundle.exercises) {
            assertTrue(e.repLow < e.repHigh)
            assertTrue(e.name.containsKey("ro") && e.name.containsKey("en"))
            assertTrue(e.stops("ro").isNotEmpty())
        }
    }

    @Test fun repEngineCountsWithHysteresis() {
        val r = RepEngine(8.0, 35.0, minHoldMs = 0)
        var t = 0L
        repeat(3) { for (v in listOf(5.0, 20.0, 40.0, 45.0, 36.0, 34.0, 40.0, 20.0, 5.0)) { r.update(v, t); t += 100 } }
        assertEquals(3, r.reps)
        assertFalse(r.update(null, t)); assertFalse(r.update(Double.NaN, t))
    }

    @Test fun trackerCalibratesThenCountsRelative() {
        val tr = ExerciseTracker(ex("cervical_rotation_rom"), 500, "en")
        var t = 0L
        repeat(8) { tr.update(mapOf("neck_yaw_est" to 10.0, "shoulder_line_tilt" to 5.0), true, t); t += 100 }
        assertFalse(tr.calibrating)
        var last: ExerciseTracker.State? = null
        repeat(2) {
            for (v in listOf(12.0, 30.0, 50.0, 52.0, 50.0, 30.0, 12.0)) {
                last = tr.update(mapOf("neck_yaw_est" to v, "shoulder_line_tilt" to 5.0), true, t); t += 150
            }
        }
        assertEquals(2, last!!.reps)
        assertTrue("resting asymmetry is not a form fault", last!!.cues.isEmpty())
    }

    @Test fun trackerChinTuckDirection() {
        val tr = ExerciseTracker(ex("chin_tuck"), 300, "ro")
        var t = 0L
        repeat(5) { tr.update(mapOf("head_forward_proxy" to 0.20), true, t); t += 100 }
        var s: ExerciseTracker.State? = null
        for (v in listOf(0.19, 0.12, 0.10, 0.10, 0.10, 0.10, 0.15, 0.20)) { s = tr.update(mapOf("head_forward_proxy" to v), true, t); t += 200 }
        assertEquals(1, s!!.reps)
    }

    @Test fun trackerWaitsForFraming() {
        val tr = ExerciseTracker(ex("chin_tuck"), 300, "en")
        repeat(10) { tr.update(mapOf("head_forward_proxy" to 0.2), false, it * 100L) }
        assertTrue(tr.calibrating)
    }

    @Test fun framingAnySideAndEdges() {
        val p = mapOf("lear" to Metrics.P(300.0, 110.0, 0.9), "rear" to Metrics.P(340.0, 110.0, 0.1),
            "lsho" to Metrics.P(5.0, 160.0, 0.9))
        assertTrue(Framing.check(p, listOf("any_ear"), 0.5, 640.0, 480.0).ready)
        val r = Framing.check(p, listOf("lsho", "nose"), 0.5, 640.0, 480.0)
        assertEquals(listOf("lsho:out_of_frame", "nose:low_conf"), r.issues)
        assertEquals(listOf("no_person"), Framing.check(null, listOf("lsho"), 0.5, 1.0, 1.0).issues)
    }

    @Test fun serverUrlPolicy() {
        assertNull(ServerConfig.urlProblem("https://example.org:8300", false))
        assertEquals("srv_http_disabled", ServerConfig.urlProblem("http://192.168.1.20:8300", false))
        assertNull(ServerConfig.urlProblem("http://192.168.1.20:8300", true))
        assertEquals("srv_http_public", ServerConfig.urlProblem("http://8.8.8.8:8300", true))
        assertEquals("srv_no_url", ServerConfig.urlProblem("", true))
        assertEquals("srv_bad_url", ServerConfig.urlProblem("ftp://x", true))
        assertTrue(ServerConfig.isPrivateLanHost("172.20.0.4")); assertFalse(ServerConfig.isPrivateLanHost("172.32.0.4"))
    }

    @Test fun blePacketParsing() {
        val b = ByteArray(20)
        fun put(o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte() }
        put(0, 65535); put(2, -1234); put(4, 250); put(6, 0); put(8, -1000)
        val p = BlePostureClient.parse(b)
        assertNotNull(p)
        assertEquals(65535, p!!.seq); assertEquals(-12.34, p.pitch, 1e-9); assertEquals(2.5, p.roll, 1e-9); assertEquals(-1000, p.ax)
        assertNull(BlePostureClient.parse(ByteArray(10)))
    }

    @Test fun csvEscapesQuotesAndCommas() {
        val s = JSONObject().put("ts", 1).put("date", "2026-10-11").put("exercise", "chin_tuck").put("reps", 3)
        val c = JSONObject().put("ts", 2).put("date", "2026-10-11").put("phase", "after").put("neck_pain", 4)
            .put("arm_symptoms", 1).put("notes", "tight, \"sharp\"")
        val csv = LocalStore.buildCsv(listOf(s), listOf(c)).lines()
        assertTrue(csv[0].startsWith("type,ts,date"))
        assertTrue(csv[2].contains("\"tight, \"\"sharp\"\"\""))
    }

    @Test fun translationsComplete() {
        assertEquals(I18n.EN.keys, I18n.RO.keys)
        for ((k, v) in I18n.EN) assertEquals("placeholders in $k", v.count { it == '%' }, I18n.RO.getValue(k).count { it == '%' })
    }
}
