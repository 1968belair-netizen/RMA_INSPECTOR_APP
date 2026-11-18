package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Issue #17: work-list chips, defaults, remembered selection, order and age. Fake data only. */
class WorkListTest {
    private val lists = ApiClient.defaultJson.decodeFromString(CodeLists.serializer(), """
        {"product_types":[{"value":"Recip","key":"recip"},{"value":"Screw","key":"screw"},{"value":"Scroll","key":"scroll"},
                          {"value":"Part","key":"part"},{"value":"Transport","key":"transport"},{"value":"","key":"unknown"}],
         "return_codes":[{"group":null,"code":"Warranty","description":"Warranty"},
                         {"group":null,"code":"Repair & Return","description":"Repair & Return"},
                         {"group":null,"code":"Inspection Only","description":"Inspection Only"},
                         {"group":null,"code":"Core&Inspection","description":"Core&Inspection"}],
         "work_list_statuses":[{"group":null,"code":"Received"}],
         "work_list_product_types":["Recip","Scroll","Screw","Part"],
         "work_list_return_codes":[{"group":null,"code":"Inspection Only"},{"group":null,"code":"REPAIR & RETURN "},
                                   {"group":null,"code":"Warranty"}]}
    """.trimIndent())

    private fun k(code: String) = CodeKey(null, code)

    @Test
    fun chipsKeepServerOrderForTypesAndNaturalSortForCodes() {
        val chips = WorkListRules.resolve(lists, null)
        assertEquals(listOf("Recip", "Screw", "Scroll", "Part", "Transport"), chips.productTypes.map { it.code })
        assertEquals(listOf("Core&Inspection", "Inspection Only", "Repair & Return", "Warranty"), chips.returnCodes.map { it.code })
    }

    @Test
    fun defaultsComeFromServerMatchedTrimmedUppercase() {
        val f = WorkListRules.resolve(lists, null).filter
        assertEquals(listOf("Recip", "Scroll", "Screw", "Part"), f.productTypes)
        assertEquals(listOf(k("Inspection Only"), k("Repair & Return"), k("Warranty")), f.returnCodes)
    }

    @Test
    fun savedChoiceWinsAndUnknownValuesFallBackToDefault() {
        val saved = WorkListFilter(listOf("Screw"), listOf(k("Warranty")))
        assertEquals(saved, WorkListRules.resolve(lists, saved).filter)
        // Saved values the server no longer offers → that row goes back to the default.
        val stale = WorkListFilter(listOf("Gone"), listOf(k("Warranty")))
        assertEquals(listOf("Recip", "Scroll", "Screw", "Part"), WorkListRules.resolve(lists, stale).filter.productTypes)
        // A row cleared on purpose stays cleared.
        val cleared = WorkListFilter(emptyList(), listOf(k("Warranty")))
        assertEquals(emptyList<String>(), WorkListRules.resolve(lists, cleared).filter.productTypes)
    }

    @Test
    fun savedFilterRoundTripsAsJson() {
        val f = WorkListFilter(listOf("Recip"), listOf(CodeKey("G1", "Warranty"), k("Repair & Return")))
        val raw = ApiClient.writeJson.encodeToString(WorkListFilter.serializer(), f)
        assertEquals(f, ApiClient.defaultJson.decodeFromString(WorkListFilter.serializer(), raw))
    }

    @Test
    fun toggleKeepsChipOrder() {
        val chips = WorkListRules.resolve(lists, null)
        var f = WorkListRules.toggleProductType(chips.filter, chips.productTypes, "Scroll")
        assertEquals(listOf("Recip", "Screw", "Part"), f.productTypes)
        f = WorkListRules.toggleProductType(f, chips.productTypes, "Scroll")
        assertEquals(listOf("Recip", "Screw", "Scroll", "Part"), f.productTypes)
        f = WorkListRules.toggleReturnCode(f, chips.returnCodes, k("Core&Inspection"))
        assertEquals(k("Core&Inspection"), f.returnCodes.first())
    }

    @Test
    fun sortOldestFirstThenRmaNumberNatural() {
        fun item(id: String, since: String) = RmaListItem(id, id, statusSince = since)
        val sorted = WorkListRules.sort(listOf(item("912357", "2026-11-13"), item("10", "2026-10-08"),
            item("9", "2026-10-08"), item("912346", "2026-10-01")))
        assertEquals(listOf("912346", "9", "10", "912357"), sorted.map { it.rmaId })
    }

    @Test
    fun ageLabel() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals("23 days", RmaAge.label("2026-10-26", false, today))
        assertEquals("1 day (estimated)", RmaAge.label("2026-11-17", true, today))
        assertEquals("Today", RmaAge.label("2026-11-18", false, today))
        assertNull(RmaAge.label("", false, today))
    }
}
