package com.hdclark.volumefreeze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
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
        fun streamName(stream: Int): String = when (stream) {
            AudioManager.STREAM_MUSIC        -> "Media"
            AudioManager.STREAM_RING         -> "Ringer"
            AudioManager.STREAM_NOTIFICATION -> "Notifications"
            AudioManager.STREAM_VOICE_CALL   -> "Phone Call"
            AudioManager.STREAM_ALARM        -> "Alarm"
            AudioManager.STREAM_SYSTEM       -> "System"
            AudioManager.STREAM_DTMF         -> "DTMF"
            10                               -> "Accessibility"  // STREAM_ACCESSIBILITY = 10
            else                             -> "Stream $stream"
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

    /** Volumes that should be enforced. */
    private var referenceVolumes: MutableMap<Int, Int> = mutableMapOf()

    /** When true the service runs but does NOT modify any volumes. */
    private var isPaused: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    /** ContentObserver registered on Settings.System for immediate change detection. */
    private var volumeObserver: ContentObserver? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForegroundCompat()

        // Restore state from persistent storage
        isPaused = PrefsManager.loadPauseState(this)
        val saved = PrefsManager.loadReferenceVolumes(this)
        if (saved.isEmpty()) {
            captureReferenceVolumes()
        } else {
            referenceVolumes = saved.toMutableMap()
        }

        startMonitoring()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE           -> handlePause()
            ACTION_RESUME          -> handleResume()
            ACTION_UPDATE_REFERENCE -> captureReferenceVolumes()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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
            PendingIntent.getService(
                this, 1,
                Intent(this, VolumeMonitorService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2,
                Intent(this, VolumeMonitorService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, toggleIntent)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
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
     * the new reference.  Any enforcement in progress is cancelled so the captured
     * values are treated as authoritative.
     */
    fun captureReferenceVolumes() {
        val volumes = mutableMapOf<Int, Int>()
        for (stream in getMonitoredStreams()) {
            try {
                volumes[stream] = audioManager.getStreamVolume(stream)
            } catch (_: Exception) {
                // Stream not available on this device / API level — skip it.
            }
        }
        referenceVolumes = volumes
        PrefsManager.saveReferenceVolumes(this, volumes)
        // Cancel any pending reset so newly captured values are not immediately
        // overwritten by a stale enforcement callback.
        handler.removeCallbacks(resetRunnable)
    }

    /**
     * For each stream that deviates from its reference value, reset it silently
     * (no UI, no sound).
     */
    private fun enforceVolumes() {
        for ((stream, refVolume) in referenceVolumes) {
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
    // Public accessors (used by MainActivity via bound-service pattern or by
    // reading SharedPreferences directly)
    // -------------------------------------------------------------------------

    fun isPaused(): Boolean = isPaused

    fun getReferenceVolumes(): Map<Int, Int> = referenceVolumes.toMap()
}
