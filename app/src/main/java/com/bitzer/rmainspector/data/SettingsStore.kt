package com.bitzer.rmainspector.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** What the device remembers between launches (spec §3.14). No secrets are stored here (the token is in [AuthStore]). */
data class AppSettings(
    val serverUrl: String,
)

class SettingsStore(private val context: Context, private val defaultServerUrl: String) {
    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        val WORK_LIST_FILTER = stringPreferencesKey("work_list_filter")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[Keys.SERVER] ?: defaultServerUrl,
    )

    /** Remembered work-list status filter; null = never chosen (use the server default). */
    val workListFilter: Flow<List<CodeKey>?> = context.settingsDataStore.data.map { p ->
        p[Keys.WORK_LIST_FILTER]?.let { raw ->
            runCatching { ApiClient.defaultJson.decodeFromString(ListSerializer(CodeKey.serializer()), raw) }.getOrNull()
        }
    }

    suspend fun setWorkListFilter(statuses: List<CodeKey>) {
        context.settingsDataStore.edit {
            it[Keys.WORK_LIST_FILTER] = ApiClient.writeJson.encodeToString(ListSerializer(CodeKey.serializer()), statuses)
        }
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.SERVER] = url }
    }
}
