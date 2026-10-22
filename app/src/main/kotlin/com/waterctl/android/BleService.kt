package com.waterctl.android

import android.Manifest
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.UUID
import com.waterctl.android.protocol.BA_ACK
import com.waterctl.android.protocol.END_EPILOGUE
import com.waterctl.android.protocol.END_PROLOGUE
import com.waterctl.android.protocol.OFFLINEBOMB_FIX
import com.waterctl.android.protocol.START_PROLOGUE
import com.waterctl.android.protocol.bufferToHexString
import com.waterctl.android.protocol.makeStartEpilogue
import com.waterctl.android.protocol.makeUnlockResponse

// Android BLE port of the water-heater session state machine (see
// https://github.com/celesWuff/waterctl/blob/2.x/src/bluetooth.ts).
// Runs inside a ForegroundService so the
// BLE link stays alive while the screen is off / app is in background.
class BleService : Service() {

    companion object {
        const val ACTION_STATE = "com.waterctl.android.STATE"
        const val ACTION_LOG = "com.waterctl.android.LOG"
        const val ACTION_ERROR = "com.waterctl.android.ERROR"
        const val ACTION_SCAN_RESULT = "com.waterctl.android.SCAN_RESULT"

        // Commands delivered through onStartCommand. Using an Intent (instead of
        // the local binder alone) is what makes this a *started* foreground
        // service. A purely bound service is destroyed by the platform the moment
        // the activity unbinds - which happens on screen-off - so the BLE link,
        // wake lock and reconnect timers all died with it.
        const val ACTION_START = "com.waterctl.android.START"
        const val ACTION_END = "com.waterctl.android.END"
        const val ACTION_STOP = "com.waterctl.android.STOP"

        const val EXTRA_PHASE = "phase"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ADDRESSES = "addresses"
        const val EXTRA_NAMES = "names"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_DEVICE = "device"

        const val CHANNEL_ID = "waterctl_session"

        const val NOTIFICATION_ID = 1

        private val TXD_UUID: UUID = uuid16(0xf1f1)
        private val RXD_UUID: UUID = uuid16(0xf1f2)
        private val CCCD_UUID: UUID = uuid16(0x2902)

        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val CONNECTION_PROBE_INTERVAL_MS = 60_000L
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val START_EPILOGUE_DELAY_MS = 500L

        private fun uuid16(v: Int): UUID {
            // 16-bit Bluetooth UUID -> 128-bit: 0000XXXX-0000-1000-8000-00805f9b34fb
            return UUID.fromString(
                java.lang.String.format(
                    "0000%04x-0000-1000-8000-00805f9b34fb",
                    v,
                ),
            )
        }
    }

    enum class Phase(val asStr: String) {
        Idle("idle"),
        Starting("starting"),
        Active("active"),
        Recovering("recovering"),
        Stopping("stopping"),
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val binder = LocalBinder()

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null

    @Volatile
    private var phase: Phase = Phase.Idle

    @Volatile
    private var deviceName: String = ""

    @Volatile
    private var deviceAddress: String = ""

    @Volatile
    private var attempts: Int = 0

    // active GATT object for the current (re)connection attempt
    private var gatt: BluetoothGatt? = null

    private var txd: BluetoothGattCharacteristic? = null
    private var rxd: BluetoothGattCharacteristic? = null

    // deadlines / pending work serialised on the main looper
    private var startEpiloguePending: Boolean = false
    private var stopping: Boolean = false

    // tracked so we never call startForeground() twice for the same promotion
    private var isForeground: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null

    private val reconnectRunnable = Runnable { attemptConnection() }

    private val handshakeTimeoutRunnable = Runnable {
        if (stopping) {
            emitLog("no response while ending, disconnecting")
            teardownGatt()
            finishSession()
        } else {
            emitLog("handshake timeout")
            onLinkLost()
        }
    }

    private val probeRunnable = object : Runnable {
        override fun run() {
            if (phase == Phase.Active) {
                // BluetoothGatt keeps its connection state; no cheap async probe
                // on Android, so we rely on the connection state cache. If the
                // cached gatt reports disconnected we treat it as lost.
                val g = gatt
                if (g == null) {
                    emitLog("fallback probe: connection lost")
                    onLinkLost()
                } else {
                    handler.postDelayed(this, CONNECTION_PROBE_INTERVAL_MS)
                }
            }
        }
    }

    private val startEpilogueRunnable = Runnable {
        if (!startEpiloguePending) return@Runnable
        startEpiloguePending = false
        val g = gatt ?: return@Runnable
        val t = txd ?: return@Runnable
        val payload = makeStartEpilogue(deviceName, false)
        emitTxLog("start epilogue", payload)
        write(g, t, payload)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
                if (address.isNullOrEmpty()) {
                    emitError("缺少设备地址")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Promote to foreground FIRST. startForegroundService() gives us
                // only ~5s to call startForeground(); doing it before any early
                // -return path guarantees we never miss that deadline.
                deviceAddress = address
                deviceName = name
                startForegroundNotification()
                startSession(address, name)
            }

            ACTION_END -> {
                end()
                // If there was nothing to end (idle), do not leave an empty
                // started service behind.
                if (phase == Phase.Idle) stopSelf(startId)
            }

            ACTION_STOP -> {
                stopReconnect()
                if (phase == Phase.Idle) stopSelf(startId)
            }

            else -> Unit
        }
        // No sticky restart: a BLE session cannot be resumed after the process is
        // gone, so a platform-recreated service has nothing useful to do.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The service is started and foreground; swiping the app off the recents
        // list must not end the running session.
        emitLog("app removed from recents; session keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    // ---- public API ----

    fun scan() {
        val adapter = bluetoothAdapter
        val s = if (adapter != null && adapter.isEnabled) {
            adapter.bluetoothLeScanner
        } else {
            null
        }
        if (s == null) {
            emitError("蓝牙不可用或未开启")
            return
        }
        emitLog("扫描中...")
        val results = LinkedHashMap<String, String>() // address -> name
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val r = result ?: return
                val name = r.scanRecord?.deviceName ?: try {
                    r.device.name ?: "未知设备"
                } catch (_: SecurityException) {
                    "未知设备"
                }
                results[r.device.address] = name
            }

            override fun onScanFailed(errorCode: Int) {
                emitError("扫描失败 (code $errorCode)")
            }
        }
        try {
            s.startScan(cb)
        } catch (e: SecurityException) {
            emitError("缺少定位权限，无法扫描")
            return
        }
        handler.postDelayed({
            try {
                s.stopScan(cb)
            } catch (_: SecurityException) {
                emitError("蓝牙扫描权限已撤销")
            } catch (_: Exception) {
            }
            val addresses = ArrayList(results.keys)
            val names = ArrayList(results.values)
            val intent = Intent(ACTION_SCAN_RESULT)
            intent.putStringArrayListExtra(EXTRA_ADDRESSES, addresses)
            intent.putStringArrayListExtra(EXTRA_NAMES, names)
            broadcast(intent)
            emitLog("扫描完成，发现 ${addresses.size} 台设备")
        }, 3_000)
    }

    fun start(address: String, name: String) = startSession(address, name)

    /**
     * Enter the foreground and begin a session. Must be called from the main
     * thread; the first statement raises the notification so the platform's 5s
     * `startForegroundService` deadline is respected.
     */
    private fun startSession(address: String, name: String) {
        if (phase == Phase.Active || phase == Phase.Starting) {
            emitError("已有会话进行中")
            return
        }
        // Reset leftover state from a previous session, but do NOT call
        // stopReconnect()/stopSelf() here: the service is about to be reused and
        // stopping it would tear down the foreground notification we need next.
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(probeRunnable)
        handler.removeCallbacks(startEpilogueRunnable)
        startEpiloguePending = false
        teardownGatt()
        deviceAddress = address
        deviceName = name
        attempts = 0
        stopping = false
        startForegroundNotification()
        acquireWakeLock()
        attemptConnection()
    }

    /**
     * Request to end the session.
     *
     * * [Phase.Active] -> negotiate a graceful shutdown (B3 prologue / B4 epilogue).
     * * Starting / Recovering / Stopping -> no live session to negotiate with, so
     *   tear everything down immediately.
     *
     * The previous implementation only handled [Phase.Active]; clicking "end" while
     * connecting or while waiting for a reconnect did nothing at all and left the
     * reconnect timer armed, so the session silently came back to life.
     */
    fun end() {
        when (phase) {
            Phase.Active -> {
                stopping = true
                setPhase(Phase.Stopping)
                cancelHandshake()
                scheduleHandshakeTimeout()
                val g = gatt
                val t = txd
                if (g != null && t != null) {
                    emitTxLog("end prologue", END_PROLOGUE)
                    write(g, t, END_PROLOGUE)
                } else {
                    emitLog("no live link while ending, disconnecting")
                    abortSession()
                }
            }

            Phase.Starting, Phase.Recovering, Phase.Stopping -> {
                emitLog("aborting session (phase=${phase.asStr})")
                abortSession()
            }

            Phase.Idle -> Unit
        }
    }

    /** Hard stop: cancels every timer, drops the link and stops the service. */
    fun stopReconnect() = abortSession()

    private fun abortSession() {
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(probeRunnable)
        handler.removeCallbacks(startEpilogueRunnable)
        startEpiloguePending = false
        teardownGatt()
        finishSession()
    }

    fun getPhase(): Phase = phase

    fun getDeviceName(): String = deviceName

    // ---- connection flow ----

    private fun attemptConnection() {
        if (stopping) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            emitError("蓝牙连接权限已撤销，请重新授予权限")
            finishSession()
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            // Bluetooth may simply be toggling; reschedule instead of dropping the
            // loop, otherwise the phase would stay Recovering forever and no
            // further attempt would ever be made (UI stuck on "正在重连…").
            emitError("蓝牙未开启，稍后重试")
            scheduleReconnect()
            return
        }
        setPhase(Phase.Starting)

        cancelHandshake()
        scheduleHandshakeTimeout()

        try {
            gatt?.close()
        } catch (_: SecurityException) {
            emitError("蓝牙连接权限已撤销")
            finishSession()
            return
        }
        gatt = null
        txd = null
        rxd = null

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            // A malformed address is permanent: retrying forever would just keep
            // the session in Recovering, so tear the session down instead.
            emitError("无效的设备地址: ${e.message}")
            finishSession()
            return
        }

        emitLog("connecting to $deviceName (${deviceAddress})...")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: SecurityException) {
            emitError("蓝牙连接权限已撤销")
            finishSession()
        } catch (e: Exception) {
            emitLog("connect failed: ${e.message}")
            onLinkLost()
        }
    }

    private fun onConnected(g: BluetoothGatt) {
        if (g !== gatt) return // stale callback from a superseded link
        emitLog("connected, discovering services...")
        try {
            if (!g.discoverServices()) {
                emitLog("discoverServices returned false")
                onLinkLost()
            }
        } catch (_: SecurityException) {
            emitError("蓝牙连接权限已撤销")
            finishSession()
        }
    }

    private fun onServicesDiscovered(g: BluetoothGatt) {
        if (g !== gatt) return // stale callback from a superseded link
        var foundTxd: BluetoothGattCharacteristic? = null
        var foundRxd: BluetoothGattCharacteristic? = null
        for (s: BluetoothGattService in g.services) {
            for (c: BluetoothGattCharacteristic in s.characteristics) {
                when (c.uuid) {
                    TXD_UUID -> foundTxd = c
                    RXD_UUID -> foundRxd = c
                }
            }
        }
        if (foundTxd == null || foundRxd == null) {
            emitLog("找不到 TXD/RXD 特征")
            onLinkLost()
            return
        }
        txd = foundTxd
        rxd = foundRxd

        // 订阅期间用户仍可能撤销权限，异步回调也必须处理。
        try {
            g.setCharacteristicNotification(foundRxd, true)
            val cccd = foundRxd.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                // no CCCD: proceed anyway
                afterSubscribed(g)
            }
            emitLog("connect+subscribe ok ($deviceName)")
        } catch (_: SecurityException) {
            emitError("蓝牙连接权限已撤销")
            finishSession()
        }
    }

    private fun afterSubscribed(g: BluetoothGatt) {
        if (g !== gatt) return // stale callback from a superseded link
        if (stopping || phase == Phase.Stopping || phase == Phase.Idle) {
            emitLog("stale subscribe event ignored (phase=${phase.asStr})")
            return
        }
        val t = txd
        if (t == null) {
            onLinkLost()
            return
        }
        emitTxLog("start prologue", START_PROLOGUE)
        write(g, t, START_PROLOGUE)
    }

    private fun onLinkLost() {
        if (stopping || phase == Phase.Stopping) {
            // We are already shutting the session down: a link loss is expected.
            teardownGatt()
            finishSession()
            return
        }
        teardownGatt()
        // Drop every timer that belonged to the dead link. The handshake timeout
        // is only cancelled once B2 arrives, so without this a stale 15s timeout
        // could fire *during* the backoff wait, bump `attempts` and push the next
        // reconnect into a longer slot (also logging a bogus "handshake timeout").
        cancelHandshake()
        handler.removeCallbacks(startEpilogueRunnable)
        handler.removeCallbacks(probeRunnable)
        startEpiloguePending = false
        scheduleReconnect()
    }

    /**
     * Arm the next reconnect attempt with exponential backoff. Shared by
     * [onLinkLost] and the "adapter unavailable" path in [attemptConnection] so
     * the recovery loop can never be silently dropped while [phase] stays
     * [Phase.Recovering].
     */
    private fun scheduleReconnect() {
        attempts += 1
        setPhase(Phase.Recovering)
        val delay = nextReconnectDelay(attempts)
        emitLog("reconnect attempt $attempts scheduled in ${delay}ms")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun nextReconnectDelay(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var ms = RECONNECT_BASE_MS
        var i = 1
        while (i < attempt) {
            ms *= 2
            if (ms >= RECONNECT_MAX_MS) return RECONNECT_MAX_MS
            i++
        }
        return ms
    }

    private fun finishSession() {
        cancelHandshake()
        handler.removeCallbacks(probeRunnable)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(startEpilogueRunnable)
        startEpiloguePending = false
        stopping = false
        teardownGatt()
        releaseWakeLock()
        // Remove the foreground notification explicitly; otherwise the "session
        // running" notification keeps lingering after the session is over and the
        // app looks like it never exited.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        isForeground = false
        stopSelf()
        setPhase(Phase.Idle)
        emitLog("session finished")
    }

    private fun setPhase(p: Phase) {
        if (phase != p) {
            phase = p
            val intent = Intent(ACTION_STATE)
            intent.putExtra(EXTRA_PHASE, p.asStr)
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName)
            broadcast(intent)

            // update foreground notification text
            if (p == Phase.Active || p == Phase.Starting || p == Phase.Recovering && !stopping) {
                updateForegroundNotification()
            }
        }
    }

    private fun teardownGatt() {
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
            // 权限撤销后系统负责释放链路，仍需清空本地引用。
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: SecurityException) {
            // 不让清理失败阻止本地状态复位。
        } catch (_: Exception) {
        }
        gatt = null
        txd = null
        rxd = null
    }

    private fun cancelHandshake() {
        handler.removeCallbacks(handshakeTimeoutRunnable)
    }

    private fun scheduleHandshakeTimeout() {
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = value
            g.writeCharacteristic(c)
        } catch (_: SecurityException) {
            emitError("蓝牙连接权限已撤销")
            finishSession()
        } catch (e: Exception) {
            emitError("写入失败: ${e.message}")
            onLinkLost()
        }
    }

    // ---- RXD dispatch (mirror of the upstream handleRxd) ----

    private fun handleRxd(value: ByteArray) {
        emitLog("RXD: ${bufferToHexString(value)}")

        val payload = value.toMutableList()

        // firmware sends "AT+STAS?" via RXD; ignore it.
        if (payload.getOrNull(0) == 0x41.toByte() &&
            payload.getOrNull(1) == 0x54.toByte() &&
            payload.getOrNull(2) == 0x2b.toByte()
        ) {
            return
        }

        val first = payload.getOrNull(0)?.toInt()?.and(0xff)
        if (first == null || (first != 0xfd && first != 0x09)) {
            emitError("WATERCTL INTERNAL Unknown RXD data")
            return
        }

        // [0xFD, 0x09, ...] => [0xFD, 0xFD, 0x09, ...]
        if (payload.getOrNull(1)?.toInt()?.and(0xff) == 0x09) {
            payload.add(0, 0xfd.toByte())
        }
        // [0x09, ...] => [0xFD, 0xFD, 0x09, ...]
        if (payload[0].toInt() and 0xff == 0x09) {
            payload.add(0, 0xfd.toByte())
            payload.add(0, 0xfd.toByte())
        }

        if (payload.size < 4) return

        val dtype = payload[3].toInt() and 0xff
        val g = gatt ?: return
        val t = txd ?: return

        when (dtype) {
            0xB0, 0xB1 -> {
                // start prologue ok; delay 500ms for key auth (AE) on new firmware
                startEpiloguePending = true
                handler.removeCallbacks(startEpilogueRunnable)
                handler.postDelayed(startEpilogueRunnable, START_EPILOGUE_DELAY_MS)
            }

            0xAE -> {
                val response = try {
                    makeUnlockResponse(payload.toByteArray(), deviceName)
                } catch (e: Exception) {
                    emitError(e.message ?: "Bad unlock request")
                    return
                }
                emitTxLog("unlock response", response)
                write(g, t, response)
                startEpiloguePending = false
                handler.removeCallbacks(startEpilogueRunnable)
            }

            0xAF -> when (payload.getOrNull(5)?.toInt()?.and(0xff)) {
                0x55 -> {
                    val epilogue = makeStartEpilogue(deviceName, true)
                    emitTxLog("start epilogue", epilogue)
                    write(g, t, epilogue)
                }

                0x01, 0x02, 0x04 -> {
                    emitError("WATERCTL INTERNAL Bad key")
                }

                else -> {
                    val epilogue = makeStartEpilogue(deviceName, true)
                    emitTxLog("start epilogue", epilogue)
                    write(g, t, epilogue)
                    emitError("WATERCTL INTERNAL Unknown RXD data")
                }
            }

            0xB2 -> {
                // session live
                attempts = 0
                stopping = false
                cancelHandshake()
                setPhase(Phase.Active)
                handler.removeCallbacks(probeRunnable)
                handler.postDelayed(probeRunnable, CONNECTION_PROBE_INTERVAL_MS)
            }

            0xB3 -> {
                emitTxLog("end epilogue", END_EPILOGUE)
                write(g, t, END_EPILOGUE)
                teardownGatt()
                finishSession()
            }

            0xAA, 0xB5, 0xB8 -> Unit

            0xBA -> {
                emitTxLog("BA ack", BA_ACK)
                write(g, t, BA_ACK)
            }

            0xBC -> {
                emitTxLog("offlinebomb fix", OFFLINEBOMB_FIX)
                write(g, t, OFFLINEBOMB_FIX)
            }

            0xC8 -> emitError("WATERCTL INTERNAL Refused")

            else -> emitError("WATERCTL INTERNAL Unknown RXD data")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog("connection state change: status=$status newState=$newState")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.post { onConnected(g) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.post {
                        if (g === gatt) {
                            if (stopping || phase == Phase.Stopping) {
                                emitLog("link lost while ending")
                                teardownGatt()
                                finishSession()
                            } else if (phase == Phase.Active || phase == Phase.Starting ||
                                phase == Phase.Recovering
                            ) {
                                emitLog("link lost")
                                onLinkLost()
                            }
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onServicesDiscovered(g)
                } else {
                    emitLog("service discovery failed (status=$status)")
                    onLinkLost()
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            handler.post {
                if (descriptor.uuid == CCCD_UUID) {
                    afterSubscribed(g)
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post {
                // A notification queued on the old GATT can land after we have
                // already swapped in a new link; acting on it would write stale
                // handshake data to the fresh connection.
                if (g === gatt) handleRxd(value)
            }
        }
    }

    // ---- notifications / wakelock ----

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "水控器会话",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙水控器")
            .setContentText("会话进行中：$deviceName")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun updateForegroundNotification() {
        // minimal: just re-post with current text
        try {
            startForegroundNotification()
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "waterctl:ble_session",
            )
        }
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun broadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun emitLog(message: String) {
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_MESSAGE, message)
        broadcast(intent)
    }

    /**
     * Log an outgoing packet. Suppressed entirely when the user turns off
     * "设置 → 在日志中显示发送的数据包"; the preference is re-read on every call so
     * toggling it takes effect immediately, even mid-session.
     */
    private fun emitTxLog(label: String, payload: ByteArray) {
        if (!Prefs.logPackets(this)) return
        emitLog("TX $label: ${bufferToHexString(payload)}")
    }

    private fun emitError(message: String) {
        val intent = Intent(ACTION_ERROR)
        intent.putExtra(EXTRA_MESSAGE, message)
        broadcast(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        teardownGatt()
        releaseWakeLock()
        super.onDestroy()
    }
}