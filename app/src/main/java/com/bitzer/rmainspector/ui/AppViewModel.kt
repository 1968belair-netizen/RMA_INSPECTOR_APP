package com.bitzer.rmainspector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.core.AppVersion
import com.bitzer.rmainspector.core.ServerUrl
import com.bitzer.rmainspector.data.ApiClient
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.AppSettings
import com.bitzer.rmainspector.data.Health
import com.bitzer.rmainspector.data.Inspector
import com.bitzer.rmainspector.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Unknown : Connection
    data object Checking : Connection
    data class Ok(val health: Health, val updateRequired: Boolean) : Connection
    data class Error(val message: String) : Connection
}

data class AppUiState(
    val settings: AppSettings? = null,
    val connection: Connection = Connection.Unknown,
    val inspectors: List<Inspector> = emptyList(),
) {
    /** More than one inspector on the server and none chosen yet → show "Who's inspecting?". */
    val needsInspectorPick: Boolean get() = inspectors.size > 1 && settings?.inspector == null
}

/** App-wide state: server address, connection, inspector (spec §2, §3.1, §3.14). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app, BuildConfig.DEFAULT_SERVER_URL)
    private val api = ApiClient()
    private val connection = MutableStateFlow<Connection>(Connection.Unknown)
    private val inspectors = MutableStateFlow<List<Inspector>>(emptyList())

    val state: StateFlow<AppUiState> = combine(store.settings, connection, inspectors) { s, c, i ->
        AppUiState(settings = s, connection = c, inspectors = i)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        refresh()
    }

    /** Re-check the server and reload the inspector list. */
    fun refresh() {
        viewModelScope.launch {
            val settings = store.settings.first()
            connection.value = Connection.Checking
            when (val h = api.health(settings.serverUrl)) {
                is ApiResult.Failure -> {
                    connection.value = Connection.Error(h.message)
                    return@launch
                }
                is ApiResult.Ok -> connection.value = Connection.Ok(
                    h.value,
                    updateRequired = !AppVersion.isAtLeast(BuildConfig.VERSION_NAME, h.value.minAppVersion),
                )
            }
            when (val r = api.inspectors(settings.serverUrl)) {
                is ApiResult.Failure -> connection.value = Connection.Error(r.message)
                is ApiResult.Ok -> {
                    val list = r.value.inspectors
                    inspectors.value = list
                    val current = settings.inspector
                    when {
                        list.size == 1 && current?.username != list[0].username -> store.setInspector(list[0])
                        current != null -> list.firstOrNull { it.username.equals(current.username, true) }
                            ?.let { if (it != current) store.setInspector(it) }
                            ?: store.setInspector(null) // removed from the server's list
                    }
                }
            }
        }
    }

    /** Saves the server address and tests it. Returns an error message if the text isn't a usable address. */
    fun saveServerUrl(text: String): String? {
        val url = ServerUrl.normalize(text) ?: return "That doesn't look like a server address. Example: 192.168.1.20:8000"
        viewModelScope.launch {
            store.setServerUrl(url)
            inspectors.value = emptyList()
            refresh()
        }
        return null
    }

    fun resetServerUrl() {
        viewModelScope.launch {
            store.setServerUrl(BuildConfig.DEFAULT_SERVER_URL)
            refresh()
        }
    }

    fun selectInspector(inspector: Inspector) {
        viewModelScope.launch { store.setInspector(inspector) }
    }
}
