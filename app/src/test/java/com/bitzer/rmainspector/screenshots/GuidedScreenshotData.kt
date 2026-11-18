package com.bitzer.rmainspector.screenshots

import com.bitzer.rmainspector.data.Checklist
import com.bitzer.rmainspector.data.ChecklistPhoto
import com.bitzer.rmainspector.data.ChecklistStep
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.LocalPhoto
import com.bitzer.rmainspector.ui.workspace.FindingsState
import com.bitzer.rmainspector.ui.workspace.GuidedButton
import com.bitzer.rmainspector.ui.workspace.GuidedRunState
import com.bitzer.rmainspector.ui.workspace.PhotosState
import com.bitzer.rmainspector.ui.workspace.WorkspaceState

/** FAKE guided inspection states (D2) built on [ScreenshotData]'s RMA 912345. */
object GuidedScreenshotData {
    private val overall = ChecklistPhoto("overall", "Overall", "always")
    private val detail = ChecklistPhoto("detail", "Detail", "non_normal")
    private val checklist = Checklist("2026-11-18-demo", listOf("07a", "48a", "63", "64").map {
        ChecklistStep(CodeKey(null, it), true, listOf(overall, detail))
    })
    private val codes = ScreenshotData.findingCodes.copy(checklists = mapOf("recip" to checklist))
    private val normal = Code(null, "50", "Normal")
    private val scored = Code(null, "34", "Scored / Scratched")

    private fun photo(id: String, row: String, slot: String) = LocalPhoto(
        photoId = id, kind = LocalPhoto.KIND_PART, rowLocalId = row, capturedAt = "2026-11-17T10:49:02-05:00", file = "$id.jpg", slot = slot)

    private fun run(rows: List<FindingRow>, photos: List<LocalPhoto>, step: Int, complete: Boolean = false) =
        ScreenshotData.workspace.copy(
            guided = GuidedButton.Continue(step + 1, 4),
            guidedRun = GuidedRunState(step, complete),
            findings = FindingsState(rows = rows, hasDraft = true, codes = codes),
            photos = PhotosState(local = photos, serverLoaded = true),
        )

    private val rows = listOf(
        FindingRow("L1.x", "L1.x", Code(null, "07a", "Terminal box"), normal),
        FindingRow("L2.x", "L2.x", Code(null, "48a", "Winding resistance"), normal, note = "Within spec, all 3 phases"),
        FindingRow("new-1", null, Code(null, "63", "Piston assy / skirt"), scored, note = "Cyl 2 skirt scored"),
    )

    /** Step 1, normal, the required overall photo is missing → Skip and Next disabled. */
    val requiredMissing = run(rows.take(1), emptyList(), 0)

    /** Step 2, normal, overall photo taken → Next enabled. */
    val normalReady = run(rows.take(2), listOf(photo("g1", "L2.x", "overall")), 1)

    /** Step 3, non-normal: overall taken, detail required and missing. */
    val nonNormalDetailMissing = run(rows, listOf(photo("g2", "new-1", "overall")), 2)

    /** Last step done → "Inspection complete". */
    val complete = run(rows, emptyList(), 3, complete = true)

    private val header = ScreenshotData.workspace.copy(findings = FindingsState(rows = rows, codes = codes))
    val buttonStart = header.copy(guided = GuidedButton.Start(4))
    val buttonContinue = header.copy(guided = GuidedButton.Continue(3, 4))
    val buttonComplete = header.copy(guided = GuidedButton.Complete)
    val buttonDisabled = ScreenshotData.workspace.copy(guided = GuidedButton.Unavailable("Recip"))
}
