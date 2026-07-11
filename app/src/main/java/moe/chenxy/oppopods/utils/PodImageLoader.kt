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
    private const val MODULE_PACKAGE = "moe.chenxy.hybridpods"

    /**
     * MiShuai model mapping table.
     * Key: device name keyword (lowercase, matched with contains())
     * Value: drawable resource prefix (image file: mishuai_[prefix]_black.png)
     *
     * To add a new model:
     * 1. Add image file: mishuai_xxx_black.png to drawable-nodpi/
     * 2. Add one line here: "keyword" to "xxx"
     */
    private val MI_SHUAI_MODEL_MAP = linkedMapOf(
        // Order matters: longer/more specific keywords first
        "glaze max" to "m30",
        "m3a" to "m3a",
        "m88" to "m88",
        "mp10" to "mp10",
        "mp12" to "mp12",
        "mp16" to "mp16",
        "r3c" to "r3c",
        "m30" to "m30",
        "m8" to "m8",
        "m3" to "m3",
        "m2" to "m2",
        "r3" to "r3",
    )

    private val resourceCache = mutableMapOf<String, Int>()

    /**
     * Resolve MiShuai device name to image resource ID using convention-based lookup.
     * Image files follow naming: mishuai_[model]_black.png
     * Returns null if no matching model found.
     */
    fun getMiShuaiImageResId(context: Context, deviceName: String): Int? {
        val name = deviceName.lowercase()
        val modelPrefix = MI_SHUAI_MODEL_MAP.entries.firstOrNull { (keyword, _) ->
            name.contains(keyword)
        }?.value

        if (modelPrefix == null) {
            // Fallback: any MiShuai device → default m30
            if (name.contains("mi shuai") || name.contains("mishuai")) {
                return resolveDrawableId(context, "mishuai_m30_black")
            }
            return null
        }

        val resName = "mishuai_${modelPrefix}_black"
        return resolveDrawableId(context, resName)
    }

    private fun resolveDrawableId(context: Context, resName: String): Int? {
        resourceCache[resName]?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val resId = moduleContext.resources.getIdentifier(resName, "drawable", MODULE_PACKAGE)
        if (resId != 0) {
            resourceCache[resName] = resId
        }
        return resId.takeIf { it != 0 }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDeviceName(context: Context, address: String): String {
        return try {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            val device = adapter?.getRemoteDevice(address)
            // Try name first, then alias, then bonded device cached name
            val name = device?.name?.takeIf { it.isNotBlank() }
                ?: device?.alias?.takeIf { it.isNotBlank() }
                ?: ""
            name
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT permission not granted
            ""
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
            val miShuaiResId = getMiShuaiImageResId(context, deviceName)
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
            val miShuaiResId = getMiShuaiImageResId(context, deviceName)
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
