package com.waterctl.android

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

/** 全局设置写入 Prefs，进行中的会话也会立即读取新值。 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        Ui.insets(root)
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(content)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.button(this, "‹") { finish() }.apply {
            textSize = 26f
            contentDescription = "返回主界面"
        }, LinearLayout.LayoutParams(dp(52), -2))
        bar.addView(Ui.text(this, "设置", 26f, bold = true).apply {
            setPadding(dp(16), 0, 0, 0)
        })
        content.addView(bar)
        content.addView(Ui.text(this, "按你的习惯，保留需要的信息", 14f, Ui.muted).apply {
            setPadding(0, dp(20), 0, dp(24))
        })
        content.addView(Ui.text(this, "运行日志", 18f, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        })
        val card = Ui.card(this)
        card.addView(Switch(this).apply {
            text = "记录发送的数据包"
            textSize = 16f
            setTextColor(Ui.ink)
            minimumHeight = dp(56)
            switchPadding = dp(12)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.accent, android.graphics.Color.rgb(110, 129, 126)),
            )
            isChecked = Prefs.logPackets(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setLogPackets(this@SettingsActivity, checked)
            }
        })
        card.addView(Ui.text(this,
            "开启后，在日志中显示以 TX 标记的发送数据。关闭可精简日志，接收的数据仍会显示。",
            14f, Ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(card)
        content.addView(Ui.text(this, "自动保存 · 立即生效，无需重新连接", 13f, Ui.accent).apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        })
        val privacy = Ui.card(this)
        privacy.addView(Ui.text(this, "数据留在本机", 16f, bold = true))
        privacy.addView(Ui.text(this, "应用只通过蓝牙与设备通信，不申请网络权限。设置与上次连接记录仅保存在本机。",
            14f, Ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(privacy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        return root
    }
}
