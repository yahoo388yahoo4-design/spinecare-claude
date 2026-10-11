package com.spinecare.app

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Small builder for the programmatic screens (one look across tabs). */
class Ui(val ctx: Context) {
    val root = ScrollView(ctx).apply { setBackgroundColor(BG) }
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 48) }

    init { root.addView(col) }

    fun <T : View> add(v: T, into: ViewGroup = col): T { into.addView(v); return v }

    fun h(t: String, into: ViewGroup = col) = add(TextView(ctx).apply {
        text = t; setTextColor(PRIMARY); textSize = 20f; setPadding(0, 24, 0, 8)
    }, into)

    fun p(t: String, color: Int = TEXT, size: Float = 14f, into: ViewGroup = col) = add(TextView(ctx).apply {
        text = t; setTextColor(color); textSize = size; setPadding(0, 4, 0, 4)
    }, into)

    fun muted(t: String, into: ViewGroup = col) = p(t, MUTED, 12f, into)
    fun warn(t: String, into: ViewGroup = col) = p(t, BAD, 13f, into)

    fun button(t: String, into: ViewGroup = col, onClick: (Button) -> Unit) = add(Button(ctx).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick(this) }
    }, into)

    fun input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, into: ViewGroup = col) =
        add(EditText(ctx).apply {
            this.hint = hint; setText(value); inputType = type
            setTextColor(TEXT); setHintTextColor(MUTED)
        }, into)

    /** 0..10 slider with a live value label. */
    fun scale(label: String, initial: Int, into: ViewGroup = col): SeekBar {
        val tv = p("$label: $initial", into = into)
        return add(SeekBar(ctx).apply {
            max = 10; progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { tv.text = "$label: $v" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, into)
    }

    fun card(into: ViewGroup = col): LinearLayout = add(LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(SURFACE); setPadding(24, 16, 24, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 12; bottomMargin = 12 }
    }, into)

    fun row(into: ViewGroup = col): LinearLayout = add(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }, into)

    companion object {
        const val BG = 0xFF0B1220.toInt()
        const val SURFACE = 0xFF121A2B.toInt()
        const val PRIMARY = 0xFF38BDF8.toInt()
        const val OK = 0xFF34D399.toInt()
        const val WARN = 0xFFFBBF24.toInt()
        const val BAD = 0xFFF87171.toInt()
        const val TEXT = 0xFFE5E7EB.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
    }
}
