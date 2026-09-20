package com.waterctl.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "waterctl"
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_NAME = "last_name"
    }

    private var service: BleService? = null
    private var bound = false
    private var pendingScan = false

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

    private var logCollapsed = false

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
            if (pendingScan) {
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
                }

                BleService.ACTION_SCAN_RESULT -> {
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
                    deviceAdapter.notifyDataSetChanged()
                    deviceEmptyHint.visibility =
                        if (deviceNames.isEmpty()) View.VISIBLE else View.GONE
                    appendLog("发现 ${addresses.size} 台设备")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLastDevice()
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

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        // ---- status card ----
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.parseColor("#FFFFFF"))
        }
        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        deviceText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusText)
        statusCard.addView(deviceText)
        root.addView(
            statusCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )

        // ---- action buttons (2 x 2) ----
        scanButton = actionButton("扫描设备") { startScan() }
        startButton = actionButton("开始会话") { startSelected() }
        endButton = actionButton("结束会话") { service?.end() }
        stopButton = actionButton("停止重连") { service?.stopReconnect() }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(scanButton, weightParams())
        row1.addView(startButton, weightParams(leftMargin = 8))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(endButton, weightParams(topMargin = 8))
        row2.addView(stopButton, weightParams(topMargin = 8, leftMargin = 8))
        root.addView(row2)

        // ---- one-tap reconnect to the previously used device ----
        quickConnectButton = actionButton(quickConnectLabel()) { quickConnect() }
        root.addView(
            quickConnectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        // ---- device list header ----
        root.addView(sectionHeader("可用设备"), topMargin(dp(14)))

        // ---- device list ----
        val listWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#FFFFFF"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_activated_1,
            deviceNames,
        )
        deviceList = ListView(this).apply {
            divider = ColorDrawable(Color.parseColor("#EEEEEE"))
            dividerHeight = dp(1)
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setSelector(ColorDrawable(Color.parseColor("#BBDEFB")))
        }
        deviceList.adapter = deviceAdapter
        deviceList.setOnItemClickListener { _, _, position, _ ->
            if (position in deviceAddresses.indices) {
                selectedIndex = position
                deviceList.setItemChecked(position, true)
                deviceText.text = "已选择: ${deviceNames[position].replace("\n", " ")}"
            }
        }
        deviceEmptyHint = TextView(this).apply {
            text = "还没有设备，先点击「扫描设备」"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(24), 0, dp(24))
        }
        listWrapper.addView(deviceList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        listWrapper.addView(deviceEmptyHint)
        root.addView(
            listWrapper,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f),
        )

        // ---- log header with collapse toggle ----
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(
            sectionHeader("日志"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        logToggle = Button(this).apply {
            text = "收起"
            textSize = 12f
            setOnClickListener { toggleLog() }
        }
        logHeader.addView(logToggle)
        root.addView(logHeader, topMargin(dp(14)))

        // ---- log ----
        logContainer = ScrollView(this).apply {
            background = rounded(Color.parseColor("#FFFFFF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        logText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#424242"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        logContainer.addView(logText)
        root.addView(
            logContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f),
        )

        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            minHeight = dp(44)
            setOnClickListener { onClick() }
        }

    private fun weightParams(
        topMargin: Int = 0,
        leftMargin: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            this.topMargin = topMargin
            this.leftMargin = leftMargin
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

    private fun toggleLog() {
        logCollapsed = !logCollapsed
        logContainer.visibility = if (logCollapsed) View.GONE else View.VISIBLE
        logToggle.text = if (logCollapsed) "展开" else "收起"
    }

    // ---- phase / status rendering ----

    private fun applyPhase(phase: String, name: String) {
        val label = when (phase) {
            "idle" -> "空闲"
            "starting" -> "连接中…"
            "active" -> "会话进行中"
            "recovering" -> "正在重连…"
            "stopping" -> "正在结束…"
            else -> phase
        }
        val color = when (phase) {
            "active" -> "#2E7D32"
            "starting", "recovering" -> "#EF6C00"
            "stopping" -> "#C62828"
            else -> "#212121"
        }
        statusText.text = "状态: $label"
        statusText.setTextColor(Color.parseColor(color))
        statusCard.background = rounded(Color.parseColor("#FFFFFF"))
        if (name.isNotEmpty()) deviceText.text = "设备: $name"

        val busy = phase != "idle"
        endButton.isEnabled = busy
        stopButton.isEnabled = busy
        scanButton.isEnabled = phase == "idle"
        startButton.isEnabled = phase == "idle"
        quickConnectButton.isEnabled = phase == "idle" && lastAddress.isNotEmpty()
    }

    // ---- lifecycle ----

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
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- scan flow (every branch logs so problems are visible) ----

    private fun startScan() {
        appendLog("→ 开始扫描流程")
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
                doScan()
            } else {
                appendLog("权限被拒绝，无法扫描")
            }
        }
    }

    private fun ensureBluetoothEnabled(): Boolean {
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
        service?.start(deviceAddresses[idx], displayName)
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadLastDevice() {
        val p = prefs()
        lastAddress = p.getString(KEY_LAST_ADDRESS, "").orEmpty()
        lastName = p.getString(KEY_LAST_NAME, "").orEmpty()
    }

    private fun saveLastDevice(address: String, name: String) {
        lastAddress = address
        lastName = name
        prefs().edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_NAME, name)
            .apply()
    }

    private fun quickConnectLabel(): String =
        if (lastAddress.isEmpty()) {
            "一键连接上次设备"
        } else {
            "一键连接: $lastName"
        }

    /** Start a session with the previously used device without scanning again. */
    private fun quickConnect() {
        if (lastAddress.isEmpty()) {
            appendLog("还没有上次连接记录，请先扫描并开始一次会话")
            return
        }
        appendLog("→ 一键连接上次设备 $lastName ($lastAddress)")
        service?.start(lastAddress, lastName)
    }

    private fun refreshStatus() {
        val p = service?.getPhase()?.asStr ?: "idle"
        applyPhase(p, "")
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = logText.text?.toString().orEmpty()
            val lines = current.split("\n").filter { it.isNotEmpty() }.takeLast(200)
            logText.text = (lines + msg).joinToString("\n")
            // keep the newest line visible
            logContainer.post { logContainer.fullScroll(View.FOCUS_DOWN) }
        }
    }
}