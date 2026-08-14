package com.mishuaipods.hook

import com.mishuaipods.pods.MiShuaiDeviceDetector
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object MiBluetoothToastHook {
    private const val TAG = "MiBtToastHook"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking Mi Bluetooth toast for MiShuai")
        val classLoader = param.defaultClassLoader

        try {
            val clazz = classLoader.loadClass("com.xiaomi.bluetooth.notification.MiuiBluetoothNotificationApi")
            val methods = clazz.declaredMethods.filter { it.name == "showNewConnectedToast" }
            for (method in methods) {
                module.hook(method).intercept { chain ->
                    val args = chain.args
                    if (args.size > 1) {
                        val device = args[1] as? android.bluetooth.BluetoothDevice
                        if (device != null && MiShuaiDeviceDetector.isMiShuai(device)) {
                            Log.d(TAG, "Intercepted connected toast for MiShuai: ${device.name}")
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Could not hook MiuiBluetoothNotificationApi: ${e.message}")
        }
    }
}
