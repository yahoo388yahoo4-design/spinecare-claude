package com.spinecare.app

import com.spinecare.app.CameraOptions.CameraDesc
import com.spinecare.app.CameraOptions.LensKind
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Orientation, lens and zoom choices for the camera screens, and the
 *  save -> list -> replay path for sessions (portrait and landscape). */
class CameraAndSessionTest {

    // ------------------------------------------------------------------ orientation

    @Test fun orientationCyclesAndIsTranslated() {
        assertEquals(CameraOptions.ORIENT_PORTRAIT, CameraOptions.nextOrientation(CameraOptions.ORIENT_AUTO))
        assertEquals(CameraOptions.ORIENT_LANDSCAPE, CameraOptions.nextOrientation(CameraOptions.ORIENT_PORTRAIT))
        assertEquals(CameraOptions.ORIENT_AUTO, CameraOptions.nextOrientation(CameraOptions.ORIENT_LANDSCAPE))
        assertEquals(CameraOptions.ORIENT_PORTRAIT, CameraOptions.nextOrientation(99))   // bad pref value
        val keys = (0..2).map { CameraOptions.orientationKey(it) } + LensKind.values().map { it.key } + listOf("lens")
        for (k in keys) { assertTrue(k, I18n.has(k, "en")); assertTrue(k, I18n.has(k, "ro")) }
    }

    @Test fun placementTiltWorksInLandscape() {
        // Phone upright in portrait: gravity along +y.
        val (p0, r0) = PlacementCoach.tiltFromGravity(0.0, 9.81, 0.0)
        assertEquals(0.0, p0, 1e-6); assertEquals(0.0, r0, 1e-6)
        // Same phone turned to landscape (display rotation 90): gravity along +x in device axes.
        val raw = PlacementCoach.tiltFromGravity(9.81, 0.0, 0.0)
        assertTrue("device axes read as a 90° roll, the old landscape bug", abs(raw.second) > 80)
        for ((rot, g) in listOf(90 to doubleArrayOf(9.81, 0.0), 270 to doubleArrayOf(-9.81, 0.0), 180 to doubleArrayOf(0.0, -9.81))) {
            val (sx, sy) = PlacementCoach.screenGravity(g[0], g[1], rot)
            val (p, r) = PlacementCoach.tiltFromGravity(sx, sy, 0.0)
            assertEquals("rotation $rot pitch", 0.0, p, 1e-6)
            assertEquals("rotation $rot roll", 0.0, r, 1e-6)
        }
        // a 5° sideways lean in landscape is still reported as 5°
        val lean = Math.toRadians(5.0)
        val (sx, sy) = PlacementCoach.screenGravity(9.81 * Math.cos(lean), -9.81 * Math.sin(lean), 90)
        assertEquals(5.0, abs(PlacementCoach.tiltFromGravity(sx, sy, 0.0).second), 1e-6)
    }

    // ------------------------------------------------------------------ lenses

    private val phone = listOf(
        CameraDesc("0", front = false, focalMm = 5.4f, sensorWidthMm = 6.4f),   // main wide
        CameraDesc("1", front = true, focalMm = 2.7f, sensorWidthMm = 3.6f),
        CameraDesc("2", front = false, focalMm = 2.2f, sensorWidthMm = 5.6f),   // ultra-wide
        CameraDesc("3", front = false, focalMm = 15f, sensorWidthMm = 4.0f),    // tele
        CameraDesc("4", front = false, focalMm = 5.4f, sensorWidthMm = 6.4f),   // same FOV as main: dropped
    )

    @Test fun separateLensesAreListedWidestFirst() {
        val back = CameraOptions.lenses(phone, front = false, mainId = "0")
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE), back.map { it.kind })
        assertEquals(listOf("2", "0", "3"), back.map { it.cameraId })
        assertEquals(1f, back[1].relZoom, 1e-6f)
        assertTrue(back[0].relZoom in 0.4f..0.6f)
        assertTrue(back[2].relZoom > 4f)
        assertTrue(back.all { it.zoomRatio == 1f })
        val front = CameraOptions.lenses(phone, front = true, mainId = "1")
        assertEquals(listOf("1"), front.map { it.cameraId })
        assertEquals(LensKind.WIDE, front.single().kind)
        assertEquals(0, CameraOptions.nextLens(back, 2))
        assertEquals(2, CameraOptions.nextLens(back, 1))
        assertEquals(0, CameraOptions.nextLens(emptyList(), 0))
    }

    @Test fun logicalCameraReachesUltraWideThroughZoom() {
        val cams = listOf(CameraDesc("0", false, 5.4f, 6.4f), CameraDesc("1", true, 2.7f, 3.6f))
        val back = CameraOptions.lenses(cams, front = false, mainId = "0", mainMinZoom = 0.6f)
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE), back.map { it.kind })
        assertEquals(listOf("0", "0"), back.map { it.cameraId })
        assertEquals(0.6f, back[0].zoomRatio, 1e-6f)
        assertEquals(1f, back[1].zoomRatio, 1e-6f)
        // pinching below 1× on that camera selects the ultra-wide entry, and back
        assertEquals(0, CameraOptions.lensForZoom(back, "0", 0.7f))
        assertEquals(1, CameraOptions.lensForZoom(back, "0", 1f))
        assertEquals(1, CameraOptions.lensForZoom(back, "0", 3f))
        // a separate ultra-wide camera wins over the zoom entry
        val sep = CameraOptions.lenses(phone, front = false, mainId = "0", mainMinZoom = 0.6f)
        assertEquals(1, sep.count { it.kind == LensKind.ULTRA_WIDE })
        assertEquals(-1, CameraOptions.lensForZoom(sep, "3", 2f))
    }

    @Test fun unknownOpticsAndMissingMainStillGiveOneLens() {
        val cams = listOf(CameraDesc("7", false, 0f, 0f), CameraDesc("8", false, 0f, 0f))
        val l = CameraOptions.lenses(cams, front = false, mainId = null)
        assertEquals(1, l.size); assertEquals(LensKind.WIDE, l[0].kind); assertEquals("7", l[0].cameraId)
        assertTrue(CameraOptions.lenses(cams, front = true, mainId = null).isEmpty())
    }

    // ------------------------------------------------------------------ zoom

    @Test fun zoomStopsLabelsAndClamping() {
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f), CameraOptions.zoomStops(0.6f, 8f))
        assertEquals(listOf(1f, 2f), CameraOptions.zoomStops(1f, 2f))
        assertEquals(listOf(1f), CameraOptions.zoomStops(1f, 1f))
        assertEquals(2f, CameraOptions.nextZoomStop(1f, 1f, 8f), 0f)
        assertEquals(3f, CameraOptions.nextZoomStop(2.4f, 1f, 8f), 0f)
        assertEquals(1f, CameraOptions.nextZoomStop(5f, 1f, 8f), 0f)         // wraps
        assertEquals(0.6f, CameraOptions.nextZoomStop(5f, 0.6f, 5f), 0f)
        assertEquals(8f, CameraOptions.clampZoom(20f, 1f, 8f), 0f)
        assertEquals(1f, CameraOptions.clampZoom(0.2f, 1f, 8f), 0f)
        assertEquals(1f, CameraOptions.clampZoom(Float.NaN, 0.6f, 8f), 0f)
        assertEquals("1×", CameraOptions.zoomLabel(1f))
        assertEquals("0.6×", CameraOptions.zoomLabel(0.6f))
        assertEquals("2.5×", CameraOptions.zoomLabel(2.5f))
        assertEquals("10×", CameraOptions.zoomLabel(10f))
    }

    @Test fun fitLetterboxesWithoutDistortion() {
        // landscape frame into a portrait recording: full width, centred vertically
        assertArrayEquals(floatArrayOf(0f, 140f, 480f, 360f), CameraOptions.fit(640, 480, 480f, 640f), 1e-3f)
        // same shape: fills the area
        assertArrayEquals(floatArrayOf(0f, 0f, 480f, 640f), CameraOptions.fit(480, 640, 480f, 640f), 1e-3f)
        // portrait frame into landscape: pillarboxed
        val r = CameraOptions.fit(480, 640, 640f, 480f)
        assertEquals(480f / 640f, r[2] / r[3], 1e-4f)
        assertEquals((640f - r[2]) / 2f, r[0], 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 10f), CameraOptions.fit(0, 0, 10f, 10f), 0f)
    }

    // ------------------------------------------------------------------ save and replay

    @Test fun savedSessionRowFindsItsReplayFiles() {
        val dir = kotlin.io.path.createTempDirectory("sessions").toFile()
        try {
            val base = "session_chin_tuck_1700000000000"
            val tlFile = File(dir, "$base.timeline.jsonl")
            val video = File(dir, "$base.mp4").apply { writeBytes(ByteArray(8)) }
            val w = SessionTimeline.Writer(tlFile, SessionTimeline.header("chin_tuck", "head_forward_proxy", 10, 1_700_000_000_000L))
            for (i in 0 until 12) w.add(sample(i * 150L, 640, 480, reps = i / 4, video = true))
            assertEquals(12, w.close())

            val row = LocalStore.sessionRow(1_700_000_100_000L, "2023-11-14", "chin_tuck", 2, 1, 2,
                mapOf("head_forward_proxy" to 0.12345, "broken" to Double.NaN, "inf" to Double.POSITIVE_INFINITY),
                mapOf("head_forward_proxy" to 3), 12, "on-device",
                "/somewhere/else/${video.name}", 12, tlFile.absolutePath, 12)
            // stored as one JSONL line and read back
            val rows = LocalStore.parseRows(listOf("not json", row.toString(), ""))
            assertEquals(1, rows.size)
            val back = LocalStore.findSession(rows, 1_700_000_100_000L)!!
            assertNull(LocalStore.findSession(rows, 42L))
            assertEquals(0.123, back.getJSONObject("avg").getDouble("head_forward_proxy"), 1e-9)
            assertFalse("NaN averages must not break saving", back.getJSONObject("avg").has("broken"))
            assertFalse(back.getJSONObject("avg").has("inf"))
            assertEquals(3, back.getJSONObject("cues").getInt("head_forward_proxy"))
            // files are found by name in the recordings folder, whatever path they were saved from
            assertEquals(video, LocalStore.recordingFor(back, "video", dir))
            assertEquals(tlFile, LocalStore.recordingFor(back, "timeline", dir))
            assertNull(LocalStore.recordingFor(JSONObject().put("video", "null"), "video", dir))
            assertNull(LocalStore.recordingFor(JSONObject(), "timeline", dir))

            val tl = SessionTimeline.read(LocalStore.recordingFor(back, "timeline", dir)!!)!!
            assertEquals(listOf(600L, 1200L), tl.repTimes)
            assertEquals(0L, tl.videoOffsetMs)
            assertEquals(1650L, tl.durationMs)
        } finally { dir.deleteRecursively() }
    }

    @Test fun manualSessionRowHasNoReplay() {
        val row = LocalStore.sessionRow(5L, "2024-01-01", "x", 10, 2, 60, emptyMap(), emptyMap(), 0, "manual")
        assertFalse(row.has("video")); assertFalse(row.has("timeline"))
        assertEquals("{}", row.getJSONObject("avg").toString())
    }

    @Test fun landscapeTimelineReplaysWithItsOwnFrameShape() {
        val f = File.createTempFile("landscape", ".jsonl")
        try {
            val w = SessionTimeline.Writer(f, SessionTimeline.header("neck_rotation", "nose_offset", 8, 0L))
            w.add(sample(0, 640, 480))          // landscape
            w.add(sample(150, 640, 480))
            w.add(sample(300, 480, 640))        // camera switched mid-session: portrait frame
            w.close()
            val tl = SessionTimeline.read(f)!!
            val s0 = tl.at(0)!!
            assertEquals(640, s0.frameW); assertEquals(480, s0.frameH)
            // normalized 0.25/0.5 is denormalized against each sample's own frame
            assertEquals(160.0, s0.pixels().getValue("nose").x, 1e-6)
            assertEquals(240.0, s0.pixels().getValue("nose").y, 1e-6)
            val s2 = tl.at(400)!!
            assertEquals(480, s2.frameW); assertEquals(640, s2.frameH)
            assertEquals(120.0, s2.pixels().getValue("nose").x, 1e-6)
            assertNotNull(SessionPreview.peak(tl))
        } finally { f.delete() }
    }

    @Test fun emptyTimelineMeansNoSensorReplay() {
        val f = File.createTempFile("empty", ".jsonl")
        try {
            assertEquals(0, SessionTimeline.Writer(f, SessionTimeline.header("x", "m", 1, 0L)).close())
            assertNull(SessionTimeline.read(f))
        } finally { f.delete() }
    }

    @Test fun timelineStopsAtTheSampleCap() {
        val f = File.createTempFile("cap", ".jsonl")
        try {
            val w = SessionTimeline.Writer(f, SessionTimeline.header("x", "m", 1, 0L))
            repeat(SessionTimeline.MAX_SAMPLES + 5) { w.add(sample(it.toLong(), 640, 480)) }
            assertEquals(SessionTimeline.MAX_SAMPLES, w.close())
            assertEquals(SessionTimeline.MAX_SAMPLES, SessionTimeline.read(f)!!.samples.size)
        } finally { f.delete() }
    }

    private fun sample(t: Long, w: Int, h: Int, reps: Int = 0, video: Boolean = false) = SessionTimeline.Sample(
        t = t, frameW = w, frameH = h, mirror = false,
        landmarks = mapOf("nose" to Metrics.P(0.25, 0.5, 0.9), "lsho" to Metrics.P(0.4, 0.7, 0.8)),
        metrics = mapOf("m" to t / 1000.0), signal = t / 1000.0, reps = reps, cue = "", ready = true, video = video,
        head = null, trunk = null,
    )
}
