package com.mishuaipods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.mishuaipods.pods.MiShuaiDeviceDetector
import com.mishuaipods.pods.SppController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object HeadsetStateDispatcher {
    private const val TAG = "HeadsetStateDispatcher"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking Bluetooth A2DP for MiShuai device dispatch")
        val classLoader = param.defaultClassLoader

        try {
            val clazz = classLoader.loadClass("com.android.bluetooth.a2dp.A2dpService")
            val method = clazz.getDeclaredMethod("handleConnectionStateChanged",
                BluetoothDevice::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            module.hook(method).intercept { chain ->
                try {
                    val args = chain.args
                    if (args.size >= 3) {
                        val device = args[0] as? BluetoothDevice
                        val state = args[2] as? Int
                        if (device != null && state != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                            Log.d(TAG, "MiShuai A2DP state: $state for ${device.name}")
                            when (state) {
                                2 -> {
                                    val ctx = serviceContext(chain)
                                    if (ctx != null) {
                                        SppController.connectPod(ctx, device)
                                    }
                                }
                                0 -> {
                                    SppController.disconnect()
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Hook callback error", e)
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook A2dpService", e)
        }
    }

    /** 不同 ROM 字段名可能不同，失败返回 null */
    private fun serviceContext(chain: XposedInterface.Chain): Context? {
        val obj = chain.thisObject ?: return null
        return try {
            obj.javaClass.getDeclaredField("mServiceContext")
                .apply { isAccessible = true }
                .get(obj) as? Context
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read mServiceContext", e)
            null
        }
    }
}
