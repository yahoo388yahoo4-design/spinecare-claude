package com.spinecare.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device store: append-only JSONL for sessions and symptom check-ins,
 *  plus the user's custom exercises and per-exercise clinician confirmations.
 *  Nothing here leaves the phone unless the user exports it. */
class LocalStore(context: Context) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val sessions = File(dir, "sessions.jsonl")
    private val checkins = File(dir, "checkins.jsonl")
    private val custom = File(dir, "custom_exercises.json")
    private val approvals = File(dir, "approvals.json")
    private val recordings = File(context.filesDir, "recordings")
    private val cacheDir = context.cacheDir

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---- sessions

    fun saveSession(exId: String, reps: Int, sets: Int, durationS: Long, avg: Map<String, Double>,
                    cues: Map<String, Int>, frames: Int, source: String,
                    videoPath: String? = null, recordedFrames: Int = 0,
                    timelinePath: String? = null, timelineSamples: Int = 0) = synchronized(LOCK) {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("date", today())
            .put("exercise", exId).put("reps", reps).put("sets", sets).put("duration_s", durationS)
            .put("avg", JSONObject(avg.mapValues { Math.round(it.value * 1000) / 1000.0 } as Map<*, *>))
            .put("cues", JSONObject(cues as Map<*, *>))
            .put("frames", frames).put("source", source)
        if (videoPath != null) o.put("video", File(videoPath).name).put("recorded_frames", recordedFrames)
        if (timelinePath != null) o.put("timeline", File(timelinePath).name).put("timeline_samples", timelineSamples)
        sessions.appendText(o.toString() + "\n")
    }

    fun allSessions(): List<JSONObject> = readJsonl(sessions)

    /** Recording file for a session row (stored by name so it survives path changes). */
    fun videoFile(row: JSONObject): File? {
        val v = row.optString("video", "")
        if (v.isEmpty() || v == "null") return null
        return File(recordings, File(v).name)
    }

    /** Sensor timeline for a session row (see SessionTimeline), stored next to recordings. */
    fun timelineFile(row: JSONObject): File? {
        val v = row.optString("timeline", "")
        if (v.isEmpty() || v == "null") return null
        return File(recordings, File(v).name)
    }

    /** Where a new session's timeline is written. */
    fun newTimelineFile(base: String): File = File(recordings.apply { mkdirs() }, "$base.timeline.jsonl")

    // ---- symptom check-ins (daily, or before/after a session)

    fun saveCheckin(phase: String, neckPain: Int, armSymptoms: Int, sleepQuality: Int?, note: String) = synchronized(LOCK) {
        val o = JSONObject().put("ts", System.currentTimeMillis()).put("date", today()).put("phase", phase)
            .put("neck_pain", neckPain).put("arm_symptoms", armSymptoms).put("notes", note.take(160))
        if (sleepQuality != null) o.put("sleep_quality", sleepQuality)
        checkins.appendText(o.toString() + "\n")
    }

    fun allCheckins(): List<JSONObject> = readJsonl(checkins)
    fun latestCheckin(): JSONObject? = allCheckins().lastOrNull()

    fun clearHistory() = synchronized(LOCK) {
        sessions.delete(); checkins.delete()
        recordings.listFiles()?.forEach { it.delete() }
    }

    // ---- custom exercises + clinician confirmations

    fun customExercises(): List<Exercise> {
        synchronized(LOCK) {
            if (!custom.exists()) return emptyList()
            val a = runCatching { JSONArray(custom.readText()) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { runCatching { Exercise.parse(a.getJSONObject(it)) }.getOrNull() }
        }
    }

    fun addCustomExercise(ex: Exercise) = synchronized(LOCK) {
        val a = if (custom.exists()) runCatching { JSONArray(custom.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        a.put(ex.toJson()); custom.writeText(a.toString())
    }

    fun removeCustomExercise(id: String) = synchronized(LOCK) {
        if (!custom.exists()) return@synchronized
        val a = runCatching { JSONArray(custom.readText()) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { out.put(it) }
        custom.writeText(out.toString())
    }

    /** The user confirmed their clinician approved this exercise and dose. */
    fun isApproved(id: String): Boolean = synchronized(LOCK) {
        approvals.exists() && runCatching { JSONObject(approvals.readText()).optBoolean(id) }.getOrDefault(false)
    }

    fun setApproved(id: String, v: Boolean) = synchronized(LOCK) {
        val o = if (approvals.exists()) runCatching { JSONObject(approvals.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        o.put(id, v); approvals.writeText(o.toString())
    }

    // ---- export

    /** Writes a CSV of sessions and check-ins to the app cache (shared through
     *  the FileProvider + share sheet, so no storage permission is needed). */
    fun exportCsv(): File {
        val out = File(cacheDir, "export").apply { mkdirs() }
        val f = File(out, "spinecare_${today()}.csv")
        f.writeText(buildCsv(allSessions(), allCheckins()))
        return f
    }

    companion object {
        private val LOCK = Any()

        private fun readJsonl(f: File): List<JSONObject> = synchronized(LOCK) {
            if (!f.exists()) emptyList()
            else f.readLines().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
        }

        fun esc(v: Any?): String = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""

        fun buildCsv(sessions: List<JSONObject>, checkins: List<JSONObject>): String {
            val sb = StringBuilder("type,ts,date,exercise,reps,sets,duration_s,frames,source,phase,neck_pain,arm_symptoms,sleep_quality,notes,avg_metrics,cue_counts\n")
            for (o in sessions) {
                sb.append(listOf("session", o.optLong("ts"), o.optString("date"), o.optString("exercise"),
                    o.optInt("reps"), o.optInt("sets"), o.optLong("duration_s"), o.optInt("frames"), o.optString("source"),
                    "", "", "", "", "", o.optJSONObject("avg")?.toString() ?: "", o.optJSONObject("cues")?.toString() ?: ""
                ).joinToString(",") { esc(it) }).append('\n')
            }
            for (o in checkins) {
                sb.append(listOf("checkin", o.optLong("ts"), o.optString("date"), "", "", "", "", "", "",
                    o.optString("phase", "daily"), o.optInt("neck_pain"), o.optInt("arm_symptoms"),
                    if (o.has("sleep_quality")) o.optInt("sleep_quality") else "", o.optString("notes"), "", ""
                ).joinToString(",") { esc(it) }).append('\n')
            }
            return sb.toString()
        }
    }
}
