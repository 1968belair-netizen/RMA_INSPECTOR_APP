package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picker ordering and search (spec §4.3). FAKE codes only. */
class CodePickingTest {
    private fun c(code: String, desc: String = "Desc $code", group: String? = null, pin: Boolean = false) = Code(group, code, desc, pin)

    @Test
    fun naturalSortWithGroupsAndNormalFirst() {
        val items = listOf(c("100"), c("48a"), c("13"), c("50", "Normal"), c("48"), c("07"), c("7"),
            c("0063", group = "COMP-MECH"), c("2", group = "B-GRP"), c("10", group = "COMP-MECH"))
        val arranged = CodePicking.arrange(items, pinned = listOf(CodeKey(null, "50")))
        assertEquals(listOf(null to "50", null to "7", null to "07", null to "13", null to "48", null to "48a", null to "100",
            "B-GRP" to "2", "COMP-MECH" to "10", "COMP-MECH" to "0063"),
            arranged.map { it.group to it.code })
        val rows = CodePicking.rows(arranged, listOf(CodeKey(null, "50")))
        assertEquals(PickerRow.Item(arranged[0], pinned = true), rows[0])
        val headers = rows.filterIsInstance<PickerRow.Header>().map { it.group }
        assertEquals(listOf("B-GRP", "COMP-MECH"), headers)
    }

    @Test
    fun pinFirstFlagFromServerAlsoPins() {
        val arranged = CodePicking.arrange(listOf(c("13"), c("50", "Normal", pin = true), c("01")))
        assertEquals(listOf("50", "01", "13"), arranged.map { it.code })
    }

    @Test
    fun macolaListsHaveNoHeaders() {
        val rows = CodePicking.rows(CodePicking.arrange(listOf(c("2"), c("1"))))
        assertTrue(rows.all { it is PickerRow.Item })
    }

    @Test
    fun hiddenCodesNotOffered() {
        val arranged = CodePicking.arrange(listOf(c("01"), c("36", "-"), c("02")), hidden = listOf(CodeKey(null, "36")))
        assertEquals(listOf("01", "02"), arranged.map { it.code })
    }

    @Test
    fun searchMatchesCodeOrDescriptionExactFirst() {
        val arranged = CodePicking.arrange(listOf(c("48", "Winding to ground insulation"), c("48a", "Winding resistance"),
            c("148", "Example part"), c("13", "Heavy wear"), c("100", "Oil separator")))
        assertEquals(listOf("48", "48a", "148"), CodePicking.filter(arranged, "48").map { it.code })
        assertEquals(listOf("48", "48a"), CodePicking.filter(arranged, "winding").map { it.code })
        assertEquals(listOf("48a"), CodePicking.filter(arranged, "48A").map { it.code })
        assertEquals(listOf("100"), CodePicking.filter(arranged, "  oil ").map { it.code })
        assertEquals(arranged, CodePicking.filter(arranged, ""))
        assertTrue(CodePicking.filter(arranged, "zzz").isEmpty())
    }

    @Test
    fun handles160PlusItems() {
        val many = (1..200).map { c(it.toString().padStart(if (it % 3 == 0) 3 else 1, '0') + if (it % 7 == 0) "a" else "", "Fake part $it") }
        val arranged = CodePicking.arrange(many)
        assertEquals(200, arranged.size)
        // Natural order: numeric value first, never parsed or rewritten.
        val codes = arranged.map { it.code }
        assertEquals(codes, codes.sortedWith(com.bitzer.rmainspector.core.NaturalSort))
        assertEquals(1, CodePicking.filter(arranged, "Fake part 163").size)
        assertEquals(200, CodePicking.rows(arranged).size)
    }

    @Test
    fun recentOnlyOfferedCodesNewestFirst() {
        val arranged = CodePicking.arrange(listOf(c("01"), c("02"), c("03")), hidden = listOf(CodeKey(null, "03")))
        val recent = CodePicking.recent(arranged, listOf(CodeKey(null, "03"), CodeKey(null, "02"), CodeKey("X", "01"), CodeKey(null, "01")))
        assertEquals(listOf("02", "01"), recent.map { it.code })
    }
}
