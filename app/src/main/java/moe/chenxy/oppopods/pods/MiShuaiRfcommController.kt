package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import moe.chenxy.oppopods.hook.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.MediaControl
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFCOMM controller for MiShuai earphones.
 *
 * Uses MiShuai's custom SPP protocol instead of OPPO's RFCOMM protocol.
 * Broadcasts the same OppoPodsAction intents so the hook layer works unchanged.
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object MiShuaiRfcommController {
    private const val TAG = "HybridPods-MiShuaiRfcomm"
    private const val AUTO_RECONNECT_DELAY_MS = 120_000L
    private const val POLL_INTERVAL_MS = 50L       // 50ms poll interval
    private const val HEARTBEAT_EVERY = 5          // heartbeat every 5 polls = 250ms
    private const val DISCONNECT_TIMEOUT = 20      // 20 polls × 50ms = 1s
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    private const val COMMAND_TIMEOUT_POLLS = 8    // 8 × 50ms = 400ms before retry
    private const val MAX_RETRIES = 3

    private val MI_SHUAI_SPP_UUID: UUID = UUID.fromString("158627bc-0547-8787-87ba-435ad8571238")

    // Basic objects
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private var receiverRegistered = false

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    private var currentEqPreset: Int = -1
    private var currentWorkMode: Int = MiShuaiPackets.WORK_MODE_MUSIC
    private var cachedDeviceName: String = ""
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownCaseCharging: Boolean = false

    // Command queue
    private val commandQueue = ArrayDeque<ByteArray>()
    private var pendingCommand: ByteArray? = null
    private var commandPollCount = 0
    private var commandRetryCount = 0

    // Poll chain state
    private var pollChainIndex = 0
    private var pollCount = 0
    private var batteryPollCount = 0

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
    )

    // Coroutine jobs
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var readerJob: kotlinx.coroutines.Job? = null
    private var pollJob: kotlinx.coroutines.Job? = null
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectPending = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    // ── UI event handling ────────────────────────────────────────────

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIAncStatus(currentAnc)
                if (::mDevice.isInitialized && isConnected) {
                    sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                }
            }
            OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                Log.i(TAG, "UI Closed")
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus()
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                val preset = intent.getIntExtra("preset", -1)
                if (preset in MiShuaiPackets.EQ_PRESETS) setEqPreset(preset)
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setWorkMode(if (enabled) MiShuaiPackets.WORK_MODE_GAME else MiShuaiPackets.WORK_MODE_MUSIC)
            }
        }
    }

    // ── Status snapshots & UI broadcasts ─────────────────────────────

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
            anc = currentAnc,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName.takeIf { it.isNotEmpty() },
            connected = isConnected && socket != null,
            connecting = connectionJob?.isActive == true,
        )
    }

    private fun currentConnectionState(): String = when {
        isConnected && socket != null && ::currentBatteryParams.isInitialized -> "connected"
        connectionJob?.isActive == true || reconnectPending -> "connecting"
        isConnected && socket != null -> "connecting"
        else -> "disconnected"
    }

    private fun changeUIConnectionState(state: String) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            putExtra("state", state)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putBatteryExtras(status)
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    // ── Connection management ────────────────────────────────────────

    fun connectPod(context: Context, device: BluetoothDevice, prefs: android.content.SharedPreferences, appRequested: Boolean = false) {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        pollJob?.cancel()
        closeSocketOnly()
        mContext = context
        mDevice = device
        cachedDeviceName = device.name ?: ""

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                this.addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true
        changeUIConnectionState("connecting")

        connectSpp(initialDelayMs = 500L)
    }

    private fun connectSpp(initialDelayMs: Long = 0L) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            if (!isConnected || !::mDevice.isInitialized) return@launch
            closeSocketOnly()
            try {
                val newSocket = mDevice.createRfcommSocketToServiceRecord(MI_SHUAI_SPP_UUID)
                newSocket.connect()
                socket = newSocket
                reconnectAttempts.set(0)
                reconnectPending = false
                Log.d(TAG, "SPP connected! uuid=$MI_SHUAI_SPP_UUID")

                changeUIConnectionState("connecting")
                startPacketReader(newSocket.inputStream)

                delay(300)
                // Send full-chain poll to get initial state
                sendPacketSafe(MiShuaiPackets.buildFullQuery())
                delay(100)
                // Start the poll chain
                startPollChain()
                startBatteryPolling()
            } catch (e: IOException) {
                Log.e(TAG, "SPP connect failed", e)
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    private fun startPollChain() {
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            pollChainIndex = 0
            while (isConnected) {
                if (pollChainIndex < MiShuaiPackets.POLL_CHAIN.size) {
                    val queryType = MiShuaiPackets.POLL_CHAIN[pollChainIndex]
                    sendPacketSafe(MiShuaiPackets.buildQuery(queryType.toByte()))
                    pollChainIndex++
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startBatteryPolling() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) {
                    sendPacketSafe(MiShuaiPackets.buildQuery(MiShuaiPackets.QUERY_BATTERY.toByte()))
                }
            }
        }
    }

    // ── Packet reader ────────────────────────────────────────────────

    private fun startPacketReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            var rTimeOut = 0
            try {
                while (isConnected) {
                    if (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            val packet = buffer.copyOfRange(0, bytesRead)
                            rTimeOut = 0
                            handleMiShuaiPacket(packet)
                        }
                    } else {
                        rTimeOut++
                        if (rTimeOut % HEARTBEAT_EVERY == 0) {
                            // Heartbeat - re-send current query if idle
                            if (pollChainIndex >= MiShuaiPackets.POLL_CHAIN.size) {
                                pollChainIndex = 0
                            }
                        }
                        if (rTimeOut >= DISCONNECT_TIMEOUT) {
                            Log.d(TAG, "Timeout, scheduling reconnect")
                            scheduleReconnect("timeout")
                            break
                        }
                    }
                    // Command queue: if pending command timed out, retry or skip
                    handleCommandTimeout()
                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "SPP read error", e)
                    scheduleReconnect("read error")
                }
            }
        }
    }

    // ── Packet handling ──────────────────────────────────────────────

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleMiShuaiPacket(packet: ByteArray) {
        Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")

        if (!MiShuaiParser.isValidResponse(packet)) {
            Log.d(TAG, "Invalid MiShuai packet: ${packet.toHexString(HexFormat.UpperCase)}")
            return
        }

        val responseType = MiShuaiParser.getResponseType(packet)

        when (responseType) {
            MiShuaiPackets.GET_BL_INFO -> handleQueryResponse(packet)
            MiShuaiPackets.NOISE_CONTROL -> handleAncResponse(packet)
            else -> Log.d(TAG, "Unhandled response type: 0x${responseType.toString(16)}")
        }
    }

    private fun handleQueryResponse(packet: ByteArray) {
        val subType = MiShuaiParser.getSubType(packet)
        when (subType) {
            MiShuaiPackets.QUERY_BATTERY -> {
                MiShuaiParser.parseBattery(packet)?.let { handleBatteryChanged(it) }
            }
            MiShuaiPackets.QUERY_ANC_SWITCH, MiShuaiPackets.QUERY_NOISE_DETAIL -> {
                MiShuaiParser.parseAncMode(packet)?.let { handleAncChanged(it) }
            }
            MiShuaiPackets.QUERY_DEVICE_NAME -> {
                MiShuaiParser.parseDeviceName(packet)?.let {
                    Log.d(TAG, "Device name: $it")
                    cachedDeviceName = it
                }
            }
            MiShuaiPackets.QUERY_EQ -> {
                MiShuaiParser.parseEqMode(packet)?.let {
                    Log.d(TAG, "EQ mode: $it")
                    if (it != currentEqPreset) {
                        currentEqPreset = it
                        changeUIEqPreset(it)
                    }
                }
            }
            MiShuaiPackets.QUERY_WORK_MODE -> {
                MiShuaiParser.parseWorkMode(packet)?.let {
                    Log.d(TAG, "Work mode: $it")
                    if (it != currentWorkMode) {
                        currentWorkMode = it
                        changeUIGameModeStatus(it == MiShuaiPackets.WORK_MODE_GAME)
                    }
                }
            }
            else -> Log.d(TAG, "Query response subType=0x${subType.toString(16)}")
        }
    }

    private fun handleAncResponse(packet: ByteArray) {
        MiShuaiParser.parseAncMode(packet)?.let { handleAncChanged(it) }
    }

    private fun handleAncChanged(mode: NoiseControlMode) {
        Log.d(TAG, "ANC mode received: $mode")
        currentAnc = MiShuaiPackets.mapAncToHyperOs(
            when (mode) {
                NoiseControlMode.OFF -> MiShuaiPackets.ANC_OFF
                NoiseControlMode.NOISE_CANCELLATION -> MiShuaiPackets.ANC_DEEP_ANC
                NoiseControlMode.TRANSPARENCY -> MiShuaiPackets.ANC_TRANSPARENCY
                NoiseControlMode.WIND_NR -> MiShuaiPackets.ANC_WIND_NR
                else -> MiShuaiPackets.ANC_OFF
            }
        )
        changeUIAncStatus(currentAnc)
    }

    // ── Battery handling ─────────────────────────────────────────────

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        val left = PodParams(
            result.left?.level ?: 0,
            result.left?.isCharging == true,
            result.left != null,
            0
        )
        val right = PodParams(
            result.right?.level ?: 0,
            result.right?.isCharging == true,
            result.right != null,
            0
        )
        val case = if (result.case != null) {
            lastKnownCaseBattery = result.case.level
            lastKnownCaseCharging = result.case.isCharging
            PodParams(result.case.level, result.case.isCharging, true, 0)
        } else {
            PodParams(lastKnownCaseBattery, lastKnownCaseCharging, false, 0)
        }

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                this.putExtra("address", mDevice.address)
                this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams, mDevice)
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected) left.battery
        else if (right.isConnected) right.battery
        else 0
    }

    // ── ANC control ──────────────────────────────────────────────────

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode
        val protocolMode = MiShuaiPackets.mapAncFromHyperOs(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiPackets.buildSetAnc(protocolMode), "anc control")
        }
        changeUIAncStatus(mode)
    }

    fun cycleAnc() {
        val cycle = listOf(4, 2, 3, 1)  // WindNR -> DeepANC -> Transparency -> Off
        val currentIndex = cycle.indexOf(currentAnc)
        val next = cycle[(currentIndex + 1).floorMod(cycle.size)]
        setANCMode(next)
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    // ── EQ control ───────────────────────────────────────────────────

    fun setEqPreset(preset: Int) {
        Log.d(TAG, "setEqPreset: $preset")
        currentEqPreset = preset
        changeUIEqPreset(preset)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiPackets.buildSetEq(preset.toByte()), "eq preset control")
        }
    }

    private fun changeUIEqPreset(preset: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            putExtra("preset", preset)
        }
    }

    // ── WorkMode (game/music) control ─────────────────────────────────

    fun setWorkMode(mode: Int) {
        Log.d(TAG, "setWorkMode: $mode")
        currentWorkMode = mode
        val isGameMode = mode == MiShuaiPackets.WORK_MODE_GAME
        changeUIGameModeStatus(isGameMode)
        // Send directly, bypass command queue to avoid blocking by higher-priority commands
        CoroutineScope(Dispatchers.IO).launch {
            val packet = MiShuaiPackets.buildSetWorkMode(mode.toByte())
            Log.d(TAG, "WorkMode packet: ${packet.joinToString(" ") { "%02X".format(it) }}")
            sendPacketSafe(packet, "work mode control")
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    // ── Query ────────────────────────────────────────────────────────

    fun queryStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiPackets.buildFullQuery(), "status query")
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiPackets.buildQuery(MiShuaiPackets.QUERY_BATTERY.toByte()), "battery query")
        }
    }

    // ── Command queue ────────────────────────────────────────────────

    private fun handleCommandTimeout() {
        val pending = pendingCommand ?: return
        commandPollCount++
        if (commandPollCount >= COMMAND_TIMEOUT_POLLS) {
            if (commandRetryCount < MAX_RETRIES) {
                commandRetryCount++
                Log.d(TAG, "Command timeout, retry $commandRetryCount")
                sendRawPacket(pending)
                commandPollCount = 0
            } else {
                Log.d(TAG, "Command max retries exceeded, skipping")
                pendingCommand = null
                commandPollCount = 0
                commandRetryCount = 0
                processNextCommand()
            }
        }
    }

    private fun processNextCommand() {
        if (commandQueue.isEmpty()) return
        pendingCommand = commandQueue.removeFirst()
        commandPollCount = 0
        commandRetryCount = 0
        pendingCommand?.let { sendRawPacket(it) }
    }

    // ── Packet sending ───────────────────────────────────────────────

    private fun sendPacketSafe(packet: ByteArray, requestReason: String? = null) {
        try {
            val currentSocket = socket ?: run {
                Log.w(TAG, "socket null: ${packet.toHexString(HexFormat.UpperCase)}")
                scheduleReconnect("socket null before send")
                return
            }
            currentSocket.outputStream.write(packet)
            currentSocket.outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
            scheduleReconnect("send error")
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sendRawPacket(packet: ByteArray) {
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Raw send failed", e)
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!isConnected || !::mDevice.isInitialized || mContext == null) return
        closeSocketOnly()
        reconnectPending = true
        if (immediate) {
            if (connectionJob?.isActive == true) return
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectPending = false
            connectSpp()
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectAttempts.incrementAndGet()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            reconnectJob = null
            reconnectPending = false
            connectSpp()
        }
    }

    private fun closeSocketOnly() {
        readerJob?.cancel()
        readerJob = null
        pollJob?.cancel()
        pollJob = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    // ── Disconnect ───────────────────────────────────────────────────

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        pollJob?.cancel()
        reconnectAttempts.set(0)
        reconnectPending = false
        closeSocketOnly()

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", device.address)
            }
            if (receiverRegistered) {
                it.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
        }

        mShowedConnectedToast = false
        currentAnc = 1
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        changeUIConnectionState("disconnected")
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    // ── Audio routing ────────────────────────────────────────────────

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) { e.printStackTrace() }
                    finally { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) { e.printStackTrace() }
                    finally { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)
    }

    // ── Media router ─────────────────────────────────────────────────

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            this@MiShuaiRfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        val executor = Executor { p0 -> CoroutineScope(Dispatchers.IO).launch { p0?.run() } }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        try { mediaRouter.unregisterRouteCallback(routeCallback) } catch (_: Exception) {}
    }
}
