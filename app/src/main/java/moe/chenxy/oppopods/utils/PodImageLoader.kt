package moe.chenxy.oppopods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.config.imageUri

object PodImageLoader {
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"

    /**
     * Map MiShuai device name to the corresponding earphone image resource.
     * Returns null if the device is not a recognized MiShuai model.
     */
    fun getMiShuaiImageResId(deviceName: String): Int? {
        val name = deviceName.lowercase()
        return when {
            name.contains("glaze max") || name.contains("m30") -> R.drawable.mishuai_m30_black
            name.contains("m3a") -> R.drawable.mishuai_m3a_black
            name.contains("m88") -> R.drawable.mishuai_m88_black
            name.contains("mp10") -> R.drawable.mishuai_mp10_black
            name.contains("mp12") -> R.drawable.mishuai_mp12_black
            name.contains("mp16") -> R.drawable.mishuai_mp16_black
            name.contains("m8") -> R.drawable.mishuai_m8_black
            name.contains("m2") -> R.drawable.mishuai_m2_black
            name.contains("r3c") -> R.drawable.mishuai_r3c_black
            name.contains("r3") -> R.drawable.mishuai_r3_black
            name.contains("m3") -> R.drawable.mishuai_m3_black
            name.contains("mi shuai") || name.contains("mishuai") -> R.drawable.mishuai_m30_black
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDeviceName(context: Context, address: String): String {
        return try {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            val device = adapter?.getRemoteDevice(address)
            device?.name ?: device?.alias ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            return custom
        }

        // Auto-match MiShuai device image by device name
        val deviceName = earphone?.name?.takeIf { it.isNotBlank() }
            ?: resolveDeviceName(context, address)
        if (deviceName.isNotBlank()) {
            val miShuaiResId = getMiShuaiImageResId(deviceName)
            if (miShuaiResId != null) {
                val moduleContext = runCatching {
                    context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                }.getOrNull() ?: return null
                return BitmapFactory.decodeResource(moduleContext.resources, miShuaiResId)
            }
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBitmapWithCustomFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        customFallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            return custom
        }

        // Auto-match MiShuai device image by device name
        val deviceName = earphone?.name?.takeIf { it.isNotBlank() }
            ?: resolveDeviceName(context, address)
        if (deviceName.isNotBlank()) {
            val miShuaiResId = getMiShuaiImageResId(deviceName)
            if (miShuaiResId != null) {
                val moduleContext = runCatching {
                    context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                }.getOrNull() ?: return null
                return BitmapFactory.decodeResource(moduleContext.resources, miShuaiResId)
            }
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

    fun loadIslandLeftBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.LEFT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_left,
        )
    }

    fun loadIslandRightBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.RIGHT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_right,
        )
    }

    private fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
}
