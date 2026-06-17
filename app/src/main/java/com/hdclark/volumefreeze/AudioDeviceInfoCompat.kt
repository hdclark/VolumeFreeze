package com.hdclark.volumefreeze

import android.media.AudioDeviceInfo
import android.os.Build

/**
 * Returns a stable-enough key for Bluetooth output profiles without calling APIs that are
 * unavailable on devices running below Android 9 (API 28).
 */
fun AudioDeviceInfo.bluetoothOutputProfileKey(): String? {
    if (!isBluetoothOutputDevice()) return null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        address.takeIf { it.isNotBlank() }?.let { return it }
    }

    val fallbackName = productName?.toString()?.takeIf { it.isNotBlank() }
    return listOfNotNull("bluetooth", type.toString(), fallbackName)
        .joinToString(separator = "_") { part -> part.toProfileKeyPart() }
        .takeIf { it.isNotBlank() }
}

fun AudioDeviceInfo.bluetoothOutputProfileName(unknownName: String): String =
    productName?.toString()?.takeIf { it.isNotBlank() } ?: unknownName

fun AudioDeviceInfo.isBluetoothOutputDevice(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

private fun String.toProfileKeyPart(): String =
    lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
