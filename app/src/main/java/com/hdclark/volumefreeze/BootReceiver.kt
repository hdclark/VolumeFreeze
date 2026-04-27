package com.hdclark.volumefreeze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts [VolumeMonitorService] automatically after the device boots or after the
 * app itself is updated (MY_PACKAGE_REPLACED).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                VolumeMonitorService.startService(context)
            }
        }
    }
}
