package com.bitzer.rmainspector.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Findings edits and the local draft (spec §4.3, §8; api §7). FAKE data only. */
class DraftsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun c(code: String, desc: String = "") = Code(null, code, desc)
    private val rma = RmaDetail(
        rmaId = "912345", displayNumber = "912345", signature = "sig-1", isOpen = true, inspectable = true,
        productType = "Recip", productTypeKey = "recip", capabilities = Capabilities(true, true, true, true, true),
        header = RmaHeader(customerName = "Example Refrigeration Co.", model = "4FE-28Y", returnCode = c("Warranty"),
            inspectionStatus = c("Received")),
        statusSince = "2026-10-26",
        parts = listOf(
            PartRow("L1.sig-1", c("07a", "Terminal box"), c("50", "Normal")),
            PartRow("L2.sig-1", c("48a", "Winding resistance"), c("50", "Normal"), note = "Within spec"),
        ),
    )
    private val inspector = DraftInspector("demo", "Demo Inspector", "DI")

    // ------------------------------------------------------------------ edits

    @Test fun serverRowsKeepTheirRowIdsAsLocalIds() {
        val rows = rma.parts.toFindingRows()
        assertEquals(listOf("L1.sig-1", "L2.sig-1"), rows.map { it.rowId })
        assertEquals(rows.map { it.rowId }, rows.map { it.localId })
        assertTrue(FindingsEdit.isUnchanged(rows, rma.parts))
    }

    @Test fun addEditReorderDeleteUndo() {
        var rows = rma.parts.toFindingRows()
        rows = FindingsEdit.add(rows, "new-1")
        assertNull(rows.last().rowId)
        assertTrue(rows.last().isBlankNew)
        rows = FindingsEdit.setPart(rows, "new-1", c("63", "Piston assy / skirt"))
        rows = FindingsEdit.setIssue(rows, "new-1", c("34", "Scored / Scratched"))
        rows = FindingsEdit.setNote(rows, "new-1", "Cyl 2 skirt scored\nlight on cyl 1", 4000)
        rows = FindingsEdit.setIssue(rows, "L2.sig-1", c("39", "Winding fault"))
        assertFalse(FindingsEdit.isUnchanged(rows, rma.parts))

        rows = FindingsEdit.move(rows, 2, 0)
        assertEquals(listOf("new-1", "L1.sig-1", "L2.sig-1"), rows.map { it.localId })
        assertEquals(rows, FindingsEdit.move(rows, 5, 0)) // unknown index → unchanged

        val (afterDelete, deleted) = FindingsEdit.delete(rows, "L1.sig-1")
        assertEquals(listOf("new-1", "L2.sig-1"), afterDelete.map { it.localId })
        assertEquals(1, deleted!!.index)
        assertEquals(rows, FindingsEdit.undo(afterDelete, deleted))

        val writes = rows.mapNotNull { it.toWrite() }
        assertEquals(listOf(null, "L1.sig-1", "L2.sig-1"), writes.map { it.rowId })
        assertEquals(CodeKey(null, "63"), writes[0].partCode)
        assertEquals("Cyl 2 skirt scored\nlight on cyl 1", writes[0].note)
    }

    @Test fun noteIsCutToTheLimit() {
        val rows = FindingsEdit.setNote(rma.parts.toFindingRows(), "L1.sig-1", "x".repeat(4100), 4000)
        assertEquals(4000, rows[0].note.length)
    }

    @Test fun rowWithoutPartOrIssueIsNotWritten() {
        assertNull(FindingRow("new-1", partCode = c("63")).toWrite())
    }

    @Test fun codesComparedByGroupAndCodeOnly() {
        val rows = FindingsEdit.setIssue(rma.parts.toFindingRows(), "L1.sig-1", Code(null, "50", "Normal (renamed)"))
        assertTrue(FindingsEdit.isUnchanged(rows, rma.parts))
    }

    // ------------------------------------------------------------------ store

    @Test fun draftSurvivesANewStoreInstance() {
        val dir = tmp.newFolder("drafts")
        var rows = FindingsEdit.add(rma.parts.toFindingRows(), "new-1")
        rows = FindingsEdit.setPart(rows, "new-1", c("63", "Piston assy / skirt"))
        rows = FindingsEdit.move(rows, 2, 0)
        DraftStore(dir).write(Draft.start(rma, inspector, rows, now = "2026-11-18T10:00:00-05:00"))

        // "App killed": a fresh store reads the same files.
        val back = DraftStore(dir).read("demo", "912345")
        assertNotNull(back)
        assertEquals(rows, back!!.rows)
        assertEquals("sig-1", back.baseSignature)
        assertEquals(inspector, back.inspector)
        assertEquals(listOf(null, "L1.sig-1", "L2.sig-1"), back.rows.map { it.rowId })
        assertEquals(rma, back.base)
    }

    @Test fun newRowsAreWrittenWithRowIdNull() {
        val dir = tmp.newFolder("drafts")
        val rows = FindingsEdit.add(rma.parts.toFindingRows(), "new-1")
        val store = DraftStore(dir)
        store.write(Draft.start(rma, inspector, rows))
        val file = File(File(dir, DraftStore.hex("demo")), DraftStore.hex("912345") + ".json")
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val saved = json["rows"]!!.jsonArray
        assertEquals(JsonNull, saved[2].jsonObject["row_id"])
        assertEquals("L1.sig-1", saved[0].jsonObject["row_id"]!!.jsonPrimitive.content)
        assertEquals("sig-1", json["base_signature"]!!.jsonPrimitive.content)
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun draftsBelongToTheirInspector() {
        val store = DraftStore(tmp.newFolder("drafts"))
        store.write(Draft.start(rma, inspector, rma.parts.toFindingRows()))
        assertNull(store.read("other", "912345"))
        assertTrue(store.list("other").isEmpty())
        assertEquals(listOf("912345"), store.list("demo").map { it.rmaId })
        store.delete("demo", "912345")
        assertNull(store.read("demo", "912345"))
    }

    @Test fun oddIdsAreSafeFileNames() {
        val store = DraftStore(tmp.newFolder("drafts"))
        val odd = rma.copy(rmaId = "../SAP/000123 4", displayNumber = "000123 4")
        store.write(Draft.start(odd, DraftInspector("j.doe@example.invalid"), emptyList()))
        assertEquals("../SAP/000123 4", store.read("j.doe@example.invalid", "../SAP/000123 4")!!.rmaId)
    }

    @Test fun unreadableDraftIsKeptAndReportedAsMissing() {
        val dir = tmp.newFolder("drafts")
        val f = File(File(dir, DraftStore.hex("demo")), DraftStore.hex("912345") + ".json")
        f.parentFile!!.mkdirs()
        f.writeText("{ not json")
        assertNull(DraftStore(dir).read("demo", "912345"))
        assertTrue(f.exists())
    }

    // ------------------------------------------------------------------ work list

    @Test fun draftedRmaThatNoLongerMatchesStaysListedAndFlagged() {
        val listed = RmaListItem("912346", "912346", statusSince = "2026-10-08")
        val draft = Draft.start(rma, inspector, rma.parts.toFindingRows())
        val (items, flagged) = WorkListDrafts.merge(listOf(listed), listOf(draft))
        assertEquals(listOf("912346", "912345"), items.map { it.rmaId })
        assertEquals(setOf("912345"), flagged)
        assertEquals("4FE-28Y", items[1].model)

        val (same, none) = WorkListDrafts.merge(listOf(listed, draft.toListItem()), listOf(draft))
        assertEquals(2, same.size)
        assertTrue(none.isEmpty())
    }
}
