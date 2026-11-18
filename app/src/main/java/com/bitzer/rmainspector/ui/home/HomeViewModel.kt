package com.bitzer.rmainspector.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeLists
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.WorkListFilter
import com.bitzer.rmainspector.data.WorkListRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Product-type chips (server order) — empty until the code lists are on the device. */
    val productTypes: List<Code> = emptyList(),
    /** Return-code chips (natural sort order). */
    val returnCodes: List<Code> = emptyList(),
    /** Selected chips; null = not known yet (the server's defaults are used). */
    val filter: WorkListFilter? = null,
    val items: List<RmaListItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedOnce: Boolean = false,
) {
    /** A chip row with nothing selected matches nothing (api §5) — no need to ask the server. */
    val nothingSelected: Boolean
        get() = filter != null &&
            ((productTypes.isNotEmpty() && filter.productTypes.isEmpty()) || (returnCodes.isNotEmpty() && filter.returnCodes.isEmpty()))
}

data class HomeState(
    val search: SearchState = SearchState.Idle,
    val workList: WorkListState = WorkListState(),
    /** One-shot: an RMA to open (single search hit). The screen clears it after navigating. */
    val openRmaId: String? = null,
)

/** Home (spec §3): one search box and the Received work list with product-type / return-code chips. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServiceLocator.settings(app)
    private val api = ServiceLocator.api
    private val codeLists = ServiceLocator.codeLists(app)
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var workJob: Job? = null

    /** What the last successful/attempted load asked for, so an unchanged 304 code-list refresh doesn't reload. */
    private var requested: Pair<String, WorkListFilter?>? = null

    init {
        viewModelScope.launch {
            val urls = store.settings.map { it.serverUrl }.distinctUntilChanged()
            urls.collect { url -> codeLists.loadCached(url) }
        }
        viewModelScope.launch {
            combine(
                store.settings.map { it.serverUrl }.distinctUntilChanged(),
                codeLists.state.map { it.lists }.distinctUntilChanged(),
            ) { url, lists -> url to lists }.collect { (url, lists) -> reconcile(url, lists) }
        }
    }

    private suspend fun baseUrl() = store.settings.first().serverUrl

    /** Chips + selection from the code lists (saved choice, else server default); reload if the filter changed. */
    private suspend fun reconcile(url: String, lists: CodeLists?) {
        val saved = store.workListFilter.first()
        if (lists != null) {
            val chips = WorkListRules.resolve(lists, saved)
            _state.update {
                it.copy(workList = it.workList.copy(productTypes = chips.productTypes, returnCodes = chips.returnCodes, filter = chips.filter))
            }
        } else {
            // No code lists on the device yet: use the saved choice if any, else let the server apply its defaults.
            _state.update { it.copy(workList = it.workList.copy(filter = saved)) }
        }
        val want = url to _state.value.workList.filter
        if (want != requested) load()
    }

    private fun load() {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val url = baseUrl()
            val ws = _state.value.workList
            requested = url to ws.filter
            if (ws.nothingSelected) {
                _state.update { it.copy(workList = it.workList.copy(items = emptyList(), loading = false, error = null, loadedOnce = true)) }
                return@launch
            }
            _state.update { it.copy(workList = it.workList.copy(loading = true, error = null)) }
            // Only send a chip row the device actually shows; otherwise the server default applies.
            val f = ws.filter
            val pt = if (f == null || (ws.productTypes.isEmpty() && f.productTypes.isEmpty())) null else f.productTypes
            val rc = if (f == null || (ws.returnCodes.isEmpty() && f.returnCodes.isEmpty())) null else f.returnCodes
            when (val r = api.workList(url, pt, rc)) {
                is ApiResult.Failure -> _state.update { it.copy(workList = it.workList.copy(loading = false, error = r.message)) }
                is ApiResult.Ok -> _state.update {
                    it.copy(workList = it.workList.copy(items = WorkListRules.sort(r.value.results), loading = false, error = null, loadedOnce = true))
                }
            }
        }
    }

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

    fun toggleProductType(chip: Code) = setFilter { f, ws -> WorkListRules.toggleProductType(f, ws.productTypes, chip.code) }

    fun toggleReturnCode(chip: Code) = setFilter { f, ws -> WorkListRules.toggleReturnCode(f, ws.returnCodes, chip.key) }

    private fun setFilter(change: (WorkListFilter, WorkListState) -> WorkListFilter) {
        val ws = _state.value.workList
        val next = change(ws.filter ?: WorkListFilter(), ws)
        _state.update { it.copy(workList = it.workList.copy(filter = next)) }
        viewModelScope.launch { store.setWorkListFilter(next) }
        load()
    }

    /** Pull to refresh / Retry. */
    fun refreshWorkList() = load()
}
