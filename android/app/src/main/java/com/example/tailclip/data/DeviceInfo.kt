package com.example.tailclip.data

/**
 * Represents a device connected to the TailClip relay server.
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,  // "android", "desktop", "cli", "browser"
    val isSelf: Boolean = false
)
