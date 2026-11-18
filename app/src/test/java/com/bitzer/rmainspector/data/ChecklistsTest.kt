package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Guided inspection engine (spec §5, api §4): required-photo rules, step progression, pre-fill, resume, drafts. FAKE data. */
class ChecklistsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun c(code: String, desc: String = "") = Code(null, code, desc)
    private val normal = c("50", "Normal")
    private val scored = c("34", "Scored / Scratched")
    private val codes = CodeLists(
        parts = listOf(c("07a", "Terminal box"), c("48a", "Winding resistance"), c("63", "Piston assy / skirt")),
        issues = listOf(normal, scored),
        normalIssueCodes = listOf(CodeKey(null, "50")),
    )
    private val overall = ChecklistPhoto("overall", "Overall", "always")
    private val detail = ChecklistPhoto("detail", "Detail", "non_normal")
    private val extra = ChecklistPhoto("extra", "Extra", "never")
    private val steps = listOf(
        ChecklistStep(CodeKey(null, "07a"), true, listOf(overall, detail, extra)),
        ChecklistStep(CodeKey(null, "48a"), true, listOf(overall, detail)),
        ChecklistStep(CodeKey(null, "63"), false, emptyList()),
    )

    private fun lp(id: String, row: String, slot: String?, sent: Boolean = false) = LocalPhoto(
        photoId = id, kind = LocalPhoto.KIND_PART, rowLocalId = row, capturedAt = "2026-11-17T10:49:02-05:00", file = "$id.jpg",
        slot = slot, sentAt = if (sent) "2026-11-17T11:00:00-05:00" else null)

    private fun status(i: Int, rows: List<FindingRow>, photos: List<LocalPhoto> = emptyList(), existing: Int = 0) =
        GuidedEngine.status(steps, i, rows, photos, existing, codes)!!

    // ------------------------------------------------------------------ photo rules

    @Test fun alwaysSlotIsRequiredForNormalAndNonNormal() {
        assertTrue(GuidedEngine.slotRequired(overall, CodeKey(null, "50"), codes.normalIssueCodes))
        assertTrue(GuidedEngine.slotRequired(overall, CodeKey(null, "34"), codes.normalIssueCodes))
    }

    @Test fun nonNormalSlotIsRequiredOnlyForNonNormalIssues() {
        assertFalse(GuidedEngine.slotRequired(detail, CodeKey(null, "50"), codes.normalIssueCodes))
        assertTrue(GuidedEngine.slotRequired(detail, CodeKey(null, "34"), codes.normalIssueCodes))
        assertFalse(GuidedEngine.slotRequired(detail, null, codes.normalIssueCodes))
    }

    @Test fun neverSlotIsNeverRequired() {
        assertFalse(GuidedEngine.slotRequired(extra, CodeKey(null, "50"), codes.normalIssueCodes))
        assertFalse(GuidedEngine.slotRequired(extra, CodeKey(null, "34"), codes.normalIssueCodes))
    }

    @Test fun untouchedStepDefaultsToNormalAndNeedsOverallPhoto() {
        val st = status(0, emptyList())
        assertNull(st.row)
        assertEquals(normal, st.issue)
        assertTrue(st.isNormal)
        assertEquals(listOf(true, false, false), st.slots.map { it.required })
        assertEquals(1, st.missingRequired)
        assertFalse(st.canNext)
        assertFalse(st.canSkip)
    }

    @Test fun normalStepWithOverallPhotoCanContinue() {
        val rows = listOf(FindingRow("new-1", null, c("07a"), normal))
        val st = status(0, rows, listOf(lp("p1", "new-1", "overall")))
        assertEquals(0, st.missingRequired)
        assertTrue(st.canNext)
        assertTrue(st.canSkip)
    }

    @Test fun nonNormalStepNeedsOverallAndDetail() {
        val rows = listOf(FindingRow("new-1", null, c("07a"), scored))
        val onlyOverall = status(0, rows, listOf(lp("p1", "new-1", "overall")))
        assertFalse(onlyOverall.isNormal)
        assertEquals(1, onlyOverall.missingRequired)
        assertFalse(onlyOverall.canNext)
        assertFalse(onlyOverall.canSkip)
        val both = status(0, rows, listOf(lp("p1", "new-1", "overall"), lp("p2", "new-1", "detail")))
        assertTrue(both.canNext)
    }

    @Test fun changingBackToNormalDropsTheDetailRequirement() {
        val photos = listOf(lp("p1", "new-1", "overall"))
        val nonNormal = status(0, listOf(FindingRow("new-1", null, c("07a"), scored)), photos)
        val back = status(0, listOf(FindingRow("new-1", null, c("07a"), normal)), photos)
        assertFalse(nonNormal.canNext)
        assertTrue(back.canNext)
    }

    @Test fun stepWithoutPhotoRulesCanAlwaysContinue() {
        val st = status(2, emptyList())
        assertTrue(st.slots.isEmpty())
        assertTrue(st.canNext)
        assertTrue(st.canSkip)
    }

    @Test fun nextNeedsAnIssueWhenCodeListsAreMissing() {
        val st = GuidedEngine.status(steps, 2, emptyList(), emptyList(), 0, null)!!
        assertNull(st.issue)
        assertFalse(st.canNext)
    }

    @Test fun photosOfOtherRowsSlotsAndSentPhotosDoNotCount() {
        val rows = listOf(FindingRow("new-1", null, c("07a"), normal), FindingRow("new-2", null, c("48a"), normal))
        val photos = listOf(lp("p1", "new-2", "overall"), lp("p2", "new-1", "detail"), lp("p3", "new-1", "overall", sent = true))
        assertEquals(1, status(0, rows, photos).missingRequired)
        assertEquals(0, status(1, rows, photos).missingRequired)
    }

    @Test fun existingUnslottedPhotosCoverOpenSlotsInOrder() {
        val rows = listOf(FindingRow("L1", "L1", c("07a"), scored))
        val st = status(0, rows, existing = 1)
        assertTrue(st.slots[0].coveredByExisting)
        assertFalse(st.slots[1].done)
        assertEquals(1, st.missingRequired)
    }

    @Test fun retakeKeepsTheLatestPhotoOfASlot() {
        val rows = listOf(FindingRow("new-1", null, c("07a"), normal))
        val st = status(0, rows, listOf(lp("old", "new-1", "overall"), lp("new", "new-1", "overall")))
        assertEquals("new", st.slots[0].photo?.photoId)
    }

    // ------------------------------------------------------------------ rows, progression, pre-fill

    @Test fun defaultIssueIsTheFirstNormalCodeInTheList() {
        assertEquals(normal, GuidedEngine.defaultIssue(codes))
        assertNull(GuidedEngine.defaultIssue(codes.copy(issues = listOf(scored))))
        assertNull(GuidedEngine.defaultIssue(null))
    }

    @Test fun existingRowsArePrefilledByPartCode() {
        val rows = listOf(FindingRow("L1", "L1", c("07a"), scored, note = "Cracked"), FindingRow("L2", "L2", c("48a"), normal))
        val st = status(0, rows)
        assertEquals("L1", st.row?.localId)
        assertEquals(scored, st.issue)
        assertEquals("Cracked", st.row?.note)
        assertEquals("L2", status(1, rows).row?.localId)
    }

    @Test fun repeatedPartInTheChecklistUsesTheNextRow() {
        val twice = listOf(ChecklistStep(CodeKey(null, "63")), ChecklistStep(CodeKey(null, "63")))
        val rows = listOf(FindingRow("a", null, c("63")), FindingRow("b", null, c("63")))
        assertEquals("a", GuidedEngine.rowFor(twice, 0, rows)?.localId)
        assertEquals("b", GuidedEngine.rowFor(twice, 1, rows)?.localId)
        assertNull(GuidedEngine.rowFor(twice, 1, rows.take(1)))
    }

    @Test fun stepsWriteIntoTheFindingsDraft() {
        val rma = RmaDetail("912345", "912345", "sig-1", isOpen = true, inspectable = true, productType = "Recip", productTypeKey = "recip",
            capabilities = Capabilities(true, true, true, true, true), parts = listOf(PartRow("L1.sig-1", c("48a", "Winding resistance"), normal)))
        var rows = rma.parts.toFindingRows()
        // Step 0 (07a) has no row yet: the guided flow adds one with the default issue, then the inspector types a note.
        val (added, id) = GuidedEngine.addRow(rows, codes.parts[0], GuidedEngine.defaultIssue(codes))
        rows = FindingsEdit.setNote(added, id, "Plate cracked", 4000)
        val st = status(0, rows)
        assertEquals(id, st.row?.localId)
        assertEquals(normal, st.row?.issueCode)
        assertNull(st.row?.rowId)
        // Step 1 (48a) is the existing row: untouched.
        assertEquals("L1.sig-1", status(1, rows).row?.localId)

        val store = DraftStore(tmp.newFolder("drafts"))
        store.write(Draft.start(rma, DraftInspector("demo"), rows))
        val back = store.read("demo", "912345")!!
        assertEquals(listOf("L1.sig-1", id), back.rows.map { it.localId })
        assertEquals("Plate cracked", back.rows.last().note)
        assertEquals(CodeKey(null, "07a"), back.rows.last().partCode?.key)
    }

    // ------------------------------------------------------------------ resume

    @Test fun progressSurvivesARestart() {
        val store = GuidedProgressStore(tmp.newFolder("guided"))
        assertNull(store.read("demo", "912345"))
        store.write("demo", "912345", GuidedProgress("v1", 2))
        assertEquals(GuidedProgress("v1", 2), GuidedProgressStore(tmp.root.resolve("guided")).read("demo", "912345"))
        assertNull(store.read("other", "912345"))
        store.write("demo", "912345", GuidedProgress("v1", 2, finished = true))
        assertTrue(store.read("demo", "912345")!!.finished)
        store.delete("demo", "912345")
        assertNull(store.read("demo", "912345"))
    }

    @Test fun lastStepIsFlagged() {
        assertFalse(status(0, emptyList()).isLast)
        assertTrue(status(2, emptyList()).isLast)
        assertNull(GuidedEngine.status(steps, 3, emptyList(), emptyList(), 0, codes))
    }
}
