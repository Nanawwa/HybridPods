package com.mishuaipods.hook

import android.bluetooth.BluetoothDevice
import com.mishuaipods.pods.MiShuaiDeviceDetector
import com.mishuaipods.pods.NoiseControlMode
import com.mishuaipods.pods.SppController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object BluetoothUpstreamHeadsetHook {
    private const val TAG = "BTUpstreamHook"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking Bluetooth upstream for MiShuai headset integration")
        val classLoader = param.defaultClassLoader

        hookBinderService(module, classLoader)
    }

    private fun hookBinderService(module: XposedModule, classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.bluetooth.hfp.HeadsetStateMachine")

            hookMethodSimple(module, clazz, "isMiTWS") { chain ->
                val device = getDevice(chain.args)
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    return@hookMethodSimple true
                }
                return@hookMethodSimple null
            }

            hookMethodSimple(module, clazz, "checkIsMiTWS") { chain ->
                val device = getDevice(chain.args)
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    return@hookMethodSimple 1
                }
                return@hookMethodSimple null
            }

            hookMethodSimple(module, clazz, "getDeviceId") { chain ->
                val device = getDevice(chain.args)
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    return@hookMethodSimple "mishuai001"
                }
                return@hookMethodSimple null
            }

            hookMethodSimple(module, clazz, "getBatteryLevel") { chain ->
                val device = getDevice(chain.args)
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    if (!SppController.hasBatteryData) {
                        // 尚未收到电量响应时上报“未知”，避免系统显示 0%
                        return@hookMethodSimple -1
                    }
                    val battery = SppController.currentBattery
                    return@hookMethodSimple minOf(battery.left, battery.right).coerceAtLeast(0)
                }
                return@hookMethodSimple null
            }

            hookMethodSimple(module, clazz, "getAncState") { chain ->
                val device = getDevice(chain.args)
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    return@hookMethodSimple mapAncToMiLink(SppController.currentAnc)
                }
                return@hookMethodSimple null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook HeadsetStateMachine", e)
        }
    }

    // 1=Off 2=NC 3=Trans 4=WindNR
    private fun mapAncToMiLink(mode: NoiseControlMode): Int = when (mode) {
        NoiseControlMode.NC_OFF -> 1
        NoiseControlMode.DEEP_ANC -> 2
        NoiseControlMode.TRANSPARENCY -> 3
        NoiseControlMode.WIND_NR -> 4
    }

    private fun getDevice(args: List<Any?>): BluetoothDevice? {
        return args.filterIsInstance<BluetoothDevice>().firstOrNull()
    }

    private fun hookMethodSimple(module: XposedModule, clazz: Class<*>, methodName: String,
                          handler: (XposedInterface.Chain) -> Any?) {
        try {
            val methods = clazz.declaredMethods.filter { it.name == methodName }
            for (method in methods) {
                module.hook(method).intercept { chain ->
                    val result = handler(chain)
                    if (result != null) {
                        return@intercept result
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Could not hook $methodName: ${e.message}")
        }
    }

}
