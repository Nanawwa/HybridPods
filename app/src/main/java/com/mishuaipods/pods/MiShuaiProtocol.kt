package com.mishuaipods.pods

object MiShuaiProtocol {
    // 帧模板
    private val CMD_TEMPLATE = byteArrayOf(0x00, 0x2C, 0x01, 0x00, 0x01, 0x00)
    private val QUERY_TEMPLATE = byteArrayOf(0x00, 0x27, 0x01, 0x00, 0x01, 0x00)

    // 模式字节
    const val MODE_WIND_NR: Byte = 0x00
    const val MODE_DEEP_ANC: Byte = 0x01
    const val MODE_TRANSPARENT: Byte = 0x02
    const val MODE_NC_OFF: Byte = 0x03

    // 查询类型（0x04 是 EQ 查询，ANC 状态是 0x07）
    const val QUERY_BATTERY: Byte = 0x01
    const val QUERY_NAME: Byte = 0x03
    const val QUERY_NC_STATUS: Byte = 0x07

    // 工作模式 (本地设置，不发 SPP)
    const val WORK_MUSIC: Byte = 0x00
    const val WORK_GAME: Byte = 0x01

    fun buildCommand(mode: Byte): ByteArray = CMD_TEMPLATE.clone().also { it[5] = mode }
    fun buildCommand(mode: NoiseControlMode): ByteArray = buildCommand(mode.byte)
    fun buildQuery(type: Byte): ByteArray = QUERY_TEMPLATE.clone().also { it[5] = type }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
