package com.bitzer.rmainspector.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/api.md rev 6 shapes (fake data). */
class ContractModelsTest {
    private val read = ApiClient.defaultJson
    private val write = ApiClient.writeJson

    private val detailJson = """
    {
      "rma_id": "912345", "display_number": "912345", "signature": "sig-3f9d0c7a1b",
      "loaded_at": "2026-11-17T10:41:12-05:00", "is_open": true, "inspectable": true,
      "product_type": "Recip", "product_type_key": "recip", "product_type_source": "header",
      "capabilities": { "findings": true, "summary": true, "photos": true, "status": true, "report": true },
      "header": { "customer_name": "Example Refrigeration Co.", "model": "4FE-28Y",
        "inspection_status": { "group": null, "code": "Received", "description": "Received" },
        "failure_code": "9-Oil Issue", "credit_status": "" },
      "lock": { "locked": false, "held_by": null, "host": null, "possibly_stale": false },
      "parts": [
        { "row_id": "L1.3f9d0c7a1b", "part_code": { "group": null, "code": "07a", "description": "Terminal box" },
          "issue_code": { "group": null, "code": "50", "description": "Normal" }, "cause_code": null, "note": "" },
        { "row_id": "1000-0002", "part_code": { "group": "COMP-MECH", "code": "0063", "description": "Piston assy / skirt" },
          "issue_code": { "group": "DEF", "code": "99 – JSIOC", "description": "Example" },
          "cause_code": { "group": "CAUSE", "code": "C1", "description": "Example cause" }, "note": "two\nlines" }
      ],
      "summary": { "exists": true, "defect_code": { "group": null, "code": "10", "description": "Lack of lubrication" },
        "blocks": [ { "block_id": "1", "text": "The primary failure mode is ..." }, { "block_id": "2", "text": "" } ],
        "inspected_by": "JD", "summary_by": "JD" },
      "photos": { "folder_found": true, "folder_display": "912xxx\\9123xx\\912345\\All Pics", "count": 0, "files": [] },
      "some_future_field": { "anything": 1 }
    }
    """.trimIndent()

    @Test
    fun parsesDetailWithOpaqueIdsAndCodeObjects() {
        val d = read.decodeFromString(RmaDetail.serializer(), detailJson)
        assertEquals("912345", d.rmaId)
        assertEquals("912345", d.displayNumber)
        assertTrue(d.isOpen && d.inspectable)
        assertEquals("Received", d.header.inspectionStatus?.code)
        assertEquals("L1.3f9d0c7a1b", d.parts[0].rowId)
        assertNull(d.parts[0].causeCode)
        assertEquals("COMP-MECH", d.parts[1].partCode.group)
        assertEquals("10", d.summary.defectCode?.code)
        assertEquals(listOf("1", "2"), d.summary.blocks.map { it.blockId })
    }

    @Test
    fun writesCodesBackExactlyAsReceived() {
        val d = read.decodeFromString(RmaDetail.serializer(), detailJson)
        val out = write.encodeToString(ListSerializer(PartRowWrite.serializer()), d.parts.map { it.toWrite() })
        // Null group is written explicitly; SAP-style group/code and odd characters survive unchanged.
        assertTrue(out, out.contains("""{"row_id":"L1.3f9d0c7a1b","part_code":{"group":null,"code":"07a"}"""))
        assertTrue(out, out.contains(""""cause_code":null"""))
        assertTrue(out, out.contains("""{"group":"COMP-MECH","code":"0063"}"""))
        assertTrue(out, out.contains("""{"group":"DEF","code":"99 – JSIOC"}"""))
        assertTrue(out, out.contains("""{"group":"CAUSE","code":"C1"}"""))
        assertFalse("descriptions are not sent", out.contains("description"))
        val back = read.decodeFromString(ListSerializer(PartRowWrite.serializer()), out)
        assertEquals(d.parts.map { it.toWrite() }, back)
    }

    @Test
    fun newRowsHaveNullRowId() {
        val row = PartRowWrite(null, CodeKey(null, "63"), CodeKey(null, "34"), null, "")
        assertEquals(
            """{"row_id":null,"part_code":{"group":null,"code":"63"},"issue_code":{"group":null,"code":"34"},"cause_code":null,"note":""}""",
            write.encodeToString(PartRowWrite.serializer(), row),
        )
    }

    @Test
    fun codeListsAndOrdering() {
        val cl = read.decodeFromString(CodeLists.serializer(), """
          { "parts": [ { "group": null, "code": "100", "description": "Oil separator" },
                       { "group": null, "code": "48a", "description": "Winding resistance" },
                       { "group": null, "code": "48", "description": "Winding to ground insulation" } ],
            "issues": [], "causes": [], "defect_codes": [],
            "inspection_statuses": [ { "group": null, "code": "Received", "description": "Received" } ],
            "summary_blocks": [ { "block_id": "1", "label": "Summary 1", "max_length": 4000 } ],
            "work_list_statuses": [ { "group": null, "code": "Received" } ], "limits": { "note": 4000 } }
        """)
        assertFalse(cl.causesEnabled)
        assertEquals(4000, cl.summaryBlocks.single().maxLength)
        assertEquals(listOf("48", "48a", "100"), cl.parts.sortedWith(CodeOrder).map { it.code })
        val grouped = listOf(Code("B", "2"), Code(null, "9"), Code("A", "10"), Code("A", "2"))
        assertEquals(listOf(null to "9", "A" to "2", "A" to "10", "B" to "2"),
            grouped.sortedWith(CodeOrder).map { it.group to it.code })
    }
}
