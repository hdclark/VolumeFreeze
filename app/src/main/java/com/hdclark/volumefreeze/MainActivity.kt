package com.hdclark.volumefreeze

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.hdclark.volumefreeze.databinding.ActivityMainBinding

/**
 * Main activity.
 *
 * Responsibilities:
 *  - Start [VolumeMonitorService] when the app opens.
 *  - Detect whether battery optimizations are enabled and direct the user to disable them.
 *  - Show the current state of each volume stream vs. the saved reference values.
 *  - Allow the user to pause / resume enforcement and to save new reference volumes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS)
        }
    }

    companion object {
        private const val UI_REFRESH_INTERVAL_MS = 1_000L
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupButtons()
        requestNotificationPermissionIfNeeded()

        // Ensure the service is running
        VolumeMonitorService.startService(this)
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Button setup
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnTogglePause.setOnClickListener {
            val isPaused = PrefsManager.loadPauseState(this)
            val action = if (isPaused) {
                VolumeMonitorService.ACTION_RESUME
            } else {
                VolumeMonitorService.ACTION_PAUSE
            }
            sendServiceAction(action)
        }

        binding.btnUpdateReference.setOnClickListener {
            sendServiceAction(VolumeMonitorService.ACTION_UPDATE_REFERENCE)
        }

        binding.btnBatteryOptimization.setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, VolumeMonitorService::class.java).apply {
            this.action = action
        }
        startService(intent)
        // Refresh after a short delay to let the service process the action
        refreshHandler.postDelayed({ refreshUi() }, 300)
    }

    // -------------------------------------------------------------------------
    // Battery optimization
    // -------------------------------------------------------------------------

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fall back to general battery optimization settings screen
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // -------------------------------------------------------------------------
    // UI refresh
    // -------------------------------------------------------------------------

    private fun refreshUi() {
        val isPaused = PrefsManager.loadPauseState(this)
        val referenceVolumes = PrefsManager.loadReferenceVolumes(this)
        val batteryOptimized = isBatteryOptimized()

        // Status chip — tint the pill background to keep rounded corners
        val statusColorRes = if (isPaused) R.color.status_paused else R.color.status_active
        binding.tvStatus.text = getString(
            if (isPaused) R.string.status_paused else R.string.status_active
        )
        ViewCompat.setBackgroundTintList(
            binding.tvStatus,
            ColorStateList.valueOf(ContextCompat.getColor(this, statusColorRes))
        )

        // Pause / Resume button label
        binding.btnTogglePause.text = if (isPaused) {
            getString(R.string.action_resume)
        } else {
            getString(R.string.action_pause)
        }

        // Battery optimization warning
        if (batteryOptimized) {
            binding.cardBatteryWarning.visibility = View.VISIBLE
        } else {
            binding.cardBatteryWarning.visibility = View.GONE
        }

        // Volume table
        buildVolumeTable(referenceVolumes)
    }

    /**
     * Creates a single table cell (TextView) for the volume table.
     * @param text Cell text content
     * @param weight Layout weight for proportional column sizing
     */
    private fun makeVolumeCell(text: String, weight: Float = 1f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight)
        }

    /**
     * Dynamically populates the volume table with one row per monitored stream,
     * showing the current volume alongside the saved reference value.
     */
    private fun buildVolumeTable(referenceVolumes: Map<Int, Int>) {
        val table = binding.tableVolumes
        // Remove all rows except the header (index 0)
        while (table.childCount > 1) {
            table.removeViewAt(1)
        }

        val streams = VolumeMonitorService.getMonitoredStreams()
        for (stream in streams) {
            val currentVolume = try {
                audioManager.getStreamVolume(stream)
            } catch (_: Exception) {
                continue  // Stream not available — skip
            }
            val maxVolume = try {
                audioManager.getStreamMaxVolume(stream)
            } catch (_: Exception) {
                continue
            }
            val refVolume = referenceVolumes[stream]

            val row = TableRow(this).apply {
                setPadding(0, 8, 0, 8)
            }

            row.addView(makeVolumeCell(VolumeMonitorService.streamName(stream), 1.5f))
            row.addView(makeVolumeCell("$currentVolume / $maxVolume"))
            row.addView(makeVolumeCell(refVolume?.toString() ?: getString(R.string.label_not_set)))

            // Highlight row if the current volume deviates from reference
            if (refVolume != null && currentVolume != refVolume) {
                row.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.row_mismatch)
                )
            }

            table.addView(row)
        }
    }
}
