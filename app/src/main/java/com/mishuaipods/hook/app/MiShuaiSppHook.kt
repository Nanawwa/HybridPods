package com.mishuaipods.hook.app

import com.mishuaipods.hook.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object MiShuaiSppHook {
    private const val TAG = "SppHook"

    fun onHook(module: XposedModule, param: PackageLoadedParam) {
        Log.d(TAG, "Hooking com.mishuai.bt for all MiShuai models")
        val classLoader = param.defaultClassLoader

        hookSpiAbstract(module, classLoader)
        // 不在本进程建 SPP 连接，避免与官方 App / 系统实例争用同一通道
    }

    private fun hookSpiAbstract(module: XposedModule, classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.mishuai.bt.Headphone.Spi.SpiAbstract")
            val method = clazz.getDeclaredMethod("ReceiveEarphoneDataDetect", IntArray::class.java)
            module.hook(method).intercept { chain ->
                val args = chain.args
                if (args.isNotEmpty()) {
                    val arr = args[0] as? IntArray
                    if (arr != null && arr.size >= 10) {
                        val type = arr[5]
                        val subType = arr[6]
                        Log.d(TAG, "[RSP] type=${String.format("%02X", type)} sub=${String.format("%02X", subType)}")
                    }
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook SpiAbstract", e)
        }

        try {
            val clazz = classLoader.loadClass("com.mishuai.bt.Headphone.Spi.Spi")
            val method = clazz.getDeclaredMethod("SendSetBlData", ByteArray::class.java)
            module.hook(method).intercept { chain ->
                val args = chain.args
                if (args.isNotEmpty()) {
                    val data = args[0] as? ByteArray
                    if (data != null && data.size >= 2) {
                        val hex = data.joinToString(" ") { String.format("%02X", it) }
                        Log.d(TAG, "[SEND] $hex")
                    }
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Spi class not found: ${e.message}")
        }
    }
}
