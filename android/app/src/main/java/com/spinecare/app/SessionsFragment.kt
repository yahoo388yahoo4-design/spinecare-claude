package com.spinecare.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.spinecare.app.I18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Progress: saved sessions with a preview thumbnail that opens the in-app
 *  replay (video and/or sensor timeline with overlay), symptom check-ins, CSV
 *  export through the share sheet, and clearing local history. */
class SessionsFragment : Fragment() {
    private var box: FrameLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        FrameLayout(requireContext()).also { box = it; render() }

    override fun onResume() { super.onResume(); render() }
    override fun onDestroyView() { super.onDestroyView(); box = null }

    private fun render() {
        val container = box ?: return
        val ctx = requireContext()
        val store = LocalStore(ctx)
        val ui = Ui(ctx)
        container.removeAllViews(); container.addView(ui.root)

        val sessions = store.allSessions().reversed()
        val checkins = store.allCheckins().reversed()
        ui.h(t("progress"))
        ui.muted(t("progress_body"))
        ui.p(t("stats").format(sessions.size, sessions.sumOf { it.optInt("reps") }, checkins.size))

        // simple 7-entry trend of neck pain from check-ins (newest last)
        val trend = checkins.take(7).reversed().joinToString("  ") { it.optInt("neck_pain").toString() }
        if (trend.isNotEmpty()) ui.p(t("pain_trend") + " " + trend, Ui.MUTED, 13f)

        ui.h(t("saved_sessions"))
        if (sessions.isEmpty()) ui.p(t("no_sessions_long"))
        for (row in sessions.take(50)) {
            val outer = ui.card()
            val video = store.videoFile(row)?.takeIf { it.exists() }
            val tlFile = store.timelineFile(row)?.takeIf { it.exists() }
            val canReplay = video != null || tlFile != null
            val line = ui.row(outer)
            if (canReplay) {
                val dp = ctx.resources.displayMetrics.density
                val thumb = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.BG)
                    contentDescription = t("replay_open")
                    layoutParams = LinearLayout.LayoutParams((96 * dp).toInt(), (72 * dp).toInt()).apply { rightMargin = (12 * dp).toInt() }
                }
                line.addView(thumb)
                viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { SessionPreview.thumbnail(video, tlFile) }
                    if (bmp != null) thumb.setImageBitmap(bmp)
                }
                outer.setOnClickListener { openReplay(ctx, row) }
            }
            val card = ui.add(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }, line)
            val ex = Plan.byId(ctx, row.optString("exercise"))
            ui.p("${row.optString("date")}  ${ex?.name(I18n.lang) ?: row.optString("exercise")}", Ui.TEXT, 14f, card)
            val dur = row.optLong("duration_s")
            ui.muted("${t("reps")}=${row.optInt("reps")}  ${t("sets")}=${row.optInt("sets")}  " +
                "${t("duration")}=${dur / 60}m ${dur % 60}s  ${t("frames")}=${row.optInt("frames")}  [${row.optString("source")}]", card)
            row.optJSONObject("avg")?.let { avg ->
                val keys = avg.keys().asSequence().toList()
                if (keys.isNotEmpty()) ui.muted(keys.joinToString("  ") { k -> "$k=${"%.2f".format(avg.optDouble(k))}" }, card)
            }
            if (canReplay) ui.button(t("replay_open"), outer) { openReplay(ctx, row) }
            if (store.videoFile(row) != null && video == null) ui.p(t("recording_missing"), Ui.BAD, 12f, outer)
        }

        ui.h(t("checkins"))
        if (checkins.isEmpty()) ui.p(t("no_checkins"))
        for (c in checkins.take(30)) {
            val phase = t("phase_" + c.optString("phase", "daily"))
            val sleep = if (c.has("sleep_quality")) "  ${t("sleep_quality")}=${c.optInt("sleep_quality")}" else ""
            ui.p("${c.optString("date")}  $phase  ${t("neck_pain")}=${c.optInt("neck_pain")}  " +
                "${t("arm_symptoms")}=${c.optInt("arm_symptoms")}$sleep", size = 13f)
            c.optString("notes").takeIf { it.isNotBlank() }?.let { ui.muted("   $it") }
        }

        val exp = ui.card()
        ui.p(t("export_title"), Ui.TEXT, 16f, exp)
        ui.muted(t("export_body"), exp)
        ui.button(t("export_title"), exp) { export(ctx, store) }
        ui.button(t("clear_history")) {
            AlertDialog.Builder(ctx).setMessage(t("confirm_clear"))
                .setPositiveButton(t("delete")) { _, _ -> store.clearHistory(); render() }
                .setNegativeButton(t("cancel"), null).show()
        }
    }

    private fun uriFor(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    private fun openReplay(ctx: Context, row: JSONObject) {
        startActivity(Intent(ctx, SessionReplayActivity::class.java).putExtra("ts", row.optLong("ts")))
    }

    private fun export(ctx: Context, store: LocalStore) {
        runCatching {
            val f = store.exportCsv()
            val share = Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
                .putExtra(Intent.EXTRA_SUBJECT, t("export_subject"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, t("export_title")))
        }.onFailure { Toast.makeText(ctx, t("export_failed"), Toast.LENGTH_LONG).show() }
    }
}
