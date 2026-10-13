package com.spinecare.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/** Per-frame sensor timeline saved next to each camera session, so a saved
 *  session can be replayed with its overlay even when no video was recorded.
 *  Same JSONL format on Android and iOS:
 *
 *    line 1: {"type":"header","v":1,"exercise":id,"metric":name,"target":reps,"started":epochMs}
 *    then:   {"t":msSinceStart,"fw":frameW,"fh":frameH,"mi":mirrored?,"lm":{"nose":[x,y,conf],..},
 *             "m":{metric:value},"s":signal?,"r":reps,"c":cue,"ok":framingReady,"v":videoFrameWritten?,
 *             "imu":{"head":[pitch,roll,yaw],"trunk":[pitch,roll,yaw]}}
 *
 *  Landmarks are normalized 0..1 in the upright camera frame (y down), not
 *  mirrored; "mi" says whether the live view was mirrored (front camera). */
object SessionTimeline {
    const val MAX_SAMPLES = 20_000   // ~50 min at the default 150 ms analysis interval

    data class Sample(
        val t: Long,
        val frameW: Int, val frameH: Int, val mirror: Boolean,
        val landmarks: Map<String, Metrics.P>,        // normalized 0..1
        val metrics: Map<String, Double>,
        val signal: Double?,
        val reps: Int,
        val cue: String,
        val ready: Boolean,
        val video: Boolean,
        val head: DoubleArray?, val trunk: DoubleArray?,
    ) {
        /** Landmarks in frame pixels (what OverlayRenderer draws). */
        fun pixels(): Map<String, Metrics.P> =
            landmarks.mapValues { Metrics.P(it.value.x * frameW, it.value.y * frameH, it.value.c) }
    }

    /** [videoText] = the recording has the text HUD burned in (all recordings before
     *  clean recordings); when false the replay draws the text as a layer it can hide. */
    data class Timeline(val exercise: String, val metric: String, val target: Int, val started: Long, val samples: List<Sample>,
                        val videoText: Boolean = true) {
        val durationMs: Long get() = samples.lastOrNull()?.t ?: 0L

        /** Session time of the first recorded video frame (video position 0). */
        val videoOffsetMs: Long get() = samples.firstOrNull { it.video }?.t ?: 0L

        /** Times at which the rep count went up. */
        val repTimes: List<Long> by lazy {
            val out = ArrayList<Long>(); var last = 0
            for (s in samples) { if (s.reps > last) out.add(s.t); last = maxOf(last, s.reps) }
            out
        }

        /** Times at which a new non-empty cue appeared. */
        val cueTimes: List<Long> by lazy {
            val out = ArrayList<Long>(); var last = ""
            for (s in samples) { if (s.cue.isNotEmpty() && s.cue != last) out.add(s.t); last = s.cue }
            out
        }

        val hasImu: Boolean by lazy { samples.any { it.head != null || it.trunk != null } }

        /** Latest sample at or before [t] (binary search). */
        fun at(t: Long): Sample? {
            if (samples.isEmpty()) return null
            var lo = 0; var hi = samples.size - 1
            if (t <= samples[0].t) return samples[0]
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (samples[mid].t <= t) lo = mid else hi = mid - 1
            }
            return samples[lo]
        }
    }

    // ---------------------------------------------------------------- encode

    fun header(exercise: String, metric: String, target: Int, started: Long, videoText: Boolean = true): String =
        JSONObject().put("type", "header").put("v", 1).put("exercise", exercise).put("metric", metric)
            .put("target", target).put("started", started).put("video_text", videoText).toString()

    private fun r(v: Double, scale: Double) = Math.round(v * scale) / scale

    fun encode(s: Sample): String {
        val o = JSONObject().put("t", s.t).put("fw", s.frameW).put("fh", s.frameH)
        if (s.mirror) o.put("mi", true)
        val lm = JSONObject()
        for ((k, p) in s.landmarks) lm.put(k, JSONArray().put(r(p.x, 1e4)).put(r(p.y, 1e4)).put(r(p.c, 100.0)))
        o.put("lm", lm)
        val m = JSONObject()
        for ((k, v) in s.metrics) if (!v.isNaN() && !v.isInfinite()) m.put(k, r(v, 1e4))
        o.put("m", m)
        s.signal?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { o.put("s", r(it, 1e4)) }
        o.put("r", s.reps)
        if (s.cue.isNotEmpty()) o.put("c", s.cue)
        o.put("ok", s.ready)
        if (s.video) o.put("v", true)
        if (s.head != null || s.trunk != null) {
            val imu = JSONObject()
            s.head?.let { imu.put("head", JSONArray().apply { it.forEach { v -> put(r(v, 100.0)) } }) }
            s.trunk?.let { imu.put("trunk", JSONArray().apply { it.forEach { v -> put(r(v, 100.0)) } }) }
            o.put("imu", imu)
        }
        return o.toString()
    }

    // ---------------------------------------------------------------- decode

    fun parse(lines: Sequence<String>): Timeline? {
        var exercise = ""; var metric = ""; var target = 0; var started = 0L; var videoText = true
        val samples = ArrayList<Sample>()
        for (line in lines) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (o.optString("type") == "header") {
                exercise = o.optString("exercise"); metric = o.optString("metric")
                target = o.optInt("target"); started = o.optLong("started")
                videoText = o.optBoolean("video_text", true)
                continue
            }
            runCatching { samples.add(parseSample(o)) }
        }
        if (samples.isEmpty()) return null
        return Timeline(exercise, metric, target, started, samples, videoText)
    }

    fun read(f: File): Timeline? = runCatching { f.bufferedReader().useLines { parse(it) } }.getOrNull()

    private fun parseSample(o: JSONObject): Sample {
        val lm = LinkedHashMap<String, Metrics.P>()
        o.optJSONObject("lm")?.let { j ->
            for (k in j.keys()) j.optJSONArray(k)?.takeIf { it.length() >= 3 }?.let {
                lm[k] = Metrics.P(it.getDouble(0), it.getDouble(1), it.getDouble(2))
            }
        }
        val m = HashMap<String, Double>()
        o.optJSONObject("m")?.let { j -> for (k in j.keys()) m[k] = j.getDouble(k) }
        fun triple(a: JSONArray?) = a?.takeIf { it.length() >= 3 }?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)) }
        val imu = o.optJSONObject("imu")
        return Sample(
            t = o.getLong("t"), frameW = o.optInt("fw"), frameH = o.optInt("fh"), mirror = o.optBoolean("mi"),
            landmarks = lm, metrics = m, signal = if (o.has("s")) o.getDouble("s") else null,
            reps = o.optInt("r"), cue = o.optString("c", ""), ready = o.optBoolean("ok"), video = o.optBoolean("v"),
            head = triple(imu?.optJSONArray("head")), trunk = triple(imu?.optJSONArray("trunk")),
        )
    }

    /** IMU orientation triple from a BlePostureClient.snapshot() entry. */
    fun imuTriple(o: JSONObject?): DoubleArray? =
        o?.let { doubleArrayOf(it.optDouble("pitch"), it.optDouble("roll"), it.optDouble("yaw")) }
            ?.takeIf { a -> a.none { it.isNaN() || it.isInfinite() } }

    /** Appends samples on the analysis thread; close() flushes and returns the count. */
    class Writer(val file: File, header: String) {
        private val out: BufferedWriter = file.bufferedWriter()
        var count = 0; private set

        init { out.write(header); out.write("\n") }

        fun add(s: Sample) {
            if (count >= MAX_SAMPLES) return
            runCatching { out.write(encode(s)); out.write("\n"); count++ }
        }

        fun close(): Int { runCatching { out.flush(); out.close() }; return count }
    }
}
