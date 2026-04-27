package com.hdclark.volumefreeze

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages persistent storage of reference volumes and app state using SharedPreferences.
 */
object PrefsManager {

    private const val PREFS_NAME = "volume_freeze_prefs"
    private const val KEY_REFERENCE_VOLUMES = "reference_volumes"
    private const val KEY_IS_PAUSED = "is_paused"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Save a map of {streamType -> volume} as the reference volumes. */
    fun saveReferenceVolumes(context: Context, volumes: Map<Int, Int>) {
        val json = JSONObject()
        volumes.forEach { (stream, volume) -> json.put(stream.toString(), volume) }
        prefs(context).edit().putString(KEY_REFERENCE_VOLUMES, json.toString()).apply()
    }

    /**
     * Load the saved reference volumes.
     * Returns an empty map if none have been saved yet.
     */
    fun loadReferenceVolumes(context: Context): Map<Int, Int> {
        val jsonString = prefs(context).getString(KEY_REFERENCE_VOLUMES, null) ?: return emptyMap()
        return try {
            val json = JSONObject(jsonString)
            val map = mutableMapOf<Int, Int>()
            json.keys().forEach { key -> map[key.toInt()] = json.getInt(key) }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Persist the paused/active enforcement state. */
    fun savePauseState(context: Context, isPaused: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PAUSED, isPaused).apply()
    }

    /** Returns true if enforcement is currently paused. */
    fun loadPauseState(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PAUSED, false)
}
