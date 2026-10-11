package com.spinecare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.spinecare.app.I18n.t

/** Plan: built-in camera-tracked exercises + the user's clinician-approved
 *  custom entries. Every exercise must be confirmed as clinician-approved
 *  before a session can start. */
class ExercisesFragment : Fragment() {
    private var container: FrameLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        FrameLayout(requireContext()).also { container = it; render() }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val box = container ?: return
        val ctx = requireContext()
        val store = LocalStore(ctx)
        val lang = I18n.lang
        val ui = Ui(ctx)
        box.removeAllViews(); box.addView(ui.root)

        ui.warn("⚠ " + t("approval_warning"))
        for (ex in Plan.all(ctx)) {
            val card = ui.card()
            ui.p(ex.name(lang), Ui.TEXT, 17f, card)
            val meta = if (ex.cameraTracked) t("meta_camera").format(t("view_" + ex.view), ex.targetReps)
                       else t("meta_manual").format(ex.targetSets, ex.targetReps)
            ui.muted(meta, card)
            ui.p(ex.description(lang), Ui.MUTED, 13f, card)
            if (ex.stops(lang).isNotEmpty()) ui.p(t("stop_if") + " " + ex.stops(lang).joinToString(), Ui.WARN, 12f, card)
            if (ex.videoUrl.isNotBlank()) ui.button(t("open_video"), card) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ex.videoUrl))) }
                    .onFailure { Toast.makeText(ctx, t("cannot_open"), Toast.LENGTH_SHORT).show() }
            }
            val approved = ex.custom || store.isApproved(ex.id)
            if (!ex.custom) {
                ui.add(CheckBox(ctx).apply {
                    text = t("approval_check"); setTextColor(Ui.TEXT); isChecked = approved
                    setOnCheckedChangeListener { _, v -> store.setApproved(ex.id, v); render() }
                }, card)
            } else ui.p("✓ " + t("approved"), Ui.OK, 12f, card)
            ui.button(if (ex.cameraTracked) t("start_guided") else t("start_manual"), card) {
                if (!(ex.custom || store.isApproved(ex.id))) {
                    AlertDialog.Builder(ctx).setTitle(t("approved_required")).setMessage(t("approval_warning"))
                        .setPositiveButton(t("ok"), null).show()
                    return@button
                }
                val cls = if (ex.cameraTracked) ExerciseActivity::class.java else ManualSessionActivity::class.java
                startActivity(Intent(ctx, cls).putExtra("exercise_id", ex.id))
            }.isEnabled = approved
            if (ex.custom) ui.button(t("delete_custom"), card) {
                AlertDialog.Builder(ctx).setMessage(t("delete_custom") + "?")
                    .setPositiveButton(t("delete")) { _, _ -> store.removeCustomExercise(ex.id); render() }
                    .setNegativeButton(t("cancel"), null).show()
            }
        }

        // Add a clinician-approved custom exercise (tracked with timer + set counter)
        val form = ui.card()
        ui.p(t("custom_title"), Ui.TEXT, 16f, form)
        ui.muted(t("custom_empty"), form)
        val name = ui.input(t("exercise_name"), into = form)
        val instr = ui.input(t("instructions"), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, into = form)
        val sets = ui.input(t("target_sets"), type = InputType.TYPE_CLASS_NUMBER, into = form)
        val reps = ui.input(t("target_reps"), type = InputType.TYPE_CLASS_NUMBER, into = form)
        val video = ui.input(t("video_url"), type = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT, into = form)
        val ok = ui.add(CheckBox(ctx).apply { text = t("approval_check"); setTextColor(Ui.TEXT) }, form)
        ui.button(t("save_exercise"), form) {
            val n = name.text.toString().trim().take(80)
            val d = instr.text.toString().trim().take(600)
            val url = video.text.toString().trim().take(300)
            if (n.isEmpty() || d.isEmpty() || !ok.isChecked) {
                Toast.makeText(ctx, t("custom_empty"), Toast.LENGTH_LONG).show(); return@button
            }
            if (url.isNotEmpty() && !url.startsWith("https://")) {
                Toast.makeText(ctx, t("video_https"), Toast.LENGTH_LONG).show(); return@button
            }
            store.addCustomExercise(Exercise.custom(n, d,
                (sets.text.toString().toIntOrNull() ?: 0).coerceIn(0, 99),
                (reps.text.toString().toIntOrNull() ?: 0).coerceIn(0, 999), url))
            Toast.makeText(ctx, t("saved_local"), Toast.LENGTH_SHORT).show()
            render()
        }
        ui.warn(t("reps_rule") + " " + t("stop_rule"))
    }

    override fun onDestroyView() { super.onDestroyView(); container = null }
}
