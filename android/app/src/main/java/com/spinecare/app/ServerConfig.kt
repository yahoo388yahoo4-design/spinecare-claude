package com.spinecare.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Coaching-server endpoint + auth + runtime options, persisted locally.
 *  Offline-first: with no server URL (the default) or in ON_DEVICE mode the
 *  app never touches the network. The password lives in encrypted storage. */
object ServerConfig {
    private const val PREFS = "spinecare"
    private const val SECURE_PREFS = "spinecare.secure"

    const val MODE_AUTO = 0        // use the server when reachable, else on-device
    const val MODE_ON_DEVICE = 1   // never use the network for coaching
    const val MODE_SERVER = 2      // server preferred; on-device keeps running if it drops

    const val STREAM_LANDMARKS = 0 // send pose landmarks only (privacy default)
    const val STREAM_FRAMES = 1    // send JPEG frames for GPU pose

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var secure: SharedPreferences? = null
    private fun s(c: Context): SharedPreferences = secure ?: synchronized(this) {
        secure ?: runCatching {
            val key = MasterKey.Builder(c.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(c.applicationContext, SECURE_PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrElse {
            // Keystore unavailable (rare, e.g. broken emulator image): keep the
            // password in memory-only prefs rather than plain storage.
            c.getSharedPreferences(SECURE_PREFS + ".volatile", Context.MODE_PRIVATE).also { it.edit().clear().apply() }
        }.also { secure = it }
    }

    fun url(c: Context) = p(c).getString("server_url", "")!!.trim().trimEnd('/')
    fun setUrl(c: Context, v: String) = p(c).edit().putString("server_url", v.trim().trimEnd('/')).apply()

    fun user(c: Context) = p(c).getString("server_user", "")!!
    fun setUser(c: Context, v: String) = p(c).edit().putString("server_user", v).apply()

    fun pass(c: Context): String {
        // migrate a password saved in plain prefs by earlier builds
        p(c).getString("server_pass", null)?.let { old ->
            s(c).edit().putString("server_pass", old).apply()
            p(c).edit().remove("server_pass").apply()
        }
        return s(c).getString("server_pass", "")!!
    }
    fun setPass(c: Context, v: String) = s(c).edit().putString("server_pass", v).apply()

    fun mode(c: Context) = p(c).getInt("mode", MODE_AUTO)
    fun setMode(c: Context, v: Int) = p(c).edit().putInt("mode", v).apply()

    fun streamMode(c: Context) = p(c).getInt("stream_mode", STREAM_LANDMARKS)
    fun setStreamMode(c: Context, v: Int) = p(c).edit().putInt("stream_mode", v).apply()

    /** Plain HTTP is only allowed to private-LAN hosts, and only when the user opts in. */
    fun allowInsecureLan(c: Context) = p(c).getBoolean("allow_insecure_lan", false)
    fun setAllowInsecureLan(c: Context, v: Boolean) = p(c).edit().putBoolean("allow_insecure_lan", v).apply()

    /** analysis frame interval in ms (lower = smoother, more battery) */
    fun frameIntervalMs(c: Context) = p(c).getInt("frame_ms", 150)
    fun setFrameIntervalMs(c: Context, v: Int) = p(c).edit().putInt("frame_ms", v.coerceIn(33, 2000)).apply()

    /** server send interval (network budget) */
    fun serverFrameIntervalMs(c: Context) = p(c).getInt("server_frame_ms", 150)
    fun setServerFrameIntervalMs(c: Context, v: Int) = p(c).edit().putInt("server_frame_ms", v.coerceIn(50, 2000)).apply()

    fun minConfidence(c: Context) = p(c).getFloat("min_conf", 0.5f)
    fun setMinConfidence(c: Context, v: Float) = p(c).edit().putFloat("min_conf", v.coerceIn(0.1f, 0.95f)).apply()

    fun haptics(c: Context) = p(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, v: Boolean) = p(c).edit().putBoolean("haptics", v).apply()

    fun audioCues(c: Context) = p(c).getBoolean("audio_cues", false)
    fun setAudioCues(c: Context, v: Boolean) = p(c).edit().putBoolean("audio_cues", v).apply()

    /** Hands-free voice commands on the exercise screen (needs the microphone). */
    fun voiceControl(c: Context) = p(c).getBoolean("voice_control", false)
    fun setVoiceControl(c: Context, v: Boolean) = p(c).edit().putBoolean("voice_control", v).apply()

    /** Record sessions with the sensor overlay burned in (local MP4). */
    fun recordSessions(c: Context) = p(c).getBoolean("record_sessions", false)
    fun setRecordSessions(c: Context, v: Boolean) = p(c).edit().putBoolean("record_sessions", v).apply()

    /** Camera screens' orientation: CameraOptions.ORIENT_AUTO, _PORTRAIT or _LANDSCAPE. */
    fun cameraOrientation(c: Context) = p(c).getInt("camera_orientation", CameraOptions.ORIENT_AUTO)
    fun setCameraOrientation(c: Context, v: Int) = p(c).edit().putInt("camera_orientation", v).apply()

    /** Whether the configured server may be used at all: a URL is set, the mode
     *  allows it, and the transport is HTTPS (or opted-in HTTP on a private LAN). */
    fun serverUsable(c: Context): Boolean =
        mode(c) != MODE_ON_DEVICE && urlProblem(url(c), allowInsecureLan(c)) == null

    /** null when the URL may be used, otherwise an I18n key describing why not. */
    fun urlProblem(url: String, allowInsecureLan: Boolean): String? {
        if (url.isBlank()) return "srv_no_url"
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return "srv_bad_url"
        val host = u.host ?: return "srv_bad_url"
        return when (u.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (!allowInsecureLan) "srv_http_disabled" else if (!isPrivateLanHost(host)) "srv_http_public" else null
            else -> "srv_bad_url"
        }
    }

    fun isPrivateLanHost(hostIn: String): Boolean {
        val h = hostIn.lowercase().trim('[', ']')
        return h == "localhost" || h.endsWith(".local") || h == "::1" ||
            Regex("""^10\.""").containsMatchIn(h) ||
            Regex("""^192\.168\.""").containsMatchIn(h) ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h) ||
            Regex("""^169\.254\.""").containsMatchIn(h) ||
            Regex("""^f[cd][0-9a-f:]*$""").matches(h) ||
            Regex("""^fe[89ab][0-9a-f:]*$""").matches(h)
    }
}
