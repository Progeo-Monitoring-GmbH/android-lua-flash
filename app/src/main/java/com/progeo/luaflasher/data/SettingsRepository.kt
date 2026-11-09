package com.progeo.luaflasher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.progeo.luaflasher.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val channel: String = DEFAULT_CHANNEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val developerMode: Boolean = false,
    /** Classic DTR/RTS reset for boards that have the circuit; ours do not. */
    val autoReset: Boolean = false,
    /** ProGeo backend for login and controller configs (API.md, API2.md). */
    val backendUrl: String = DEFAULT_BACKEND_URL,
    /** Location last used to look up controllers. */
    val locationId: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.progeo.com/firmware/"
        val DEFAULT_BACKEND_URL: String = BuildConfig.BACKEND_URL

        /** Only beta is published so far; stable answers 404. */
        const val DEFAULT_CHANNEL = "beta"
        val CHANNELS = listOf("stable", "beta")
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val channel = stringPreferencesKey("channel")
        val baseUrl = stringPreferencesKey("base_url")
        val developerMode = booleanPreferencesKey("developer_mode")
        val autoReset = booleanPreferencesKey("auto_reset")
        val backendUrl = stringPreferencesKey("backend_url")
        val locationId = stringPreferencesKey("location_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            channel = p[Keys.channel] ?: AppSettings.DEFAULT_CHANNEL,
            baseUrl = p[Keys.baseUrl] ?: AppSettings.DEFAULT_BASE_URL,
            developerMode = p[Keys.developerMode] ?: false,
            autoReset = p[Keys.autoReset] ?: false,
            backendUrl = p[Keys.backendUrl] ?: AppSettings.DEFAULT_BACKEND_URL,
            locationId = p[Keys.locationId] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setChannel(channel: String) = context.dataStore.edit { it[Keys.channel] = channel }

    suspend fun setBaseUrl(url: String) = context.dataStore.edit {
        val normalized = normalize(url)
        if (normalized == AppSettings.DEFAULT_BASE_URL || url.isBlank()) it.remove(Keys.baseUrl) else it[Keys.baseUrl] = normalized
    }

    suspend fun setBackendUrl(url: String) = context.dataStore.edit {
        val normalized = normalize(url)
        if (normalized == AppSettings.DEFAULT_BACKEND_URL || url.isBlank()) it.remove(Keys.backendUrl) else it[Keys.backendUrl] = normalized
    }

    suspend fun setLocationId(id: String) = context.dataStore.edit { it[Keys.locationId] = id.trim() }

    suspend fun setDeveloperMode(on: Boolean) = context.dataStore.edit { it[Keys.developerMode] = on }

    suspend fun setAutoReset(on: Boolean) = context.dataStore.edit { it[Keys.autoReset] = on }

    private fun normalize(url: String) = url.trim().let { u -> if (u.endsWith("/")) u else "$u/" }
}
