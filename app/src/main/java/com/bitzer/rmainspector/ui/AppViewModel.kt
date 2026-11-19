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
import com.bitzer.rmainspector.data.QueueItem
import com.bitzer.rmainspector.data.QueueItemRules
import com.bitzer.rmainspector.data.QueueRules
import com.bitzer.rmainspector.data.SignOutPrompt
import com.bitzer.rmainspector.data.SignOutRules
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
    /** The whole upload queue on this tablet (all inspectors); Settings groups it (spec §6.6). */
    val queue: List<QueueItem> = emptyList(),
    /** Sign out tapped with uploads waiting (spec §2); null = no prompt. */
    val signOutPrompt: SignOutPrompt? = null,
    /** "N uploads waiting from your earlier session" on Home after signing in; 0 = no banner. */
    val earlierUploads: Int = 0,
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
    private val queue = ServiceLocator.uploadQueue(app)
    private val engine = ServiceLocator.uploadQueueEngine(app)
    private val signOutPrompt = MutableStateFlow<SignOutPrompt?>(null)

    /** The inspector who signed in while some of their uploads were held: Home shows the banner for them. */
    private val earlierFor = MutableStateFlow<String?>(null)

    /** Last username, to pre-fill the sign-in form (not secret). */
    val lastUsername: String get() = auth.lastUsername

    private val base = combine(store.settings, connection, auth.session, signIn, auth.expired) { s, c, session, p, expired ->
        AppUiState(settings = s, connection = c, user = session?.user, signIn = p, sessionExpired = expired)
    }

    val state: StateFlow<AppUiState> = combine(base, queue.items, signOutPrompt, earlierFor) { b, items, prompt, earlier ->
        val u = b.user?.username
        b.copy(
            queue = items,
            signOutPrompt = if (u != null) prompt else null,
            earlierUploads = if (u != null && earlier == u) QueueRules.waitingFor(items, u).size else 0,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        // The stored session is read synchronously, so the first frame already knows whether to show sign-in.
        AppUiState(user = auth.session.value?.user, sessionExpired = auth.expired.value, queue = queue.snapshot()),
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
                    // Uploads this inspector left behind are held until now: say so on Home; the queue sends them (spec §2).
                    earlierFor.value = r.value.user.username.takeIf { QueueRules.waitingFor(queue.snapshot(), it).isNotEmpty() }
                    signOutPrompt.value = null
                    auth.save(Session(r.value.token, r.value.user))
                    signIn.value = SignInProgress()
                    refreshCodeLists()
                }
                is ApiResult.Failure -> signIn.value = SignInProgress(error = r.message)
            }
        }
    }

    fun clearSignInError() = signIn.update { it.copy(error = null) }

    /**
     * Settings → Sign out. With nothing waiting it signs out at once; with uploads waiting it asks first (spec §2):
     * Send now / Sign out anyway.
     */
    fun requestSignOut() {
        val session = auth.session.value ?: return
        val prompt = SignOutRules.begin(queue.snapshot(), session.user.username, offline = connection.value is Connection.Error)
        if (prompt == null) signOut() else signOutPrompt.value = prompt
    }

    /** Send now (also "Try again"): sends this inspector's uploads, then signs out; offline or a problem → the prompt says so. */
    fun sendBeforeSignOut() {
        val session = auth.session.value ?: return
        val username = session.user.username
        if (signOutPrompt.value is SignOutPrompt.Sending) return
        val before = queue.snapshot()
        signOutPrompt.value = SignOutPrompt.Sending(QueueRules.waitingFor(before, username).size)
        viewModelScope.launch {
            runCatching { engine.drain(force = true) }
            if (auth.session.value?.token != session.token) { signOutPrompt.value = null; return@launch } // signed out meanwhile (401)
            val next = SignOutRules.afterSend(before, queue.snapshot(), username)
            if (next == null) { signOutPrompt.value = null; signOut() } else signOutPrompt.value = next
        }
    }

    /** Sign out anyway: the uploads stay on the tablet, held until the same inspector signs in again. */
    fun signOutAnyway() {
        signOutPrompt.value = null
        signOut()
    }

    /** Stay signed in (not while it is sending). */
    fun dismissSignOutPrompt() {
        if (signOutPrompt.value !is SignOutPrompt.Sending) signOutPrompt.value = null
    }

    /** Forgets the token first, then tells the server (best effort). Queued work is kept (decision 2026-11-18). */
    private fun signOut() {
        val session = auth.session.value ?: return
        earlierFor.value = null
        auth.clear()
        viewModelScope.launch {
            api.logout(store.settings.first().serverUrl, session.token)
        }
    }

    fun dismissEarlierUploads() { earlierFor.value = null }

    /** Settings → Upload queue → Retry now. Only the signed-in inspector's own items; never anyone else's. */
    fun retryQueueItem(id: String) {
        val username = auth.session.value?.user?.username ?: return
        val item = queue.get(id) ?: return
        if (!QueueItemRules.canRetry(item, username)) return
        queue.modify(id) { QueueItemRules.forRetry(it) }
        viewModelScope.launch { runCatching { engine.drain(force = true, onlyId = id) } }
    }

    /** Settings → Upload queue → Cancel (after the confirmation). Findings and photos stay on the tablet. */
    fun cancelQueueItem(id: String) {
        val username = auth.session.value?.user?.username ?: return
        val item = queue.get(id) ?: return
        if (QueueItemRules.canCancel(item, username)) queue.remove(id)
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
