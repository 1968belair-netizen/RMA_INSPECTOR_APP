package com.bitzer.rmainspector.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.data.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val query: String, val matchedOn: String, val items: List<RmaListItem>) : SearchState
    data class NoMatch(val query: String) : SearchState
    data class Error(val message: String) : SearchState
}

data class WorkListState(
    /** Every inspection status the server offers, in server order (filter chips). */
    val statuses: List<Code> = emptyList(),
    /** Selected statuses; empty until the server's default or the saved choice is known. */
    val selected: List<CodeKey> = emptyList(),
    val items: List<RmaListItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedOnce: Boolean = false,
)

data class HomeState(
    val search: SearchState = SearchState.Idle,
    val workList: WorkListState = WorkListState(),
    /** One-shot: an RMA to open (single search hit). The screen clears it after navigating. */
    val openRmaId: String? = null,
)

/** Home (spec §3.2–3.3): free-text search and the status-filtered work list. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServiceLocator.settings(app)
    private val api = ServiceLocator.api
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var workJob: Job? = null

    init {
        // Reload everything when the server address changes (and once at start).
        viewModelScope.launch {
            store.settings.map { it.serverUrl }.distinctUntilChanged().collect { loadStatusesAndWorkList() }
        }
    }

    private suspend fun baseUrl() = store.settings.first().serverUrl

    fun search(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(search = SearchState.Searching) }
            val result = when (val r = api.search(baseUrl(), q)) {
                is ApiResult.Failure -> SearchState.Error(r.message)
                is ApiResult.Ok -> when (r.value.results.size) {
                    0 -> SearchState.NoMatch(q)
                    1 -> {
                        _state.update { it.copy(openRmaId = r.value.results.single().rmaId) }
                        SearchState.Results(q, r.value.matchedOn, r.value.results)
                    }
                    else -> SearchState.Results(q, r.value.matchedOn, r.value.results)
                }
            }
            _state.update { it.copy(search = result) }
        }
    }

    fun clearSearch() = _state.update { it.copy(search = SearchState.Idle) }

    fun onRmaOpened() = _state.update { it.copy(openRmaId = null) }

    private suspend fun loadStatusesAndWorkList() {
        if (loadStatuses()) loadWorkList()
    }

    /** Status chips + selection (saved choice, else the server default). False if the server can't be reached. */
    private suspend fun loadStatuses(): Boolean {
        _state.update { it.copy(workList = it.workList.copy(loading = true, error = null)) }
        return when (val cl = api.codeLists(baseUrl())) {
            is ApiResult.Failure -> {
                _state.update { it.copy(workList = it.workList.copy(loading = false, error = cl.message)) }
                false
            }
            is ApiResult.Ok -> {
                val all = cl.value.inspectionStatuses
                val allKeys = all.map { it.key }.toSet()
                val saved = store.workListFilter.first()?.filter { it in allKeys }
                val selected = if (saved.isNullOrEmpty()) cl.value.workListStatuses.filter { it in allKeys } else saved
                _state.update { it.copy(workList = it.workList.copy(statuses = all, selected = selected)) }
                true
            }
        }
    }

    private suspend fun loadWorkList() {
        val selected = _state.value.workList.selected
        if (selected.isEmpty()) {
            _state.update { it.copy(workList = it.workList.copy(items = emptyList(), loading = false, error = null, loadedOnce = true)) }
            return
        }
        _state.update { it.copy(workList = it.workList.copy(loading = true, error = null)) }
        when (val r = api.workList(baseUrl(), selected)) {
            is ApiResult.Failure -> _state.update { it.copy(workList = it.workList.copy(loading = false, error = r.message)) }
            is ApiResult.Ok -> _state.update {
                it.copy(workList = it.workList.copy(items = r.value.results, loading = false, error = null, loadedOnce = true))
            }
        }
    }

    fun toggleStatus(key: CodeKey) {
        val current = _state.value.workList.selected
        val next = if (key in current) current - key else current + key
        _state.update { it.copy(workList = it.workList.copy(selected = next)) }
        viewModelScope.launch { store.setWorkListFilter(next) }
        refreshWorkList()
    }

    /** Pull-to-refresh / Retry. Reloads the status list too if it never loaded. */
    fun refreshWorkList() {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            if (_state.value.workList.statuses.isEmpty()) loadStatusesAndWorkList() else loadWorkList()
        }
    }
}
