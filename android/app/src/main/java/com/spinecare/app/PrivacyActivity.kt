package com.spinecare.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.spinecare.app.I18n.t

/** Privacy policy screen. Also the target Health Connect opens to explain why
 *  the app asks for steps/sleep (required for its permission dialog). */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val ui = Ui(this)
        ui.h(t("privacy"))
        ui.p(t("privacy_body"))
        ui.p(t("remote_privacy"))
        ui.p(t("hc_rationale"))
        ui.p(t("mediapipe_metrics"))
        ui.p(t("record_privacy"))
        ui.button(t("ok")) { finish() }
        setContentView(ui.root)
    }
}
