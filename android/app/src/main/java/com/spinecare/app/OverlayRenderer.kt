package com.spinecare.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max

/** Shared skeleton + HUD drawing used by the live OverlayView AND the session
 *  recorder, so saved videos show exactly what the user saw during the session. */
object OverlayRenderer {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val neck = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 8f; style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textBg = Paint().apply { style = Paint.Style.FILL; color = 0xCC0B1220.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34D399"); textSize = 56f; isFakeBoldText = true }

    private val conns = listOf(
        "lsho" to "rsho", "lsho" to "lhip", "rsho" to "rhip", "lhip" to "rhip",
        "lsho" to "lelb", "rsho" to "relb", "lelb" to "lwri", "relb" to "rwri",
        "lhip" to "lknee", "rhip" to "rknee", "lknee" to "lank", "rknee" to "rank",
    )

    /** Draws skeleton + HUD. [lms] are in pixels of a [frameW]x[frameH] camera
     *  frame, mapped with the same center-crop the camera preview uses.
     *  [mirror] flips x for front-camera WYSIWYG display/recording. [areaW]x[areaH]
     *  is the drawing area from the canvas origin (session replay letterboxes it). */
    fun draw(canvas: Canvas, lms: Map<String, Metrics.P>?, frameW: Int, frameH: Int, missing: List<String>,
             ready: Boolean, metricsLine: String, cue: String, repsText: String, mirror: Boolean,
             areaW: Float = canvas.width.toFloat(), areaH: Float = canvas.height.toFloat()) {
        val w = areaW; val h = areaH
        if (lms != null && frameW > 0 && frameH > 0) {
            val s = max(w / frameW, h / frameH)
            val ox = (w - frameW * s) / 2f; val oy = (h - frameH * s) / 2f
            fun px(x: Double): Float { val v = ox + x.toFloat() * s; return if (mirror) w - v else v }
            fun py(y: Double): Float = oy + y.toFloat() * s
            val missingNames = missing.map { Framing.resolve(lms, it) }.toSet()
            val color = if (ready) Color.parseColor("#34D399") else Color.parseColor("#FBBF24")
            line.color = color
            for ((a, b) in conns) {
                val la = lms[a] ?: continue; val lb = lms[b] ?: continue
                if (la.c > Metrics.VIS && lb.c > Metrics.VIS) canvas.drawLine(px(la.x), py(la.y), px(lb.x), py(lb.y), line)
            }
            Metrics.bestSide(lms)?.let { side ->
                val ear = lms[side + "ear"]; val sho = lms[side + "sho"]
                if (ear != null && sho != null) {
                    neck.color = if (ready) Color.parseColor("#38BDF8") else Color.parseColor("#F87171")
                    canvas.drawLine(px(ear.x), py(ear.y), px(sho.x), py(sho.y), neck)
                }
            }
            for ((name, lm) in lms) {
                if (lm.c > Metrics.VIS || name in missingNames) {
                    val isMissing = name in missingNames
                    dot.color = if (isMissing) Color.parseColor("#F87171") else color
                    canvas.drawCircle(px(lm.x), py(lm.y), if (isMissing) 14f else 7f, dot)
                }
            }
        }
        if (repsText.isNotEmpty()) canvas.drawText(repsText, w - bigText.measureText(repsText) - 24f, 84f, bigText)
        val lines = ArrayList<String>()
        if (cue.isNotEmpty()) lines.addAll(wrap(cue, w - 32f))
        if (metricsLine.isNotEmpty()) metricsLine.split('\n').forEach { lines.addAll(wrap(it, w - 32f)) }
        if (lines.isNotEmpty()) {
            val blockH = lines.size * 34f + 16f
            canvas.drawRect(0f, h - blockH, w, h, textBg)
            var y = h - blockH + 34f
            for (ln in lines) { canvas.drawText(ln, 16f, y, text); y += 34f }
        }
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        if (maxW <= 0f || text.measureText(s) <= maxW) return listOf(s)
        val out = ArrayList<String>(); var cur = StringBuilder()
        for (word in s.split(' ')) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (text.measureText(trial) > maxW && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder(word) }
            else cur = StringBuilder(trial)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
