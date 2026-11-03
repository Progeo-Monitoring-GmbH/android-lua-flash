package com.progeo.luaflasher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val channel: String = DEFAULT_CHANNEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val developerMode: Boolean = false,
    /** Classic DTR/RTS reset for boards that have the circuit; ours do not. */
    val autoReset: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.progeo.com/firmware/"

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
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            channel = p[Keys.channel] ?: AppSettings.DEFAULT_CHANNEL,
            baseUrl = p[Keys.baseUrl] ?: AppSettings.DEFAULT_BASE_URL,
            developerMode = p[Keys.developerMode] ?: false,
            autoReset = p[Keys.autoReset] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setChannel(channel: String) = context.dataStore.edit { it[Keys.channel] = channel }

    suspend fun setBaseUrl(url: String) = context.dataStore.edit {
        val normalized = url.trim().let { u -> if (u.endsWith("/")) u else "$u/" }
        if (normalized == AppSettings.DEFAULT_BASE_URL || url.isBlank()) it.remove(Keys.baseUrl) else it[Keys.baseUrl] = normalized
    }

    suspend fun setDeveloperMode(on: Boolean) = context.dataStore.edit { it[Keys.developerMode] = on }

    suspend fun setAutoReset(on: Boolean) = context.dataStore.edit { it[Keys.autoReset] = on }
}
