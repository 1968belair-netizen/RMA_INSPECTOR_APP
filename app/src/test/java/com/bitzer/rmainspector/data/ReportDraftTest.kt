package com.bitzer.rmainspector.data

import com.bitzer.rmainspector.ui.workspace.photosForPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report tab data rules (spec §7, §9; api §9.2). FAKE data. */
class ReportDraftTest {
    private val settings = ReportSettings(photosPerPage = 6, fixedPages = 2)
    private val rma = RmaDetail(
        "912345", "912345", "sig-1", isOpen = true, inspectable = true,
        summary = Summary(exists = true, defectCode = Code(null, "10", "Lack of lubrication"),
            blocks = listOf(SummaryBlock("1", "Example text")), inspectedBy = "", summaryBy = "XY"),
    )
    private fun listing(sig: String, vararg files: String) =
        PhotoListing(true, "912xxx", emptyList(), ReportPicsFolder(true, sig, files.toList()))

    @Test fun pagesAreFixedPlusPhotoPages() {
        assertEquals(2, ReportDraft.pageCount(0, settings))
        assertEquals(3, ReportDraft.pageCount(1, settings))
        assertEquals(3, ReportDraft.pageCount(6, settings))
        assertEquals(4, ReportDraft.pageCount(7, settings))
        assertEquals(6, ReportDraft.pageCount(20, settings))
    }

    @Test fun seedFillsInitialsOnlyWhereEmptyAndIndependently() {
        val d = ReportDraft.seed(rma, listing("rp-1", "05 Desk photo.jpg"), "JD")
        assertEquals("JD", d.inspectedBy)
        assertEquals("XY", d.summaryBy)
        assertEquals("Example text", d.summary("1"))
        assertEquals("10", d.defectCode?.code)
        assertEquals("05 Desk photo", d.picks.single().caption)
        assertEquals("report_pics", d.picks.single().source)
        assertEquals("rp-1", d.picksBaseSignature)
        assertFalse(d.picksDirty)
    }

    @Test fun addRemoveToggleUseTheFilenameAsCaption() {
        var d = ReportDraft(picksBaseSignature = "rp-empty")
        d = d.add("03-1 Piston assy, skirt - 34 Scored, Scratched.jpg")
        assertEquals("03-1 Piston assy, skirt - 34 Scored, Scratched", d.picks.single().caption)
        assertEquals("all_pics", d.picks.single().source)
        assertTrue(d.picksDirty)
        assertEquals(1, d.add("03-1 Piston assy, skirt - 34 Scored, Scratched.jpg").picks.size)
        assertTrue(d.toggle("03-1 Piston assy, skirt - 34 Scored, Scratched.jpg").picks.isEmpty())
    }

    @Test fun reportCopyCountsAsPickedAfterSave() {
        val saved = ReportDraft().adoptServer(listOf("00-1 As received.jpg"), "rp-2")
        assertTrue(saved.isPicked("00-1 As received.jpg"))
        assertFalse(saved.picksDirty)
        // An All Pics file whose caption became the copy's name is also "in the report".
        val d = ReportDraft().add("00-1 As received.jpg").withCaption(0, "00-1 First photo")
        assertTrue(d.isPicked("00-1 First photo.jpg"))
    }

    @Test fun captionProblemsMirrorTheServer() {
        val d = ReportDraft(picks = listOf(
            ReportPick("all_pics", "a.jpg", "Same"), ReportPick("all_pics", "b.jpg", "same"),
            ReportPick("all_pics", "c.jpg", "  "), ReportPick("all_pics", "d.jpg", "x".repeat(120)),
            ReportPick("all_pics", "e.jpg", "Fine"),
        ))
        val p = d.captionProblems()
        assertEquals(CaptionProblem.DUPLICATE, p[0])
        assertEquals(CaptionProblem.DUPLICATE, p[1])
        assertEquals(CaptionProblem.EMPTY, p[2])
        assertEquals(CaptionProblem.TOO_LONG, p[3])
        assertNull(p[4])
    }

    @Test fun reportOrderIsNaturalOrderOfCaptions() {
        val d = ReportDraft(picks = listOf(
            ReportPick("all_pics", "a.jpg", "03-10 Late"), ReportPick("all_pics", "b.jpg", "03-2 Early"), ReportPick("all_pics", "c.jpg", "00-1 First"),
        ))
        assertEquals(listOf(3, 2, 1), d.reportPositions().let { listOf(it[0], it[1], it[2]) }.map { it })
        assertEquals(listOf("00-1 First", "03-2 Early", "03-10 Late"), d.inReportOrder().picks.map { it.caption })
    }

    @Test fun refreshKeepsEditedPicksAndFlagsAChangedServerList() {
        val mine = ReportDraft.seed(rma, listing("rp-1"), "JD").add("00-1 As received.jpg")
        val r = ReportDraft.refresh(mine, rma, listing("rp-2", "06 Someone else.jpg"), "JD")
        assertTrue(r.serverChanged)
        assertEquals(1, r.draft.picks.size)                    // not replaced
        assertEquals("rp-1", r.draft.picksBaseSignature)       // still based on what the inspector saw
        val clean = ReportDraft.seed(rma, listing("rp-1"), "JD")
        val r2 = ReportDraft.refresh(clean, rma, listing("rp-2", "06 Someone else.jpg"), "JD")
        assertFalse(r2.serverChanged)                          // nothing edited → follow the server
        assertEquals("06 Someone else", r2.draft.picks.single().caption)
        assertEquals("rp-2", r2.draft.picksBaseSignature)
    }

    @Test fun refreshKeepsEditedFields() {
        val edited = ReportDraft.seed(rma, null, "JD").withSummary("1", "My text").withInspectedBy("ZZ")
        val r = ReportDraft.refresh(edited, rma.copy(signature = "sig-2"), null, "JD")
        assertEquals("My text", r.draft.summary("1"))
        assertEquals("ZZ", r.draft.inspectedBy)
    }

    @Test fun requestCarriesBaseSignatureAndPicksExactly() {
        val d = ReportDraft(picksBaseSignature = "rp-empty").add("00-1 As received.jpg")
        val body = ApiClient.writeJson.encodeToString(ReportPicsRequest.serializer(), d.toRequest("sub-1"))
        assertTrue(body.contains("\"base_signature\":\"rp-empty\""))
        assertTrue(body.contains("\"source\":\"all_pics\""))
        assertTrue(body.contains("\"caption\":\"00-1 As received\""))
    }

    @Test fun photoStripFiltersByRowThenPositionThenPartCode() {
        val row = FindingRow("L2.x", "L2.x", Code(null, "48a", "Winding"), Code(null, "50", "Normal"))
        val photos = listOf(
            PhotoEntry("00-1 As received.jpg", seq = 0),
            PhotoEntry("by row.jpg", seq = 9, rowId = "L2.x"),
            PhotoEntry("02-1 by position.jpg", seq = 2),
            PhotoEntry("unknown seq by code.jpg", partCode = CodeKey(null, "48a")),
            PhotoEntry("other.jpg", seq = 3, rowId = "L3.x"),
        )
        assertEquals(photos.size, photosForPart(photos, null, 0).size)
        assertEquals(listOf("by row.jpg", "02-1 by position.jpg", "unknown seq by code.jpg"), photosForPart(photos, row, 2).map { it.filename })
    }
}
