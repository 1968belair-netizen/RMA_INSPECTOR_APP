package com.bitzer.rmainspector.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** What the device remembers between launches (spec §3.14). No secrets are stored here (the token is in [AuthStore]). */
data class AppSettings(
    val serverUrl: String,
)

class SettingsStore(private val context: Context, private val defaultServerUrl: String) {
    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        /** Product-type + return-code chips (A4). */
        val WORK_LIST_FILTER = stringPreferencesKey("work_list_chips")
        /** Pre-A4 status chips — no longer used. */
        val OLD_STATUS_FILTER = stringPreferencesKey("work_list_filter")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[Keys.SERVER] ?: defaultServerUrl,
    )

    /** Remembered work-list chips (spec §3.3); null = never chosen (use the server defaults). Kept across sign-out. */
    val workListFilter: Flow<WorkListFilter?> = context.settingsDataStore.data.map { p ->
        p[Keys.WORK_LIST_FILTER]?.let { raw ->
            runCatching { ApiClient.defaultJson.decodeFromString(WorkListFilter.serializer(), raw) }.getOrNull()
        }
    }

    suspend fun setWorkListFilter(filter: WorkListFilter) {
        context.settingsDataStore.edit {
            it[Keys.WORK_LIST_FILTER] = ApiClient.writeJson.encodeToString(WorkListFilter.serializer(), filter)
        }
    }

    /** Back to the server defaults (used by emulator tests). */
    suspend fun clearWorkListFilter() {
        context.settingsDataStore.edit { it.remove(Keys.WORK_LIST_FILTER); it.remove(Keys.OLD_STATUS_FILTER) }
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.SERVER] = url }
    }
}
