package moe.chenxy.oppopods.pods

/**
 * Parser for MiShuai earphone SPP response packets.
 *
 * Response frame format:
 * [0x00] [Type] 0x02 0x00 [Length] [SubType] [Data...]
 *
 * The Type byte matches the query/control type.
 * The Length byte (at offset 4) gives the payload length after the 6-byte header.
 * The SubType byte (at offset 5) echoes the query sub-type for GET_BL_INFO responses.
 */
object MiShuaiParser {

    /**
     * Check if a packet is a valid MiShuai response.
     * Valid: first byte == 0x00, third byte == 0x02 (response direction).
     */
    fun isValidResponse(data: ByteArray): Boolean {
        return data.size >= 6 &&
                data[0] == 0x00.toByte() &&
                data[2] == 0x02.toByte()
    }

    /**
     * Get the response type (byte 1 �?the command/query category).
     */
    fun getResponseType(data: ByteArray): Int {
        return data[1].toInt() and 0xFF
    }

    /**
     * Get the sub-type (byte 5) �?for GET_BL_INFO responses.
     */
    fun getSubType(data: ByteArray): Int {
        return if (data.size > 5) data[5].toInt() and 0xFF else 0
    }

    // ── Battery parsing ──────────────────────────────────────────────

    /**
     * Parse battery response (query type 0x01).
     *
     *抓包数据: 00 27 02 00 05 01 03 64 64 60
     * [7] = left battery %, [8] = right battery %, [9] = case battery %
     * High bit (0x80) indicates charging; low 7 bits = percentage.
     */
    fun parseBattery(data: ByteArray): BatteryParser.BatteryResult? {
        if (data.size < 10) return null
        if (!isValidResponse(data)) return null

        val leftRaw = data[7].toInt() and 0xFF
        val rightRaw = data[8].toInt() and 0xFF
        val caseRaw = data[9].toInt() and 0xFF

        val left = BatteryParser.BatteryInfo(
            level = (leftRaw and 0x7F).coerceIn(0, 100),
            isCharging = (leftRaw and 0x80) != 0
        )
        val right = BatteryParser.BatteryInfo(
            level = (rightRaw and 0x7F).coerceIn(0, 100),
            isCharging = (rightRaw and 0x80) != 0
        )
        val case = BatteryParser.BatteryInfo(
            level = (caseRaw and 0x7F).coerceIn(0, 100),
            isCharging = (caseRaw and 0x80) != 0
        )

        return BatteryParser.BatteryResult(left, right, case)
    }

    // ── ANC mode parsing ─────────────────────────────────────────────

    /**
     * Parse ANC mode response (query type 0x07 �?ANC switch state).
     *
     * 抓包数据: 00 27 02 00 04 07 02 00 00
     * [7] = current ANC mode byte.
     */
    fun parseAncMode(data: ByteArray): NoiseControlMode? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null

        val mode = data[7].toInt() and 0xFF
        return when (mode) {
            MiShuaiPackets.ANC_WIND_NR -> NoiseControlMode.OFF
            MiShuaiPackets.ANC_DEEP_ANC -> NoiseControlMode.NOISE_CANCELLATION
            MiShuaiPackets.ANC_TRANSPARENCY -> NoiseControlMode.TRANSPARENCY
            MiShuaiPackets.ANC_OFF -> NoiseControlMode.OFF
            else -> null
        }
    }

    /**
     * Parse noise detail response (query type 0x0C).
     * Similar to ANC mode but may contain more detail.
     */
    fun parseNoiseDetail(data: ByteArray): NoiseControlMode? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null

        val mode = data[7].toInt() and 0xFF
        return when (mode) {
            MiShuaiPackets.ANC_WIND_NR -> NoiseControlMode.OFF
            MiShuaiPackets.ANC_DEEP_ANC -> NoiseControlMode.NOISE_CANCELLATION
            MiShuaiPackets.ANC_TRANSPARENCY -> NoiseControlMode.TRANSPARENCY
            MiShuaiPackets.ANC_OFF -> NoiseControlMode.OFF
            else -> null
        }
    }

    // ── Device name parsing ──────────────────────────────────────────

    /**
     * Parse device name response (query type 0x03).
     *
     * 抓包数据: 00 27 02 00 13 03 11 4D 69 73 68 75 61 69 ...
     * [7..] = ASCII characters.
     */
    fun parseDeviceName(data: ByteArray): String? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null

        return try {
            String(data, 7, data.size - 7, Charsets.US_ASCII).trim()
        } catch (_: Exception) {
            null
        }
    }

    // ── EQ mode parsing ──────────────────────────────────────────────

    /**
     * Parse EQ mode response (query type 0x04).
     * [7] = current EQ preset index.
     */
    fun parseEqMode(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return data[7].toInt() and 0xFF
    }

    // ── Touch settings parsing ───────────────────────────────────────

    /**
     * Parse touch settings response (query type 0x05).
     * 32-byte payload starting at [7].
     */
    fun parseTouchSettings(data: ByteArray): ByteArray? {
        if (data.size < 39) return null  // 7 header + 32 payload
        if (!isValidResponse(data)) return null
        return data.copyOfRange(7, 39)
    }

    // ── Work mode parsing ────────────────────────────────────────────

    /**
     * Parse work mode response (query type 0x08).
     * [7] = current work mode.
     */
    fun parseWorkMode(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return data[7].toInt() and 0xFF
    }

    // ── Firmware version parsing ─────────────────────────────────────

    /**
     * Parse firmware version response (query type 0x02).
     * [7..] = version string.
     */
    fun parseFirmwareVersion(data: ByteArray): String? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return try {
            String(data, 7, data.size - 7, Charsets.US_ASCII).trim()
        } catch (_: Exception) {
            null
        }
    }

    // ── Language parsing ─────────────────────────────────────────────

    /**
     * Parse language response (query type 0x0A).
     * [7] = current language code.
     */
    fun parseLanguage(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return data[7].toInt() and 0xFF
    }

    // ── Alert volume parsing ─────────────────────────────────────────

    /**
     * Parse alert volume response (query type 0x13).
     * [7] = current volume level.
     */
    fun parseAlertVolume(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return data[7].toInt() and 0xFF
    }

    // ── Touch switch parsing ─────────────────────────────────────────

    /**
     * Parse touch switch response (query type 0x16).
     * [7] = touch switch state (0=off, 1=on).
     */
    fun parseTouchSwitch(data: ByteArray): Boolean? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return (data[7].toInt() and 0xFF) == 1
    }

    // ── Finder parsing ───────────────────────────────────────────────

    /**
     * Parse finder response (query type 0x14).
     * [7] = finder state.
     */
    fun parseFinder(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (!isValidResponse(data)) return null
        return data[7].toInt() and 0xFF
    }
}
