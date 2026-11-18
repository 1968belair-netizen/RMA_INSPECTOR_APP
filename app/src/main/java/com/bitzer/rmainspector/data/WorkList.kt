package com.bitzer.rmainspector.data

import com.bitzer.rmainspector.core.NaturalSort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The work-list chip selection (spec §3.3), remembered on the device. Product types are the `product_types`
 * values; return codes are code keys exactly as received.
 */
@Serializable
data class WorkListFilter(
    @SerialName("product_types") val productTypes: List<String> = emptyList(),
    @SerialName("return_codes") val returnCodes: List<CodeKey> = emptyList(),
) {
    val productTypeKeys: Set<CodeKey> get() = productTypes.map { CodeKey(null, it) }.toSet()
    val returnCodeKeys: Set<CodeKey> get() = returnCodes.toSet()
}

/** Chips offered on Home and the selection to use. */
data class WorkListChips(
    /** Product types as chip objects (`code` = the product type value), in server order; blank values left out. */
    val productTypes: List<Code>,
    /** Return codes in natural sort order. */
    val returnCodes: List<Code>,
    val filter: WorkListFilter,
)

object WorkListRules {
    private fun norm(s: String?) = s.orEmpty().trim().uppercase()

    fun productTypeChips(lists: CodeLists): List<Code> =
        lists.productTypes.map { it.value }.filter { it.isNotBlank() }.distinct().map { Code(null, it, it) }

    fun returnCodeChips(lists: CodeLists): List<Code> =
        lists.returnCodes.distinctBy { it.key }.sortedWith(CodeOrder)

    /** Server defaults (`work_list_*`), mapped onto the chips (matched trimmed + uppercase, like the server). */
    fun defaults(lists: CodeLists): WorkListFilter {
        val pts = productTypeChips(lists)
        val rcs = returnCodeChips(lists)
        return WorkListFilter(
            productTypes = lists.workListProductTypes.mapNotNull { d -> pts.firstOrNull { norm(it.code) == norm(d) }?.code }.distinct(),
            returnCodes = lists.workListReturnCodes.mapNotNull { d ->
                rcs.firstOrNull { norm(it.code) == norm(d.code) && norm(it.group) == norm(d.group) }?.key
            }.distinct(),
        )
    }

    /**
     * The saved choice where it still fits the server's lists, else the server default — per chip row.
     * A row the user cleared on purpose stays empty; a row whose saved values the server no longer offers falls
     * back to the default.
     */
    fun resolve(lists: CodeLists, saved: WorkListFilter?): WorkListChips {
        val pts = productTypeChips(lists)
        val rcs = returnCodeChips(lists)
        val def = defaults(lists)
        if (saved == null) return WorkListChips(pts, rcs, def)
        val ptValues = pts.map { it.code }.toSet()
        val rcKeys = rcs.map { it.key }.toSet()
        val savedPt = saved.productTypes.filter { it in ptValues }
        val savedRc = saved.returnCodes.filter { it in rcKeys }
        return WorkListChips(
            pts, rcs,
            WorkListFilter(
                productTypes = if (saved.productTypes.isNotEmpty() && savedPt.isEmpty()) def.productTypes else savedPt,
                returnCodes = if (saved.returnCodes.isNotEmpty() && savedRc.isEmpty()) def.returnCodes else savedRc,
            ),
        )
    }

    /** Toggle one chip; keeps the chip row's order so the saved value is stable. */
    fun toggleProductType(filter: WorkListFilter, chips: List<Code>, value: String): WorkListFilter {
        val next = if (value in filter.productTypes) filter.productTypes - value else filter.productTypes + value
        return filter.copy(productTypes = chips.map { it.code }.filter { it in next } + next.filter { v -> chips.none { it.code == v } })
    }

    fun toggleReturnCode(filter: WorkListFilter, chips: List<Code>, key: CodeKey): WorkListFilter {
        val next = if (key in filter.returnCodes) filter.returnCodes - key else filter.returnCodes + key
        return filter.copy(returnCodes = chips.map { it.key }.filter { it in next } + next.filter { k -> chips.none { it.key == k } })
    }

    /** Oldest `status_since` first, then RMA number (natural order). The server sorts too; this keeps it so. */
    fun sort(items: List<RmaListItem>): List<RmaListItem> =
        items.sortedWith(compareBy<RmaListItem> { it.statusSince.ifBlank { "9999" } }
            .thenComparator { a, b -> NaturalSort.compare(a.displayNumber, b.displayNumber) })
}

/** "23 days" from `status_since` (spec §3.2). */
object RmaAge {
    fun days(statusSince: String, today: LocalDate): Long? {
        val d = runCatching { LocalDate.parse(statusSince.trim().take(10)) }.getOrNull() ?: return null
        return ChronoUnit.DAYS.between(d, today).coerceAtLeast(0)
    }

    fun label(statusSince: String, estimated: Boolean, today: LocalDate): String? {
        val n = days(statusSince, today) ?: return null
        val text = when (n) {
            0L -> "Today"
            1L -> "1 day"
            else -> "$n days"
        }
        return if (estimated) "$text (estimated)" else text
    }
}
