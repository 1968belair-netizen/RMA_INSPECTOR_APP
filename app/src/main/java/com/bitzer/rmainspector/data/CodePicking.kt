package com.bitzer.rmainspector.data

/** One line in a code picker list: a group heading (SAP code groups) or a code. */
sealed interface PickerRow {
    data class Header(val group: String) : PickerRow
    data class Item(val code: Code, val pinned: Boolean) : PickerRow
}

/**
 * Ordering and search for code pickers (spec §4.3): pinned codes first (`pin_first` from the server or a
 * `normal_issue_codes` entry — `50` Normal for issues), then natural sort on group, then code. Hidden codes are
 * not offered. Search matches code or description (case-insensitive); exact code, then code prefix, rank first.
 * Codes are compared as text only.
 */
object CodePicking {
    fun arrange(items: List<Code>, pinned: Collection<CodeKey> = emptyList(), hidden: Collection<CodeKey> = emptyList()): List<Code> {
        val hiddenSet = hidden.toSet()
        val pinSet = pinned.toSet()
        val visible = items.filter { it.key !in hiddenSet }.distinctBy { it.key }
        val (top, rest) = visible.partition { it.pinFirst || it.key in pinSet }
        return top.sortedWith(CodeOrder) + rest.sortedWith(CodeOrder)
    }

    fun isPinned(code: Code, pinned: Collection<CodeKey>): Boolean = code.pinFirst || code.key in pinned

    /** Filters an [arrange]d list. Blank query = everything, unchanged order. */
    fun filter(arranged: List<Code>, query: String): List<Code> {
        val q = query.trim()
        if (q.isEmpty()) return arranged
        val exact = ArrayList<Code>()
        val prefix = ArrayList<Code>()
        val other = ArrayList<Code>()
        for (c in arranged) {
            when {
                c.code.equals(q, ignoreCase = true) -> exact += c
                c.code.startsWith(q, ignoreCase = true) -> prefix += c
                c.code.contains(q, ignoreCase = true) || c.description.contains(q, ignoreCase = true) ||
                    (c.group?.contains(q, ignoreCase = true) == true) -> other += c
            }
        }
        return exact + prefix + other
    }

    /**
     * Rows for the list: pinned codes first without a heading; group headings only when the list has more than
     * one group (Macola lists have none).
     */
    fun rows(arranged: List<Code>, pinned: Collection<CodeKey> = emptyList(), withHeaders: Boolean = true): List<PickerRow> {
        val showHeaders = withHeaders && arranged.mapTo(HashSet()) { it.group }.let { it.size > 1 || it.singleOrNull() != null }
        val out = ArrayList<PickerRow>(arranged.size + 8)
        var lastGroup: String? = null
        var headerShown = false
        for (c in arranged) {
            val pin = isPinned(c, pinned)
            if (showHeaders && !pin) {
                val g = c.group ?: ""
                if (!headerShown || g != lastGroup) {
                    if (g.isNotEmpty()) out += PickerRow.Header(g)
                    lastGroup = g
                    headerShown = true
                }
            }
            out += PickerRow.Item(c, pin)
        }
        return out
    }

    /** Recently used codes that are still offered, newest first. */
    fun recent(arranged: List<Code>, recentKeys: List<CodeKey>, max: Int = 6): List<Code> {
        val byKey = arranged.associateBy { it.key }
        return recentKeys.mapNotNull { byKey[it] }.take(max)
    }
}
