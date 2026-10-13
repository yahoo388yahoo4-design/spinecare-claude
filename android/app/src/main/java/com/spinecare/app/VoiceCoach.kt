package com.spinecare.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Spoken guidance for exercise sessions: setup and calibration prompts, form
 *  cues (throttled so they never talk over each other), rep counts with
 *  feedback, tempo prompts, milestones, and answers to voice commands.
 *  [guidance] = speak automatic coaching; when false only explicit replies
 *  (voice-command answers) are spoken.
 *  [onSpeaking] fires on the main thread so voice control can stop listening
 *  while the phone talks and does not hear itself.
 *  [onProblem] fires on the main thread with an I18n key when speech cannot
 *  work as asked (no engine, language missing), so the screen can say why
 *  instead of staying silent. */
class VoiceCoach(
    context: Context,
    private val guidance: Boolean,
    private val onProblem: (String) -> Unit = {},
    private val onSpeaking: (Boolean) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    private var failed = false
    private val pending = ArrayDeque<String>()
    private var lastCueAt = 0L
    private var utterances = 0
    private var triedGoogle = false
    @Volatile var speaking = false; private set
    /** Last phrase spoken, for the "repeat" command. */
    @Volatile var lastSpoken = ""; private set

    private var tts: TextToSpeech = create(null)

    /** Some devices bind the default engine slowly or not at all: if it is not
     *  ready after [INIT_TIMEOUT_MS], retry once with Google's engine. */
    private val initWatchdog = Runnable { if (!ready) retryWithGoogle() }

    init { main.postDelayed(initWatchdog, INIT_TIMEOUT_MS) }

    private fun create(engine: String?): TextToSpeech {
        lateinit var created: TextToSpeech
        val listener = TextToSpeech.OnInitListener { st ->
            main.post {
                if (created !== tts) return@post
                if (st == TextToSpeech.SUCCESS) onReady() else retryWithGoogle()
            }
        }
        created = if (engine == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { setSpeaking(true) }
            override fun onDone(id: String?) { if (!created.isSpeaking) setSpeaking(false) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { setSpeaking(false) }
            override fun onError(id: String?, errorCode: Int) { setSpeaking(false) }
            override fun onStop(id: String?, interrupted: Boolean) { setSpeaking(false) }
        })
        return created
    }

    private fun retryWithGoogle() {
        if (ready || triedGoogle) { if (!ready) fail("tts_no_engine"); return }
        triedGoogle = true
        val google = "com.google.android.tts"
        val installed = runCatching { tts.engines.any { it.name == google } }.getOrDefault(false)
        if (!installed) { fail("tts_no_engine"); return }
        runCatching { tts.shutdown() }
        tts = create(google)
        main.postDelayed(initWatchdog, INIT_TIMEOUT_MS)
    }

    private fun fail(key: String) {
        if (failed) return
        failed = true; pending.clear()
        onProblem(key)
    }

    private fun onReady() {
        ready = true
        main.removeCallbacks(initWatchdog)
        // Speech, not media: some devices otherwise route it quietly or mute it while the mic listens.
        runCatching {
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        applyLanguage()
        var first = true
        while (pending.isNotEmpty()) { speak(pending.removeFirst(), flush = first); first = false }
    }

    private fun applyLanguage() {
        val want = if (I18n.lang == "ro") Locale("ro", "RO") else Locale.US
        if (tts.setLanguage(want) >= TextToSpeech.LANG_AVAILABLE) return
        if (I18n.lang == "ro") onProblem("tts_lang_missing")
        if (tts.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.getDefault())
    }

    private fun setSpeaking(v: Boolean) {
        main.post { if (speaking != v) { speaking = v; onSpeaking(v) } }
    }

    /** Speaks now (flush) or after what is already queued. Always spoken. */
    fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank() || failed) return
        if (!ready) {
            if (flush) pending.clear()
            pending.addLast(text)
            while (pending.size > 3) pending.removeFirst()
            return
        }
        lastSpoken = text
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val r = tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "u${utterances++}")
        if (r == TextToSpeech.ERROR && utterances <= 1) onProblem("tts_error")
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

    /** Short realtime prompt during a movement ("hold", "and back"): always
     *  interrupts, because a late tempo prompt is worse than none. */
    fun prompt(text: String) {
        if (!guidance || text.isBlank()) return
        lastCueAt = System.currentTimeMillis()
        speak(text, flush = true)
    }

    /** Rep count, with milestone phrases at halfway and at the target, then
     *  optional short [feedback] on the rep just done. */
    fun rep(reps: Int, target: Int, feedback: String = "") {
        if (!guidance) return
        val count = when {
            target > 0 && reps == target -> I18n.t("vg_target").format(target)
            target >= 6 && reps == target / 2 -> "$reps. " + I18n.t("vg_halfway")
            else -> "$reps."
        }
        lastCueAt = System.currentTimeMillis()
        speak(if (feedback.isBlank()) count else "$count $feedback", flush = true)
    }

    fun stop() { runCatching { tts.stop() } }

    fun shutdown() { main.removeCallbacks(initWatchdog); runCatching { tts.stop(); tts.shutdown() } }

    private companion object { const val INIT_TIMEOUT_MS = 4000L }
}
