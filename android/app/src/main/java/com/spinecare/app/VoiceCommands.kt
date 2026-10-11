package com.spinecare.app

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** Hands-free command vocabulary (shared/voice_commands.json, also used by iOS).
 *  Recognized speech is matched as whole words after lower-casing and removing
 *  diacritics, in English and Romanian regardless of the app language. When
 *  several commands match, the earlier one in `priority` wins (stop and pause
 *  first), then the longest phrase. */
class VoiceCommands(json: String) {
    enum class Cmd { STOP, PAUSE, RESUME, SKIP, RECALIBRATE, FLIP, STATUS, REPEAT, HELP, START }

    private data class Phrase(val cmd: Cmd, val text: String, val rank: Int)

    private val phrases: List<Phrase>

    init {
        val o = JSONObject(json)
        val prio = o.getJSONArray("priority")
        val cmds = o.getJSONObject("commands")
        val out = ArrayList<Phrase>()
        for (i in 0 until prio.length()) {
            val key = prio.getString(i)
            val cmd = runCatching { Cmd.valueOf(key.uppercase()) }.getOrNull() ?: continue
            val langs = cmds.optJSONObject(key) ?: continue
            for (lang in langs.keys()) {
                val a = langs.getJSONArray(lang)
                for (j in 0 until a.length()) normalize(a.getString(j)).takeIf { it.isNotEmpty() }?.let { out.add(Phrase(cmd, it, i)) }
            }
        }
        phrases = out
    }

    /** The command in [heard], or null. */
    fun parse(heard: String): Cmd? {
        val text = " " + normalize(heard) + " "
        if (text.isBlank()) return null
        return phrases.filter { text.contains(" ${it.text} ") }
            .sortedWith(compareBy<Phrase> { it.rank }.thenByDescending { it.text.length })
            .firstOrNull()?.cmd
    }

    /** First match among several recognizer hypotheses (best first). */
    fun parseAny(hypotheses: List<String>): Pair<Cmd, String>? {
        for (h in hypotheses) parse(h)?.let { return it to h }
        return null
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^a-z0-9']+")

        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
                .replace('’', '\'').replace(NON_WORD, " ").trim().replace(Regex(" +"), " ")

        @Volatile private var cached: VoiceCommands? = null

        fun load(c: Context): VoiceCommands = cached ?: synchronized(this) {
            cached ?: VoiceCommands(c.assets.open("voice_commands.json").bufferedReader().use { it.readText() }).also { cached = it }
        }
    }
}
