package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Inspection workspace (spec §4). The rma_id comes from navigation and is passed to the server untouched.
 * Tab, selection and the failure-info panel live here so they survive rotation.
 */
class WorkspaceViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(ARG_RMA_ID))
    private val store = ServiceLocator.settings(app)
    private val api = ServiceLocator.api
    private val codeLists = ServiceLocator.codeLists(app)
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    init {
        load()
        // After a mid-session re-sign-in the user comes back to this screen: reload with the new token.
        viewModelScope.launch {
            ServiceLocator.auth(app).session.map { it?.token }.distinctUntilChanged().drop(1).filterNotNull().collect { load() }
        }
        // Guided button follows the server's checklists (cached code lists; refreshed elsewhere).
        viewModelScope.launch {
            runCatching { codeLists.loadCached(store.settings.first().serverUrl) }
            codeLists.state.collect { updateGuided() }
        }
    }

    fun load() {
        viewModelScope.launch {
            val current = _state.value.load
            _state.update {
                it.copy(load = if (current is WorkspaceLoad.Loaded) current.copy(refreshing = true) else WorkspaceLoad.Loading)
            }
            val base = store.settings.first().serverUrl
            val next = when (val r = api.rmaDetail(base, rmaId)) {
                is ApiResult.Ok -> WorkspaceLoad.Loaded(r.value)
                is ApiResult.Failure -> WorkspaceLoad.Error(r.message, notFound = r.code == "RMA_NOT_FOUND")
            }
            _state.update { it.copy(load = next) }
            updateGuided()
        }
    }

    private fun updateGuided() {
        val rma = _state.value.rma ?: return
        val checklists = codeLists.state.value.lists?.checklists.orEmpty()
        _state.update { it.copy(guided = guidedButtonFor(rma, checklists)) }
    }

    fun selectTab(tab: WorkspaceTab) = _state.update { if (it.tab == tab) it else it.copy(tab = tab, selection = null) }
    fun select(id: String?) = _state.update { it.copy(selection = id) }
    fun toggleFailureInfo() = _state.update { it.copy(failureExpanded = !it.failureExpanded) }

    /** Guided inspection is built in milestone B; v1 has no checklists, so the button stays disabled. */
    fun startGuided() = Unit

    /** Conflict review (spec §6.5) comes with Submit in milestone B. */
    fun reviewChanges() = Unit

    companion object {
        const val ARG_RMA_ID = "rmaId"
    }
}
