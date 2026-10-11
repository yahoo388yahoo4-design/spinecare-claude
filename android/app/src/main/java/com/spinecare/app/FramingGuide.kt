package com.spinecare.app

import com.spinecare.app.I18n.t

/** Maps an exercise view to concrete camera-placement guidance, and framing
 *  issues to short corrections (English/Romanian). */
object FramingGuide {
    data class Guide(val headline: String, val steps: List<String>)

    fun forView(view: String): Guide = when (view) {
        "side" -> Guide(t("guide_side_title"), listOf(t("guide_side_1"), t("guide_side_2"), t("guide_side_3"), t("guide_light")))
        "front" -> Guide(t("guide_front_title"), listOf(t("guide_front_1"), t("guide_front_2"), t("guide_front_3")))
        "back" -> Guide(t("guide_back_title"), listOf(t("guide_back_1"), t("guide_back_2"), t("guide_light")))
        else -> Guide(t("guide_any_title"), listOf(t("guide_any_1")))
    }

    /** Human-readable correction for a framing issue such as "lsho:out_of_frame". */
    fun fixFor(issue: String): String {
        val part = issue.substringBefore(':')
        val body = bodyPart(part)
        return when {
            issue.endsWith(":low_conf") -> t("fix_low_conf").format(body)
            issue.endsWith(":out_of_frame") -> t("fix_out_of_frame").format(body)
            issue == "no_person" -> t("fix_no_person")
            else -> t("fix_generic")
        }
    }

    private fun bodyPart(name: String): String {
        val key = name.removePrefix("any_").removePrefix("l").removePrefix("r").let {
            if (name == "nose") "nose" else if (name.startsWith("any_")) name.removePrefix("any_") else it
        }
        return t("part_$key")
    }
}
