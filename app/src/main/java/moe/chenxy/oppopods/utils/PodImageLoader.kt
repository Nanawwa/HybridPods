package moe.chenxy.oppopods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.config.imageUri

object PodImageLoader {
    private const val TAG = "HybridPods-Image"
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
        "glaze max" to "m8",
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

        Log.i(TAG, "getMiShuaiImageResId: name='$deviceName', lowercase='$name', modelPrefix=$modelPrefix")

        if (modelPrefix == null) {
            // Fallback: any MiShuai device → default m8
            if (name.contains("mi shuai") || name.contains("mishuai")) {
                val resId = resolveDrawableId(context, "mishuai_m8_black")
                Log.i(TAG, "Fallback MiShuai image: resId=$resId")
                return resId
            }
            Log.i(TAG, "Not a MiShuai device")
            return null
        }

        val resName = "mishuai_${modelPrefix}_black"
        val resId = resolveDrawableId(context, resName)
        Log.i(TAG, "MiShuai image: model=$modelPrefix, resName=$resName, resId=$resId")
        return resId
    }

    private fun resolveDrawableId(context: Context, resName: String): Int? {
        resourceCache[resName]?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: run {
            Log.e(TAG, "resolveDrawableId: createPackageContext failed for $MODULE_PACKAGE")
            return null
        }
        val resId = moduleContext.resources.getIdentifier(resName, "drawable", MODULE_PACKAGE)
        if (resId != 0) {
            resourceCache[resName] = resId
        }
        Log.i(TAG, "resolveDrawableId: resName=$resName, resId=$resId")
        return resId.takeIf { it != 0 }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDeviceName(context: Context, address: String): String {
        return try {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            val device = adapter?.getRemoteDevice(address)
            val name = device?.name?.takeIf { it.isNotBlank() }
                ?: device?.alias?.takeIf { it.isNotBlank() }
                ?: ""
            Log.i(TAG, "resolveDeviceName: address=$address, name='$name'")
            name
        } catch (e: SecurityException) {
            Log.w(TAG, "resolveDeviceName: BLUETOOTH_CONNECT permission not granted")
            ""
        } catch (e: Exception) {
            Log.w(TAG, "resolveDeviceName: error=${e.message}")
            ""
        }
    }

    private fun tryLoadMiShuaiImage(
        context: Context,
        address: String,
        deviceName: String?
    ): Bitmap? {
        val name = deviceName?.takeIf { it.isNotBlank() } ?: return null
        val miShuaiResId = getMiShuaiImageResId(context, name) ?: return null
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: run {
            Log.e(TAG, "tryLoadMiShuaiImage: createPackageContext failed")
            return null
        }
        val bitmap = BitmapFactory.decodeResource(moduleContext.resources, miShuaiResId)
        Log.i(TAG, "tryLoadMiShuaiImage: bitmap=${bitmap?.width}x${bitmap?.height}, resId=$miShuaiResId")
        return bitmap
    }

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        Log.i(TAG, "loadBitmap: address=$address, resource=$resource")
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        Log.i(TAG, "loadBitmap: earphone=${earphone?.name}, hasPrefs=${earphone != null}")

        // Try custom image first
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            Log.i(TAG, "loadBitmap: returning custom image")
            return custom
        }

        // Try MiShuai image
        val deviceName = earphone?.name?.takeIf { it.isNotBlank() }
            ?: resolveDeviceName(context, address)
        Log.i(TAG, "loadBitmap: deviceName='$deviceName'")
        val miBitmap = tryLoadMiShuaiImage(context, address, deviceName)
        if (miBitmap != null) {
            Log.i(TAG, "loadBitmap: returning MiShuai image")
            return miBitmap
        }

        // Fallback to default
        Log.i(TAG, "loadBitmap: returning default fallback resId=$fallbackResId")
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
        Log.i(TAG, "loadBitmapWithCustomFallback: address=$address, resource=$resource")
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        Log.i(TAG, "loadBitmapWithCustomFallback: earphone=${earphone?.name}")

        // Try custom image
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            Log.i(TAG, "loadBitmapWithCustomFallback: returning custom image")
            return custom
        }

        // Try MiShuai image
        val deviceName = earphone?.name?.takeIf { it.isNotBlank() }
            ?: resolveDeviceName(context, address)
        Log.i(TAG, "loadBitmapWithCustomFallback: deviceName='$deviceName'")
        val miBitmap = tryLoadMiShuaiImage(context, address, deviceName)
        if (miBitmap != null) {
            Log.i(TAG, "loadBitmapWithCustomFallback: returning MiShuai image")
            return miBitmap
        }

        // Fallback to default
        Log.i(TAG, "loadBitmapWithCustomFallback: returning default fallback resId=$fallbackResId")
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
