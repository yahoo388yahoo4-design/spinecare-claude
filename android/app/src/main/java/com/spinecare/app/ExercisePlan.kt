package com.spinecare.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FormRule(val metric: String, val maxAbs: Double, val relative: Boolean, val cue: Map<String, String>)

/** One plan entry. Built-in entries come from shared/exercises.json (camera
 *  tracked); custom entries are added by the user after clinician approval
 *  and are tracked manually (timer + set counter). */
data class Exercise(
    val id: String,
    val name: Map<String, String>,
    val description: Map<String, String>,
    val view: String,                 // side | front | back | any
    val tracking: String,             // camera | manual
    val requiredLandmarks: List<String>,
    val minConf: Double,
    val signal: String,
    val direction: Double,
    val repLow: Double,
    val repHigh: Double,
    val targetReps: Int,
    val targetSets: Int,
    val formRules: List<FormRule>,
    val stopConditions: Map<String, List<String>>,
    val clinicianApproved: Boolean,
    val custom: Boolean = false,
    val videoUrl: String = "",
) {
    fun name(lang: String) = name[lang] ?: name["en"] ?: id
    fun description(lang: String) = description[lang] ?: description["en"] ?: ""
    fun stops(lang: String) = stopConditions[lang] ?: stopConditions["en"] ?: emptyList()
    val cameraTracked get() = tracking == "camera"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", JSONObject(name as Map<*, *>)).put("description", JSONObject(description as Map<*, *>))
        .put("view", view).put("tracking", tracking).put("target_reps", targetReps).put("target_sets", targetSets)
        .put("video_url", videoUrl).put("custom", custom).put("clinician_approved", clinicianApproved)

    companion object {
        fun parse(o: JSONObject): Exercise {
            val band = o.optJSONObject("rep_band")
            val stops = o.opt("stop_conditions")
            return Exercise(
                id = o.getString("id"),
                name = strMap(o.opt("name")),
                description = strMap(o.opt("description")),
                view = o.optString("view", "any"),
                tracking = o.optString("tracking", "camera"),
                requiredLandmarks = strList(o.optJSONArray("required_landmarks")),
                minConf = o.optDouble("min_conf", 0.5),
                signal = o.optString("signal", ""),
                direction = o.optDouble("direction", 1.0),
                repLow = band?.optDouble("low", 0.0) ?: 0.0,
                repHigh = band?.optDouble("high", 0.0) ?: 0.0,
                targetReps = o.optInt("target_reps", 0),
                targetSets = o.optInt("target_sets", 1),
                formRules = o.optJSONArray("form_rules")?.let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        FormRule(r.getString("metric"), r.getDouble("max_abs"), r.optBoolean("relative", false), strMap(r.opt("cue")))
                    }
                } ?: emptyList(),
                stopConditions = when (stops) {
                    is JSONObject -> stops.keys().asSequence().associateWith { strList(stops.optJSONArray(it)) }
                    is JSONArray -> mapOf("en" to strList(stops))
                    else -> emptyMap()
                },
                clinicianApproved = o.optBoolean("clinician_approved", false),
                custom = o.optBoolean("custom", false),
                videoUrl = o.optString("video_url", ""),
            )
        }

        private fun strMap(v: Any?): Map<String, String> = when (v) {
            is JSONObject -> v.keys().asSequence().associateWith { v.optString(it) }
            is String -> mapOf("en" to v)
            else -> emptyMap()
        }

        private fun strList(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        /** Manual-tracking entry added by the user (clinician-approved plan item). */
        fun custom(name: String, instructions: String, sets: Int, reps: Int, videoUrl: String) = Exercise(
            id = "custom-" + System.currentTimeMillis(),
            name = mapOf("en" to name, "ro" to name),
            description = mapOf("en" to instructions, "ro" to instructions),
            view = "any", tracking = "manual", requiredLandmarks = emptyList(), minConf = 0.5,
            signal = "", direction = 1.0, repLow = 0.0, repHigh = 0.0,
            targetReps = reps, targetSets = sets, formRules = emptyList(), stopConditions = emptyMap(),
            clinicianApproved = true, custom = true, videoUrl = videoUrl,
        )
    }
}

/** Built-in plan (shared/exercises.json, bundled as an asset) plus the user's
 *  custom clinician-approved entries. Nothing here is self-prescribed. */
object Plan {
    data class Bundle(val exercises: List<Exercise>, val calibrationS: Double)

    @Volatile private var builtIn: Bundle? = null

    fun parseBundle(json: String): Bundle {
        val o = JSONObject(json)
        val a = o.getJSONArray("exercises")
        return Bundle((0 until a.length()).map { Exercise.parse(a.getJSONObject(it)) }, o.optDouble("calibration_s", 1.5))
    }

    fun bundle(ctx: Context): Bundle = builtIn ?: synchronized(this) {
        builtIn ?: parseBundle(ctx.assets.open("exercises.json").bufferedReader().use { it.readText() }).also { builtIn = it }
    }

    fun all(ctx: Context): List<Exercise> = bundle(ctx).exercises + LocalStore(ctx).customExercises()
    fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }
}
