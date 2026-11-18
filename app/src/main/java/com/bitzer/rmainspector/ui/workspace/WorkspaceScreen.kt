package com.bitzer.rmainspector.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.PartRow
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** Test tags used by the emulator tests. */
object WorkspaceTags {
    const val HEADER = "workspace_header"
    const val FAILURE_TOGGLE = "workspace_failure_toggle"
    const val GUIDED = "workspace_guided"
    fun tab(tab: WorkspaceTab) = "workspace_tab_${tab.name.lowercase()}"
    fun listPane(tab: WorkspaceTab) = "workspace_list_${tab.name.lowercase()}"
    fun detailPane(tab: WorkspaceTab) = "workspace_detail_${tab.name.lowercase()}"
}

/** Callbacks of [WorkspaceContent] — one object so later issues can add actions without touching every caller. */
data class WorkspaceCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectTab: (WorkspaceTab) -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onToggleFailureInfo: () -> Unit = {},
    val onGuided: () -> Unit = {},
    val onReviewChanges: () -> Unit = {},
    // Findings (B1): rows are addressed by FindingRow.localId; indices are list positions.
    val onAddPart: () -> Unit = {},
    val onSetPart: (localId: String, code: Code) -> Unit = { _, _ -> },
    val onSetIssue: (localId: String, code: Code) -> Unit = { _, _ -> },
    val onSetCause: (localId: String, code: Code?) -> Unit = { _, _ -> },
    val onSetNote: (localId: String, note: String) -> Unit = { _, _ -> },
    val onMoveRow: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onDeleteRow: (localId: String) -> Unit = {},
    val onUndoDelete: () -> Unit = {},
    val onDismissUndo: () -> Unit = {},
    // Photos (B2): rows are addressed by FindingRow.localId, photos by photo id (`PhotoItem.id`).
    val onTakePhoto: (rowLocalId: String) -> Unit = {},
    val onAddRmaPhoto: () -> Unit = {},
    val onSelectTag: (String) -> Unit = {},
    val onTagText: (String) -> Unit = {},
    val onConfirmTag: () -> Unit = {},
    val onCancelTag: () -> Unit = {},
    val onRetakePhoto: (photoId: String) -> Unit = {},
    val onDeletePhoto: (photoId: String) -> Unit = {},
    val onPhotoCaptured: (java.io.File) -> Unit = {},
    val onCloseCamera: () -> Unit = {},
    val onReloadPhotos: () -> Unit = {},
    val onDismissPhotoMessage: () -> Unit = {},
    /** Report tab actions (C1). */
    val report: ReportCallbacks = ReportCallbacks(),
    /** Submit inspection actions (B3). */
    val submit: SubmitCallbacks = SubmitCallbacks(),
)

/** Width from which the tabs show list and detail side by side (11" tablet landscape ≈ 1280 dp). */
val TWO_PANE_MIN_WIDTH = 840.dp

/** Inspection workspace (spec §4): persistent header, banners, Findings / Photos / Report tabs. */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    vm: WorkspaceViewModel = viewModel(),
    reportVm: ReportViewModel = viewModel(),
    submitVm: ReportSubmitViewModel = viewModel(),
    inspectionVm: SubmitViewModel = viewModel(),
) {
    val base by vm.state.collectAsStateWithLifecycle()
    val report by reportVm.state.collectAsStateWithLifecycle()
    val inspection by inspectionVm.phase.collectAsStateWithLifecycle()
    val rma = base.rma
    LaunchedEffect(rma) { if (rma != null) reportVm.setRma(rma) }
    // The inspection submit saved something: the draft is gone, so reload the RMA from the server.
    LaunchedEffect(Unit) { inspectionVm.refresh.collect { vm.afterSubmit() } }
    val submit by submitVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        submitVm.events.collect { e ->
            when (e) {
                is SubmitEvent.DraftChanged -> reportVm.applyDraft(e.draft)
                SubmitEvent.ReloadRma -> vm.load()
                SubmitEvent.ReloadPhotos -> reportVm.loadPhotos()
            }
        }
    }
    val state = base.copy(report = report, submit = inspection)
    WorkspaceContent(
        state,
        WorkspaceCallbacks(
            submit = SubmitCallbacks(
                onOpen = { inspectionVm.open(vm.submitInput()) },
                onCancel = inspectionVm::cancel,
                onSubmit = inspectionVm::submit,
                onConfirmWarning = inspectionVm::confirmWarning,
                onDismissWarning = inspectionVm::dismissWarning,
                onClose = inspectionVm::close,
            ),
            report = ReportCallbacks(
                onToggleShowAll = reportVm::toggleShowAll,
                onEnlarge = reportVm::enlarge,
                onTogglePick = reportVm::togglePick,
                onDefect = reportVm::setDefect,
                onSummary = reportVm::setSummary,
                onInsertTemplate = reportVm::insertTemplate,
                onInspectedBy = reportVm::setInspectedBy,
                onSummaryBy = reportVm::setSummaryBy,
                onArrange = reportVm::openArrange,
                onCaption = reportVm::setCaption,
                onRemovePick = reportVm::removePick,
                onSortByCaption = reportVm::sortByCaption,
                onSavePicks = reportVm::savePicks,
                onConflictUseServer = reportVm::conflictUseServer,
                onConflictKeepMine = reportVm::conflictKeepMine,
                onConflictDismiss = reportVm::conflictDismiss,
                onTogglePartsExpanded = reportVm::togglePartsExpanded,
                onDismissMessage = reportVm::dismissMessage,
                onRetryPhotos = reportVm::loadPhotos,
                onSubmitReport = {
                    rma?.let { submitVm.begin(it, report.draft, report.lists.summaryBlocks.associate { b -> b.blockId to b.label }, base.findings.hasDraft) }
                },
                loadImage = reportVm::loadImage,
            ),
            onBack = onBack,
            onRefresh = vm::load,
            onSelectTab = vm::selectTab,
            onSelect = vm::select,
            onToggleFailureInfo = vm::toggleFailureInfo,
            onGuided = vm::startGuided,
            onReviewChanges = vm::reviewChanges,
            onAddPart = vm::addPart,
            onSetPart = vm::setPart,
            onSetIssue = vm::setIssue,
            onSetCause = vm::setCause,
            onSetNote = vm::setNote,
            onMoveRow = vm::moveRow,
            onDeleteRow = vm::deleteRow,
            onUndoDelete = vm::undoDelete,
            onDismissUndo = vm::dismissUndo,
            onTakePhoto = vm::takePhoto,
            onAddRmaPhoto = vm::addRmaPhoto,
            onSelectTag = vm::selectTag,
            onTagText = vm::setTagText,
            onConfirmTag = vm::confirmTag,
            onCancelTag = vm::cancelTag,
            onRetakePhoto = vm::retakePhoto,
            onDeletePhoto = vm::deletePhoto,
            onPhotoCaptured = vm::photoCaptured,
            onCloseCamera = vm::closeCamera,
            onReloadPhotos = vm::reloadPhotos,
            onDismissPhotoMessage = vm::dismissPhotoMessage,
        ),
        thumbs = vm.thumbs,
        overlay = { s, cb ->
            if (s.photos.capture != null) CameraOverlay(s.photos, cb)
            ReportSubmitOverlay(submit, ReportSubmitCallbacks(
                onConfirm = submitVm::confirm, onCancel = submitVm::cancel, onFix = submitVm::fix, onIgnore = submitVm::ignore,
                onAddToDictionary = submitVm::addToDictionary, onSpellContinue = submitVm::spellContinue,
                onErrorOk = submitVm::errorOk, onRetry = submitVm::retry, onNeedPage = submitVm::needPage,
                onDonePreview = submitVm::donePreview))
        },
    )
}

/** Stateless workspace — used by [WorkspaceScreen] and by screenshot tests. */
@Composable
fun WorkspaceContent(
    state: WorkspaceState,
    callbacks: WorkspaceCallbacks,
    thumbs: PhotoThumbLoader? = null,
    /** Extra full-screen layer; the app puts the camera here so screenshot tests never touch CameraX. */
    overlay: @Composable (WorkspaceState, WorkspaceCallbacks) -> Unit = { _, _ -> },
) {
    CompositionLocalProvider(LocalPhotoThumbs provides (thumbs ?: LocalPhotoThumbs.current)) {
        Box(Modifier.fillMaxSize()) {
            WorkspaceScaffold(state, callbacks)
            TagPickerOverlay(state, callbacks)
            SubmitOverlay(state.submit, callbacks.submit)
            overlay(state, callbacks)
        }
    }
}

@Composable
private fun WorkspaceScaffold(state: WorkspaceState, callbacks: WorkspaceCallbacks) {
    Scaffold(
        topBar = {
            BitzerTopBar(
                title = "Inspection",
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.submitAvailable) {
                        TextButton(
                            onClick = callbacks.submit.onOpen,
                            modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.OPEN),
                            colors = ButtonDefaults.textButtonColors(contentColor = BitzerColors.Green50),
                        ) { Text("SUBMIT", fontWeight = FontWeight.Bold) }
                    }
                    IconButton(onClick = callbacks.onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
            when (val load = state.load) {
                WorkspaceLoad.Loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("  Loading the RMA…", style = MaterialTheme.typography.bodyLarge)
                }
                is WorkspaceLoad.Error -> Column(Modifier.padding(24.dp)) {
                    InfoBanner(Icons.Filled.ErrorOutline, load.message, BitzerColors.Error) {
                        if (!load.notFound) TextButton(onClick = callbacks.onRefresh) { Text("Retry") }
                    }
                }
                is WorkspaceLoad.Loaded -> Workspace(state, load.rma, load.refreshing, twoPane, callbacks)
            }
        }
    }
}

@Composable
private fun Workspace(state: WorkspaceState, rma: RmaDetail, refreshing: Boolean, twoPane: Boolean, cb: WorkspaceCallbacks) {
    // Portrait: back from a detail returns to the list (spec §4.2).
    BackHandler(enabled = !twoPane && state.selection != null) { cb.onSelect(null) }

    Column(Modifier.fillMaxSize()) {
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.heightIn(min = 4.dp))
        WorkspaceHeader(rma, state.failureExpanded, state.guided, wide = twoPane, cb)
        val banners = state.banners
        if (banners.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                banners.forEach { WorkspaceBannerView(it, cb.onReviewChanges) }
            }
        }
        WorkspaceTabs(state.tab, cb.onSelectTab)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TabBody(state, rma, twoPane, cb)
        }
    }
}

// ---------------------------------------------------------------------------------------------- Header

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceHeader(rma: RmaDetail, failureExpanded: Boolean, guided: GuidedButton, wide: Boolean, cb: WorkspaceCallbacks) {
    val h = rma.header
    Surface(color = BitzerColors.White, modifier = Modifier.fillMaxWidth().testTag(WorkspaceTags.HEADER)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RMA ${rma.displayNumber}", style = MaterialTheme.typography.headlineSmall, color = BitzerColors.Black)
                    Text(h.customerName.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
                }
                if (wide) GuidedButtonView(guided, cb.onGuided)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderField("Model", h.model)
                HeaderField("Product type", rma.productType.ifBlank { "Unknown" } + if (rma.productTypeSource == "model") " (from model)" else "")
                HeaderField("Return code", h.returnCode.text())
                HeaderField("Status", h.inspectionStatus.text())
                HeaderField("Serial", h.failedSerialNo)
            }
            if (!wide) GuidedButtonView(guided, cb.onGuided, Modifier.fillMaxWidth())
            FailureInfo(rma, failureExpanded, cb.onToggleFailureInfo)
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(BitzerColors.Green))
}

@Composable
private fun HeaderField(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BitzerColors.Grey60)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GuidedButtonView(guided: GuidedButton, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = guided !is GuidedButton.Unavailable && guided !is GuidedButton.Complete
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp).testTag(WorkspaceTags.GUIDED),
        colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green, disabledContainerColor = BitzerColors.Grey10,
            disabledContentColor = BitzerColors.Grey60),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(guided.label())
    }
}

/** Expandable failure info (read only — RMA-team fields, spec §4.1). */
@Composable
private fun FailureInfo(rma: RmaDetail, expanded: Boolean, onToggle: () -> Unit) {
    val h = rma.header
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide failure info" else "Show failure info", onClick = onToggle)
                .testTag(WorkspaceTags.FAILURE_TOGGLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FAILURE INFO", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
            Spacer(Modifier.width(12.dp))
            Text(
                if (expanded) "Read only — RMA team fields" else h.failureCode.ifBlank { "No failure code" },
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60, modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = BitzerColors.DarkGreen)
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailField("Failure code", h.failureCode)
                DetailField("Failure description", h.failureDescription)
                DetailField("Comments", h.comments)
                DetailField("Credit status", h.creditStatus.ifBlank { "Not set yet" })
                DetailField("Failed date", h.failedDate)
                DetailField("RMA comment", h.rmaComment)
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- Banners

@Composable
private fun WorkspaceBannerView(banner: WorkspaceBanner, onReviewChanges: () -> Unit) {
    when (banner) {
        is WorkspaceBanner.ReadOnly -> InfoBanner(
            if (banner.reason == ReadOnlyReason.SCROLL) Icons.Filled.Info else Icons.Filled.Block, banner.text, BitzerColors.Grey60,
        )
        is WorkspaceBanner.Locked -> InfoBanner(Icons.Filled.Lock, banner.text, BitzerColors.Warning)
        WorkspaceBanner.ChangedElsewhere -> InfoBanner(
            Icons.Filled.SyncProblem,
            "This RMA was changed elsewhere since you opened it. Review the changes before saving — nothing is overwritten.",
            BitzerColors.Warning,
        ) {
            TextButton(onClick = onReviewChanges, modifier = Modifier.heightIn(min = 56.dp)) { Text("Review") }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Tabs

@Composable
private fun WorkspaceTabs(selected: WorkspaceTab, onSelect: (WorkspaceTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = BitzerColors.Grey10,
        contentColor = BitzerColors.DarkGreen,
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selected.ordinal]), color = BitzerColors.Green, height = 3.dp)
        },
    ) {
        WorkspaceTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.heightIn(min = 56.dp).testTag(WorkspaceTags.tab(tab)),
                selectedContentColor = BitzerColors.DarkGreen,
                unselectedContentColor = BitzerColors.Grey90,
                text = { Text(tab.label.uppercase(), style = MaterialTheme.typography.titleSmall) },
            )
        }
    }
}

/**
 * Two panes in landscape (list left, detail right); portrait shows the list, or the detail once something is
 * selected (spec §4.2). Milestones B/C replace the pane bodies; the frame stays.
 */
@Composable
private fun TabBody(state: WorkspaceState, rma: RmaDetail, twoPane: Boolean, cb: WorkspaceCallbacks) {
    val tab = state.tab
    if (tab == WorkspaceTab.REPORT) {
        // The Report tab lays itself out (spec §7: keyboard collapse, portrait collapsible section).
        ReportTab(state.findingRows, state.selection, state.report, state.readOnly, twoPane, cb.report, cb.onSelect)
        return
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        Box(m.testTag(WorkspaceTags.listPane(tab))) {
            when (tab) {
                WorkspaceTab.FINDINGS -> FindingsList(state, cb)
                WorkspaceTab.PHOTOS -> PhotosList(state, cb)
                WorkspaceTab.REPORT -> Unit
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { m ->
        Box(m.testTag(WorkspaceTags.detailPane(tab))) {
            when (tab) {
                WorkspaceTab.FINDINGS -> FindingSheet(state, cb)
                WorkspaceTab.PHOTOS -> PhotosDetail(state, cb)
                WorkspaceTab.REPORT -> Unit
            }
        }
    }
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(0.42f).fillMaxHeight())
            VerticalDivider(color = BitzerColors.Grey30)
            detail(Modifier.weight(0.58f).fillMaxHeight())
        }
    } else if (state.selection == null) {
        list(Modifier.fillMaxSize())
    } else {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { cb.onSelect(null) }, modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Back to ${tab.label.lowercase()}")
            }
            HorizontalDivider(color = BitzerColors.Grey30)
            detail(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// --------------------------------------------------------------------------------------------- Pieces

@Composable
private fun PartListRow(index: Int, row: PartRow, selected: Boolean, onClick: () -> Unit) {
    ListRow(selected, onClick) {
        Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60,
            modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text("${row.partCode.text()}  ·  ${row.issueCode.text()}", style = MaterialTheme.typography.bodyLarge)
            if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90, maxLines = 1)
        }
    }
}

@Composable
private fun ListRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (selected) BitzerColors.Green10 else BitzerColors.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    HorizontalDivider(color = BitzerColors.Grey10)
}

@Composable
private fun PaneTitle(title: String, note: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
        if (note != null) Text("   $note", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
    }
}

@Composable
private fun DetailColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
    }
}

@Composable
private fun Placeholder(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
        Spacer(Modifier.size(8.dp))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60, modifier = Modifier.padding(16.dp))
}

@Composable
private fun DetailField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60, modifier = Modifier.width(170.dp))
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** "50  Normal"; a code whose description repeats it (return codes, statuses) shows once: "Warranty". */
private fun Code?.text(): String = when {
    this == null -> ""
    description.isBlank() || description == code -> code
    else -> label
}
