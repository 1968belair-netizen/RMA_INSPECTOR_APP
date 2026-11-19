package com.bitzer.rmainspector.data

/**
 * The conflict screen's logic (spec §6.5, issue #65), all pure so the JVM tests cover it.
 *
 * Rule: any change made elsewhere is **reviewed, never overwritten**, and nothing is merged automatically. This file
 * only *describes* the two sides and builds what "Reload and keep my changes" would produce; the inspector decides.
 */

/** Which part of the record a [ChangeLine] is about, in the order the screen lists them (spec §6.5). */
enum class ChangeArea(val title: String) {
    STATUS("Status"),
    PARTS("Parts"),
    DEFECT("Defect code"),
    SUMMARY("Summaries"),
    INITIALS("Initials"),
    TEAM("RMA team fields"),
}

/** One line of the review: [label] changed from [before] to [after]. [info] = information only (RMA-team fields). */
data class ChangeLine(
    val area: ChangeArea,
    val label: String,
    val before: String,
    val after: String,
    val info: Boolean = false,
)

/** How one summary field (defect code, a summary block, initials) stands against the RMA the inspector loaded. */
enum class ItemState {
    /** Nobody changed it. */
    UNCHANGED,
    /** Only the inspector changed it → kept as is. */
    MINE_ONLY,
    /** Only the server's side changed it → the server's value is taken. */
    SERVER_ONLY,
    /** Both sides changed it to the same value → nothing to decide. */
    BOTH_SAME,
    /** Both sides changed it differently → the inspector must pick. */
    BOTH_DIFFERENT,
}

/** The inspector's explicit choice for a [ItemState.BOTH_DIFFERENT] field. [COMBINE] (text only): the server's text, then mine. */
enum class Pick { MINE, SERVER, COMBINE }

/**
 * One summary field with its three values as text ([base] = as loaded, [mine], [server]). [isText] = a summary block
 * (can be combined); [maxLength] is its server limit.
 */
data class SummaryItem(
    val key: String,
    val label: String,
    val base: String,
    val mine: String,
    val server: String,
    val state: ItemState,
    val isText: Boolean,
    val maxLength: Int = DEFAULT_MAX,
) {
    companion object { const val DEFAULT_MAX = 4000 }
}

/** What re-applying the inspector's parts edits on top of the server's rows gives. */
data class ReapplyResult(
    /** The rows after re-applying: server rows (new `row_id`s) with my edits, then my new rows. */
    val rows: List<FindingRow>,
    /** Old [FindingRow.localId] → new one, so the photos of a row follow it. Only rows that exist afterwards. */
    val idMap: Map<String, String>,
    /** Things the inspector must look at: edits that couldn't be matched, removals the desk overtook, order. */
    val flags: List<String>,
)

/** Everything the conflict screen shows. Built by [ConflictReview.build]. */
data class ConflictReview(
    val rmaId: String,
    val displayNumber: String,
    val serverChanges: List<ChangeLine>,
    val myChanges: List<ChangeLine>,
    val reapply: ReapplyResult,
    val summary: List<SummaryItem>,
    /** Photos taken on this tablet and not sent yet. */
    val photosPending: Int,
) {
    /** The summary fields changed on both sides to different values: each needs an explicit pick. */
    val bothSides: List<SummaryItem> get() = summary.filter { it.state == ItemState.BOTH_DIFFERENT }

    /** Only RMA-team fields changed on the server: information only, nothing to decide (spec §6.5). */
    val teamOnly: Boolean get() = serverChanges.isNotEmpty() && serverChanges.all { it.info }

    val hasMyChanges: Boolean get() = myChanges.isNotEmpty()

    /** "Reload and keep my changes" is allowed once every both-sides field has a pick. */
    fun canKeepMine(picks: Map<String, Pick>): Boolean = bothSides.all { it.key in picks }

    companion object {
        /**
         * @param base the RMA as the inspector loaded it
         * @param current the RMA on the server now
         * @param local the inspector's rows
         * @param report the Report tab edits (null = none)
         * @param blockLabels summary block id → label; [blockLimits] id → max length
         */
        fun build(
            base: RmaDetail,
            current: RmaDetail,
            local: List<FindingRow>,
            report: ReportDraft?,
            photosPending: Int,
            blockLabels: Map<String, String> = emptyMap(),
            blockLimits: Map<String, Int> = emptyMap(),
        ): ConflictReview {
            val items = ConflictSummary.items(base.summary, current.summary, report, blockLabels, blockLimits)
            return ConflictReview(
                rmaId = current.rmaId,
                displayNumber = current.displayNumber,
                serverChanges = ConflictDiff.serverChanges(base, current, items),
                myChanges = ConflictDiff.myChanges(base, local, items),
                reapply = ConflictReapply.rows(base.parts, current.parts, local),
                summary = items,
                photosPending = photosPending,
            )
        }
    }
}

/** Text of a code for people: "07a Terminal box". */
internal fun codeText(c: Code?): String = c?.let { "${it.code} ${it.description}".trim() }.orEmpty()

private fun rowText(row: FindingRow): String = SubmitReview.rowText(row)

private fun partRowText(row: PartRow): String = rowText(row.toFindingRow())

private fun PartRow.toFindingRow() =
    FindingRow(localId = rowId, rowId = rowId, partCode = partCode, issueCode = issueCode, causeCode = causeCode, note = note)

/** Same content (part, issue, cause, note); the `row_id` is ignored — it changes with every save. */
private fun sameValues(a: FindingRow, b: FindingRow): Boolean =
    a.partCode?.key == b.partCode?.key && a.issueCode?.key == b.issueCode?.key &&
        a.causeCode?.key == b.causeCode?.key && a.note == b.note

/** Describes what changed, on each side, as lines for the screen. */
object ConflictDiff {
    /**
     * Everything that changed on the server since [base] was loaded, in the order of spec §6.5: status, rows, defect
     * code, summaries, initials, then RMA-team fields (information only).
     */
    fun serverChanges(base: RmaDetail, current: RmaDetail, items: List<SummaryItem>): List<ChangeLine> {
        val out = mutableListOf<ChangeLine>()
        val bh = base.header
        val ch = current.header
        if (bh.inspectionStatus?.key != ch.inspectionStatus?.key) {
            out += ChangeLine(ChangeArea.STATUS, "Status", codeText(bh.inspectionStatus), codeText(ch.inspectionStatus))
        }
        out += partChanges(base.parts.map { it.toFindingRow() }, current.parts.map { it.toFindingRow() })
        for (it in items) {
            if (it.base == it.server) continue
            val area = when {
                it.key == ConflictSummary.DEFECT -> ChangeArea.DEFECT
                it.isText -> ChangeArea.SUMMARY
                else -> ChangeArea.INITIALS
            }
            out += ChangeLine(area, it.label, it.base, it.server)
        }
        fun team(label: String, before: String, after: String) {
            if (before != after) out += ChangeLine(ChangeArea.TEAM, label, before, after, info = true)
        }
        team("Credit status", bh.creditStatus, ch.creditStatus)
        team("Return code", codeText(bh.returnCode), codeText(ch.returnCode))
        team("Failure code", bh.failureCode, ch.failureCode)
        team("Failure description", bh.failureDescription, ch.failureDescription)
        team("Comments", bh.comments, ch.comments)
        team("RMA comment", bh.rmaComment, ch.rmaComment)
        team("Customer", bh.customerName, ch.customerName)
        team("Model", bh.model, ch.model)
        team("Serial number", bh.failedSerialNo, ch.failedSerialNo)
        team("Sales order", bh.salesOrderNo, ch.salesOrderNo)
        team("Failed date", bh.failedDate, ch.failedDate)
        return out
    }

    /** The inspector's own edits: parts rows, then the summary fields only they changed (or changed on both sides). */
    fun myChanges(base: RmaDetail, local: List<FindingRow>, items: List<SummaryItem>): List<ChangeLine> {
        val out = mutableListOf<ChangeLine>()
        val baseRows = base.parts.map { it.toFindingRow() }
        val baseById = baseRows.associateBy { it.rowId }
        val localIds = local.mapNotNull { it.rowId }.toSet()
        for (r in local) {
            if (r.isBlankNew) continue
            if (r.rowId == null) out += ChangeLine(ChangeArea.PARTS, "Row added", "", rowText(r))
            else {
                val orig = baseById[r.rowId] ?: continue
                if (!sameValues(r, orig)) out += ChangeLine(ChangeArea.PARTS, "Row changed", rowText(orig), rowText(r))
            }
        }
        for (b in baseRows) if (b.rowId !in localIds) out += ChangeLine(ChangeArea.PARTS, "Row removed", rowText(b), "")
        for (it in items) {
            if (it.state != ItemState.MINE_ONLY && it.state != ItemState.BOTH_SAME && it.state != ItemState.BOTH_DIFFERENT) continue
            val area = when {
                it.key == ConflictSummary.DEFECT -> ChangeArea.DEFECT
                it.isText -> ChangeArea.SUMMARY
                else -> ChangeArea.INITIALS
            }
            out += ChangeLine(area, it.label, it.base, it.mine)
        }
        return out
    }

    /**
     * Row changes between two row lists, matched by **values** (row ids change with every save): exact matches are
     * unchanged; leftovers with the same part are "changed"; the rest are removed / added.
     */
    fun partChanges(before: List<FindingRow>, after: List<FindingRow>): List<ChangeLine> {
        val usedB = BooleanArray(before.size)
        val usedA = BooleanArray(after.size)
        val pairs = mutableListOf<Pair<Int, Int>>()
        for ((bi, b) in before.withIndex()) {
            val ai = after.indices.firstOrNull { !usedA[it] && sameValues(b, after[it]) } ?: continue
            usedB[bi] = true; usedA[ai] = true; pairs += bi to ai
        }
        val out = mutableListOf<ChangeLine>()
        for ((bi, b) in before.withIndex()) {
            if (usedB[bi]) continue
            val ai = after.indices.firstOrNull { !usedA[it] && after[it].partCode?.key == b.partCode?.key }
            if (ai != null) {
                usedB[bi] = true; usedA[ai] = true
                out += ChangeLine(ChangeArea.PARTS, "Row changed", rowText(b), rowText(after[ai]))
            }
        }
        for ((bi, b) in before.withIndex()) if (!usedB[bi]) out += ChangeLine(ChangeArea.PARTS, "Row removed", rowText(b), "")
        for ((ai, a) in after.withIndex()) if (!usedA[ai]) out += ChangeLine(ChangeArea.PARTS, "Row added", "", rowText(a))
        val sortedByBefore = pairs.sortedBy { it.first }.map { it.second }
        if (sortedByBefore != sortedByBefore.sorted()) out += ChangeLine(ChangeArea.PARTS, "Row order", "", "The order of the rows changed")
        return out
    }
}

/** Re-applies the inspector's parts edits on top of the server's current rows (spec §6.5, option 1). */
object ConflictReapply {
    /**
     * Starts from the server's rows ([current], new `row_id`s) and applies what the inspector did to [base]:
     * - added rows are appended;
     * - edited rows are matched to a server row **by the original values** and get the inspector's values;
     * - removed rows are matched the same way and dropped;
     * - anything that can't be matched is flagged, never guessed: an edit whose row changed at the desk is added as a
     *   new row (nothing is lost); a removal the desk overtook keeps the desk's row.
     * Row order of the server is kept; the inspector's new rows go last.
     */
    fun rows(
        base: List<PartRow>,
        current: List<PartRow>,
        local: List<FindingRow>,
        newId: () -> String = FindingsEdit::newLocalId,
    ): ReapplyResult {
        val baseRows = base.map { it.toFindingRow() }
        val baseById = baseRows.associateBy { it.rowId }
        val server = current.map { it.toFindingRow() }
        val taken = BooleanArray(server.size)
        val result = arrayOfNulls<FindingRow>(server.size).also { for (i in server.indices) it[i] = server[i] }
        val idMap = linkedMapOf<String, String>()
        val flags = mutableListOf<String>()
        val appended = mutableListOf<FindingRow>()
        val localIds = local.mapNotNull { it.rowId }.toSet()

        fun claim(match: (FindingRow) -> Boolean): Int {
            for (i in server.indices) if (!taken[i] && match(server[i])) { taken[i] = true; return i }
            return -1
        }

        // Pass 1: existing rows of the inspector, matched by their original values.
        val existing = local.filter { it.rowId != null }
        val matched = HashMap<String, Int>()
        for (l in existing) {
            val orig = baseById[l.rowId] ?: continue
            val i = claim { sameValues(it, orig) }
            if (i >= 0) matched[l.localId] = i
        }
        // Pass 2: unmatched rows.
        for (l in existing) {
            val orig = baseById[l.rowId]
            val edited = orig != null && !sameValues(l, orig)
            val i = matched[l.localId]
            when {
                orig == null -> {
                    val id = newId()
                    appended += l.copy(localId = id, rowId = null)
                    idMap[l.localId] = id
                    flags += "${rowText(l)}: this row is not in the RMA you loaded, so it was added as a new row."
                }
                i != null -> {
                    if (edited) result[i] = server[i].copy(partCode = l.partCode, issueCode = l.issueCode, causeCode = l.causeCode, note = l.note)
                    idMap[l.localId] = server[i].localId
                }
                !edited -> {
                    // Nothing of mine to keep. If the desk changed the row, the desk's version stays and my photos follow it.
                    val j = claim { it.partCode?.key == orig!!.partCode?.key }
                    if (j >= 0) idMap[l.localId] = server[j].localId
                }
                else -> {
                    val id = newId()
                    appended += l.copy(localId = id, rowId = null)
                    idMap[l.localId] = id
                    flags += "Your change to ${rowText(orig!!)} could not be matched: that row was changed or removed at the desk. " +
                        "Your version was added as a new row — compare it with the desk's rows in Review."
                }
            }
        }
        // Removed rows: matched by the original values, then dropped.
        for (b in baseRows) {
            if (b.rowId in localIds) continue
            val i = claim { sameValues(it, b) }
            if (i >= 0) result[i] = null
            else if (server.indices.any { !taken[it] && server[it].partCode?.key == b.partCode?.key }) {
                flags += "You removed ${rowText(b)}, but that row was changed at the desk. The desk's row was kept — remove it in Review if it should still go."
            }
        }
        // My new rows, in my order, after the re-applied ones.
        val newRows = local.filter { it.rowId == null }
        val rows = result.filterNotNull() + appended + newRows
        // Order: the server's order is kept; say so when the inspector had moved rows.
        val keptOrder = baseRows.filter { it.rowId in localIds }.map { it.rowId }
        val myOrder = local.mapNotNull { it.rowId }
        val lastExisting = local.indexOfLast { it.rowId != null }
        val newRowInMiddle = local.withIndex().any { (i, r) -> r.rowId == null && i < lastExisting }
        if (myOrder != keptOrder || newRowInMiddle) {
            flags += "You changed the order of the rows. The order of the rows on the server was kept and your new rows are at the end — reorder them in Review if needed."
        }
        return ReapplyResult(rows, idMap, flags)
    }
}

/** The summary side of the conflict: defect code, summary blocks, Inspected by / Summary by. */
object ConflictSummary {
    const val DEFECT = "defect"
    const val INSPECTED_BY = "inspected_by"
    const val SUMMARY_BY = "summary_by"
    fun blockKey(blockId: String) = "block:$blockId"
    fun blockId(key: String): String? = key.removePrefix("block:").takeIf { key.startsWith("block:") }

    private fun keyText(c: Code?): String = c?.let { "${it.group.orEmpty()}:${it.code}" }.orEmpty()

    private fun state(base: String, mine: String, server: String): ItemState {
        val lm = mine != base
        val sm = server != base
        return when {
            !lm && !sm -> ItemState.UNCHANGED
            lm && !sm -> ItemState.MINE_ONLY
            !lm && sm -> ItemState.SERVER_ONLY
            mine == server -> ItemState.BOTH_SAME
            else -> ItemState.BOTH_DIFFERENT
        }
    }

    /**
     * Every summary field with its three values. A Report tab draft that was never edited ([ReportDraft.fieldsDirty]
     * false) counts as "no changes of mine".
     */
    fun items(
        base: Summary,
        current: Summary,
        report: ReportDraft?,
        blockLabels: Map<String, String> = emptyMap(),
        blockLimits: Map<String, Int> = emptyMap(),
    ): List<SummaryItem> {
        val dirty = report != null && report.fieldsDirty
        val out = mutableListOf<SummaryItem>()
        val baseBlocks = base.blocks.associate { it.blockId to it.text }
        val currentBlocks = current.blocks.associate { it.blockId to it.text }
        val ids = (baseBlocks.keys + currentBlocks.keys + (if (dirty) report!!.summaries.keys else emptySet()) + blockLabels.keys).toList()
            .sortedWith { a, b -> com.bitzer.rmainspector.core.NaturalSort.compare(a, b) }
        val multi = ids.size > 1

        val bd = keyText(base.defectCode)
        val md = if (dirty) keyText(report!!.defectCode) else bd
        val sd = keyText(current.defectCode)
        out += SummaryItem(DEFECT, "General defect code", codeText(base.defectCode),
            if (dirty) codeText(report!!.defectCode) else codeText(base.defectCode), codeText(current.defectCode),
            state(bd, md, sd), isText = false)
        for (id in ids) {
            val b = baseBlocks[id].orEmpty()
            val m = if (dirty) report!!.summaries[id] ?: b else b
            val s = currentBlocks[id].orEmpty()
            val label = blockLabels[id]?.takeIf { it.isNotBlank() } ?: if (multi) "Summary $id" else "Report summary"
            out += SummaryItem(blockKey(id), label, b, m, s, state(b, m, s), isText = true, maxLength = blockLimits[id] ?: SummaryItem.DEFAULT_MAX)
        }
        val mi = if (dirty) report!!.inspectedBy else base.inspectedBy
        val ms = if (dirty) report!!.summaryBy else base.summaryBy
        out += SummaryItem(INSPECTED_BY, "Inspected by", base.inspectedBy, mi, current.inspectedBy, state(base.inspectedBy, mi, current.inspectedBy), isText = false)
        out += SummaryItem(SUMMARY_BY, "Summary by", base.summaryBy, ms, current.summaryBy, state(base.summaryBy, ms, current.summaryBy), isText = false)
        return out
    }

    /** The text a pick gives for [item]: mine, the server's, or the server's text then mine after a blank line. */
    fun chosenText(item: SummaryItem, pick: Pick): String = when (pick) {
        Pick.MINE -> item.mine
        Pick.SERVER -> item.server
        Pick.COMBINE ->
            if (item.server.isBlank()) item.mine
            else if (item.mine.isBlank() || item.mine == item.server) item.server
            else (item.server.trimEnd() + "\n\n" + item.mine).take(item.maxLength)
    }

    /**
     * The Report tab draft after "keep my changes": my values where only I changed them, the server's where only the
     * server did, the inspector's pick where both did. The draft is dirty only where it differs from the server now.
     * Call only when every [ItemState.BOTH_DIFFERENT] item has a pick; a missing pick keeps the server's value.
     */
    fun merged(current: RmaDetail, report: ReportDraft, items: List<SummaryItem>, picks: Map<String, Pick>): ReportDraft {
        fun pickOf(i: SummaryItem): Pick = when (i.state) {
            ItemState.MINE_ONLY -> Pick.MINE
            ItemState.BOTH_DIFFERENT -> picks[i.key] ?: Pick.SERVER
            else -> Pick.SERVER
        }
        var defect = current.summary.defectCode
        var inspectedBy = current.summary.inspectedBy
        var summaryBy = current.summary.summaryBy
        val summaries = report.summaries.toMutableMap()
        var dirty = false
        for (i in items) {
            val p = pickOf(i)
            val usesMine = p == Pick.MINE || (p == Pick.COMBINE && !i.isText)
            when {
                i.key == DEFECT -> {
                    if (usesMine && report.fieldsDirty) defect = report.defectCode
                    if (keyText(defect) != keyText(current.summary.defectCode)) dirty = true
                }
                i.key == INSPECTED_BY -> {
                    inspectedBy = if (usesMine) i.mine else i.server
                    if (inspectedBy != current.summary.inspectedBy) dirty = true
                }
                i.key == SUMMARY_BY -> {
                    summaryBy = if (usesMine) i.mine else i.server
                    if (summaryBy != current.summary.summaryBy) dirty = true
                }
                else -> {
                    val id = blockId(i.key) ?: continue
                    val text = chosenText(i, p)
                    summaries[id] = text
                    if (text != i.server) dirty = true
                }
            }
        }
        return report.copy(defectCode = defect, summaries = summaries, inspectedBy = inspectedBy, summaryBy = summaryBy,
            fieldsDirty = dirty, rmaSignature = current.signature)
    }
}

/** `details.current` of `409 RMA_CHANGED` (api §7) as an RMA; null when the server didn't send a readable one (the screen then fetches it). */
fun conflictCurrent(details: kotlinx.serialization.json.JsonObject?): RmaDetail? =
    runCatching { details?.get("current")?.let { ApiClient.defaultJson.decodeFromJsonElement(RmaDetail.serializer(), it) } }.getOrNull()
