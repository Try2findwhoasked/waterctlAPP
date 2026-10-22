package com.waterctl.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val MENU_SETTINGS = 1
        private const val MENU_ABOUT = 2

        /** 关于页展示的上游仓库地址 */
        private const val REPO_URL = "https://github.com/celesWuff/waterctl"
    }

    private var service: BleService? = null
    private var bound = false
    private var pendingScan = false
    private var scanning = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scanTimeout = Runnable {
        finishScan()
        deviceEmptyHint.text = "扫描未完成，请确认蓝牙已开启后重试"
    }

    private fun finishScan() {
        scanning = false
        uiHandler.removeCallbacks(scanTimeout)
        scanButton.text = "扫描设备"
        applyPhase(currentPhase, "")
    }

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var stopButton: Button
    private lateinit var logText: TextView
    private lateinit var deviceList: ListView
    private lateinit var deviceEmptyHint: TextView
    private lateinit var logContainer: ScrollView
    private lateinit var logToggle: Button
    private lateinit var quickConnectButton: Button

    private var logCollapsed = true

    // last successfully started device, persisted across launches
    private var lastAddress: String = ""
    private var lastName: String = ""

    private val deviceNames = ArrayList<String>()
    private val deviceAddresses = ArrayList<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var selectedIndex = -1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BleService.LocalBinder).getService()
            bound = true
            refreshStatus()
            // if the user already tapped scan, continue once bound
            if (pendingScan && hasScanPermissions()) {
                pendingScan = false
                doScan()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.ACTION_STATE -> {
                    val phase = intent.getStringExtra(BleService.EXTRA_PHASE) ?: "idle"
                    val name = intent.getStringExtra(BleService.EXTRA_DEVICE_NAME) ?: ""
                    applyPhase(phase, name)
                }

                BleService.ACTION_LOG -> {
                    val msg = intent.getStringExtra(BleService.EXTRA_MESSAGE) ?: ""
                    appendLog(msg)
                }

                BleService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(BleService.EXTRA_MESSAGE) ?: ""
                    appendLog("[错误] $msg")
                    if (scanning) {
                        finishScan()
                        deviceEmptyHint.text = "扫描失败\n$msg\n请检查蓝牙与权限后重试"
                    }
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                }

                BleService.ACTION_SCAN_RESULT -> {
                    finishScan()
                    val addresses =
                        intent.getStringArrayListExtra(BleService.EXTRA_ADDRESSES) ?: ArrayList()
                    val names =
                        intent.getStringArrayListExtra(BleService.EXTRA_NAMES) ?: ArrayList()
                    deviceNames.clear()
                    deviceAddresses.clear()
                    for (i in 0 until minOf(addresses.size, names.size)) {
                        deviceNames.add("${names[i]}\n${addresses[i]}")
                        deviceAddresses.add(addresses[i])
                    }
                    selectedIndex = -1
                    deviceList.clearChoices()
                    deviceEmptyHint.text = "未发现附近设备\n请靠近水控器，确认设备已开启后重新扫描"
                    updateDeviceList()
                    appendLog("发现 ${addresses.size} 台设备")
                }
            }
        }
    }

    // 旧系统分支不支持接收器标记；API 33+ 已显式限制为应用内部广播。
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLastDevice()
        logCollapsed = savedInstanceState?.getBoolean("logCollapsed", true) ?: true
        setContentView(buildLayout())
        applyPhase("idle", "")

        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_STATE)
            addAction(BleService.ACTION_LOG)
            addAction(BleService.ACTION_ERROR)
            addAction(BleService.ACTION_SCAN_RESULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    // ---- layout ----

    private var currentPhase = "idle"
    private var currentDevice = ""
    private lateinit var deviceCount: TextView

    private fun dp(value: Int) = Ui.dp(this, value)

    // 触摸监听只协调嵌套滚动并返回 false，点击仍交由原生控件处理。
    @SuppressLint("ClickableViewAccessibility")
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        Ui.insets(root)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        scroll.addView(content)

        val titleBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(Ui.text(this, "蓝牙水控器", 26f, bold = true))
        title.addView(Ui.text(this, "连接设备 · 轻松用水", 13f, Ui.muted))
        titleBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        titleBar.addView(Ui.button(this, "⋮") { }.apply {
            textSize = 24f
            contentDescription = "更多选项：设置与关于"
            setOnClickListener { showOverflowMenu(it) }
        }, LinearLayout.LayoutParams(dp(52), -2))
        content.addView(titleBar, margin(bottom = 24))

        statusCard = Ui.card(this)
        statusCard.addView(Ui.text(this, "连接状态", 12f, Ui.muted, true))
        statusText = Ui.text(this, "", 25f, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        deviceText = Ui.text(this, "", 14f, Ui.muted)
        statusCard.addView(statusText)
        statusCard.addView(deviceText)
        content.addView(statusCard, margin(bottom = 12))

        quickConnectButton = Ui.button(this, quickConnectLabel(), primary = true) { quickConnect() }
        content.addView(quickConnectButton, margin(bottom = 20))

        val deviceHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        deviceCount = Ui.text(this, "附近设备", 18f, bold = true)
        deviceHeader.addView(deviceCount, LinearLayout.LayoutParams(0, -2, 1f))
        scanButton = Ui.button(this, "扫描设备") { startScan() }
        deviceHeader.addView(scanButton)
        content.addView(deviceHeader, margin(bottom = 10))
        val listWrapper = Ui.card(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        deviceAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, deviceNames) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    minimumHeight = dp(80)
                    background = Ui.shape(this@MainActivity,
                        if (position == selectedIndex) Color.rgb(227, 242, 238) else Color.WHITE, 14)
                }
                row.addView(Ui.text(this@MainActivity,
                    (if (position == selectedIndex) "✓  " else "") + deviceNames[position].substringBefore("\n"),
                    16f, if (position == selectedIndex) Ui.accent else Ui.ink, true))
                row.addView(Ui.text(this@MainActivity, deviceAddresses[position], 12f, Ui.muted))
                return row
            }
        }
        deviceList = ListView(this).apply {
            divider = ColorDrawable(Ui.border)
            dividerHeight = dp(1)
            adapter = deviceAdapter
            choiceMode = ListView.CHOICE_MODE_SINGLE
        }
        deviceList.setOnItemClickListener { _, _, position, _ ->
            if (currentPhase == "idle" && position in deviceAddresses.indices) {
                selectedIndex = position
                deviceList.setItemChecked(position, true)
                deviceAdapter.notifyDataSetChanged()
                applyPhase(currentPhase, "")
            }
        }
        // 列表自行滚动，外层页面仍可在小屏、横屏或大字体下访问全部操作。
        deviceList.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        listWrapper.addView(deviceList, LinearLayout.LayoutParams(-1, dp(240)))
        deviceEmptyHint = Ui.text(this, "还没有发现设备\n点击「扫描设备」，查找附近的蓝牙水控器", 14f, Ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(32), dp(12), dp(32))
        }
        listWrapper.addView(deviceEmptyHint)
        content.addView(listWrapper, margin(bottom = 12))
        startButton = Ui.button(this, "连接所选设备", primary = true) { startSelected() }
        content.addView(startButton, margin(bottom = 8))
        val actions = LinearLayout(this)
        endButton = Ui.button(this, "结束会话", danger = true) { endSession() }
        stopButton = Ui.button(this, "停止重连", danger = true) { hardStopSession() }
        actions.addView(endButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        content.addView(actions, margin(bottom = 20))

        val logHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(Ui.text(this, "运行日志", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        logToggle = Ui.button(this, "展开") { toggleLog() }
        logHeader.addView(logToggle)
        content.addView(logHeader, margin(bottom = 10))
        logContainer = ScrollView(this).apply {
            background = Ui.shape(this@MainActivity, Color.rgb(25, 47, 49), 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        logText = Ui.text(this, "日志将在扫描或连接后显示", 12f, Color.rgb(204, 230, 220)).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logContainer.addView(logText)
        content.addView(logContainer, LinearLayout.LayoutParams(-1, dp(200)))
        logContainer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        logContainer.visibility = if (logCollapsed) View.GONE else View.VISIBLE
        logToggle.text = if (logCollapsed) "展开" else "收起"
        content.addView(Ui.text(this, "仅使用蓝牙连接 · 数据保存在本机", 12f, Ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        updateDeviceList()
        return root
    }

    private fun margin(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(bottom)
    }

    private fun updateDeviceList() {
        deviceAdapter.notifyDataSetChanged()
        deviceList.visibility = if (deviceNames.isEmpty()) View.GONE else View.VISIBLE
        deviceEmptyHint.visibility = if (deviceNames.isEmpty()) View.VISIBLE else View.GONE
        deviceCount.text = if (deviceNames.isEmpty()) "附近设备" else "附近设备 · ${deviceNames.size}"
        deviceList.layoutParams.height = dp(80 * deviceNames.size.coerceIn(1, 3))
        deviceList.requestLayout()
        applyPhase(currentPhase, "")
    }

    private fun toggleLog() {
        logCollapsed = !logCollapsed
        logContainer.visibility = if (logCollapsed) View.GONE else View.VISIBLE
        logToggle.text = if (logCollapsed) "展开" else "收起"
    }

    private fun applyPhase(phase: String, name: String) {
        currentPhase = phase
        if (name.isNotEmpty()) currentDevice = name
        val label = when (phase) {
            "idle" -> "准备连接"
            "starting" -> "正在连接…"
            "active" -> "正在用水"
            "recovering" -> "正在重连…"
            "stopping" -> "正在结束…"
            else -> phase
        }
        val color = when (phase) {
            "starting", "recovering" -> Color.rgb(151, 94, 18)
            "stopping" -> Color.rgb(170, 57, 57)
            else -> Ui.accent
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusCard.background = Ui.shape(this,
            if (phase == "active") Color.rgb(222, 242, 233) else Color.WHITE, stroke = Ui.border)
        deviceText.text = when {
            phase != "idle" -> currentDevice.ifEmpty { "正在与设备建立连接" }
            selectedIndex in deviceNames.indices -> "已选择：${deviceNames[selectedIndex].substringBefore("\n")}"
            else -> "扫描附近设备，或一键连接上次设备"
        }
        val busy = phase != "idle"
        endButton.isEnabled = busy
        stopButton.isEnabled = phase == "recovering"
        scanButton.isEnabled = !busy && !scanning
        deviceList.isEnabled = !busy && !scanning
        startButton.isEnabled = !busy && !scanning && selectedIndex in deviceAddresses.indices
        quickConnectButton.isEnabled = !busy && !scanning && lastAddress.isNotEmpty()
    }

    // ---- lifecycle ----

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("logCollapsed", logCollapsed)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, BleService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- scan flow (every branch logs so problems are visible) ----

    private fun startScan() {
        if (scanning || currentPhase != "idle") return
        appendLog("→ 开始扫描流程")
        // Android 12+ 读取蓝牙状态本身也需要 CONNECT 权限，必须先申请。
        if (!hasScanPermissions()) {
            pendingScan = true
            requestScanPermissions()
            return
        }
        if (!ensureBluetoothEnabled()) {
            appendLog("蓝牙未开启，请开启后重试")
            return
        }
        appendLog("→ 蓝牙已开启")
        if (!ensureLocationEnabled()) {
            appendLog("位置服务未开启，无法扫描 BLE")
            return
        }
        appendLog("→ 位置服务正常")
        if (hasScanPermissions()) {
            doScan()
        } else {
            pendingScan = true
            requestScanPermissions()
            appendLog("→ 正在请求权限…")
        }
    }

    private fun doScan() {
        val svc = service
        if (svc == null) {
            appendLog("服务未连接，正在重新绑定…")
            pendingScan = true
            try {
                bindService(
                    Intent(this, BleService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (e: Exception) {
                appendLog("绑定失败: ${e.message}")
            }
            return
        }
        scanning = true
        scanButton.text = "正在扫描…"
        deviceEmptyHint.text = "正在查找附近设备…\n请将手机靠近水控器，扫描约需 3 秒"
        applyPhase(currentPhase, "")
        uiHandler.postDelayed(scanTimeout, 5_000)
        appendLog("→ 调用扫描")
        svc.scan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (!pendingScan) return
            pendingScan = false
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ) {
                appendLog("→ 权限已授予，继续扫描")
                startScan()
            } else {
                appendLog("权限被拒绝，无法扫描")
            }
        }
    }

    private fun ensureBluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) {
            appendLog("此设备不支持蓝牙")
            return false
        }
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun ensureLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            true
        }
    }

    private fun hasScanPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestScanPermissions() {
        val needed = LinkedHashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        requestPermissions(needed.toTypedArray(), 1)
    }

    private fun startSelected() {
        val idx = selectedIndex
        if (idx < 0 || idx >= deviceAddresses.size) {
            appendLog("请先点击选择一台设备")
            return
        }
        val displayName = deviceNames[idx].substringBefore("\n")
        saveLastDevice(deviceAddresses[idx], displayName)
        quickConnectButton.text = quickConnectLabel()
        startSessionService(deviceAddresses[idx], displayName)
    }

    /**
     * Start the session through [startForegroundService] instead of the local
     * binder.
     *
     * A service that is only created by [bindService] is destroyed by the
     * platform the instant the last client unbinds - which happens as soon as
     * the activity is stopped, i.e. when the screen turns off. That silently
     * killed the GATT link, the wake lock and every reconnect timer.
     * [startForegroundService] turns the service into a *started* foreground
     * service that outlives the activity.
     */
    private fun startSessionService(address: String, name: String) {
        requestBatteryOptimizationExemption()
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.ACTION_START
            putExtra(BleService.EXTRA_ADDRESS, address)
            putExtra(BleService.EXTRA_DEVICE, name)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            appendLog("启动前台服务失败: ${e.message}")
            // fall back to the already-bound instance
            service?.start(address, name)
        }
    }

    /**
     * Doze / app-standby throttling is what usually kills long-lived BLE links on
     * OEM ROMs. Ask once for an exemption so the foreground service keeps its
     * timers and radio access while the screen is off.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
            appendLog("→ 已请求忽略电池优化（建议允许，否则锁屏后可能被系统限制）")
        } catch (e: Exception) {
            appendLog("无法请求电池优化豁免: ${e.message}")
        }
    }

    /** End the session via the service so it also works if the binder is gone. */
    private fun endSession() {
        val svc = service
        if (svc != null) {
            svc.end()
        } else {
            try {
                startService(
                    Intent(this, BleService::class.java).apply {
                        action = BleService.ACTION_END
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Hard-stop the session / reconnect loop. */
    private fun hardStopSession() {
        val svc = service
        if (svc != null) {
            svc.stopReconnect()
        } else {
            try {
                startService(
                    Intent(this, BleService::class.java).apply {
                        action = BleService.ACTION_STOP
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun loadLastDevice() {
        lastAddress = Prefs.lastAddress(this)
        lastName = Prefs.lastName(this)
    }

    private fun saveLastDevice(address: String, name: String) {
        lastAddress = address
        lastName = name
        Prefs.saveLastDevice(this, address, name)
    }

    private fun quickConnectLabel(): String =
        if (lastAddress.isEmpty()) {
            "暂无上次连接记录"
        } else {
            "连接上次设备 · ${lastName.ifEmpty { lastAddress }}"
        }

    /** Start a session with the previously used device without scanning again. */
    private fun quickConnect() {
        if (lastAddress.isEmpty()) {
            appendLog("还没有上次连接记录，请先扫描并开始一次会话")
            return
        }
        appendLog("→ 一键连接上次设备 $lastName ($lastAddress)")
        startSessionService(lastAddress, lastName)
    }

    // ---- overflow menu (设置 / 关于) ----

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SETTINGS, 0, "设置")
        popup.menu.add(0, MENU_ABOUT, 1, "关于")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SETTINGS -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                MENU_ABOUT -> {
                    showAboutDialog()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val message = buildString {
            append("蓝牙水控器 FOSS 安卓原生版\n")
            append("版本 $version\n\n")
            append("协议移植自 celesWuff/waterctl（TypeScript）的 2.x 分支，")
            append("以 Kotlin 重写，不含任何第三方运行时依赖。\n\n")
            append("免责声明\n")
            append("• 本程序仅供对已获授权的水控设备进行操作，请遵守设备管理方的规定。\n")
            append("• 请勿用于任何非法用途；因使用本程序造成的设备损坏、费用损失")
            append("或其它后果，由使用者自行承担。\n")
            append("• 开始使用即表示您已理解并接受上述条款。\n\n")
            append("许可证：MIT\n")
            append("仓库：$REPO_URL")
        }
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun refreshStatus() {
        val p = service?.getPhase()?.asStr ?: "idle"
        applyPhase(p, service?.getDeviceName().orEmpty())
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = logText.text?.toString().orEmpty()
                .takeUnless { it == "日志将在扫描或连接后显示" }.orEmpty()
            val lines = current.split("\n").filter { it.isNotEmpty() }.takeLast(199)
            logText.text = (lines + msg).joinToString("\n")
            // keep the newest line visible
            logContainer.post { logContainer.fullScroll(View.FOCUS_DOWN) }
        }
    }
}