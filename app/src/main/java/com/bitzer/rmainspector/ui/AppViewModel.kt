package com.bitzer.rmainspector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.core.AppVersion
import com.bitzer.rmainspector.core.ServerUrl
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.AppSettings
import com.bitzer.rmainspector.data.CodeListRepository
import com.bitzer.rmainspector.data.Health
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.Session
import com.bitzer.rmainspector.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Unknown : Connection
    data object Checking : Connection
    data class Ok(val health: Health, val updateRequired: Boolean) : Connection
    data class Error(val message: String) : Connection
}

/** Sign-in form progress (spec §2). */
data class SignInProgress(
    val signingIn: Boolean = false,
    val error: String? = null,
)

data class AppUiState(
    val settings: AppSettings? = null,
    val connection: Connection = Connection.Unknown,
    /** The signed-in user; null = show the sign-in screen. Initials pre-fill Inspected by / Summary by. */
    val user: User? = null,
    val signIn: SignInProgress = SignInProgress(),
    /** The server rejected the token mid-session: sign-in is shown over the current screen (decision 2026-11-18). */
    val sessionExpired: Boolean = false,
) {
    val signedIn: Boolean get() = user != null
}

/** App-wide state: server address, connection, signed-in user (spec §2, §3.14). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServiceLocator.settings(app)
    private val auth = ServiceLocator.auth(app)
    private val api = ServiceLocator.api
    private val codeLists = ServiceLocator.codeLists(app)
    private val connection = MutableStateFlow<Connection>(Connection.Unknown)
    private val signIn = MutableStateFlow(SignInProgress())

    /** Last username, to pre-fill the sign-in form (not secret). */
    val lastUsername: String get() = auth.lastUsername

    val state: StateFlow<AppUiState> = combine(store.settings, connection, auth.session, signIn, auth.expired) { s, c, session, p, expired ->
        AppUiState(settings = s, connection = c, user = session?.user, signIn = p, sessionExpired = expired)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        // The stored session is read synchronously, so the first frame already knows whether to show sign-in.
        AppUiState(user = auth.session.value?.user, sessionExpired = auth.expired.value),
    )

    init {
        refresh()
        viewModelScope.launch {
            codeLists.loadCached(store.settings.first().serverUrl) // usable offline after one download
            // Every few hours while the app runs and someone is signed in (a 304 when nothing changed).
            while (true) {
                delay(CodeListRepository.CHECK_INTERVAL_MS)
                if (auth.session.value != null) codeLists.refreshIfStale(store.settings.first().serverUrl)
            }
        }
    }

    /**
     * Download code lists now (sign-in, app start / reconnect). Sends the ETag, so an unchanged list costs a
     * `304`. Does nothing while signed out (the server would answer 401).
     */
    fun refreshCodeLists() {
        if (auth.session.value == null) return
        viewModelScope.launch { codeLists.refresh(store.settings.first().serverUrl, force = true) }
    }

    /** Re-check the server (reachability + version). Refreshes the signed-in user's name/initials if possible. */
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
            val session = auth.session.value ?: return@launch
            refreshCodeLists()
            // A 401 here signs the user out via ApiClient → AuthStore.expire.
            (api.me(settings.serverUrl) as? ApiResult.Ok)?.value?.let { user ->
                if (user != session.user && auth.session.value?.token == session.token) auth.save(session.copy(user = user))
            }
        }
    }

    fun signIn(username: String, password: String) {
        if (signIn.value.signingIn) return
        if (username.isBlank() || password.isEmpty()) {
            signIn.value = SignInProgress(error = "Enter your username and password.")
            return
        }
        viewModelScope.launch {
            signIn.value = SignInProgress(signingIn = true)
            val base = store.settings.first().serverUrl
            when (val r = api.login(base, username, password, auth.deviceId)) {
                is ApiResult.Ok -> {
                    auth.save(Session(r.value.token, r.value.user))
                    signIn.value = SignInProgress()
                    refreshCodeLists()
                }
                is ApiResult.Failure -> signIn.value = SignInProgress(error = r.message)
            }
        }
    }

    fun clearSignInError() = signIn.update { it.copy(error = null) }

    /** Settings → Sign out. Forgets the token first, then tells the server (best effort). Queued work is kept. */
    fun signOut() {
        val session = auth.session.value ?: return
        auth.clear()
        viewModelScope.launch {
            api.logout(store.settings.first().serverUrl, session.token)
        }
    }

    /** Saves the server address and tests it. Returns an error message if the text isn't a usable address. */
    fun saveServerUrl(text: String): String? {
        val url = ServerUrl.normalize(text) ?: return "That doesn't look like a server address. Example: 192.168.1.20:8000"
        viewModelScope.launch {
            store.setServerUrl(url)
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
}
