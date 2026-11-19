package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.data.ReportPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filename → caption chips (spec §9.1) and the All / Report photo sets (#82). FAKE names only. */
class ReportPhotoCaptionsTest {
    @Test fun partPhotoGivesPartAndIssue() {
        val c = ReportPhotoCaptions.parse("03-1 Piston assy, skirt - 34 Scored, Scratched.jpg")
        assertEquals("03-1", c.badge)
        assertFalse(c.rmaLevel)
        assertEquals("Piston assy, skirt", c.partDescription)
        assertEquals("34", c.issueCode)
        assertEquals("Scored, Scratched", c.issueDescription)
        assertEquals("34 Scored, Scratched", c.issueLabel)
    }

    @Test fun issueCodeIsTextNotNumber() {
        val c = ReportPhotoCaptions.parse("12-2 Terminal box - 48a Burnt terminal.jpg")
        assertEquals("48a", c.issueCode)
        assertEquals("48a Burnt terminal", c.issueLabel)
        assertEquals("12-2", c.badge)
    }

    @Test fun issueWithoutDescription() {
        val c = ReportPhotoCaptions.parse("02-1 Winding resistance - 07.jpg")
        assertEquals("Winding resistance", c.partDescription)
        assertEquals("07", c.issueCode)
        assertNull(c.issueDescription)
        assertEquals("07", c.issueLabel)
    }

    @Test fun rmaLevelPhoto() {
        val c = ReportPhotoCaptions.parse("00-2 Nameplate.jpg")
        assertTrue(c.rmaLevel)
        assertEquals("Nameplate", c.rmaTag)
        assertEquals("00-2", c.badge)
        assertNull(c.partDescription)
        assertNull(c.issueLabel)
    }

    @Test fun threeDigitPositionAndTwoDigitPhotoNumber() {
        val c = ReportPhotoCaptions.parse("101-12 Bearing - 13 Heavy wear.jpg")
        assertEquals("101-12", c.badge)
        assertEquals("Bearing", c.partDescription)
        assertEquals("13", c.issueCode)
    }

    @Test fun partWithoutIssueKeepsDescription() {
        val c = ReportPhotoCaptions.parse("04-1 Connecting rod.jpg")
        assertEquals("Connecting rod", c.partDescription)
        assertNull(c.issueCode)
    }

    @Test fun deskPhotoWithoutPatternKeepsWholeName() {
        val c = ReportPhotoCaptions.parse("05 Desk photo.jpg")
        assertNull(c.badge)
        assertFalse(c.rmaLevel)
        assertEquals("05 Desk photo", c.text)
        assertNull(c.partDescription)
    }

    @Test fun extensionIsOptionalAndCaseInsensitive() {
        assertEquals("As received", ReportPhotoCaptions.parse("00-1 As received").rmaTag)
        assertEquals("As received", ReportPhotoCaptions.parse("00-1 As received.JPG").rmaTag)
    }

    @Test fun emptyNameDoesNotThrow() {
        assertEquals("", ReportPhotoCaptions.parse("").text)
    }

    @Test fun longCaptionIsKeptWhole() {
        val part = "Connecting rod main bearing with a very long part description here"
        val c = ReportPhotoCaptions.parse("04-3 $part - 13 Heavy wear on the bearing surface near the oil groove.jpg")
        assertEquals(part, c.partDescription)
        assertEquals("Heavy wear on the bearing surface near the oil groove", c.issueDescription)
    }

    private val all = listOf(
        PhotoEntry("00-1 As received.jpg"), PhotoEntry("01-1 Terminal box - 50 Normal.jpg"),
        PhotoEntry("02-1 Winding resistance - 39 Winding fault.jpg"), PhotoEntry("10-1 Oil pump - 50 Normal.jpg"),
        PhotoEntry("2-1 odd.jpg"),
    )
    private val picks = listOf(
        ReportPick("all_pics", "10-1 Oil pump - 50 Normal.jpg", "10-1 Oil pump"),
        ReportPick("all_pics", "00-1 As received.jpg", "00-1 As received"),
        ReportPick("report_pics", "05 Desk photo.jpg", "05 Desk photo"),
    )

    @Test fun allSetKeepsServerOrder() {
        val v = ReportPhotoCaptions.viewerPhotos(all, picks, reportOnly = false)
        assertEquals(all.map { it.filename }, v.map { it.filename })
        assertTrue(v.all { it.folder == "all" })
    }

    @Test fun reportSetIsThePicksInReportOrder() {
        val v = ReportPhotoCaptions.viewerPhotos(all, picks, reportOnly = true)
        assertEquals(listOf("00-1 As received.jpg", "05 Desk photo.jpg", "10-1 Oil pump - 50 Normal.jpg"), v.map { it.filename })
        assertEquals(listOf("all", "report", "all"), v.map { it.folder })
    }

    @Test fun emptySets() {
        assertTrue(ReportPhotoCaptions.viewerPhotos(emptyList(), picks, reportOnly = false).isEmpty())
        assertTrue(ReportPhotoCaptions.viewerPhotos(all, emptyList(), reportOnly = true).isEmpty())
    }

    @Test fun samePhotoInBothFoldersIsKeptSeparately() {
        val v = ReportPhotoCaptions.viewerPhotos(all, listOf(
            ReportPick("all_pics", "00-1 As received.jpg", "00-1 As received"),
            ReportPick("all_pics", "00-1 As received.jpg", "00-1 As received"),
        ), reportOnly = true)
        assertEquals(1, v.size)
    }

    @Test fun indexOfKeyFallsBackToFirst() {
        val v = ReportPhotoCaptions.viewerPhotos(all, picks, reportOnly = false)
        assertEquals(2, ReportPhotoCaptions.indexOfKey(v, "all/02-1 Winding resistance - 39 Winding fault.jpg"))
        assertEquals(0, ReportPhotoCaptions.indexOfKey(v, "all/gone.jpg"))
        assertEquals(0, ReportPhotoCaptions.indexOfKey(v, null))
        assertEquals(-1, ReportPhotoCaptions.indexOfKey(emptyList(), "x"))
    }

    @Test fun counterIsOneBased() {
        assertEquals("1 / 7", ReportPhotoCaptions.counter(0, 7))
        assertEquals("7 / 7", ReportPhotoCaptions.counter(6, 7))
    }
}
