package com.bitzer.rmainspector.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Photo storage, preview names, grouping and the upload hook (spec §4.4, §9; api §8). FAKE data only. */
class PhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cases: JSONObject by lazy {
        JSONObject(listOf(File("../testdata/photo_naming_cases.json"), File("testdata/photo_naming_cases.json")).first { it.exists() }.readText())
    }

    private fun photo(id: String, row: String? = null, tag: String? = null, tagText: String? = null, sent: Boolean = false) = LocalPhoto(
        photoId = id, kind = if (row != null) LocalPhoto.KIND_PART else LocalPhoto.KIND_RMA, rowLocalId = row, tag = tag, tagText = tagText,
        capturedAt = "2026-11-17T10:49:02-05:00", file = "$id.jpg", sha256 = "abc", sentAt = if (sent) "2026-11-17T11:00:00-05:00" else null,
    )

    // ------------------------------------------------------------ preview names = shared naming cases

    @Test fun partPreviewNamesMatchSharedCases() {
        val arr = cases.getJSONArray("part_photos")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val seq = c.getInt("seq"); val n = c.getInt("n")
            val rows = (1 until seq).map { FindingRow("f$it") } +
                FindingRow("target", partCode = Code(null, "P", c.getString("part")), issueCode = Code(null, c.getString("issue_cd"), c.getString("issue")))
            val local = (1..n).map { photo("p$it", row = "target") }
            val items = PhotoGroups.build(rows, local, emptyList()).first { it.rowLocalId == "target" }.items
            assertEquals("case $i", c.getString("expect"), items.last().name)
            assertTrue(items.last().isPreview)
        }
    }

    @Test fun rmaPreviewNamesMatchSharedCases() {
        val arr = cases.getJSONArray("rma_photos")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val text = if (c.isNull("tag_text")) null else c.getString("tag_text")
            val local = (1..c.getInt("n")).map { photo("p$it", tag = c.getString("tag"), tagText = text) }
            val items = PhotoGroups.build(emptyList(), local, emptyList()).first { it.isRma }.items
            assertEquals("case $i", c.getString("expect"), items.last().name)
        }
    }

    @Test fun nextNumberFollowsServerFiles() {
        val rows = listOf(FindingRow("a", partCode = Code(null, "07a", "Terminal box"), issueCode = Code(null, "50", "Normal")))
        val server = listOf(PhotoEntry("01-1 Terminal box - 50 Normal.jpg", seq = 1), PhotoEntry("01-2 Terminal box - 50 Normal.jpg", seq = 1),
            PhotoEntry("00-3 Nameplate.jpg", seq = 0))
        val g = PhotoGroups.build(rows, listOf(photo("p1", row = "a"), photo("p2", tag = "Overview")), server)
        assertEquals("01-3 Terminal box - 50 Normal.jpg", g.first { it.rowLocalId == "a" }.items.last().name)
        assertEquals("00-4 Overview.jpg", g.first { it.isRma }.items.last().name)
        assertEquals("00-4 Overview.jpg", PhotoGroups.rmaPreview(listOf(photo("p2", tag = "Overview")).dropLast(1), server, "Overview", null))
    }

    @Test fun rowWithoutPartOrIssueHasNoNameYet() {
        val g = PhotoGroups.build(listOf(FindingRow("a")), listOf(photo("p1", row = "a")), emptyList())
        val item = g.first { it.rowLocalId == "a" }.items.single()
        assertNull(item.name)
        assertNotNull(item.nameHint)
    }

    // ------------------------------------------------------------ grouping

    @Test fun groupsAreRmaFirstThenRowsThenUnlinked() {
        val part = Code(null, "63", "Piston assy")
        val rows = listOf(
            FindingRow("L1", rowId = "L1", partCode = Code(null, "07a", "Terminal box"), issueCode = Code(null, "50", "Normal")),
            FindingRow("new-1", partCode = part, issueCode = Code(null, "34", "Scored")),
        )
        val server = listOf(
            PhotoEntry("00-1 As received.jpg", seq = 0),
            PhotoEntry("01-1 Terminal box - 50 Normal.jpg", seq = 1, rowId = "L1"),
            PhotoEntry("02-1 Piston assy - 34 Scored.jpg", seq = 2, partCode = part.key),
            PhotoEntry("09-1 Mystery.jpg", seq = 9, partCode = CodeKey(null, "99")),
        )
        val local = listOf(photo("p1", row = "new-1"), photo("p2", row = "removed"), photo("sent", row = "new-1", sent = true))
        val g = PhotoGroups.build(rows, local, server)
        assertEquals(listOf(PhotoGroups.RMA_KEY, "L1", "new-1", PhotoGroups.UNLINKED_KEY), g.map { it.key })
        assertEquals(1, g[0].items.size)
        assertEquals(1, g[1].items.size)
        assertEquals(listOf("02-1 Piston assy - 34 Scored.jpg", "02-2 Piston assy - 34 Scored.jpg"), g[2].items.map { it.name })
        assertEquals(2, g[3].items.size) // server photo nothing links to + the photo of the removed row
        assertEquals(mapOf("L1" to 1, "new-1" to 2), PhotoGroups.countsByRow(rows, local, server))
    }

    @Test fun emptyRowStillHasAGroup() {
        val g = PhotoGroups.build(listOf(FindingRow("a")), emptyList(), emptyList())
        assertEquals(2, g.size)
        assertTrue(g.all { it.items.isEmpty() })
    }

    // ------------------------------------------------------------ local store

    @Test fun storedLocallyFirstAndNotSentUntilConfirmed() {
        val store = PhotoStore(tmp.newFolder("photos"))
        val f = store.newFile("demo", "912345", "p1"); f.writeBytes(byteArrayOf(1, 2, 3))
        store.add("demo", "912345", photo("p1", row = "a"))
        assertEquals(listOf("p1"), store.pending("demo", "912345").map { it.photoId })
        assertTrue(store.file("demo", "912345", store.list("demo", "912345").single()).exists())
        assertTrue(store.list("other", "912345").isEmpty()) // per inspector

        store.markSent("demo", "912345", "p1", "01-1 Terminal box - 50 Normal.jpg")
        assertTrue(store.pending("demo", "912345").isEmpty())
        assertEquals("01-1 Terminal box - 50 Normal.jpg", store.list("demo", "912345").single().serverFilename)
    }

    @Test fun retakeKeepsPositionAndDeletesOldFile() {
        val store = PhotoStore(tmp.newFolder("photos"))
        for (id in listOf("p1", "p2", "p3")) { store.newFile("u", "r", id).writeBytes(byteArrayOf(1)); store.add("u", "r", photo(id, row = "a")) }
        store.newFile("u", "r", "p9").writeBytes(byteArrayOf(2))
        store.replace("u", "r", "p2", photo("p9", row = "a"))
        assertEquals(listOf("p1", "p9", "p3"), store.list("u", "r").map { it.photoId })
        assertFalse(File(store.newFile("u", "r", "p2").path).exists())
        store.remove("u", "r", "p1")
        assertEquals(listOf("p9", "p3"), store.list("u", "r").map { it.photoId })
    }

    @Test fun sentPhotosAreKeptThirtyDays() {
        val store = PhotoStore(tmp.newFolder("photos"))
        store.newFile("u", "r", "p1").writeBytes(byteArrayOf(1)); store.add("u", "r", photo("p1", row = "a"))
        store.markSent("u", "r", "p1", "x.jpg", now = "2026-11-01T10:00:00Z")
        assertEquals(0, store.purgeSent("u", "r", now = Instant.parse("2026-11-20T10:00:00Z")))
        assertEquals(1, store.purgeSent("u", "r", now = Instant.parse("2026-12-05T10:00:00Z")))
        assertTrue(store.list("u", "r").isEmpty())
    }

    // ------------------------------------------------------------ upload hook for B3

    @Test fun uploadMetaFollowsRowPositionAndRowId() {
        val rows = listOf(
            FindingRow("L1", rowId = "L1", partCode = Code(null, "07a", "Terminal box"), issueCode = Code(null, "50", "Normal")),
            FindingRow("new-1", partCode = Code(null, "63", "Piston"), issueCode = Code("G", "34", "Scored")),
            FindingRow("new-2", partCode = Code(null, "64", "Rings")),
        )
        val m = PhotoUpload.meta(photo("p1", row = "new-1"), rows)!!
        assertEquals(2, m.seq); assertNull(m.rowId); assertEquals(CodeKey("G", "34"), m.issueCode); assertEquals("part", m.kind)
        assertEquals("L1", PhotoUpload.meta(photo("p2", row = "L1"), rows)!!.rowId)
        assertNull(PhotoUpload.meta(photo("p3", row = "new-2"), rows)) // no issue yet
        assertNull(PhotoUpload.meta(photo("p4", row = "gone"), rows))
        val r = PhotoUpload.meta(photo("p5", tag = "Other", tagText = "Oil sample"), rows)!!
        assertEquals(0, r.seq); assertEquals("Oil sample", r.tagText); assertNull(r.partCode)
    }

    // ------------------------------------------------------------ pure image helpers

    @Test fun scalingNeverUpsizes() {
        assertEquals(2048 to 1536, PhotoMath.targetSize(4000, 3000, 2048))
        assertEquals(1536 to 2048, PhotoMath.targetSize(3000, 4000, 2048))
        assertEquals(1600 to 1200, PhotoMath.targetSize(1600, 1200, 2048))
        assertEquals(2, PhotoMath.sampleSize(8000, 6000, 2048))
        assertEquals(1, PhotoMath.sampleSize(3000, 2000, 2048))
    }

    @Test fun captureTimeFromExif() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("2026-11-17T10:49:02-05:00", PhotoMath.capturedAt("2026:11:17 10:49:02", null, zone, Instant.EPOCH))
        assertEquals("2026-11-17T10:49:02+01:00", PhotoMath.capturedAt("2026:11:17 10:49:02", "+01:00", zone, Instant.EPOCH))
        assertEquals("2026-11-17T10:49:02-05:00", PhotoMath.capturedAt(null, null, zone, Instant.parse("2026-11-17T15:49:02Z")))
    }
}
