package com.spinecare.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Draws pose skeleton + framing status + cue highlights over the camera preview.
 *  Delegates all drawing to OverlayRenderer so recordings match the live view.
 *  State is replaced as one immutable snapshot from the analysis thread. */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Frame(
        val landmarks: Map<String, Metrics.P>? = null,
        val frameW: Int = 0, val frameH: Int = 0,
        val missing: List<String> = emptyList(), val ready: Boolean = false,
        val metricsLine: String = "", val cue: String = "", val repsText: String = "",
    )

    @Volatile var frame = Frame()
    @Volatile var mirror = false

    fun show(f: Frame) { frame = f; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame
        OverlayRenderer.draw(canvas, f.landmarks, f.frameW, f.frameH, f.missing, f.ready, f.metricsLine, f.cue, f.repsText, mirror)
    }
}
