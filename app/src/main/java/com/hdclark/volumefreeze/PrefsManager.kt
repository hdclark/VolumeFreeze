package com.hdclark.volumefreeze

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AudioOutputProfile(
    val key: String,
    val name: String,
    val isBluetooth: Boolean,
)

/**
 * Manages persistent storage of reference volumes and app state using SharedPreferences.
 *
 * Reference volumes are stored per audio-output device:
 *  - Key [DEVICE_KEY_PHONE] for the built-in phone speaker / wired output.
 *  - The Bluetooth device address when available, or a routed-device fallback key on API 24–27.
 *
 * Enabled-streams: the set of [AudioManager] stream types that should be actively enforced.
 * A null/absent value means all monitored streams are enabled (default).
 */
object PrefsManager {

    private const val PREFS_NAME = "volume_freeze_prefs"

    /** Legacy key kept for migration (phone-speaker volumes saved before per-device support). */
    private const val KEY_REFERENCE_VOLUMES_LEGACY = "reference_volumes"

    /** Prefix for per-device reference volume keys: "reference_volumes_<deviceKey>". */
    private const val KEY_REFERENCE_PREFIX = "reference_volumes_"

    private const val KEY_IS_PAUSED = "is_paused"
    private const val KEY_ENABLED_STREAMS = "enabled_streams"
    private const val KEY_KNOWN_OUTPUTS = "known_outputs"

    /** SharedPreferences key used for the built-in phone speaker / wired output. */
    const val DEVICE_KEY_PHONE = "phone_speaker"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Reference volumes (per device)
    // -------------------------------------------------------------------------

    /** Save a map of {streamType → volume} as the reference volumes for [deviceKey]. */
    fun saveReferenceVolumes(context: Context, deviceKey: String, volumes: Map<Int, Int>) {
        val json = JSONObject()
        volumes.forEach { (stream, volume) -> json.put(stream.toString(), volume) }
        prefs(context).edit()
            .putString(KEY_REFERENCE_PREFIX + deviceKey, json.toString())
            .apply()
    }

    /**
     * Load the saved reference volumes for [deviceKey].
     * For [DEVICE_KEY_PHONE], also checks the legacy key so existing users retain their data.
     * Returns an empty map if no data has been saved yet.
     */
    fun loadReferenceVolumes(context: Context, deviceKey: String): Map<Int, Int> {
        val p = prefs(context)
        val raw = p.getString(KEY_REFERENCE_PREFIX + deviceKey, null)
            ?: if (deviceKey == DEVICE_KEY_PHONE) p.getString(KEY_REFERENCE_VOLUMES_LEGACY, null) else null
        val jsonString = raw ?: return emptyMap()
        return parseVolumesJson(jsonString)
    }

    private fun parseVolumesJson(jsonString: String): Map<Int, Int> =
        try {
            val json = JSONObject(jsonString)
            val map = mutableMapOf<Int, Int>()
            json.keys().forEach { key -> map[key.toInt()] = json.getInt(key) }
            map
        } catch (_: Exception) {
            emptyMap()
        }

    fun rememberOutputDevice(context: Context, key: String, name: String, isBluetooth: Boolean) {
        if (key == DEVICE_KEY_PHONE) return
        val outputs = loadKnownOutputDevices(context).associateBy { it.key }.toMutableMap()
        outputs[key] = AudioOutputProfile(key, name, isBluetooth)
        saveKnownOutputDevices(context, outputs.values)
    }

    fun loadKnownOutputDevices(context: Context): List<AudioOutputProfile> {
        val jsonString = prefs(context).getString(KEY_KNOWN_OUTPUTS, null) ?: return emptyList()
        return try {
            val json = JSONArray(jsonString)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val key = item.optString("key")
                    val name = item.optString("name")
                    if (key.isNotBlank() && name.isNotBlank()) {
                        add(AudioOutputProfile(key, name, item.optBoolean("bluetooth", true)))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveKnownOutputDevices(context: Context, outputs: Collection<AudioOutputProfile>) {
        val json = JSONArray()
        outputs.sortedWith(compareBy<AudioOutputProfile> { !it.isBluetooth }.thenBy { it.name.lowercase() })
            .forEach { output ->
                json.put(JSONObject().apply {
                    put("key", output.key)
                    put("name", output.name)
                    put("bluetooth", output.isBluetooth)
                })
            }
        prefs(context).edit().putString(KEY_KNOWN_OUTPUTS, json.toString()).apply()
    }

    // -------------------------------------------------------------------------
    // Enabled streams
    // -------------------------------------------------------------------------

    /**
     * Persist which stream types should be actively enforced.
     * Pass the full set of monitored streams to enable all of them.
     */
    fun saveEnabledStreams(context: Context, enabledStreams: Set<Int>) {
        val json = JSONArray()
        enabledStreams.forEach { json.put(it) }
        prefs(context).edit().putString(KEY_ENABLED_STREAMS, json.toString()).apply()
    }

    /**
     * Load the set of enabled stream types.
     * Returns `null` if nothing has been saved (caller should treat `null` as "all enabled").
     */
    fun loadEnabledStreams(context: Context): Set<Int>? {
        val jsonString = prefs(context).getString(KEY_ENABLED_STREAMS, null) ?: return null
        return try {
            val json = JSONArray(jsonString)
            val set = mutableSetOf<Int>()
            for (i in 0 until json.length()) set.add(json.getInt(i))
            set
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Pause state
    // -------------------------------------------------------------------------

    /** Persist the paused/active enforcement state. */
    fun savePauseState(context: Context, isPaused: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PAUSED, isPaused).apply()
    }

    /** Returns true if enforcement is currently paused. */
    fun loadPauseState(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PAUSED, false)
}
