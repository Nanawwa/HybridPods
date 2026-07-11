package moe.chenxy.oppopods.pods

/**
 * Device capability detection for MiShuai earphones.
 * MiShuai Glaze Max (model 32) does not support adaptive ANC, spatial audio, or spatial sound switch.
 */
fun detectMiShuaiCapabilities(deviceName: String): DeviceCapabilities {
    return DeviceCapabilities(
        adaptiveSupported = false,
        spatialAudioSupported = false,
        spatialSoundSwitchSupported = false,
        ancImplementation = AncImplementation.STANDARD,
    )
}
