package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.BuildRequest
import com.bitzer.rmainspector.data.BuildResponse
import com.bitzer.rmainspector.data.PdfBytes
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.ReportSubmitRules
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.SpellFlow
import com.bitzer.rmainspector.data.SpellItem
import com.bitzer.rmainspector.data.StatusWarning
import com.bitzer.rmainspector.data.SubmitError
import com.bitzer.rmainspector.data.SubmitErrorKind
import com.bitzer.rmainspector.data.SubmitStep
import com.bitzer.rmainspector.data.SummaryOnlyRequest
import com.bitzer.rmainspector.data.submitErrorFor
import com.bitzer.rmainspector.data.toSummaryWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Submit report (spec §6.2): save report photos and summary → spell check → build PDF/XLSX → PDF preview.
 * Happy path first: a failure shows a message and keeps everything on the tablet; "Try again" resumes at the
 * failed step with the same submission ids (idempotent). Nothing is ever overwritten silently.
 */
class ReportSubmitViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(WorkspaceViewModel.ARG_RMA_ID))
    private val api = ServiceLocator.api
    private val settings = ServiceLocator.settings(app)
    private val codeLists = ServiceLocator.codeLists(app)

    private val _state = MutableStateFlow(ReportSubmitState())
    val state: StateFlow<ReportSubmitState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SubmitEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SubmitEvent> = _events.asSharedFlow()

    private lateinit var rma: RmaDetail
    private lateinit var draft: ReportDraft
    private var blockLabels: Map<String, String> = emptyMap()
    private var ignored: Set<String> = emptySet()
    private var failedStep: SubmitStep = SubmitStep.PICS
    private var failedError: SubmitError? = null
    private var picsId = ""
    private var summaryId = ""
    private var buildId = ""
    private var built: BuildResponse? = null
    private var job: Job? = null
    private var source: PdfPageSource? = null
    private var pdfFile: File? = null
    private val inFlight = HashSet<Int>()

    /** "Submit report" was tapped. [blockLabels] = summary block id → label. */
    fun begin(detail: RmaDetail, current: ReportDraft, blockLabels: Map<String, String>, hasUnsentFindings: Boolean) {
        if (_state.value.phase != SubmitPhase.Idle) return
        rma = detail
        draft = current
        this.blockLabels = blockLabels
        ignored = emptySet()
        built = null
        picsId = UUID.randomUUID().toString()
        summaryId = UUID.randomUUID().toString()
        buildId = UUID.randomUUID().toString()
        when {
            hasUnsentFindings -> fail(SubmitError(SubmitErrorKind.GENERIC, "Submit the findings first",
                "This RMA has findings that aren't submitted yet. Submit the inspection first, then the report. Your work is kept on the tablet.", false), SubmitStep.PICS)
            current.captionProblems().isNotEmpty() -> fail(SubmitError(SubmitErrorKind.VALIDATION, "Fix the photo captions",
                "Some report photo captions are empty, too long or the same. Fix the captions marked in red under Arrange, then submit again.", false), SubmitStep.PICS)
            else -> {
                val roles = ReportSubmitRules.resolveRoles(codeLists.state.value.lists?.statusRoles)
                when (val w = ReportSubmitRules.warningFor(detail.header.inspectionStatus?.code, roles)) {
                    StatusWarning.NONE -> runFrom(SubmitStep.PICS)
                    else -> _state.update { it.copy(phase = SubmitPhase.Confirm(w, roles.reportComplete)) }
                }
            }
        }
    }

    fun confirm() {
        if (_state.value.phase is SubmitPhase.Confirm) runFrom(SubmitStep.PICS)
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = SubmitPhase.Idle) }
    }

    fun retry() = runFrom(failedStep)

    fun errorOk() {
        val kind = failedError?.kind
        _state.update { it.copy(phase = SubmitPhase.Idle) }
        when (kind) {
            SubmitErrorKind.CHANGED_ELSEWHERE -> _events.tryEmit(SubmitEvent.Conflict(rma, failedError?.current))
            SubmitErrorKind.PICS_CHANGED -> _events.tryEmit(SubmitEvent.ReloadPhotos)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------------------ Spell review

    fun fix(index: Int, suggestion: String) {
        val review = _state.value.phase as? SubmitPhase.SpellReview ?: return
        val item = SpellFlow.remaining(review.items, ignored).getOrNull(index) ?: return
        val text = SpellFlow.applyFix(draft.summary(item.blockId), item.issue, suggestion)
        setDraft(draft.withSummary(item.blockId, text))
        recheck()
    }

    fun ignore(index: Int) {
        val review = _state.value.phase as? SubmitPhase.SpellReview ?: return
        val item = SpellFlow.remaining(review.items, ignored).getOrNull(index) ?: return
        ignored = ignored + item.issue.word.lowercase()
        _state.update { it.copy(phase = review.copy(items = SpellFlow.remaining(review.items, ignored))) }
    }

    fun addToDictionary(index: Int) {
        val review = _state.value.phase as? SubmitPhase.SpellReview ?: return
        val item = SpellFlow.remaining(review.items, ignored).getOrNull(index) ?: return
        _state.update { it.copy(phase = review.copy(busy = true)) }
        job?.cancel()
        job = viewModelScope.launch {
            when (val r = api.addToDictionary(base(), item.issue.word)) {
                is ApiResult.Ok -> recheckNow()
                is ApiResult.Failure -> fail(submitErrorFor(r), SubmitStep.SPELL)
            }
        }
    }

    fun spellContinue() {
        if (_state.value.phase is SubmitPhase.SpellReview) runFrom(SubmitStep.SUMMARY)
    }

    private fun recheck() {
        val review = _state.value.phase as? SubmitPhase.SpellReview ?: return
        _state.update { it.copy(phase = review.copy(busy = true)) }
        job?.cancel()
        job = viewModelScope.launch { recheckNow() }
    }

    private suspend fun recheckNow() {
        val items = checkSpelling() ?: return
        _state.update { it.copy(phase = SubmitPhase.SpellReview(items)) }
    }

    // ------------------------------------------------------------------------------------------ Pipeline

    private fun runFrom(start: SubmitStep) {
        job?.cancel()
        job = viewModelScope.launch {
            var step = start
            while (true) {
                _state.update { it.copy(phase = SubmitPhase.Working(step)) }
                when (step) {
                    SubmitStep.PICS -> { if (!savePics()) return@launch; step = SubmitStep.SPELL }
                    SubmitStep.SPELL -> {
                        val items = checkSpelling() ?: return@launch
                        if (items.isNotEmpty()) {
                            _state.update { it.copy(phase = SubmitPhase.SpellReview(items)) }
                            return@launch
                        }
                        step = SubmitStep.SUMMARY
                    }
                    SubmitStep.SUMMARY -> { if (!saveSummary()) return@launch; step = SubmitStep.BUILD }
                    SubmitStep.BUILD -> { if (!build()) return@launch; step = SubmitStep.PDF }
                    SubmitStep.PDF -> { loadPdf(); return@launch }
                }
            }
        }
    }

    private suspend fun base(): String = settings.settings.first().serverUrl

    private fun setDraft(d: ReportDraft) {
        draft = d
        _events.tryEmit(SubmitEvent.DraftChanged(d))
    }

    private fun fail(error: SubmitError, step: SubmitStep) {
        failedStep = step
        failedError = error
        _state.update { it.copy(phase = SubmitPhase.Error(error)) }
    }

    private suspend fun savePics(): Boolean {
        if (!draft.picksDirty) return true
        return when (val r = api.putReportPics(base(), rmaId, draft.toRequest(picsId))) {
            is ApiResult.Ok -> { setDraft(draft.adoptServer(r.value.reportPics, r.value.newSignature)); true }
            is ApiResult.Failure -> { fail(submitErrorFor(r), SubmitStep.PICS); false }
        }
    }

    /** Spell check of every summary block with text; null = failed (error shown). */
    private suspend fun checkSpelling(): List<SpellItem>? {
        val out = mutableListOf<SpellItem>()
        for ((blockId, text) in draft.summaries.toSortedMap()) {
            if (text.isBlank()) continue
            when (val r = api.spellcheck(base(), text)) {
                is ApiResult.Ok -> r.value.issues.forEach { out += SpellItem(blockId, blockLabels[blockId] ?: "Summary", it) }
                is ApiResult.Failure -> { fail(submitErrorFor(r), SubmitStep.SPELL); return null }
            }
        }
        return SpellFlow.remaining(out, ignored)
    }

    private suspend fun saveSummary(): Boolean {
        if (!ReportSubmitRules.summaryChanged(draft, rma.summary)) return true
        val req = SummaryOnlyRequest(summaryId, rma.signature, rma.productTypeKey, null, draft.toSummaryWrite(), null)
        return when (val r = api.saveSummary(base(), rmaId, req)) {
            is ApiResult.Ok -> {
                rma = rma.copy(signature = r.value.newSignature.ifEmpty { rma.signature }, summary = ReportSubmitRules.summaryAfterSave(draft, rma.summary))
                setDraft(draft.copy(fieldsDirty = false, rmaSignature = rma.signature))
                summaryId = UUID.randomUUID().toString()
                true
            }
            is ApiResult.Failure -> { fail(submitErrorFor(r), SubmitStep.SUMMARY); false }
        }
    }

    private suspend fun build(): Boolean =
        when (val r = api.buildReport(base(), rmaId, BuildRequest(buildId))) {
            is ApiResult.Ok -> { built = r.value; _events.tryEmit(SubmitEvent.ReloadRma); true }
            is ApiResult.Failure -> { fail(submitErrorFor(r), SubmitStep.BUILD); false }
        }

    private suspend fun loadPdf() {
        val b = built
        val bytes = when (val r = api.reportPdf(base(), rmaId)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Failure -> { fail(submitErrorFor(r), SubmitStep.PDF); return }
        }
        if (!PdfBytes.looksLikePdf(bytes)) {
            fail(SubmitError(SubmitErrorKind.GENERIC, "Preview not available",
                "The server sent a report file the preview can't open. The report was built; look for it in the RMA folder.", true), SubmitStep.PDF)
            return
        }
        val file = withContext(Dispatchers.IO) {
            File(getApplication<Application>().cacheDir, "report_preview/$rmaId-${System.nanoTime()}.pdf").also {
                it.parentFile?.mkdirs()
                it.writeBytes(bytes)
            }
        }
        closeSource()
        val src = withContext(Dispatchers.IO) { PdfPageSource.open(file) }
        source = src
        pdfFile = file
        val count = PdfBytes.resolvePageCount(src?.pageCount, b?.pdf?.pages, bytes)
        val notes = buildList {
            b?.statusChanged?.to?.let { add("Status set to ${it.code}.") }
            b?.warnings?.forEach { add(it.message) }
        }
        inFlight.clear()
        _state.update {
            it.copy(phase = SubmitPhase.Preview(PdfPreviewState(
                title = b?.pdf?.filename?.ifBlank { null } ?: "Report", pageCount = count, notes = notes)))
        }
    }

    /** Preview asks for a page; rendered once, the few nearest pages are kept. */
    fun needPage(index: Int) {
        val phase = _state.value.phase as? SubmitPhase.Preview ?: return
        val src = source ?: return
        if (index >= src.pageCount || phase.preview.pages.containsKey(index) || !inFlight.add(index)) return
        viewModelScope.launch {
            val bmp = src.render(index, RENDER_WIDTH_PX)
            inFlight.remove(index)
            if (bmp != null) _state.update { s ->
                val p = s.phase as? SubmitPhase.Preview ?: return@update s
                val pages = (p.preview.pages + (index to bmp)).let { m ->
                    if (m.size <= KEEP_PAGES) m else m.keys.sortedBy { kotlin.math.abs(it - index) }.take(KEEP_PAGES).associateWith { m.getValue(it) }
                }
                s.copy(phase = p.copy(preview = p.preview.copy(pages = pages)))
            }
        }
    }

    fun donePreview() {
        closeSource()
        _state.update { it.copy(phase = SubmitPhase.Idle) }
        _events.tryEmit(SubmitEvent.ReloadRma)
    }

    private fun closeSource() {
        source?.close()
        source = null
        pdfFile?.delete()
        pdfFile = null
    }

    override fun onCleared() {
        closeSource()
        super.onCleared()
    }

    companion object {
        const val RENDER_WIDTH_PX = 1200
        const val KEEP_PAGES = 8
    }
}
