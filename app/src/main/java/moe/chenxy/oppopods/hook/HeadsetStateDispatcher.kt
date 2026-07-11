package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.DeviceType
import moe.chenxy.oppopods.pods.MiShuaiRfcommController
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("HybridPods", "AdapterService.onCreate hook skipped", it)
        }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                Log.d("HybridPods", "A2DP Connection State: $currState, isSupportedPod ${isSupportedPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                if (!isSupportedPod(device)) return@post

                val deviceType = DeviceType.detect(device.name ?: "")
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    when (deviceType) {
                        DeviceType.MI_SHUAI -> MiShuaiRfcommController.connectPod(context, device, prefs)
                        else -> RfcommController.connectPod(context, device, prefs)
                    }
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    when (deviceType) {
                        DeviceType.MI_SHUAI -> MiShuaiRfcommController.disconnectedPod(context, device)
                        else -> RfcommController.disconnectedPod(context, device)
                    }
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_UI_INIT,
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("HybridPods", "connect request from app device=${device.name}/${device.address}")
                        val deviceType = DeviceType.detect(device.name ?: "")
                        when (deviceType) {
                            DeviceType.MI_SHUAI -> MiShuaiRfcommController.connectPod(context, device, prefs, appRequested = true)
                            else -> RfcommController.connectPod(context, device, prefs, appRequested = true)
                        }
                    }
                    OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("HybridPods", "disconnect request from app device=${device.name}/${device.address}")
                        val deviceType = DeviceType.detect(device.name ?: "")
                        when (deviceType) {
                            DeviceType.MI_SHUAI -> MiShuaiRfcommController.disconnectedPod(context, device)
                            else -> RfcommController.disconnectedPod(context, device)
                        }
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * Detect supported earphones (OPPO or MiShuai) by device name.
     */
    @SuppressLint("MissingPermission")
    fun isSupportedPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return DeviceType.isSupported(name)
    }
}
