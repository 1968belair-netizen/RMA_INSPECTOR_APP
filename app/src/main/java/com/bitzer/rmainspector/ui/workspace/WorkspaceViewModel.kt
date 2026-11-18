package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.DeletedRow
import com.bitzer.rmainspector.data.Draft
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.FindingsEdit
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.toDraftInspector
import com.bitzer.rmainspector.data.toFindingRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Inspection workspace (spec §4). The rma_id comes from navigation and is passed to the server untouched.
 * Tab, selection and the failure-info panel live here so they survive rotation.
 *
 * Findings (spec §4.3): every edit updates the rows at once and is saved to the inspector's local draft
 * ([com.bitzer.rmainspector.data.DraftStore]) in the background — the latest state always wins, and a pending save
 * is flushed when the screen closes. If the rows end up equal to the server's rows the draft is removed.
 */
class WorkspaceViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(ARG_RMA_ID))
    private val store = ServiceLocator.settings(app)
    private val api = ServiceLocator.api
    private val codeLists = ServiceLocator.codeLists(app)
    private val auth = ServiceLocator.auth(app)
    private val drafts = ServiceLocator.drafts(app)
    private val recent = ServiceLocator.recentCodes(app)
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    /** The draft as edited (null = none). Read from disk once per inspector. */
    private var draft: Draft? = null
    private var draftOwner: String? = null

    private sealed interface DiskOp {
        data class Save(val draft: Draft) : DiskOp
        data class Delete(val username: String, val rmaId: String) : DiskOp
    }
    private val pending = AtomicReference<DiskOp?>(null)
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        load()
        // After a mid-session re-sign-in the user comes back to this screen: reload with the new token.
        viewModelScope.launch {
            auth.session.map { it?.token }.distinctUntilChanged().drop(1).filterNotNull().collect { load() }
        }
        // Guided button and pickers follow the server's code lists (cached; refreshed elsewhere).
        viewModelScope.launch {
            runCatching { codeLists.loadCached(store.settings.first().serverUrl) }
            codeLists.state.collect { cl ->
                _state.update { it.copy(findings = it.findings.copy(codes = cl.lists)) }
                updateGuided()
            }
        }
        viewModelScope.launch { refreshRecent() }
        // Draft writer: one at a time, always the newest state.
        viewModelScope.launch {
            for (signal in saveSignal) {
                val op = pending.getAndSet(null) ?: continue
                withContext(Dispatchers.IO) { runCatching { runOp(op) } }
            }
        }
    }

    private fun runOp(op: DiskOp) = when (op) {
        is DiskOp.Save -> drafts.write(op.draft)
        is DiskOp.Delete -> drafts.delete(op.username, op.rmaId)
    }

    override fun onCleared() {
        // Leaving the screen: make sure the last edit is on disk.
        pending.getAndSet(null)?.let { runCatching { runOp(it) } }
        super.onCleared()
    }

    fun load() {
        viewModelScope.launch {
            val current = _state.value.load
            _state.update {
                it.copy(load = if (current is WorkspaceLoad.Loaded) current.copy(refreshing = true) else WorkspaceLoad.Loading)
            }
            val base = store.settings.first().serverUrl
            when (val r = api.rmaDetail(base, rmaId)) {
                is ApiResult.Ok -> applyLoaded(r.value)
                is ApiResult.Failure -> _state.update { it.copy(load = WorkspaceLoad.Error(r.message, notFound = r.code == "RMA_NOT_FOUND")) }
            }
            updateGuided()
        }
    }

    /** Shows the RMA with this inspector's draft (if any); a draft based on an older signature → changed elsewhere. */
    private suspend fun applyLoaded(rma: RmaDetail) {
        val username = auth.session.value?.user?.username
        if (username != draftOwner) {
            draft = username?.let { u -> withContext(Dispatchers.IO) { drafts.read(u, rma.rmaId) } }
            draftOwner = username
        }
        val d = draft
        _state.update {
            it.copy(
                load = WorkspaceLoad.Loaded(rma),
                changedElsewhere = d != null && d.baseSignature != rma.signature,
                findings = it.findings.copy(rows = d?.rows ?: rma.parts.toFindingRows(), hasDraft = d != null),
            )
        }
    }

    private fun updateGuided() {
        val rma = _state.value.rma ?: return
        val checklists = codeLists.state.value.lists?.checklists.orEmpty()
        _state.update { it.copy(guided = guidedButtonFor(rma, checklists)) }
    }

    fun selectTab(tab: WorkspaceTab) {
        dropBlankNewRow(null)
        _state.update { if (it.tab == tab) it else it.copy(tab = tab, selection = null) }
    }

    fun select(id: String?) {
        dropBlankNewRow(id)
        _state.update { it.copy(selection = id) }
    }

    fun toggleFailureInfo() = _state.update { it.copy(failureExpanded = !it.failureExpanded) }

    /** Guided inspection is built in milestone B; v1 has no checklists, so the button stays disabled. */
    fun startGuided() = Unit

    /** Conflict review (spec §6.5) comes with Submit in milestone B. */
    fun reviewChanges() = Unit

    // ------------------------------------------------------------------------------------------ Findings

    /** "Add part": a new row (`row_id: null`) at the end, opened in the part sheet. */
    fun addPart() {
        if (!_state.value.findingsEditable) return
        val id = FindingsEdit.newLocalId()
        dropBlankNewRow(id)
        commit(FindingsEdit.add(rows(), id))
        _state.update { it.copy(selection = id) }
    }

    fun setPart(localId: String, code: Code) {
        remember("parts", code.key)
        commit(FindingsEdit.setPart(rows(), localId, code))
    }

    fun setIssue(localId: String, code: Code) {
        remember("issues", code.key)
        commit(FindingsEdit.setIssue(rows(), localId, code))
    }

    fun setCause(localId: String, code: Code?) {
        code?.let { remember("causes", it.key) }
        commit(FindingsEdit.setCause(rows(), localId, code))
    }

    fun setNote(localId: String, note: String) =
        commit(FindingsEdit.setNote(rows(), localId, note, _state.value.findings.noteLimit))

    fun moveRow(from: Int, to: Int) = commit(FindingsEdit.move(rows(), from, to))

    /** Deletes a row (desk rows too); Undo is offered until dismissed. */
    fun deleteRow(localId: String) {
        val (next, deleted) = FindingsEdit.delete(rows(), localId)
        if (deleted == null) return
        if (_state.value.selection == localId) _state.update { it.copy(selection = null) }
        commit(next, undo = if (deleted.row.isBlankNew) null else deleted)
    }

    fun undoDelete() {
        val u = _state.value.findings.undo ?: return
        commit(FindingsEdit.undo(rows(), u), undo = null)
    }

    fun dismissUndo() = _state.update { it.copy(findings = it.findings.copy(undo = null)) }

    private fun rows(): List<FindingRow> = _state.value.findingRows

    /** Closing the sheet of a new row nobody filled in removes it (nothing is lost). */
    private fun dropBlankNewRow(nextSelection: String?) {
        val sel = _state.value.selection ?: return
        if (sel == nextSelection) return
        val row = rows().firstOrNull { it.localId == sel } ?: return
        if (row.isBlankNew) commit(rows().filterNot { it.localId == sel })
    }

    /** Applies new rows and saves the draft (or removes it when the rows match the server again). */
    private fun commit(newRows: List<FindingRow>, undo: DeletedRow? = _state.value.findings.undo) {
        val s = _state.value
        val rma = s.rma ?: return
        if (!s.findingsEditable) return
        val user = auth.session.value?.user ?: return
        val current = draft
        val baseParts = (current?.base ?: rma).parts
        val next = when {
            FindingsEdit.isUnchanged(newRows, baseParts) -> null
            current != null -> current.copy(rows = newRows, updatedAt = Draft.nowIso())
            else -> Draft.start(rma, user.toDraftInspector(), newRows)
        }
        draft = next
        draftOwner = user.username
        if (next != null || current != null) {
            pending.set(next?.let { DiskOp.Save(it) } ?: DiskOp.Delete(user.username, rma.rmaId))
            saveSignal.trySend(Unit)
        }
        _state.update {
            it.copy(
                findings = it.findings.copy(rows = newRows, hasDraft = next != null, undo = undo),
                changedElsewhere = next != null && next.baseSignature != rma.signature,
            )
        }
    }

    private fun remember(list: String, key: CodeKey) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recent.record(list, key) }
            refreshRecent()
        }
    }

    private suspend fun refreshRecent() {
        val (p, i, c) = withContext(Dispatchers.IO) { Triple(recent.get("parts"), recent.get("issues"), recent.get("causes")) }
        _state.update { it.copy(findings = it.findings.copy(recentParts = p, recentIssues = i, recentCauses = c)) }
    }

    companion object {
        const val ARG_RMA_ID = "rmaId"
    }
}
