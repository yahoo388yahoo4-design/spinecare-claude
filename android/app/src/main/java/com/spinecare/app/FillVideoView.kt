package com.spinecare.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import kotlin.math.max
import kotlin.math.min

/** Video player view for session replays that fills its whole area: the
 *  picture is scaled to cover the view (centre crop) instead of leaving bars,
 *  or, with [fill] off, fitted inside it. A TextureView with its own
 *  MediaPlayer, offering the few VideoView calls the replay screen uses. The
 *  player survives the view being hidden; if the surface is destroyed (the app
 *  went to the background) it is reopened and onPrepared fires again. */
class FillVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var fill = true
        set(v) { field = v; applyTransform() }

    private var mp: MediaPlayer? = null
    private var path: String? = null
    private var prepared = false
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var onPrepared: ((MediaPlayer) -> Unit)? = null
    private var onInfo: ((MediaPlayer, Int, Int) -> Boolean)? = null
    private var onError: ((MediaPlayer, Int, Int) -> Boolean)? = null

    init { surfaceTextureListener = this; isOpaque = false }

    fun setOnPreparedListener(l: (MediaPlayer) -> Unit) { onPrepared = l }
    fun setOnInfoListener(l: (MediaPlayer, Int, Int) -> Boolean) { onInfo = l }
    fun setOnErrorListener(l: (MediaPlayer, Int, Int) -> Boolean) { onError = l }

    fun setVideoPath(p: String) { path = p; open() }

    private fun open() {
        val p = path ?: return
        val s = surface ?: return       // opened again once the surface exists
        release()
        val m = MediaPlayer()
        mp = m
        m.setSurface(s)
        m.setOnPreparedListener {
            if (mp !== it) return@setOnPreparedListener
            prepared = true; videoW = it.videoWidth; videoH = it.videoHeight
            applyTransform()
            onPrepared?.invoke(it)
        }
        m.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; applyTransform() }
        m.setOnInfoListener { player, what, extra -> onInfo?.invoke(player, what, extra) ?: false }
        m.setOnErrorListener { player, what, extra -> prepared = false; onError?.invoke(player, what, extra) ?: true }
        runCatching { m.setDataSource(p); m.prepareAsync() }.onFailure { prepared = false; onError?.invoke(m, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0) }
    }

    private fun release() {
        prepared = false
        mp?.let { runCatching { it.reset(); it.release() } }
        mp = null
    }

    fun start() { if (prepared) runCatching { mp?.start() } }
    fun pause() { if (prepared) runCatching { if (mp?.isPlaying == true) mp?.pause() } }
    val isPlaying: Boolean get() = prepared && runCatching { mp?.isPlaying == true }.getOrDefault(false)
    val currentPosition: Int get() = if (prepared) runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0) else 0
    fun seekTo(ms: Int) { if (prepared) runCatching { mp?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) } }
    fun stopPlayback() { release(); path = null }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st); open(); applyTransform()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { applyTransform() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release(); surface?.release(); surface = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); applyTransform() }

    /** A TextureView stretches the video to the view; this scales it back to its
     *  own shape, large enough to cover the view (fill) or to fit inside it. */
    private fun applyTransform() {
        val w = width.toFloat(); val h = height.toFloat()
        if (videoW <= 0 || videoH <= 0 || w <= 0f || h <= 0f) return
        val k = if (fill) max(w / videoW, h / videoH) else min(w / videoW, h / videoH)
        setTransform(Matrix().apply { setScale(videoW * k / w, videoH * k / h, w / 2f, h / 2f) })
    }

    companion object {
        /** Scale that maps a [fw]x[fh] picture onto a [w]x[h] area: covering it (fill) or inside it. */
        fun scale(fw: Float, fh: Float, w: Float, h: Float, fill: Boolean) =
            if (fill) max(w / fw, h / fh) else min(w / fw, h / fh)
    }
}
