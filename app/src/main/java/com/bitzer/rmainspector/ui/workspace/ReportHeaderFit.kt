package com.bitzer.rmainspector.ui.workspace

/** Optional fields of the slim Report-tab header line. RMA number and the status chip are always visible and not listed. */
enum class HeaderField {
    CUSTOMER, MODEL, SERIAL, RETURN_CODE, FAILURE;

    companion object {
        /** Left-to-right order on the slim line. */
        val DISPLAY_ORDER: List<HeaderField> = listOf(CUSTOMER, MODEL, SERIAL, RETURN_CODE, FAILURE)

        /** Order in which fields move into Details when the line is too narrow (issue #81): serial first, model last. */
        val DROP_ORDER: List<HeaderField> = listOf(SERIAL, RETURN_CODE, FAILURE, CUSTOMER, MODEL)
    }
}

/**
 * Measured width of one field in px. [shortPx] is the width after ellipsizing (customer only, ~12 characters);
 * null = this field is never ellipsized, only dropped.
 */
data class FieldWidth(val field: HeaderField, val fullPx: Int, val shortPx: Int? = null)

/**
 * Everything the fit needs, in px, so the function is pure and JVM-testable.
 * - [availablePx]: width of the header line.
 * - [gapPx]: space between neighbouring items.
 * - [rmaPx], [statusPx]: the always-visible items. [detailsPx]: the Details control. [chipPx]: the "+n" chip (shown only when n > 0).
 * - [twoLineBelowPx]: windows narrower than this use two lines (RMA + status on line 1, the rest on line 2) before any field is dropped.
 */
data class FitParams(
    val availablePx: Int,
    val gapPx: Int,
    val rmaPx: Int,
    val statusPx: Int,
    val detailsPx: Int,
    val chipPx: Int,
    val twoLineBelowPx: Int,
)

/**
 * Result of [fitReportHeader]. [visible] is in display order. [customerShort]: the customer name is ellipsized.
 * [twoLine]: fields sit on a second line. [hidden] = number of fields moved into Details (the "+n" chip).
 */
data class HeaderFit(
    val visible: List<HeaderField>,
    val customerShort: Boolean,
    val twoLine: Boolean,
    val hidden: Int,
)

/**
 * Decides which fields fit on the slim header line. Never shrinks fonts and never wraps into a tall block:
 *  1. everything fits on one line -> done;
 *  2. a very narrow window (< [FitParams.twoLineBelowPx]) -> two lines, nothing dropped yet;
 *  3. otherwise (or if even line 2 is too short) fields drop in [HeaderField.DROP_ORDER]; the customer name is
 *     ellipsized to ~12 characters before it is dropped.
 */
fun fitReportHeader(p: FitParams, fields: List<FieldWidth>): HeaderFit {
    val byField = fields.associateBy { it.field }
    val all = HeaderField.DISPLAY_ORDER.filter { it in byField }

    fun fieldsWidth(visible: Set<HeaderField>, short: Boolean): Int {
        val ws = all.filter { it in visible }.map { f ->
            val w = byField.getValue(f)
            if (f == HeaderField.CUSTOMER && short) w.shortPx ?: w.fullPx else w.fullPx
        }
        return if (ws.isEmpty()) 0 else ws.sum() + p.gapPx * (ws.size - 1)
    }

    /** One-line width: RMA, visible fields, status, [chip when something is hidden], Details. */
    fun oneLineWidth(visible: Set<HeaderField>, short: Boolean): Int {
        val items = 2 + visible.size + 1 + (if (visible.size < all.size) 1 else 0)
        val sum = p.rmaPx + p.statusPx + p.detailsPx + (if (visible.size < all.size) p.chipPx else 0) + fieldsWidth(visible, short)
        // fieldsWidth already holds the gaps between fields; add the gaps between the other items and the field block.
        val blocks = items - visible.size + (if (visible.isEmpty()) 0 else 1)
        return sum + p.gapPx * (blocks - 1)
    }

    fun result(visible: Set<HeaderField>, short: Boolean, twoLine: Boolean) =
        HeaderFit(all.filter { it in visible }, short && HeaderField.CUSTOMER in visible, twoLine, all.size - visible.size)

    val full = all.toSet()
    if (oneLineWidth(full, false) <= p.availablePx) return result(full, false, false)

    val twoLine = p.availablePx < p.twoLineBelowPx
    var visible = full
    var short = false
    while (true) {
        val fits = if (twoLine) fieldsWidth(visible, short) <= p.availablePx else oneLineWidth(visible, short) <= p.availablePx
        if (fits) return result(visible, short, twoLine)
        val next = HeaderField.DROP_ORDER.firstOrNull { it in visible } ?: return result(visible, short, twoLine)
        val w = byField.getValue(next)
        if (next == HeaderField.CUSTOMER && !short && w.shortPx != null && w.shortPx < w.fullPx) {
            short = true
            continue
        }
        visible = visible - next
    }
}

/** "Example Refrigeration Co." -> "Example Refr…" (first [max] characters). */
fun ellipsizeCustomer(name: String, max: Int = 12): String =
    if (name.length <= max) name else name.take(max).trimEnd() + "…"
