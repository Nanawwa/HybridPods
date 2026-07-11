package moe.chenxy.oppopods.pods

enum class DeviceType {
    OPPO,
    MI_SHUAI,
    UNKNOWN;

    companion object {
        fun detect(deviceName: String): DeviceType {
            val name = deviceName.lowercase()
            return when {
                name.contains("oppo") || name.contains("enco") -> OPPO
                name.contains("mi shuai") || name.contains("mishuai") || name.contains("咪帅") -> MI_SHUAI
                else -> UNKNOWN
            }
        }

        fun isSupported(deviceName: String): Boolean = detect(deviceName) != UNKNOWN
    }
}
