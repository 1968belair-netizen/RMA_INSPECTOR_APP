package com.bitzer.rmainspector.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** What the device remembers between launches (spec §3.14). No secrets are stored here. */
data class AppSettings(
    val serverUrl: String,
    val inspector: Inspector?,
)

class SettingsStore(private val context: Context, private val defaultServerUrl: String) {
    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        val INSP_USER = stringPreferencesKey("inspector_username")
        val INSP_NAME = stringPreferencesKey("inspector_display_name")
        val INSP_INITIALS = stringPreferencesKey("inspector_initials")
        val INSP_AUDIT = stringPreferencesKey("inspector_audit_user_id")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[Keys.SERVER] ?: defaultServerUrl,
        inspector = this[Keys.INSP_USER]?.let {
            Inspector(
                username = it,
                displayName = this[Keys.INSP_NAME] ?: it,
                initials = this[Keys.INSP_INITIALS] ?: "",
                auditUserId = this[Keys.INSP_AUDIT] ?: "",
            )
        },
    )

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.SERVER] = url }
    }

    suspend fun setInspector(inspector: Inspector?) {
        context.settingsDataStore.edit {
            if (inspector == null) {
                it.remove(Keys.INSP_USER); it.remove(Keys.INSP_NAME); it.remove(Keys.INSP_INITIALS); it.remove(Keys.INSP_AUDIT)
            } else {
                it[Keys.INSP_USER] = inspector.username
                it[Keys.INSP_NAME] = inspector.displayName
                it[Keys.INSP_INITIALS] = inspector.initials
                it[Keys.INSP_AUDIT] = inspector.auditUserId
            }
        }
    }
}
