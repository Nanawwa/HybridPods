package com.mishuaipods.hook

import android.bluetooth.BluetoothDevice
import com.mishuaipods.pods.MiShuaiDeviceDetector
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object SettingsHeadsetHook {
    private const val TAG = "SettingsHeadsetHook"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking Settings for MiShuai headset integration")
        val classLoader = param.defaultClassLoader

        try {
            val clazz = classLoader.loadClass("com.android.settingslib.bluetooth.CachedBluetoothDevice")
            val method = clazz.getDeclaredMethod("isMiHeadset")
            module.hook(method).intercept { chain ->
                val thisObj = chain.thisObject ?: return@intercept chain.proceed()
                val deviceField = thisObj.javaClass
                    .getDeclaredField("mDevice")
                    .apply { isAccessible = true }
                val device = deviceField.get(thisObj) as? BluetoothDevice
                if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                    return@intercept true
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Could not hook CachedBluetoothDevice: ${e.message}")
        }
    }
}
