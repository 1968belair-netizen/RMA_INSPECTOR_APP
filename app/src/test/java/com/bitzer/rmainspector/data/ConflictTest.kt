package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conflict diff / re-apply logic (#65, spec §6.5). FAKE data. */
class ConflictTest {
    private fun c(code: String, desc: String = code) = Code(null, code, desc)
    private fun row(id: String, part: String, issue: String = "50", note: String = "") =
        PartRow(id, c(part), c(issue), null, note)

    private fun rma(sig: String, parts: List<PartRow>, summary: Summary = Summary(blocks = listOf(SummaryBlock("1", "")), inspectedBy = "JD", summaryBy = "JD"),
                    header: RmaHeader = RmaHeader(inspectionStatus = c("Received"))) =
        RmaDetail(rmaId = "912345", displayNumber = "912345", signature = sig, parts = parts, summary = summary, header = header)

    private val baseParts = listOf(row("L1.a", "07a"), row("L2.a", "48a", note = "ok"))
    private val base = rma("a", baseParts)

    private fun fr(r: PartRow) = FindingRow(r.rowId, r.rowId, r.partCode, r.issueCode, r.causeCode, r.note)
    private var n = 0
    private val ids = { "new-t${++n}" }

    @Test fun addedRowIsAppendedAfterServerRows() {
        val server = listOf(row("L1.b", "07a"), row("L2.b", "48a", note = "ok"))
        val mine = baseParts.map(::fr) + FindingRow("new-1", null, c("63"), c("34"), note = "scored")
        val r = ConflictReapply.rows(baseParts, server, mine, ids)
        assertEquals(listOf("L1.b", "L2.b", "new-1"), r.rows.map { it.localId })
        assertEquals(listOf("L1.b", "L2.b", null), r.rows.map { it.rowId })
        assertEquals(mapOf("L1.a" to "L1.b", "L2.a" to "L2.b"), r.idMap)
        assertTrue(r.flags.isEmpty())
    }

    @Test fun editedRowIsMatchedByOriginalValuesEvenWhenRowsMoved() {
        // The desk inserted a row at the top; my edit of 48a must land on the 48a row, not on position 2.
        val server = listOf(row("L1.b", "99"), row("L2.b", "07a"), row("L3.b", "48a", note = "ok"))
        val mine = listOf(fr(baseParts[0]), fr(baseParts[1]).copy(note = "open phase"))
        val r = ConflictReapply.rows(baseParts, server, mine, ids)
        assertEquals(listOf("99", "07a", "48a"), r.rows.map { it.partCode!!.code })
        assertEquals("open phase", r.rows[2].note)
        assertEquals("L3.b", r.rows[2].rowId)
        assertEquals("L3.b", r.idMap["L2.a"])
        assertTrue(r.flags.isEmpty())
    }

    @Test fun removedRowIsDroppedWhenMatched() {
        val server = listOf(row("L1.b", "07a"), row("L2.b", "48a", note = "ok"))
        val r = ConflictReapply.rows(baseParts, server, listOf(fr(baseParts[0])), ids)
        assertEquals(listOf("07a"), r.rows.map { it.partCode!!.code })
        assertTrue(r.flags.isEmpty())
    }

    @Test fun removalOvertakenByDeskIsFlaggedAndRowKept() {
        val server = listOf(row("L1.b", "07a"), row("L2.b", "48a", note = "desk changed this"))
        val r = ConflictReapply.rows(baseParts, server, listOf(fr(baseParts[0])), ids)
        assertEquals(listOf("07a", "48a"), r.rows.map { it.partCode!!.code })
        assertEquals(1, r.flags.size)
    }

    @Test fun editOfRowChangedAtTheDeskIsFlaggedAndAddedAsNewRow() {
        val server = listOf(row("L1.b", "07a"), row("L2.b", "48a", "39", note = "desk"))
        val mine = listOf(fr(baseParts[0]), fr(baseParts[1]).copy(note = "mine"))
        val r = ConflictReapply.rows(baseParts, server, mine, ids)
        assertEquals(3, r.rows.size)
        assertEquals("desk", r.rows[1].note)
        val added = r.rows[2]
        assertEquals("mine", added.note)
        assertEquals(null, added.rowId)
        assertTrue(added.localId.startsWith("new-t"))
        assertEquals(added.localId, r.idMap["L2.a"])
        assertEquals(1, r.flags.size)
    }

    @Test fun reorderIsFlaggedNotApplied() {
        val server = listOf(row("L1.b", "07a"), row("L2.b", "48a", note = "ok"))
        val r = ConflictReapply.rows(baseParts, server, listOf(fr(baseParts[1]), fr(baseParts[0])), ids)
        assertEquals(listOf("07a", "48a"), r.rows.map { it.partCode!!.code })
        assertEquals(1, r.flags.size)
    }

    @Test fun serverChangesAreListedInSpecOrderAndTeamFieldsAreInfo() {
        val cur = rma("b", listOf(row("L1.b", "07a"), row("L2.b", "48a", "39", note = "ok"), row("L3.b", "62")),
            Summary(defectCode = c("10", "Lack of lubrication"), blocks = listOf(SummaryBlock("1", "typed at the desk")), inspectedBy = "AB", summaryBy = "JD"),
            RmaHeader(inspectionStatus = c("Inspected"), creditStatus = "F - Full Warranty Credit"))
        val items = ConflictSummary.items(base.summary, cur.summary, null)
        val lines = ConflictDiff.serverChanges(base, cur, items)
        assertEquals(listOf(ChangeArea.STATUS, ChangeArea.PARTS, ChangeArea.PARTS, ChangeArea.DEFECT, ChangeArea.SUMMARY, ChangeArea.INITIALS, ChangeArea.TEAM),
            lines.map { it.area })
        assertTrue(lines.last().info)
        assertFalse(lines.first().info)
    }

    @Test fun teamOnlyChangeNeedsNoPick() {
        val cur = rma("b", baseParts, header = RmaHeader(inspectionStatus = c("Received"), creditStatus = "FD - Freight Damage"))
        val review = ConflictReview.build(base, cur, baseParts.map(::fr), null, 0)
        assertTrue(review.teamOnly)
        assertTrue(review.canKeepMine(emptyMap()))
    }

    private fun report(text: String, by: String = "JD") = ReportDraft(rmaId = "912345", summaries = mapOf("1" to text),
        inspectedBy = by, summaryBy = "JD", fieldsDirty = true, rmaSignature = "a")

    @Test fun bothSidesSummaryNeedsAnExplicitPick() {
        val cur = rma("b", baseParts, Summary(blocks = listOf(SummaryBlock("1", "desk text")), inspectedBy = "JD", summaryBy = "JD"))
        val rep = report("my text")
        val review = ConflictReview.build(base, cur, baseParts.map(::fr), rep, 0)
        assertEquals(listOf("block:1"), review.bothSides.map { it.key })
        assertFalse(review.canKeepMine(emptyMap()))
        assertTrue(review.canKeepMine(mapOf("block:1" to Pick.SERVER)))
        assertEquals("my text", ConflictSummary.merged(cur, rep, review.summary, mapOf("block:1" to Pick.MINE)).summary("1"))
        val server = ConflictSummary.merged(cur, rep, review.summary, mapOf("block:1" to Pick.SERVER))
        assertEquals("desk text", server.summary("1"))
        assertFalse(server.fieldsDirty)
        assertEquals("desk text\n\nmy text", ConflictSummary.merged(cur, rep, review.summary, mapOf("block:1" to Pick.COMBINE)).summary("1"))
        assertEquals("b", server.rmaSignature)
    }

    @Test fun onlyMineKeptOnlyServerTaken() {
        val cur = rma("b", baseParts, Summary(blocks = listOf(SummaryBlock("1", "")), inspectedBy = "AB", summaryBy = "JD"))
        val rep = report("my text")  // block changed by me only; inspected_by: base JD, mine JD, server AB
        val review = ConflictReview.build(base, cur, baseParts.map(::fr), rep, 0)
        assertTrue(review.bothSides.isEmpty())
        val m = ConflictSummary.merged(cur, rep, review.summary, emptyMap())
        assertEquals("my text", m.summary("1"))
        assertEquals("AB", m.inspectedBy)
        assertTrue(m.fieldsDirty)
    }

    @Test fun bothChangedToTheSameValueIsNotAConflict() {
        val cur = rma("b", baseParts, Summary(blocks = listOf(SummaryBlock("1", "same")), inspectedBy = "JD", summaryBy = "JD"))
        val review = ConflictReview.build(base, cur, baseParts.map(::fr), report("same"), 0)
        assertTrue(review.bothSides.isEmpty())
        assertEquals(ItemState.BOTH_SAME, review.summary.first { it.key == "block:1" }.state)
    }

    @Test fun myChangesListRowsAndSummary() {
        val mine = listOf(fr(baseParts[0]).copy(note = "x"), FindingRow("new-1", null, c("63"), c("34")))
        val items = ConflictSummary.items(base.summary, base.summary, report("my text"))
        val lines = ConflictDiff.myChanges(base, mine, items)
        assertEquals(listOf("Row added", "Row changed", "Row removed", "Report summary"), lines.map { it.label }.let { l -> listOf("Row added", "Row changed", "Row removed", "Report summary").filter { it in l } })
        assertTrue(lines.any { it.label == "Report summary" && it.after == "my text" })
    }
}
