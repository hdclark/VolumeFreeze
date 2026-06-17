package com.hdclark.volumefreeze

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Foreground service that continuously monitors all accessible audio volume streams and
 * resets them to the stored reference values whenever an unwanted change is detected.
 *
 * Detection strategy (in order of priority):
 *  1. ContentObserver on Settings.System.CONTENT_URI – fires within milliseconds of any change.
 *  2. Polling fallback every [POLLING_INTERVAL_MS] milliseconds – catches anything the
 *     observer may have missed.
 *
 * Ducking handling: when a change is detected via the ContentObserver, the reset is
 * intentionally delayed by [DUCKING_DELAY_MS] so that brief system-driven ducks (e.g.
 * for notification sounds) can complete before we restore the reference level.  The
 * polling loop still resets any lingering deviations within ~10 s.
 *
 * Bluetooth support: separate reference volumes are stored for the built-in phone speaker
 * and for each A2DP Bluetooth audio device (identified by address when available, or a routed-device fallback key
 * on API 24–27).  The service
 * listens for A2DP connection-state changes and automatically switches to the appropriate
 * reference set when a device connects or disconnects.
 */
class VolumeMonitorService : Service() {

    // -------------------------------------------------------------------------
    // Companions / constants
    // -------------------------------------------------------------------------

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "volume_freeze_channel"

        /** How often the fallback polling loop checks for volume deviations. */
        const val POLLING_INTERVAL_MS = 10_000L

        /**
         * How long to wait after detecting a volume change before resetting it.
         * This allows brief ducking events (e.g. notification sounds) to complete
         * without being immediately cancelled.
         */
        const val DUCKING_DELAY_MS = 3_000L

        // Service action constants
        const val ACTION_PAUSE = "com.hdclark.volumefreeze.ACTION_PAUSE"
        const val ACTION_RESUME = "com.hdclark.volumefreeze.ACTION_RESUME"
        const val ACTION_UPDATE_REFERENCE = "com.hdclark.volumefreeze.ACTION_UPDATE_REFERENCE"
        const val EXTRA_DEVICE_KEY = "com.hdclark.volumefreeze.EXTRA_DEVICE_KEY"

        /** All volume streams we attempt to monitor.  STREAM_ACCESSIBILITY guarded to API 26+. */
        fun getMonitoredStreams(): List<Int> = buildList {
            add(AudioManager.STREAM_MUSIC)
            add(AudioManager.STREAM_RING)
            add(AudioManager.STREAM_NOTIFICATION)
            add(AudioManager.STREAM_VOICE_CALL)
            add(AudioManager.STREAM_ALARM)
            add(AudioManager.STREAM_SYSTEM)
            add(AudioManager.STREAM_DTMF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(AudioManager.STREAM_ACCESSIBILITY)
            }
        }

        /** Human-readable names for each stream constant. */
        fun streamName(stream: Int): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                stream == AudioManager.STREAM_ACCESSIBILITY
            ) {
                return "Accessibility"
            }

            return when (stream) {
                AudioManager.STREAM_MUSIC        -> "Media"
                AudioManager.STREAM_RING         -> "Ringer"
                AudioManager.STREAM_NOTIFICATION -> "Notifications"
                AudioManager.STREAM_VOICE_CALL   -> "Phone Call"
                AudioManager.STREAM_ALARM        -> "Alarm"
                AudioManager.STREAM_SYSTEM       -> "System"
                AudioManager.STREAM_DTMF         -> "DTMF"
                else                             -> "Stream $stream"
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, VolumeMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    /** Volumes that should be enforced (for the current audio output device). */
    private var referenceVolumes: MutableMap<Int, Int> = mutableMapOf()

    /** When true the service runs but does NOT modify any volumes. */
    private var isPaused: Boolean = false

    /** Device key identifying the current audio output device. */
    private var currentDeviceKey: String = PrefsManager.DEVICE_KEY_PHONE

    private val handler = Handler(Looper.getMainLooper())

    /** ContentObserver registered on Settings.System for immediate change detection. */
    private var volumeObserver: ContentObserver? = null

    /** BroadcastReceiver for Bluetooth connection-state changes. */
    private var btReceiver: BroadcastReceiver? = null

    /** Audio device callback catches route changes even when Bluetooth profile broadcasts lag. */
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForegroundCompat()

        // Determine which audio device is active, then restore state
        currentDeviceKey = getCurrentOutputDeviceKey()
        isPaused = PrefsManager.loadPauseState(this)
        val saved = PrefsManager.loadReferenceVolumes(this, currentDeviceKey)
        if (saved.isEmpty()) {
            captureReferenceVolumes()
        } else {
            referenceVolumes = saved.toMutableMap()
        }

        registerBluetoothReceiver()
        registerAudioDeviceCallback()
        startMonitoring()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE           -> handlePause()
            ACTION_RESUME          -> handleResume()
            ACTION_UPDATE_REFERENCE -> captureReferenceVolumes(intent.getStringExtra(EXTRA_DEVICE_KEY))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        unregisterAudioDeviceCallback()
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Foreground service helpers
    // -------------------------------------------------------------------------

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = if (isPaused) {
            createServiceActionPendingIntent(1, ACTION_RESUME)
        } else {
            createServiceActionPendingIntent(2, ACTION_PAUSE)
        }

        val statusText = if (isPaused) {
            getString(R.string.status_paused)
        } else {
            getString(R.string.status_active)
        }

        val toggleLabel = if (isPaused) {
            getString(R.string.action_resume)
        } else {
            getString(R.string.action_pause)
        }

        val toggleIcon = if (isPaused) {
            R.drawable.ic_action_resume
        } else {
            R.drawable.ic_action_pause
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.action_update_reference_short),
                createServiceActionPendingIntent(3, ACTION_UPDATE_REFERENCE)
            )
            .build()
    }

    private fun createServiceActionPendingIntent(
        requestCode: Int,
        action: String,
    ): PendingIntent {
        val intent = Intent(this, VolumeMonitorService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // -------------------------------------------------------------------------
    // Bluetooth A2DP tracking
    // -------------------------------------------------------------------------

    /**
     * Returns the profile key of the first routed Bluetooth output device, or
     * [PrefsManager.DEVICE_KEY_PHONE] if none is connected (or permission is missing).
     */
    @SuppressLint("MissingPermission")
    fun getCurrentOutputDeviceKey(): String = getCurrentBluetoothOutputProfile()?.key
        ?: PrefsManager.DEVICE_KEY_PHONE

    /**
     * Prefer the routed output devices reported by AudioManager over A2DP profile
     * connection state. Some devices are connected but not the active media route,
     * and profile broadcasts can arrive before routing has completed.
     */
    private fun getCurrentBluetoothOutputProfile(): AudioOutputProfile? = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.bluetoothOutputProfileKey() != null }
            ?.let { device ->
                val profileKey = device.bluetoothOutputProfileKey() ?: return@let null
                AudioOutputProfile(
                    key = profileKey,
                    name = device.bluetoothOutputProfileName(
                        getString(R.string.label_unknown_bluetooth_device)
                    ),
                    isBluetooth = true
                )
            }
    } catch (_: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun rememberCurrentOutputDevice() {
        getCurrentBluetoothOutputProfile()?.let { profile ->
            PrefsManager.rememberOutputDevice(this, profile.key, profile.name, true)
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                    state == BluetoothProfile.STATE_CONNECTED ||
                    state == BluetoothProfile.STATE_DISCONNECTED
                ) {
                    onBluetoothConnectionChanged()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED is a system broadcast — use
            // RECEIVER_EXPORTED so the system Bluetooth stack can deliver it.
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(btReceiver, filter)
        }
    }

    private fun unregisterBluetoothReceiver() {
        btReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        btReceiver = null
    }


    private fun registerAudioDeviceCallback() {
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.any { it.isBluetoothOutputDevice() }) onBluetoothConnectionChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isBluetoothOutputDevice() }) onBluetoothConnectionChanged()
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    private fun unregisterAudioDeviceCallback() {
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
    }

    /**
     * Called when an A2DP device connects or disconnects.
     * Switches to the reference volumes for the newly active output device.
     * If no volumes have been saved for the device yet, captures current volumes as the
     * initial reference.
     */
    private fun onBluetoothConnectionChanged() {
        // Small delay to allow the system to finalize the connection state
        handler.postDelayed({
            val newKey = getCurrentOutputDeviceKey()
            rememberCurrentOutputDevice()
            if (newKey == currentDeviceKey) return@postDelayed
            currentDeviceKey = newKey
            val saved = PrefsManager.loadReferenceVolumes(this, currentDeviceKey)
            if (saved.isEmpty()) {
                captureReferenceVolumes()
            } else {
                referenceVolumes = saved.toMutableMap()
                // Cancel any pending enforcement so newly loaded values take effect cleanly
                handler.removeCallbacks(resetRunnable)
            }
        }, 500L)
    }

    // -------------------------------------------------------------------------
    // Monitoring
    // -------------------------------------------------------------------------

    private fun startMonitoring() {
        // Primary: ContentObserver for near-instant detection
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleVolumeEnforcement()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )

        // Fallback: polling loop every POLLING_INTERVAL_MS
        handler.post(pollingRunnable)
    }

    private fun stopMonitoring() {
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Schedules a volume enforcement check after [DUCKING_DELAY_MS].
     * Any pending reset is cancelled first so that rapid successive changes
     * (e.g. during ducking) are coalesced into a single enforcement call.
     */
    private fun scheduleVolumeEnforcement() {
        if (isPaused) return
        handler.removeCallbacks(resetRunnable)
        handler.postDelayed(resetRunnable, DUCKING_DELAY_MS)
    }

    private val resetRunnable = Runnable {
        if (!isPaused) enforceVolumes()
    }

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) enforceVolumes()
            handler.postDelayed(this, POLLING_INTERVAL_MS)
        }
    }

    // -------------------------------------------------------------------------
    // Volume management
    // -------------------------------------------------------------------------

    /**
     * Reads the current volume for every monitored stream and saves the values as
     * the new reference for the current audio output device.  Any enforcement in
     * progress is cancelled so the captured values are treated as authoritative.
     */
    fun captureReferenceVolumes(deviceKeyOverride: String? = null) {
        val activeDeviceKey = getCurrentOutputDeviceKey()
        currentDeviceKey = activeDeviceKey
        rememberCurrentOutputDevice()
        val saveDeviceKey = deviceKeyOverride ?: activeDeviceKey
        val volumes = mutableMapOf<Int, Int>()
        for (stream in getMonitoredStreams()) {
            try {
                volumes[stream] = audioManager.getStreamVolume(stream)
            } catch (_: Exception) {
                // Stream not available on this device / API level — skip it.
            }
        }
        if (saveDeviceKey == activeDeviceKey) {
            referenceVolumes = volumes
        }
        PrefsManager.saveReferenceVolumes(this, saveDeviceKey, volumes)
        updateNotification()
        // Cancel any pending reset so newly captured values are not immediately
        // overwritten by a stale enforcement callback.
        handler.removeCallbacks(resetRunnable)
    }

    /**
     * For each stream that is enabled for enforcement and deviates from its reference
     * value, reset it silently (no UI, no sound).
     */
    private fun enforceVolumes() {
        val newKey = getCurrentOutputDeviceKey()
        if (newKey != currentDeviceKey) {
            currentDeviceKey = newKey
            rememberCurrentOutputDevice()
            val saved = PrefsManager.loadReferenceVolumes(this, currentDeviceKey)
            referenceVolumes = if (saved.isEmpty()) {
                captureReferenceVolumes()
                referenceVolumes
            } else {
                saved.toMutableMap()
            }
            // Cancel any pending enforcement so newly loaded values take effect cleanly
            handler.removeCallbacks(resetRunnable)
        }

        val enabledStreams = PrefsManager.loadEnabledStreams(this)
        for ((stream, refVolume) in referenceVolumes) {
            // Skip streams that the user has disabled for enforcement
            if (enabledStreams != null && stream !in enabledStreams) continue
            try {
                val current = audioManager.getStreamVolume(stream)
                if (current != refVolume) {
                    audioManager.setStreamVolume(stream, refVolume, 0)
                }
            } catch (_: SecurityException) {
                // Some streams (e.g. STREAM_RING in DND) require special permission.
            } catch (_: Exception) {
                // Stream unavailable or otherwise non-modifiable — ignore.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pause / Resume
    // -------------------------------------------------------------------------

    private fun handlePause() {
        isPaused = true
        PrefsManager.savePauseState(this, true)
        handler.removeCallbacks(resetRunnable)
        updateNotification()
    }

    private fun handleResume() {
        isPaused = false
        PrefsManager.savePauseState(this, false)
        // Capture fresh reference volumes when resuming
        captureReferenceVolumes()
        updateNotification()
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    fun isPaused(): Boolean = isPaused

    fun getReferenceVolumes(): Map<Int, Int> = referenceVolumes.toMap()
}
