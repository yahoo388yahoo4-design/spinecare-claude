package com.spinecare.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.spinecare.app.I18n.t

/** Today: safety notice, follow-up reminders, daily symptom check-in and recent activity. */
class HomeFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val store = LocalStore(ctx)
        val ui = Ui(ctx)

        val top = ui.row()
        ui.add(android.widget.TextView(ctx).apply {
            text = "SpineCare"; setTextColor(Ui.PRIMARY); textSize = 24f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }, top)
        ui.button(t("lang_switch"), top) { (activity as? MainActivity)?.toggleLanguage() }
        ui.muted(t("status_prototype"))
        ui.p(t("welcome"), Ui.TEXT, 18f)
        ui.p(t("intro"))

        val safety = ui.card()
        ui.p("⚕ " + t("safety"), Ui.TEXT, 16f, safety)
        ui.p(t("safety_body"), into = safety)
        ui.warn(t("red_flags"), safety)
        ui.button(t("got_it"), safety) {
            AlertDialog.Builder(ctx).setTitle(t("safety")).setMessage(t("safety_body") + "\n\n" + t("red_flags"))
                .setPositiveButton(t("got_it"), null).show()
        }

        ui.h(t("followup"))
        ui.p("• " + t("followup_mri"))
        ui.p("• " + t("followup_physio"))

        ui.h(t("plan"))
        val all = Plan.all(ctx)
        val approved = all.count { store.isApproved(it.id) || it.custom }
        ui.p(t("plan_status").format(approved, all.size))
        ui.muted(t("not_advice"))

        ui.h(t("daily_checkin"))
        val neck = ui.scale(t("neck_pain"), store.latestCheckin()?.optInt("neck_pain", 3) ?: 3)
        val arm = ui.scale(t("arm_symptoms"), store.latestCheckin()?.optInt("arm_symptoms", 2) ?: 2)
        val sleep = ui.scale(t("sleep_quality"), 6)
        val note = ui.input(t("note_hint"), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        ui.muted(t("pain_warning"))
        val status = ui.p("", Ui.OK)
        ui.button(t("save_checkin")) {
            store.saveCheckin("daily", neck.progress, arm.progress, sleep.progress, note.text.toString().trim())
            note.setText("")
            status.text = t("saved_local")
            Toast.makeText(ctx, t("saved_local"), Toast.LENGTH_SHORT).show()
        }

        ui.h(t("recent"))
        val recent = store.allSessions().takeLast(6).reversed()
        if (recent.isEmpty()) ui.p(t("no_sessions"))
        recent.forEach { r ->
            val name = Plan.byId(ctx, r.optString("exercise"))?.name(I18n.lang) ?: r.optString("exercise")
            ui.p("${r.optString("date")}  $name  ${t("reps")}=${r.optInt("reps")}  [${r.optString("source")}]", size = 13f)
        }

        val privacy = ui.card()
        ui.p(t("privacy"), Ui.TEXT, 16f, privacy)
        ui.muted(t("privacy_body"), privacy)
        return ui.root
    }
}
