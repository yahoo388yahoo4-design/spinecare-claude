package com.spinecare.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.spinecare.app.I18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Configuration: coaching mode, server URL/user/password (password in
 *  encrypted storage), what is streamed, HTTP-on-LAN opt-in, tuning, feedback. */
class SettingsFragment : Fragment() {
    private var serverStatus: TextView? = null
    private var oldShown = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val ui = Ui(ctx)
        fun radio(group: RadioGroup, id: Int, text: String, checked: Boolean) =
            group.addView(RadioButton(ctx).apply { this.id = id; this.text = text; setTextColor(Ui.TEXT); isChecked = checked })
        fun switch(text: String, on: Boolean) = ui.add(SwitchCompat(ctx).apply { this.text = text; setTextColor(Ui.TEXT); isChecked = on })

        ui.p(t("settings"), Ui.TEXT, 22f)
        ui.h(t("language"))
        val lang = ui.add(RadioGroup(ctx))
        I18n.LANGS.forEachIndexed { n, l -> radio(lang, 300 + n, t("lang_$l"), I18n.lang == l) }
        lang.setOnCheckedChangeListener { _, id -> (activity as? MainActivity)?.setLanguage(I18n.LANGS[id - 300]) }
        ui.muted(t("local_status"))

        ui.h(t("mode_title"))
        val mode = ui.add(RadioGroup(ctx))
        val m = ServerConfig.mode(ctx)
        radio(mode, 100, t("mode_auto"), m == ServerConfig.MODE_AUTO)
        radio(mode, 101, t("mode_device"), m == ServerConfig.MODE_ON_DEVICE)
        radio(mode, 102, t("mode_server"), m == ServerConfig.MODE_SERVER)

        ui.h(t("server_title"))
        val url = ui.input("https://192.168.1.20:8300", ServerConfig.url(ctx), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = ui.input(t("server_user"), ServerConfig.user(ctx))
        val pass = ui.input(t("server_pass"), ServerConfig.pass(ctx), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        ui.p(t("stream_title"), Ui.TEXT, 14f)
        val stream = ui.add(RadioGroup(ctx))
        val sm = ServerConfig.streamMode(ctx)
        radio(stream, 200, t("stream_landmarks"), sm == ServerConfig.STREAM_LANDMARKS)
        radio(stream, 201, t("stream_frames"), sm == ServerConfig.STREAM_FRAMES)
        val insecure = switch(t("allow_http"), ServerConfig.allowInsecureLan(ctx))
        ui.muted(t("http_warning"))
        ui.muted(t("remote_privacy"))
        ui.muted(t("mediapipe_metrics"))
        ui.button(t("test_server")) {
            save(ctx, mode, url.text.toString(), user.text.toString(), pass.text.toString(), stream, insecure.isChecked)
            testConnection()
        }
        serverStatus = ui.p(t("server_unknown"), Ui.MUTED, 12f)

        ui.h(t("tracking_title"))
        val frameMs = ui.input(t("frame_ms"), ServerConfig.frameIntervalMs(ctx).toString(), InputType.TYPE_CLASS_NUMBER)
        val serverMs = ui.input(t("server_frame_ms"), ServerConfig.serverFrameIntervalMs(ctx).toString(), InputType.TYPE_CLASS_NUMBER)
        val minConf = ui.input(t("min_conf"), ServerConfig.minConfidence(ctx).toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        ui.h(t("feedback_title"))
        val haptics = switch(t("haptics"), ServerConfig.haptics(ctx))
        val audio = switch(t("voice_guidance"), ServerConfig.audioCues(ctx))
        ui.muted(t("voice_guidance_body"))
        ui.button(t("test_voice")) { testVoice() }
        voiceStatus = ui.p("", Ui.MUTED, 12f)
        val tempo = switch(t("set_tempo_prompts"), ServerConfig.tempoPrompts(ctx))
        ui.muted(t("set_tempo_prompts_body"))
        val moveDemo = switch(t("set_move_demo"), ServerConfig.moveDemo(ctx))
        ui.muted(t("set_move_demo_body"))
        val repFb = switch(t("set_rep_feedback"), ServerConfig.repFeedback(ctx))
        ui.muted(t("set_rep_feedback_body"))
        val tapHide = switch(t("set_tap_hide"), ServerConfig.tapHidesText(ctx))
        ui.muted(t("set_tap_hide_body"))
        val voice = switch(t("voice_control"), ServerConfig.voiceControl(ctx))
        ui.muted(t("voice_control_body"))
        val record = switch(t("record"), ServerConfig.recordSessions(ctx))
        ui.muted(t("record_privacy"))
        val cleanRec = switch(t("set_clean_rec"), ServerConfig.cleanRecordings(ctx))
        ui.muted(t("set_clean_rec_body"))

        ui.button(t("save_settings")) {
            save(ctx, mode, url.text.toString(), user.text.toString(), pass.text.toString(), stream, insecure.isChecked)
            ServerConfig.setFrameIntervalMs(ctx, frameMs.text.toString().toIntOrNull() ?: 150)
            ServerConfig.setServerFrameIntervalMs(ctx, serverMs.text.toString().toIntOrNull() ?: 150)
            ServerConfig.setMinConfidence(ctx, minConf.text.toString().toFloatOrNull() ?: 0.5f)
            ServerConfig.setHaptics(ctx, haptics.isChecked)
            ServerConfig.setAudioCues(ctx, audio.isChecked)
            ServerConfig.setVoiceControl(ctx, voice.isChecked)
            ServerConfig.setRecordSessions(ctx, record.isChecked)
            ServerConfig.setTempoPrompts(ctx, tempo.isChecked)
            ServerConfig.setMoveDemo(ctx, moveDemo.isChecked)
            ServerConfig.setRepFeedback(ctx, repFb.isChecked)
            ServerConfig.setTapHidesText(ctx, tapHide.isChecked)
            ServerConfig.setCleanRecordings(ctx, cleanRec.isChecked)
            Toast.makeText(ctx, t("settings_saved"), Toast.LENGTH_SHORT).show()
        }

        ui.h(t("uninstall_title"))
        oldShown = AppPackages.oldInstalled(ctx)
        if (oldShown) {
            ui.warn(t("uninstall_old_body"))
            ui.button(t("uninstall_old")) { AppPackages.uninstall(ctx, AppPackages.OLD_PACKAGE) }
        }
        ui.muted(t("uninstall_body"))
        ui.button(t("uninstall_self")) { AppPackages.uninstall(ctx, ctx.packageName) }

        ui.muted(t("disclaimer"))
        return ui.root
    }

    private var voiceStatus: TextView? = null
    private var testCoach: VoiceCoach? = null

    /** Speaks a sample sentence in the app language and says what is wrong if it cannot. */
    private fun testVoice() {
        val ctx = requireContext()
        voiceStatus?.text = t("test_voice_running")
        testCoach?.shutdown()
        testCoach = VoiceCoach(ctx, true, onProblem = { key ->
            voiceStatus?.text = t(key)
            if (key == "tts_no_engine" || key == "tts_lang_missing") AppPackages.openTtsSettings(ctx)
        }, onSpeaking = { speaking -> if (speaking) voiceStatus?.text = t("test_voice_ok") }).also {
            it.speak(t("test_voice_phrase"), flush = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // back from the system uninstall dialog: drop the old-app row once it is gone
        if (oldShown && !AppPackages.oldInstalled(requireContext())) {
            oldShown = false
            parentFragmentManager.beginTransaction().replace(id, SettingsFragment()).commit()
        }
    }

    private fun save(ctx: android.content.Context, mode: RadioGroup, url: String, user: String, pass: String,
                     stream: RadioGroup, insecure: Boolean) {
        ServerConfig.setMode(ctx, when (mode.checkedRadioButtonId) {
            101 -> ServerConfig.MODE_ON_DEVICE; 102 -> ServerConfig.MODE_SERVER; else -> ServerConfig.MODE_AUTO
        })
        ServerConfig.setUrl(ctx, url)
        ServerConfig.setUser(ctx, user.trim())
        ServerConfig.setPass(ctx, pass)
        ServerConfig.setStreamMode(ctx, if (stream.checkedRadioButtonId == 201) ServerConfig.STREAM_FRAMES else ServerConfig.STREAM_LANDMARKS)
        ServerConfig.setAllowInsecureLan(ctx, insecure)
    }

    private fun testConnection() {
        val ctx = requireContext()
        val base = ServerConfig.url(ctx)
        ServerConfig.urlProblem(base, ServerConfig.allowInsecureLan(ctx))?.let { serverStatus?.text = t(it); return }
        serverStatus?.text = t("testing").format(base)
        val user = ServerConfig.user(ctx); val pass = ServerConfig.pass(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
                    val req = Request.Builder().url("$base/health").apply {
                        if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass))
                    }.build()
                    http.newCall(req).execute().use { r ->
                        when {
                            r.isSuccessful -> t("server_online") + " — " + (r.body?.string()?.take(120) ?: "")
                            r.code == 401 -> t("server_auth_failed")
                            else -> "HTTP ${r.code}"
                        }
                    }
                }.getOrElse { t("server_offline") }
            }
            serverStatus?.text = result
        }
    }

    override fun onDestroyView() {
        super.onDestroyView(); serverStatus = null; voiceStatus = null
        testCoach?.shutdown(); testCoach = null
    }
}
