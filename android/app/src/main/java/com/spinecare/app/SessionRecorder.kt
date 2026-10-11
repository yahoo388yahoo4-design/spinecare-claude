package com.spinecare.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Records the session as MP4 with the sensor overlay burned in: each frame is
 *  the camera image + skeleton/metrics/cues drawn via OverlayRenderer, encoded
 *  through MediaCodec (surface input) and muxed with MediaMuxer.
 *
 *  Fixes vs. the first prototype: the muxer is started only after the encoder
 *  reports its output format (starting it earlier throws, so nothing was ever
 *  recorded), frames are drawn with a hardware canvas (software canvases are
 *  not supported on encoder surfaces on many devices), the size is rounded to
 *  codec-friendly even dimensions, and the drain loop runs until end-of-stream. */
class SessionRecorder(file: File, width: Int, height: Int) {
    private val w = (width / 16) * 16
    private val h = (height / 16) * 16
    private val codec = MediaCodec.createEncoderByType(MIME)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    @Volatile private var muxerStarted = false
    @Volatile private var stopping = false
    private val drainThread = Thread({ drain() }, "rec-drain")
    private val surface: Surface
    var framesWritten = 0; private set

    init {
        val fmt = MediaFormat.createVideoFormat(MIME, w, h)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 15)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        drainThread.start()
    }

    /** Draws one composited frame (camera bitmap + overlay) onto the encoder surface. */
    fun drawFrame(camera: Bitmap, lms: Map<String, Metrics.P>?, frameW: Int, frameH: Int, missing: List<String>,
                  ready: Boolean, metricsLine: String, cue: String, repsText: String, mirror: Boolean) {
        if (stopping) return
        try {
            val c: Canvas = surface.lockHardwareCanvas()
            try {
                c.drawColor(Color.BLACK)
                c.save()
                if (mirror) c.scale(-1f, 1f, c.width / 2f, c.height / 2f)
                c.drawBitmap(camera, null, RectF(0f, 0f, c.width.toFloat(), c.height.toFloat()), null)
                c.restore()
                OverlayRenderer.draw(c, lms, frameW, frameH, missing, ready, metricsLine, cue, repsText, mirror)
            } finally {
                surface.unlockCanvasAndPost(c)
            }
            framesWritten++
        } catch (_: Exception) { /* surface gone or encoder stopped */ }
    }

    fun stop() {
        stopping = true
        runCatching { codec.signalEndOfInputStream() }
        drainThread.join(3000)
        runCatching { codec.stop() }; runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { surface.release() }
    }

    private fun drain() {
        val bi = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(bi, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track < 0) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                }
                idx >= 0 -> {
                    val isConfig = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxerStarted && bi.size > 0 && !isConfig) {
                        codec.getOutputBuffer(idx)?.let { buf ->
                            buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                            runCatching { muxer.writeSampleData(track, buf, bi) }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                // keep polling until EOS arrives after stop(); join() bounds the wait
            }
        }
    }

    companion object { private const val MIME = "video/avc" }
}
