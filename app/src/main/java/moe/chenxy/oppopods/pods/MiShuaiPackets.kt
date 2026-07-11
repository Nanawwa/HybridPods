package moe.chenxy.oppopods.pods

/**
 * MiShuai earphone SPP protocol packet definitions.
 *
 * Frame format (fixed 6-byte header + variable payload):
 * [0x00] [Type] [Direction] 0x00 0x01 [Payload...]
 *
 * Direction: 0x01 = send/query, 0x02 = response
 * Type byte encodes the command or query category.
 */
object MiShuaiPackets {

    // ── Frame templates ──────────────────────────────────────────────

    /** Build a command frame: 00 [type] 01 00 01 [value] */
    fun buildCommand(type: Int, value: Byte): ByteArray = byteArrayOf(
        0x00, type.toByte(), 0x01, 0x00, 0x01, value
    )

    /** Build a query frame: 00 27 01 00 01 [subType] */
    fun buildQuery(subType: Byte): ByteArray = byteArrayOf(
        0x00, GET_BL_INFO.toByte(), 0x01, 0x00, 0x01, subType
    )

    /** Full-chain poll: 00 27 01 00 01 FF */
    fun buildFullQuery(): ByteArray = buildQuery(QUERY_FULL.toByte())

    // ── Control type constants (frame header byte 2) ─────────────────

    const val NOISE_CONTROL = 0x2C        // 44 �?ANC mode set
    const val SOUND_EFFECTS = 0x20        // 32 �?EQ set
    const val WORK_MODE = 0x25            // 37 �?work mode set
    const val AUDIO_PROTOCOL = 0x2B       // 43 �?audio protocol set
    const val BL_LANGUAGE = 0x29          // 41 �?language set
    const val BL_ALERT_VOLUME = 0x32      // 50 �?alert volume set
    const val BL_TOUCH_SWITCH = 0x33      // 51 �?touch switch set
    const val BL_FINDER = 0x2A            // 42 �?finder set
    const val BL_ANC_MODE_SWITCH = 0x21   // 33 �?ANC mode switch set
    const val TOUCH_SETTINGS = 0x22       // 34 �?touch settings set
    const val BL_POWER_OFF = 0x23         // 35 �?power off
    const val BL_RESET = 0x24             // 36 �?factory reset
    const val CLEAR_PAIR = 0x30           // 47 �?clear pair
    const val GET_BL_INFO = 0x27          // 39 �?query (all sub-types)

    // ── Query sub-types ──────────────────────────────────────────────

    const val QUERY_BATTERY = 0x01
    const val QUERY_FIRMWARE = 0x02
    const val QUERY_DEVICE_NAME = 0x03
    const val QUERY_EQ = 0x04
    const val QUERY_TOUCH_SETTINGS = 0x05
    const val QUERY_ANC_SWITCH = 0x07
    const val QUERY_WORK_MODE = 0x08
    const val QUERY_LANGUAGE = 0x0A
    const val QUERY_AUDIO_PROTOCOL = 0x0B
    const val QUERY_NOISE_DETAIL = 0x0C
    const val QUERY_ALERT_VOLUME = 0x13
    const val QUERY_FINDER = 0x14
    const val QUERY_TOUCH_SWITCH = 0x16
    const val QUERY_FULL = 0xFF

    // ── Full poll chain order ────────────────────────────────────────
    // 1�?�?�?�?�?�?0�?2�?9�?2�?1�?�?0
    // Battery→Firmware→Name→EQ→Touch→WorkMode→Language→NoiseDetail�?    // AlertVol→TouchSwitch→AudioProtocol→AncSwitch→Finder

    val POLL_CHAIN = intArrayOf(
        QUERY_BATTERY,          // 1
        QUERY_FIRMWARE,         // 2
        QUERY_DEVICE_NAME,      // 3
        QUERY_EQ,               // 4
        QUERY_TOUCH_SETTINGS,   // 5
        QUERY_WORK_MODE,        // 8
        QUERY_LANGUAGE,         // 10 (0x0A)
        QUERY_NOISE_DETAIL,     // 12 (0x0C)
        QUERY_ALERT_VOLUME,     // 19 (0x13)
        QUERY_TOUCH_SWITCH,     // 22 (0x16)
        QUERY_AUDIO_PROTOCOL,   // 11 (0x0B)
        QUERY_ANC_SWITCH,       // 7
        QUERY_FINDER,           // 20 (0x14)
    )

    // ── ANC mode values (used in NOISE_CONTROL command) ──────────────

    const val ANC_WIND_NR = 0x00       // 抗风降噪
    const val ANC_DEEP_ANC = 0x01      // 深度降噪
    const val ANC_TRANSPARENCY = 0x02  // 环境�?通�?    const val ANC_OFF = 0x03           // 降噪�?
    // ── EQ presets (SoundEffects = 0x20) ────────────────────────────

    const val EQ_HIFI = 0              // HiFi 高保�?    const val EQ_POP = 1               // POP 流行
    const val EQ_ROCK = 2              // Rock 摇滚
    const val EQ_FPS = 3               // FPS 游戏音效
    const val EQ_LC = 4                // Lc
    const val EQ_CUSTOM = 5            // Custom 自定�?EQ

    /** All supported EQ preset IDs. */
    val EQ_PRESETS = intArrayOf(EQ_HIFI, EQ_POP, EQ_ROCK, EQ_FPS, EQ_LC, EQ_CUSTOM)

    // ── WorkMode (game/music mode, WorkMode = 0x25) ─────────────────

    const val WORK_MODE_MUSIC = 0      // 音乐模式
    const val WORK_MODE_GAME = 1       // 游戏模式

    // ── Pre-built command packets ────────────────────────────────────

    fun buildSetAnc(mode: Byte): ByteArray = buildCommand(NOISE_CONTROL, mode)

    fun buildSetEq(preset: Byte): ByteArray {
        return if (preset == EQ_CUSTOM.toByte()) {
            // Custom EQ needs 10-byte payload; for now send with zeroed EQ data
            byteArrayOf(0x00, SOUND_EFFECTS.toByte(), 0x01, 0x00, 0x0C, 0x0A, preset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        } else {
            buildCommand(SOUND_EFFECTS, preset)
        }
    }

    fun buildSetWorkMode(mode: Byte): ByteArray = buildCommand(WORK_MODE, mode)

    /** Map MiShuai ANC byte to HyperOS ANC status (1=Off, 2=NC, 3=Transparency, 4=WindNR). */
    fun mapAncToHyperOs(protocolMode: Int): Int = when (protocolMode) {
        ANC_WIND_NR -> 4       // Wind NR (custom status)
        ANC_DEEP_ANC -> 2      // ANC
        ANC_TRANSPARENCY -> 3  // Transparency
        ANC_OFF -> 1           // Off
        else -> 1
    }

    /** Map HyperOS ANC status to MiShuai protocol byte. */
    fun mapAncFromHyperOs(hyperOsStatus: Int): Byte = when (hyperOsStatus) {
        1 -> ANC_OFF.toByte()
        2 -> ANC_DEEP_ANC.toByte()
        3 -> ANC_TRANSPARENCY.toByte()
        4 -> ANC_WIND_NR.toByte()
        else -> ANC_OFF.toByte()
    }

    /** Map NoiseControlMode to MiShuai protocol byte. */
    fun mapAncFromNoiseControl(mode: NoiseControlMode): Byte = when (mode) {
        NoiseControlMode.OFF -> ANC_OFF.toByte()
        NoiseControlMode.NOISE_CANCELLATION -> ANC_DEEP_ANC.toByte()
        NoiseControlMode.TRANSPARENCY -> ANC_TRANSPARENCY.toByte()
        else -> ANC_OFF.toByte()
    }
}
