package com.spinecare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Spoken guidance for exercise sessions: setup and calibration prompts, form
 *  cues (throttled so they never talk over each other), rep counts, milestones,
 *  and answers to voice commands. [guidance] = speak automatic coaching; when
 *  false only explicit replies (voice-command answers) are spoken.
 *  [onSpeaking] fires on the main thread so voice control can stop listening
 *  while the phone talks and does not hear itself. */
class VoiceCoach(context: Context, private val guidance: Boolean, private val onSpeaking: (Boolean) -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null
    private var lastCueAt = 0L
    private var utterances = 0
    @Volatile var speaking = false; private set
    /** Last phrase spoken, for the "repeat" command. */
    @Volatile var lastSpoken = ""; private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { st ->
        if (st == TextToSpeech.SUCCESS) main.post {
            ready = true
            applyLanguage()
            pending?.let { pending = null; speak(it, flush = true) }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { setSpeaking(true) }
            override fun onDone(id: String?) { if (!tts.isSpeaking) setSpeaking(false) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { setSpeaking(false) }
            override fun onStop(id: String?, interrupted: Boolean) { setSpeaking(false) }
        })
    }

    private fun setSpeaking(v: Boolean) {
        main.post { if (speaking != v) { speaking = v; onSpeaking(v) } }
    }

    private fun applyLanguage() {
        val want = if (I18n.lang == "ro") Locale("ro", "RO") else Locale.US
        if (tts.setLanguage(want) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
    }

    /** Speaks now (flush) or after what is already queued. Always spoken. */
    fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) { pending = text; return }
        lastSpoken = text
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u${utterances++}")
    }

    /** Automatic coaching prompt (only with guidance on). */
    fun guide(text: String, flush: Boolean = false) { if (guidance) speak(text, flush) }

    /** Form/framing cue: dropped if another cue was spoken in the last 2.5 s. */
    fun cue(text: String) {
        if (!guidance || text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastCueAt < 2500 || (speaking && now - lastCueAt < 4000)) return
        lastCueAt = now
        speak(text, flush = true)
    }

    /** Rep count, with milestone phrases at halfway and at the target. */
    fun rep(reps: Int, target: Int) {
        if (!guidance) return
        val text = when {
            target > 0 && reps == target -> I18n.t("vg_target").format(target)
            target >= 6 && reps == target / 2 -> "$reps. " + I18n.t("vg_halfway")
            else -> reps.toString()
        }
        lastCueAt = System.currentTimeMillis()
        speak(text, flush = true)
    }

    fun stop() { runCatching { tts.stop() } }

    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
