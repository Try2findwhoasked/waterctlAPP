package com.waterctl.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 两个页面共享色彩、触控反馈与安全区，避免系统默认控件混入不同的视觉风格。 */
internal object Ui {
    val ink = Color.rgb(24, 52, 55)
    val muted = Color.rgb(92, 115, 118)
    val accent = Color.rgb(0, 112, 104)
    val canvas = Color.rgb(242, 247, 246)
    val border = Color.rgb(219, 232, 229)

    fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int, radius: Int = 20, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            stroke?.let { setStroke(dp(context, 1), it) }
        }

    fun text(context: Context, label: String, size: Float = 14f, color: Int = ink,
             bold: Boolean = false) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(context, 2).toFloat(), 1f)
    }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dp(context, 20)
        setPadding(padding, padding, padding, padding)
        background = shape(context, Color.WHITE, stroke = border)
    }

    fun button(context: Context, label: String, primary: Boolean = false,
               danger: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(context, 52)
        minimumHeight = dp(context, 52)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        stateListAnimator = null
        val foreground = if (primary) Color.WHITE else if (danger) Color.rgb(170, 57, 57) else accent
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(133, 152, 150), foreground),
        ))
        val fills = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(context, Color.rgb(232, 239, 237), 14))
            addState(intArrayOf(), shape(context,
                if (primary) accent else if (danger) Color.rgb(255, 240, 237) else Color.rgb(227, 242, 238), 14))
        }
        background = RippleDrawable(ColorStateList.valueOf(0x22007068), fills, shape(context, Color.WHITE, 14))
        setOnClickListener { onClick() }
    }

    fun insets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }
}
