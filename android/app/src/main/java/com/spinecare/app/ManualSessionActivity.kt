package com.spinecare.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.spinecare.app.I18n.t

/** Timer + set counter session for custom clinician-approved exercises that
 *  the camera does not track, with optional before/after symptom check-ins. */
class ManualSessionActivity : AppCompatActivity() {
    private lateinit var exercise: Exercise
    private lateinit var store: LocalStore
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var sets = 0
    private var running = false
    private lateinit var timer: TextView
    private lateinit var setsView: TextView
    private lateinit var startBtn: Button

    private val tick = object : Runnable {
        override fun run() {
            val s = (System.currentTimeMillis() - startedAt) / 1000
            timer.text = "%02d:%02d".format(s / 60, s % 60)
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        store = LocalStore(this)
        exercise = Plan.byId(this, intent.getStringExtra("exercise_id") ?: "") ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ui = Ui(this)
        setContentView(ui.root)
        val lang = I18n.lang
        ui.h(exercise.name(lang))
        ui.p(exercise.description(lang))
        ui.muted(t("meta_manual").format(exercise.targetSets, exercise.targetReps))
        ui.warn(t("reps_rule") + " " + t("stop_rule"))
        timer = ui.p("00:00", Ui.OK, 40f)
        setsView = ui.p(t("sets_done").format(0, exercise.targetSets), Ui.TEXT, 18f)
        startBtn = ui.button(t("start")) {
            AlertDialog.Builder(this).setTitle(t("phase_before")).setMessage(t("symptom_question"))
                .setPositiveButton(t("ok")) { _, _ -> begin() }.setNegativeButton(t("skip")) { _, _ -> begin() }.show()
        }
        ui.button(t("set_done")) {
            if (!running) return@button
            sets++; setsView.text = t("sets_done").format(sets, exercise.targetSets)
        }
        ui.button(t("stop_save")) { finishSession(save = true) }
        ui.button(t("cancel_session")) { finishSession(save = false) }
        ui.warn(t("pain_warning"))
    }

    private fun begin() {
        if (running) return
        running = true; sets = 0; startedAt = System.currentTimeMillis()
        startBtn.isEnabled = false
        handler.post(tick)
    }

    private fun finishSession(save: Boolean) {
        if (!running) { finish(); return }
        running = false
        handler.removeCallbacks(tick)
        if (save) {
            val dur = (System.currentTimeMillis() - startedAt) / 1000
            store.saveSession(exercise.id, sets * exercise.targetReps, sets, dur, emptyMap(), emptyMap(), 0, "manual")
            Toast.makeText(this, t("saved_local"), Toast.LENGTH_SHORT).show()
            val ui = Ui(this)
            val neck = ui.scale(t("neck_pain"), 0)
            val arm = ui.scale(t("arm_symptoms"), 0)
            AlertDialog.Builder(this).setTitle(t("phase_after")).setView(ui.root)
                .setPositiveButton(t("save_checkin")) { _, _ -> store.saveCheckin("after", neck.progress, arm.progress, null, ""); finish() }
                .setNegativeButton(t("skip")) { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
        } else finish()
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
}
