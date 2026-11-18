package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeLists
import com.bitzer.rmainspector.data.PhotoListing
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.ReportPick
import com.bitzer.rmainspector.data.ReportSettings
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.SummaryBlockDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Report tab (spec §7): photo strip, defect code, summary, Inspected by / Summary by, report-photo picks.
 * Edits go to a local [ReportDraft] right away. Report photos are saved to the server with [savePicks]
 * (`PUT /report-pics`); a `409 REPORT_PICS_CHANGED` is shown for review, never overwritten silently.
 * The rma_id comes from navigation (same argument as [WorkspaceViewModel]); the loaded RMA arrives by [setRma].
 */
class ReportViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(WorkspaceViewModel.ARG_RMA_ID))
    private val api = ServiceLocator.api
    private val settings = ServiceLocator.settings(app)
    private val codeLists = ServiceLocator.codeLists(app)
    private val recent = ServiceLocator.recentCodes(app)
    private val drafts = ServiceLocator.reportDrafts(app)
    private val auth = ServiceLocator.auth(app)

    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var rma: RmaDetail? = null
    private var listing: PhotoListing? = null
    private var lastAttempt: Pair<List<ReportPick>, String>? = null
    private val bitmaps = LruCache<String, ImageBitmap>(80)

    init {
        viewModelScope.launch {
            runCatching { codeLists.loadCached(settings.settings.first().serverUrl) }
            codeLists.state.collect { s -> _state.update { it.copy(lists = listsFrom(s.lists)) } }
        }
    }

    private fun listsFrom(l: CodeLists?) = ReportLists(
        defectCodes = l?.defectCodes.orEmpty(),
        recentDefect = recent.get(RECENT_DEFECT),
        summaryBlocks = l?.summaryBlocks?.takeIf { it.isNotEmpty() } ?: listOf(SummaryBlockDef("1", "Report summary", 4000)),
        summaryTemplate = l?.summaryTemplate.orEmpty(),
        settings = l?.report ?: ReportSettings(),
    )

    /** The workspace loaded (or reloaded) the RMA: merge it into the draft and fetch the photo list. */
    fun setRma(detail: RmaDetail) {
        if (rma?.signature == detail.signature && rma?.rmaId == detail.rmaId && listing != null) return
        rma = detail
        loadPhotos()
    }

    fun loadPhotos() {
        val detail = rma ?: return
        viewModelScope.launch {
            _state.update { it.copy(photosLoad = ReportPhotosLoad.Loading) }
            val base = settings.settings.first().serverUrl
            val r = api.photoListing(base, detail.rmaId)
            val photos = (r as? ApiResult.Ok)?.value
            listing = photos ?: listing
            val initials = auth.session.value?.user?.initials.orEmpty()
            val merged = ReportDraft.refresh(drafts.get(detail.rmaId), detail, photos, initials)
            drafts.put(merged.draft)
            _state.update {
                it.copy(
                    draft = merged.draft,
                    photos = photos?.allPics ?: it.photos,
                    photosLoad = when (r) {
                        is ApiResult.Ok -> ReportPhotosLoad.Loaded
                        is ApiResult.Failure -> ReportPhotosLoad.Error(r.message)
                    },
                    conflict = if (merged.serverChanged && photos != null) {
                        ReportPicsConflict(photos.reportPics.files, photos.reportPics.signature)
                    } else it.conflict,
                )
            }
        }
    }

    private fun edit(change: (ReportDraft) -> ReportDraft) {
        _state.update { s ->
            val d = change(s.draft)
            drafts.put(d)
            s.copy(draft = d)
        }
    }

    /** The submit flow changed the draft (fixed spelling, saved photos): take it over. */
    fun applyDraft(d: ReportDraft) = edit { d }

    fun toggleShowAll() = _state.update { it.copy(showAll = !it.showAll) }
    fun enlarge(filename: String?) = _state.update { it.copy(enlarged = filename) }
    fun togglePartsExpanded() = _state.update { it.copy(partsExpanded = !it.partsExpanded) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun openArrange(open: Boolean) = _state.update { it.copy(arrange = open) }

    fun togglePick(filename: String) {
        val had = _state.value.draft.isPicked(filename)
        edit { it.toggle(filename) }
        _state.update { it.copy(message = ReportMessage(if (had) "Removed from the report: $filename" else "Added to the report: $filename")) }
    }

    fun setDefect(code: Code?) {
        edit { it.withDefect(code) }
        if (code != null) {
            recent.record(RECENT_DEFECT, code.key)
            _state.update { it.copy(lists = it.lists.copy(recentDefect = recent.get(RECENT_DEFECT))) }
        }
    }

    fun setSummary(blockId: String, text: String) {
        val max = _state.value.lists.summaryBlocks.firstOrNull { it.blockId == blockId }?.maxLength ?: Int.MAX_VALUE
        edit { it.withSummary(blockId, text.take(max)) }
    }

    /** "Insert standard wording": fills an empty block; a block with text gets the wording added after a blank line. */
    fun insertTemplate(blockId: String) {
        val template = _state.value.lists.summaryTemplate
        if (template.isBlank()) return
        val current = _state.value.draft.summary(blockId)
        setSummary(blockId, if (current.isBlank()) template else current.trimEnd() + "\n\n" + template)
    }

    fun setInspectedBy(v: String) = edit { it.withInspectedBy(v) }
    fun setSummaryBy(v: String) = edit { it.withSummaryBy(v) }
    fun setCaption(index: Int, text: String) = edit { it.withCaption(index, text) }
    fun removePick(index: Int) = edit { it.removeAt(index) }
    fun sortByCaption() = edit { it.inReportOrder() }

    /** `PUT /report-pics`. A retry after a dropped connection reuses the submission id (idempotent, api §1). */
    fun savePicks() {
        val s = _state.value
        if (s.saving) return
        if (s.draft.captionProblems().isNotEmpty()) {
            _state.update { it.copy(message = ReportMessage("Fix the captions marked in red before saving.", error = true)) }
            return
        }
        val picks = s.draft.picks
        val attempt = lastAttempt?.takeIf { it.first == picks && it.second.isNotEmpty() }
        val submissionId = attempt?.second ?: UUID.randomUUID().toString()
        lastAttempt = picks to submissionId
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val base = settings.settings.first().serverUrl
            when (val r = api.putReportPics(base, rmaId, s.draft.toRequest(submissionId))) {
                is ApiResult.Ok -> {
                    lastAttempt = null
                    val c = r.value.changes
                    edit { it.adoptServer(r.value.reportPics, r.value.newSignature) }
                    _state.update {
                        it.copy(saving = false, conflict = null, message = ReportMessage(
                            "Report photos saved to the server (${r.value.reportPics.size} in Report Pics" +
                                if (c.copied + c.renamed + c.removed > 0) ": ${c.copied} copied, ${c.renamed} renamed, ${c.removed} removed)" else ")"))
                    }
                    loadPhotosQuiet()
                }
                is ApiResult.Failure -> {
                    if (r.code == "REPORT_PICS_CHANGED") {
                        lastAttempt = null
                        _state.update { it.copy(saving = false, conflict = conflictFrom(r.details)) }
                    } else {
                        // Validation / source gone / offline: keep the picks, say why.
                        if (r.httpStatus != null && !r.retryable) lastAttempt = null
                        _state.update { it.copy(saving = false, message = ReportMessage(r.message, error = true)) }
                    }
                }
            }
        }
    }

    private suspend fun loadPhotosQuiet() {
        val base = settings.settings.first().serverUrl
        (api.photoListing(base, rmaId) as? ApiResult.Ok)?.value?.let { l ->
            listing = l
            _state.update { it.copy(photos = l.allPics) }
        }
    }

    private fun conflictFrom(details: JsonObject?): ReportPicsConflict {
        val files = (details?.get("current") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val sig = (details?.get("current_signature") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ReportPicsConflict(files, sig)
    }

    /** Inspector takes the server's `Report Pics ` list; their own picks are dropped (they chose this). */
    fun conflictUseServer() {
        val c = _state.value.conflict ?: return
        edit { it.adoptServer(c.current, c.currentSignature) }
        _state.update { it.copy(conflict = null, message = ReportMessage("Using the report photos from the server.")) }
    }

    /** Inspector keeps their picks and saves over the server's list — an explicit choice, then the normal save. */
    fun conflictKeepMine() {
        val c = _state.value.conflict ?: return
        edit { it.rebase(c.currentSignature) }
        _state.update { it.copy(conflict = null) }
        savePicks()
    }

    fun conflictDismiss() = _state.update {
        it.copy(conflict = null, message = ReportMessage("Report photos changed elsewhere and are not saved yet. Save again to review.", error = true))
    }

    /** Photo bytes → image for the strip, Arrange and the enlarged view (thumbnails cached in memory). */
    suspend fun loadImage(folder: String, filename: String, thumb: Boolean): ImageBitmap? {
        val key = "$folder|$filename|$thumb"
        bitmaps.get(key)?.let { return it }
        val base = settings.settings.first().serverUrl
        val bytes = (api.photoBytes(base, rmaId, folder, filename, thumb) as? ApiResult.Ok)?.value ?: return null
        val bmp = withContext(Dispatchers.Default) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = if (thumb) 1 else 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        if (thumb) bitmaps.put(key, bmp)
        return bmp
    }

    companion object {
        const val RECENT_DEFECT = "defect"
    }
}
