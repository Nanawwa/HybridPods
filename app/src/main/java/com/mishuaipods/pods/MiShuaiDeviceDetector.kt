package com.mishuaipods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID

object MiShuaiDeviceDetector {
    private const val TAG = "MiShuaiDetector"

    private val FALLBACK_UUIDS = listOf(
        "158627bc-0547-8787-87ba-435ad8571238",
        "00001101-0000-1000-8000-00805F9B34FB"
    )

    fun isMiShuai(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val name = device.name ?: return false
        val lowerName = name.lowercase()
        return lowerName.contains("mi shuai") ||
               lowerName.contains("mishuai") ||
               lowerName.contains("mi_shuai") ||
               name.contains("咪帅") ||
               lowerName.contains("glaze")
    }

    @SuppressLint("MissingPermission")
    fun getModelName(device: BluetoothDevice?): String {
        if (device == null) return "Unknown"
        return device.name ?: device.address ?: "Unknown"
    }

    @SuppressLint("MissingPermission")
    fun findPaired(adapter: BluetoothAdapter): BluetoothDevice? {
        val devices = adapter.bondedDevices ?: return null
        return devices.firstOrNull { isMiShuai(it) }
    }

    @SuppressLint("MissingPermission")
    fun connectSpp(device: BluetoothDevice): BluetoothSocket? {
        // 先取消发现，否则 connect 会报 Service discovery failed
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Exception) {}

        // Step 1: SDP discovery
        try {
            val sdpTriggered = device.fetchUuidsWithSdp()
            if (sdpTriggered) {
                Thread.sleep(2000)
                val uuids = device.uuids
                if (uuids != null) {
                    for (parcelUuid in uuids) {
                        val uuid = parcelUuid.uuid
                        val uuidStr = uuid.toString().lowercase()
                        if (isAudioProfileUuid(uuidStr)) continue
                        try {
                            val socket = device.createRfcommSocketToServiceRecord(uuid)
                            socket.connect()
                            android.util.Log.i(TAG, "SPP connected via SDP: $uuidStr")
                            return socket
                        } catch (_: Exception) {
                            android.util.Log.d(TAG, "SDP UUID failed: $uuidStr")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "SDP discovery failed: ${e.message}")
        }

        // Step 2: Fallback UUIDs
        for (uuidStr in FALLBACK_UUIDS) {
            try {
                val uuid = UUID.fromString(uuidStr)
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                android.util.Log.i(TAG, "SPP connected via fallback: $uuidStr")
                return socket
            } catch (_: Exception) {
                android.util.Log.d(TAG, "Fallback UUID failed: $uuidStr")
            }
        }

        android.util.Log.e(TAG, "All SPP attempts failed for ${device.name}")
        return null
    }

    private fun isAudioProfileUuid(uuidStr: String): Boolean {
        return uuidStr.startsWith("0000110b") ||
               uuidStr.startsWith("0000110e") ||
               uuidStr.startsWith("0000111e") ||
               uuidStr.startsWith("0000110a") ||
               uuidStr.startsWith("0000110d")
    }
}
