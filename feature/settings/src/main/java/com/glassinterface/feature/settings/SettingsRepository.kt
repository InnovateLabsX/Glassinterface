package com.glassinterface.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassinterface.core.common.AlertConfig
import com.glassinterface.core.common.SceneMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "glass_settings")

/**
 * Repository for persisting user preferences using DataStore.
 * Now includes server URL for the AI engine connection.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val KEY_SENSITIVITY = floatPreferencesKey("alert_sensitivity")
        private val KEY_SCENE_MODE = stringPreferencesKey("scene_mode")
        private val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    }

    /**
     * Observe the current [AlertConfig] as a reactive flow.
     */
    val alertConfig: Flow<AlertConfig> = context.dataStore.data.map { prefs ->
        AlertConfig(
            sensitivity = prefs[KEY_SENSITIVITY] ?: 0.5f,
            mode = prefs[KEY_SCENE_MODE]?.let { SceneMode.valueOf(it) } ?: SceneMode.OUTDOOR,
            cooldownMs = prefs[KEY_COOLDOWN_MS] ?: 3000L,
            serverUrl = prefs[KEY_SERVER_URL] ?: "http://10.0.2.2:8000"
        )
    }

    suspend fun updateSensitivity(value: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SENSITIVITY] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun updateSceneMode(mode: SceneMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCENE_MODE] = mode.name
        }
    }

    suspend fun updateCooldown(ms: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COOLDOWN_MS] = ms.coerceAtLeast(500L)
        }
    }

    suspend fun updateServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url
        }
    }
}
