package com.waterctl.android

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置页。目前只有一个开关：是否在日志中显示本机发送的数据包。
 *
 * 所有设置都是全局的（不区分设备），写入 [Prefs]；[BleService] 每次发包前重新读取
 * 偏好，因此切换后立即生效，进行中的会话也无需重连。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun rounded(bgColor: Int, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /**
     * targetSdk 35 起系统强制「边到边」绘制，内容会延伸进状态栏（刘海 / 挖孔所在区域）
     * 与导航栏。这里把系统安全区换算成根布局的内边距，避免顶部被遮挡。
     */
    private fun applySystemInsets(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                l = bars.left
                t = bars.top
                r = bars.right
                b = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                l = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                t = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                r = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                b = insets.systemWindowInsetBottom
            }
            v.setPadding(left + l, top + t, right + r, bottom + b)
            insets
        }
        view.requestApplyInsets()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        applySystemInsets(root, dp(16), dp(16), dp(16), dp(16))

        // ---- back bar ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "← 返回"
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(
            TextView(this).apply {
                text = "设置"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                setPadding(dp(8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(bar)

        // ---- packet logging ----
        root.addView(sectionHeader("数据包"), topMargin(dp(18)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(Color.parseColor("#FFFFFF"))
        }
        card.addView(
            CheckBox(this).apply {
                text = "在日志中显示发送的数据包"
                textSize = 15f
                isChecked = Prefs.logPackets(this@SettingsActivity)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setLogPackets(this@SettingsActivity, checked)
                }
            },
        )
        card.addView(
            TextView(this).apply {
                text = "开启后，日志会以 TX 前缀显示本机发出的每一条数据包（十六进制）。" +
                    "关闭后不再记录，日志更简洁；接收到的数据包始终显示。"
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 0, 0, dp(8))
            },
        )
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        return root
    }

    private fun sectionHeader(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#616161"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private fun topMargin(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = margin }
}