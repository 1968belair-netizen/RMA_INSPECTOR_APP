package com.bitzer.rmainspector.ui.guided

import com.bitzer.rmainspector.data.Checklist
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.GuidedEngine
import com.bitzer.rmainspector.data.StepStatus
import com.bitzer.rmainspector.ui.workspace.WorkspaceState
import com.bitzer.rmainspector.ui.workspace.photoGroups

/** The checklist for this RMA's product type (server-served, api §4), or null. */
fun WorkspaceState.guidedChecklist(): Checklist? {
    val r = rma ?: return null
    return findings.codes?.checklists?.get(r.productTypeKey)?.takeIf { it.steps.isNotEmpty() }
}

/** What the guided screen shows for the current step — computed from the workspace state, so it is always in sync with Findings. */
fun WorkspaceState.guidedStatus(): StepStatus? {
    val run = guidedRun ?: return null
    val list = guidedChecklist() ?: return null
    val row = GuidedEngine.rowFor(list.steps, run.step, findingRows)
    val existing = row?.let { r -> photoGroups.firstOrNull { it.rowLocalId == r.localId }?.items?.count { it.local?.slot == null } } ?: 0
    return GuidedEngine.status(list.steps, run.step, findingRows, photos.local, existing, findings.codes)
}

/** Part code + description for the step header (falls back to the bare code when the part list is missing). */
fun WorkspaceState.guidedPart(status: StepStatus): Code =
    findings.codes?.parts?.firstOrNull { it.key == status.step.partCode } ?: Code(status.step.partCode.group, status.step.partCode.code)
