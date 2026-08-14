package com.mishuaipods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.mishuaipods.BuildConfig
import com.mishuaipods.hook.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@SuppressLint("MissingPermission", "StaticFieldLeak")
object SppController {
    private const val TAG = "SppController"
    private const val AUTO_RECONNECT_DELAY_MS = 120_000L
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    /** 帧头固定 5 字节: 00 27 02 00 len，整帧 = 5 + len */
    private const val FRAME_HEADER_SIZE = 5

    /** 控制广播签名权限 */
    const val CONTROL_PERMISSION = "com.mishuaipods.permission.CONTROL"

    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice

    var isConnected = false
        private set
    var currentBattery = BatteryResult(0, 0, 0)
        private set
    var hasBatteryData = false
        private set
    var currentAnc: NoiseControlMode = NoiseControlMode.NC_OFF
        private set
    var currentWorkMode: Byte = MiShuaiProtocol.WORK_MUSIC
        private set
    var currentDeviceName: String = ""
        private set
    private var cachedDeviceName: String = ""
    private var receiverRegistered = false
    private var batteryPollJob: Job? = null

    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var readerJob: Job? = null

    /** 主动断开标记，置位后不再自动重连 */
    private var disconnectRequested = false

    data class StatusSnapshot(
        val battery: BatteryResult?,
        val anc: NoiseControlMode,
        val workMode: Byte,
        val address: String?,
        val deviceName: String,
        val connected: Boolean,
        val connecting: Boolean
    )

    fun currentStatusSnapshot(): StatusSnapshot = StatusSnapshot(
        battery = currentBattery,
        anc = currentAnc,
        workMode = currentWorkMode,
        address = if (::mDevice.isInitialized) mDevice.address else null,
        deviceName = currentDeviceName.ifEmpty { cachedDeviceName },
        connected = isConnected && socket != null,
        connecting = isConnected && socket == null
    )

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            intent?.let { handleUIEvent(it) }
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                changeUIBatteryStatus(currentBattery)
                changeUIAncStatus(currentAnc)
                changeUIWorkMode(currentWorkMode)
                if (::mDevice.isInitialized && socket != null) {
                    sendAppStatusBroadcast(ACTION_PODS_CONNECTED) {
                        putExtra("address", mDevice.address)
                        putExtra("device_name", currentDeviceName.ifEmpty { cachedDeviceName })
                    }
                }
            }
            ACTION_ANC_SELECT -> {
                val mode = intent.getIntExtra("status", 0)
                setAncMode(NoiseControlMode.fromIndex(mode))
            }
            ACTION_REFRESH_STATUS -> {
                queryAllStatus()
            }
            ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            ACTION_GAME_MODE_SET -> {
                // Game mode is LOCAL ONLY - no SPP command
                val enabled = intent.getBooleanExtra("enabled", false)
                val newMode = if (enabled) MiShuaiProtocol.WORK_GAME else MiShuaiProtocol.WORK_MUSIC
                currentWorkMode = newMode
                changeUIWorkMode(newMode)
                Log.d(TAG, "Work mode: ${if (enabled) "game" else "music"}")
            }
            ACTION_REQUEST_CONNECT -> {
                if (socket == null) {
                    val ctx = mContext ?: return
                    autoConnect(ctx)
                }
            }
        }
    }

    fun autoConnect(context: Context) {
        Log.i(TAG, "autoConnect: finding paired MiShuai device")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = MiShuaiDeviceDetector.findPaired(adapter)
        if (device == null) {
            Log.w(TAG, "No paired MiShuai device found")
            return
        }
        connectPod(context, device)
    }

    fun connectPod(context: Context, device: BluetoothDevice) {
        Log.i(TAG, "connectPod: ${device.name} (${device.address})")
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        batteryPollJob?.cancel()
        closeSocket()

        mContext = context.applicationContext
        mDevice = device
        cachedDeviceName = device.name ?: ""
        disconnectRequested = false

        ensureReceiverRegistered()

        isConnected = true
        changeUIConnectionState("connecting")

        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(300)
                Log.d(TAG, "Connecting SPP...")
                val newSocket = MiShuaiDeviceDetector.connectSpp(device)
                if (newSocket == null) {
                    Log.e(TAG, "SPP connect failed")
                    changeUIConnectionState("error")
                    scheduleReconnect("connect failed")
                    return@launch
                }
                if (disconnectRequested) {
                    try { newSocket.close() } catch (_: IOException) {}
                    return@launch
                }
                socket = newSocket
                Log.d(TAG, "SPP connected!")
                changeUIConnectionState("connected")

                startPacketReader(newSocket.inputStream)
                delay(500)
                queryAllStatus()
                startBatteryPolling()
            } catch (e: IOException) {
                Log.e(TAG, "SPP error: ${e.message}")
                closeSocket()
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    fun disconnect() {
        disconnectRequested = true
        isConnected = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        batteryPollJob?.cancel()
        closeSocket()
        currentAnc = NoiseControlMode.NC_OFF
        currentWorkMode = MiShuaiProtocol.WORK_MUSIC
        currentDeviceName = ""
        hasBatteryData = false
        changeUIConnectionState("disconnected")
    }

    fun setAncMode(mode: NoiseControlMode) {
        Log.d(TAG, "setAncMode: $mode")
        currentAnc = mode
        changeUIAncStatus(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiProtocol.buildCommand(mode))
        }
    }

    fun cycleAnc() {
        val cycle = listOf(
            NoiseControlMode.DEEP_ANC,
            NoiseControlMode.TRANSPARENCY,
            NoiseControlMode.WIND_NR,
            NoiseControlMode.NC_OFF
        )
        val currentIndex = cycle.indexOf(currentAnc)
        val next = cycle[(currentIndex + 1).floorMod(cycle.size)]
        setAncMode(next)
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    fun queryAllStatus() {
        Log.d(TAG, "queryAllStatus")
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_BATTERY))
            delay(100)
            sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_NAME))
            delay(100)
            sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_NC_STATUS))
        }
    }

    private fun startBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (socket != null) {
                    sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_BATTERY))
                    delay(100)
                    // 顺带同步触控切换的降噪状态
                    sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_NC_STATUS))
                }
            }
        }
    }

    private fun startPacketReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(256)
            val accumulator = ByteArrayOutputStream(64)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        accumulator.write(buffer, 0, bytesRead)
                        parseAccumulatedFrames(accumulator)
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) Log.e(TAG, "Read error: ${e.message}")
            }
            if (isConnected) handleLinkDrop()
        }
    }

    /** SPP 流分帧：按包头长度字段累积解析，兼容半包/粘包 */
    private fun parseAccumulatedFrames(accumulator: ByteArrayOutputStream) {
        val buf = accumulator.toByteArray()
        var offset = 0
        while (true) {
            if (buf.size - offset < FRAME_HEADER_SIZE) break
            if (buf[offset] != 0x00.toByte() ||
                buf[offset + 1] != 0x27.toByte() ||
                buf[offset + 2] != 0x02.toByte()
            ) {
                // 数据错位，逐字节重同步
                offset++
                continue
            }
            val len = buf[offset + 4].toInt() and 0xFF
            val frameSize = FRAME_HEADER_SIZE + len
            if (buf.size - offset < frameSize) break // 帧未收全，等待后续数据
            handleResponse(buf.copyOfRange(offset, offset + frameSize))
            offset += frameSize
        }
        if (offset > 0) {
            val remaining = buf.copyOfRange(offset, buf.size)
            accumulator.reset()
            accumulator.write(remaining)
        }
    }

    private fun handleResponse(data: ByteArray) {
        if (data.size < 7) return
        if (data[0] != 0x00.toByte() || data[1] != 0x27.toByte()) return
        if (data[2] != 0x02.toByte()) return

        val type = data[5].toInt() and 0xFF
        Log.d(TAG, "Response type=${String.format("%02X", type)}")

        when (type) {
            0x01 -> parseBatteryResponse(data)
            0x03 -> parseNameResponse(data)
            0x07 -> parseNoiseStatusResponse(data)
        }
    }

    private fun parseBatteryResponse(data: ByteArray) {
        if (data.size < 10) return
        // 0x80 为充电标志，低 7 位是电量
        val left = (data[7].toInt() and 0x7F).coerceIn(0, 100)
        val right = (data[8].toInt() and 0x7F).coerceIn(0, 100)
        val case = (data[9].toInt() and 0x7F).coerceIn(0, 100)
        Log.i(TAG, "Battery: L=$left R=$right C=$case")
        currentBattery = BatteryResult(left, right, case)
        hasBatteryData = true
        changeUIBatteryStatus(currentBattery)
    }

    private fun parseNameResponse(data: ByteArray) {
        val name = buildString {
            for (i in 7 until data.size) {
                val c = data[i].toInt() and 0xFF
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }.trim()
        if (name.isNotEmpty()) {
            currentDeviceName = name
            Log.i(TAG, "Device name: $name")
        }
    }

    private fun parseNoiseStatusResponse(data: ByteArray) {
        if (data.size < 8) return
        val mode = NoiseControlMode.fromByte(data[7])
        Log.i(TAG, "Noise mode: ${mode.label}")
        currentAnc = mode
        changeUIAncStatus(mode)
    }

    private fun sendPacketSafe(packet: ByteArray) {
        try {
            val currentSocket = socket ?: return
            currentSocket.outputStream.write(packet)
            currentSocket.outputStream.flush()
            Log.d(TAG, "Sent: ${MiShuaiProtocol.bytesToHex(packet)}")
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
        }
    }

    /** 意外断线（非主动断开）时关闭并重连 */
    private fun handleLinkDrop() {
        Log.w(TAG, "SPP link dropped")
        closeSocket()
        if (!disconnectRequested && isConnected) {
            changeUIConnectionState("error")
            scheduleReconnect("link dropped")
        } else {
            isConnected = false
            changeUIConnectionState("disconnected")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!isConnected || !::mDevice.isInitialized || mContext == null || disconnectRequested) return
        Log.d(TAG, "schedule reconnect: $reason")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            if (isConnected && !disconnectRequested) {
                connectPod(mContext!!, mDevice)
            }
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        val ctx = mContext ?: return
        ctx.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(ACTION_PODS_UI_INIT)
                addAction(ACTION_ANC_SELECT)
                addAction(ACTION_REFRESH_STATUS)
                addAction(ACTION_CYCLE_ANC)
                addAction(ACTION_GAME_MODE_SET)
                addAction(ACTION_REQUEST_CONNECT)
            },
            CONTROL_PERMISSION,
            null,
            Context.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun currentConnectionState(): String = when {
        isConnected && socket != null -> "connected"
        isConnected -> "connecting"
        else -> "disconnected"
    }

    private fun changeUIConnectionState(state: String) {
        sendAppStatusBroadcast(ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", currentDeviceName.ifEmpty { cachedDeviceName })
            }
            putExtra("state", state)
        }
    }

    private fun changeUIBatteryStatus(battery: BatteryResult) {
        sendAppStatusBroadcast(ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putExtra("left_battery", battery.left)
            putExtra("right_battery", battery.right)
            putExtra("case_battery", battery.caseVal)
        }
    }

    private fun changeUIAncStatus(mode: NoiseControlMode) {
        sendAppStatusBroadcast(ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putExtra("status", mode.ordinal)
        }
    }

    private fun changeUIWorkMode(mode: Byte) {
        sendAppStatusBroadcast(ACTION_PODS_WORK_MODE_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putExtra("mode", mode.toInt())
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            // 限制接收方必须持有 CONTROL 权限
            ctx.sendBroadcast(this, CONTROL_PERMISSION)
        }
    }

    const val ACTION_PODS_UI_INIT = "com.mishuaipods.action.PODS_UI_INIT"
    const val ACTION_PODS_UI_CLOSED = "com.mishuaipods.action.PODS_UI_CLOSED"
    const val ACTION_ANC_SELECT = "com.mishuaipods.action.ANC_SELECT"
    const val ACTION_REFRESH_STATUS = "com.mishuaipods.action.REFRESH_STATUS"
    const val ACTION_CYCLE_ANC = "com.mishuaipods.action.CYCLE_ANC"
    const val ACTION_GAME_MODE_SET = "com.mishuaipods.action.GAME_MODE_SET"
    const val ACTION_REQUEST_CONNECT = "com.mishuaipods.action.REQUEST_CONNECT"
    const val ACTION_PODS_CONNECTED = "com.mishuaipods.action.PODS_CONNECTED"
    const val ACTION_PODS_DISCONNECTED = "com.mishuaipods.action.PODS_DISCONNECTED"
    const val ACTION_PODS_CONNECTION_STATE_CHANGED = "com.mishuaipods.action.CONNECTION_STATE_CHANGED"
    const val ACTION_PODS_BATTERY_CHANGED = "com.mishuaipods.action.BATTERY_CHANGED"
    const val ACTION_PODS_ANC_CHANGED = "com.mishuaipods.action.ANC_CHANGED"
    const val ACTION_PODS_WORK_MODE_CHANGED = "com.mishuaipods.action.WORK_MODE_CHANGED"
}
