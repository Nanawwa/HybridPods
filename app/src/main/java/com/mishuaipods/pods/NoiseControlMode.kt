package com.mishuaipods.pods

enum class NoiseControlMode(val byte: Byte, val label: String, val labelCn: String) {
    WIND_NR(0x00, "Wind NR", "抗风降噪"),
    DEEP_ANC(0x01, "Deep ANC", "深度降噪"),
    TRANSPARENCY(0x02, "Transparency", "环境音"),
    NC_OFF(0x03, "NC Off", "降噪关");

    companion object {
        fun fromByte(b: Byte): NoiseControlMode = entries.firstOrNull { it.byte == b } ?: NC_OFF
        fun fromIndex(index: Int): NoiseControlMode = entries.getOrElse(index) { NC_OFF }
    }
}
