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
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hdclark.volumefreeze.databinding.ActivityMainBinding

/**
 * Main activity.
 *
 * Responsibilities:
 *  - Start [VolumeMonitorService] when the app opens.
 *  - Detect whether battery optimizations are enabled and direct the user to disable them.
 *  - Show the current state of each volume stream vs. the saved reference values.
 *  - Allow the user to pause / resume enforcement, save new reference volumes, and toggle
 *    per-stream enforcement via checkboxes.
 *  - Display the currently active audio output device (phone speaker or Bluetooth name).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private var selectedProfileKey: String = PrefsManager.DEVICE_KEY_PHONE
    private var lastActiveProfileKey: String? = null
    private var isUpdatingProfileSpinner = false

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

        // Push content below the status bar so the title is not obscured by the bezel
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            windowInsets
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupButtons()
        requestPermissionsIfNeeded()

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

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                REQUEST_NOTIFICATION_PERMISSION
            )
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
            sendServiceAction(VolumeMonitorService.ACTION_UPDATE_REFERENCE, selectedProfileKey)
        }

        binding.btnBatteryOptimization.setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun sendServiceAction(action: String, deviceKey: String? = null) {
        val intent = Intent(this, VolumeMonitorService::class.java).apply {
            this.action = action
            deviceKey?.let { putExtra(VolumeMonitorService.EXTRA_DEVICE_KEY, it) }
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
    // Bluetooth helpers
    // -------------------------------------------------------------------------

    private fun getCurrentOutputProfile(): AudioOutputProfile {
        val bluetoothProfile = try {
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

        bluetoothProfile?.let {
            PrefsManager.rememberOutputDevice(this, it.key, it.name, true)
            return it
        }

        return AudioOutputProfile(
            PrefsManager.DEVICE_KEY_PHONE,
            getString(R.string.label_phone_speaker),
            false
        )
    }

    // -------------------------------------------------------------------------
    // UI refresh
    // -------------------------------------------------------------------------

    private fun refreshUi() {
        val isPaused = PrefsManager.loadPauseState(this)
        val activeProfile = getCurrentOutputProfile()
        val deviceKey = activeProfile.key
        if (lastActiveProfileKey != deviceKey) {
            selectedProfileKey = deviceKey
            lastActiveProfileKey = deviceKey
        }
        val referenceVolumes = PrefsManager.loadReferenceVolumes(this, selectedProfileKey)
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

        // Audio device label
        binding.tvAudioDevice.text = getString(R.string.label_audio_device, activeProfile.name)

        // Known output profiles
        buildKnownOutputs(deviceKey)
        buildProfileSelector(deviceKey)

        // Volume table
        buildVolumeTable(referenceVolumes)
    }

    private fun buildKnownOutputs(activeDeviceKey: String) {
        val phone = AudioOutputProfile(
            PrefsManager.DEVICE_KEY_PHONE,
            getString(R.string.label_phone_speaker),
            false
        )
        val outputs = listOf(phone) + PrefsManager.loadKnownOutputDevices(this)
        binding.tvKnownOutputs.text = outputs.distinctBy { it.key }.joinToString("\n") { output ->
            val savedStreams = PrefsManager.loadReferenceVolumes(this, output.key).size
            val prefix = if (output.key == activeDeviceKey) "• " else "  "
            prefix + getString(R.string.known_output_saved, output.name, savedStreams)
        }
    }

    private fun buildProfileSelector(activeDeviceKey: String) {
        val profiles = getAllOutputProfiles()
        val labels = profiles.map { profile ->
            if (profile.key == activeDeviceKey) {
                getString(R.string.reference_profile_active, profile.name)
            } else {
                profile.name
            }
        }
        val selectedIndex = profiles.indexOfFirst { it.key == selectedProfileKey }.coerceAtLeast(0)

        isUpdatingProfileSpinner = true
        binding.spinnerReferenceProfile.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerReferenceProfile.setSelection(selectedIndex, false)
        isUpdatingProfileSpinner = false

        binding.spinnerReferenceProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingProfileSpinner) return
                selectedProfileKey = profiles.getOrNull(position)?.key ?: PrefsManager.DEVICE_KEY_PHONE
                buildVolumeTable(PrefsManager.loadReferenceVolumes(this@MainActivity, selectedProfileKey))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun getAllOutputProfiles(): List<AudioOutputProfile> {
        val phone = AudioOutputProfile(
            PrefsManager.DEVICE_KEY_PHONE,
            getString(R.string.label_phone_speaker),
            false
        )
        return (listOf(phone) + PrefsManager.loadKnownOutputDevices(this)).distinctBy { it.key }
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
     * showing the current volume alongside the saved reference value and a checkbox
     * that toggles whether the stream is enforced.
     */
    private fun buildVolumeTable(referenceVolumes: Map<Int, Int>) {
        val table = binding.tableVolumes
        // Remove all rows except the header (index 0)
        while (table.childCount > 1) {
            table.removeViewAt(1)
        }

        val streams = VolumeMonitorService.getMonitoredStreams()
        val enabledStreams = PrefsManager.loadEnabledStreams(this)
            ?: streams.toSet()   // null means all are enabled

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

            // Enforce checkbox — set checked state before attaching listener to avoid spurious callbacks
            val checkbox = CheckBox(this).apply {
                isChecked = stream in enabledStreams
                layoutParams = TableRow.LayoutParams(
                    0, TableRow.LayoutParams.WRAP_CONTENT, 0.8f
                ).apply { gravity = android.view.Gravity.CENTER }
                // Attach listener after setting isChecked so it only fires on user interaction
                setOnCheckedChangeListener { _, checked ->
                    val current = PrefsManager.loadEnabledStreams(this@MainActivity)
                        ?: VolumeMonitorService.getMonitoredStreams().toSet()
                    val updated = if (checked) current + stream else current - stream
                    PrefsManager.saveEnabledStreams(this@MainActivity, updated)
                }
            }
            row.addView(checkbox)

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

