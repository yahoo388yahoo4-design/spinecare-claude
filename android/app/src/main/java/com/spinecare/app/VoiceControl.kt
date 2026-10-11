package com.spinecare.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Hands-free commands while an exercise screen is open: listens in short
 *  rounds with the platform SpeechRecognizer (restarting after each result,
 *  silence or error), prefers on-device recognition, and hands each matched
 *  command to [onCommand] on the main thread. Call [hold] while the app is
 *  speaking so it does not react to its own voice. No audio is stored. */
class VoiceControl(
    private val context: Context,
    private val commands: VoiceCommands,
    private val onCommand: (VoiceCommands.Cmd, String) -> Unit,
    private val onState: (listening: Boolean, heard: String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var held = false
    private var listening = false
    private var lastCommandAt = 0L
    private var lastPartialCmd: VoiceCommands.Cmd? = null
    private var errorsInRow = 0

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active || !available) return
        active = true; errorsInRow = 0
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        setListening(false, null)
    }

    /** Pause listening while the phone speaks; resume afterwards. */
    fun hold(on: Boolean) {
        if (held == on) return
        held = on
        if (!active) return
        if (on) { main.removeCallbacksAndMessages(null); runCatching { recognizer?.cancel() }; setListening(false, null) }
        else restart(250)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (I18n.lang == "ro") "ro-RO" else "en-US")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    private fun listen() {
        if (!active || held) return
        lastPartialCmd = null
        runCatching { recognizer?.startListening(intent()) }.onFailure { restart(1500) }
    }

    private fun restart(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        if (active && !held) main.postDelayed({ listen() }, delayMs)
    }

    private fun setListening(v: Boolean, heard: String?) {
        listening = v
        onState(v, heard)
    }

    private fun handle(hypotheses: List<String>, final: Boolean) {
        val hit = commands.parseAny(hypotheses) ?: return
        val now = System.currentTimeMillis()
        // A partial result can already carry the command; don't fire it twice.
        if (!final) { if (hit.first == lastPartialCmd) return; lastPartialCmd = hit.first }
        else if (hit.first == lastPartialCmd) return
        if (now - lastCommandAt < 800) return
        lastCommandAt = now
        onCommand(hit.first, hit.second)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { errorsInRow = 0; setListening(true, null) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (list.isNotEmpty() && list[0].isNotBlank()) handle(list, final = false)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            setListening(false, list.firstOrNull())
            handle(list, final = true)
            restart(150)
        }

        override fun onError(error: Int) {
            setListening(false, null)
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stop(); return }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(150)
                else -> {
                    // busy / network / client errors: back off, then rebuild the recognizer
                    errorsInRow++
                    if (errorsInRow >= 3) {
                        runCatching { recognizer?.destroy() }
                        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
                    }
                    restart(minOf(8000L, 600L * errorsInRow))
                }
            }
        }
    }
}
