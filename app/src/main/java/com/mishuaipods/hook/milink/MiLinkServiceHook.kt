package com.mishuaipods.hook.milink

import android.bluetooth.BluetoothDevice
import com.mishuaipods.hook.Log
import com.mishuaipods.pods.MiShuaiDeviceDetector
import com.mishuaipods.pods.NoiseControlMode
import com.mishuaipods.pods.SppController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object MiLinkServiceHook {
    private const val TAG = "MiLinkHook"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking MiLinkService for Fusion Device Center")
        val classLoader = param.defaultClassLoader

        hookMiLinkClasses(module, classLoader)
    }

    private fun hookMiLinkClasses(module: XposedModule, classLoader: ClassLoader) {
        val classes = listOf(
            "com.xiaomi.milink.service.DeviceInfo",
            "com.xiaomi.milink.service.bluetooth.MiBluetoothDeviceInfo",
            "com.xiaomi.milink.service.headset.MiHeadsetInfo"
        )
        for (className in classes) {
            try {
                val clazz = classLoader.loadClass(className)
                hookDeviceMethods(module, clazz)
                Log.d(TAG, "Hooked $className")
            } catch (_: Throwable) {}
        }
    }

    private fun hookDeviceMethods(module: XposedModule, clazz: Class<*>) {
        hookMethodSimple(module, clazz, "checkIsMiTW") { chain ->
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
