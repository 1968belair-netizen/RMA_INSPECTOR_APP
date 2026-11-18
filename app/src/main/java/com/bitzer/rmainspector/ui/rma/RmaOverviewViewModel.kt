package com.bitzer.rmainspector.ui.rma

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface OverviewState {
    data object Loading : OverviewState
    data class Loaded(val rma: RmaDetail, val refreshing: Boolean = false) : OverviewState
    data class Error(val message: String, val notFound: Boolean) : OverviewState
}

/** RMA overview (spec §3.4). The rma_id comes from navigation and is passed to the server untouched. */
class RmaOverviewViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(ARG_RMA_ID))
    private val store = ServiceLocator.settings(app)
    private val api = ServiceLocator.api
    private val _state = MutableStateFlow<OverviewState>(OverviewState.Loading)
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    init {
        load()
        // After a mid-session re-sign-in the user comes back to this screen: reload with the new token.
        viewModelScope.launch {
            ServiceLocator.auth(app).session.map { it?.token }.distinctUntilChanged().drop(1).filterNotNull().collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            val current = _state.value
            _state.value = if (current is OverviewState.Loaded) current.copy(refreshing = true) else OverviewState.Loading
            val base = store.settings.first().serverUrl
            _state.value = when (val r = api.rmaDetail(base, rmaId)) {
                is ApiResult.Ok -> OverviewState.Loaded(r.value)
                is ApiResult.Failure -> OverviewState.Error(r.message, notFound = r.code == "RMA_NOT_FOUND")
            }
        }
    }

    companion object {
        const val ARG_RMA_ID = "rmaId"
    }
}
