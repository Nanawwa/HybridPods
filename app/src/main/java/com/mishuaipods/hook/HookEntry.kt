package com.mishuaipods.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.mishuaipods.hook.app.MiShuaiSppHook
import com.mishuaipods.hook.milink.MiLinkServiceHook

class HookEntry : XposedModule() {
    private val TAG = "HookEntry"

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        Log.i(TAG, "onPackageLoaded: ${param.packageName}")

        when (param.packageName) {
            "com.mishuai.bt" -> {
                MiShuaiSppHook.onHook(this, param)
            }
            "com.android.bluetooth" -> {
                HeadsetStateDispatcher.onHook(this, param)
                BluetoothUpstreamHeadsetHook.onHook(this, param)
            }
            "com.milink.service" -> {
                MiLinkServiceHook.onHook(this, param)
            }
            "com.xiaomi.bluetooth" -> {
                MiBluetoothToastHook.onHook(this, param)
                BluetoothUpstreamHeadsetHook.onHook(this, param)
            }
            "com.android.settings" -> {
                SettingsHeadsetHook.onHook(this, param)
            }
        }
    }
}
