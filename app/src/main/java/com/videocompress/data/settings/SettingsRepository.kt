package com.videocompress.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val TARGET_BITRATE_MBPS = intPreferencesKey("target_bitrate_mbps")
        val TARGET_RESOLUTION = intPreferencesKey("target_resolution")
        val MIN_FILE_SIZE_MB = longPreferencesKey("min_file_size_mb")

        const val DEFAULT_BITRATE_MBPS = 8
        const val DEFAULT_RESOLUTION = 1080
        const val DEFAULT_MIN_SIZE_MB = 100L

        const val RESOLUTION_720P = 720
        const val RESOLUTION_1080P = 1080
        const val RESOLUTION_ORIGINAL = -1
    }

    val targetBitrateMbps: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TARGET_BITRATE_MBPS] ?: DEFAULT_BITRATE_MBPS
    }

    val targetResolution: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TARGET_RESOLUTION] ?: DEFAULT_RESOLUTION
    }

    val minFileSizeMb: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[MIN_FILE_SIZE_MB] ?: DEFAULT_MIN_SIZE_MB
    }

    suspend fun setTargetBitrateMbps(value: Int) {
        context.dataStore.edit { it[TARGET_BITRATE_MBPS] = value }
    }

    suspend fun setTargetResolution(value: Int) {
        context.dataStore.edit { it[TARGET_RESOLUTION] = value }
    }

    suspend fun setMinFileSizeMb(value: Long) {
        context.dataStore.edit { it[MIN_FILE_SIZE_MB] = value }
    }
}
