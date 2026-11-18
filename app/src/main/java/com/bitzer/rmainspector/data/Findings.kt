package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One parts row on the tablet (spec §4.3) — what the Findings tab edits and the draft stores.
 * - [localId]: the tablet's own key for the row (list keys, selection, photos of the row). For rows that came from
 *   the server it is the server's `row_id`; new rows get `new-<uuid>`.
 * - [rowId]: the server's opaque `row_id`, echoed back unchanged; **null for new rows** (api §7).
 * - Codes are code objects exactly as received from `/codelists` or the RMA detail. Part / issue stay null until
 *   the inspector picks them (manual entry has no requirements; submit checks them, B3).
 */
@Serializable
data class FindingRow(
    @SerialName("local_id") val localId: String,
    @SerialName("row_id") val rowId: String? = null,
    @SerialName("part_code") val partCode: Code? = null,
    @SerialName("issue_code") val issueCode: Code? = null,
    @SerialName("cause_code") val causeCode: Code? = null,
    val note: String = "",
) {
    val isNew: Boolean get() = rowId == null

    /** A new row with nothing filled in — dropped when its sheet is closed. */
    val isBlankNew: Boolean get() = isNew && partCode == null && issueCode == null && causeCode == null && note.isEmpty()

    /** Same content as [other] (codes compared by `group` + `code`, as the server does). */
    fun sameContent(other: FindingRow): Boolean =
        rowId == other.rowId && partCode?.key == other.partCode?.key && issueCode?.key == other.issueCode?.key &&
            causeCode?.key == other.causeCode?.key && note == other.note

    /** The row as submitted (api §7), or null while part or issue is missing. */
    fun toWrite(): PartRowWrite? {
        val p = partCode ?: return null
        val i = issueCode ?: return null
        return PartRowWrite(rowId, p.key, i.key, causeCode?.key, note)
    }
}

/** Server rows → tablet rows, in server order; each keeps its `row_id` (also used as the local id). */
fun List<PartRow>.toFindingRows(): List<FindingRow> =
    map { FindingRow(localId = it.rowId, rowId = it.rowId, partCode = it.partCode, issueCode = it.issueCode, causeCode = it.causeCode, note = it.note) }

/** A deleted row kept for Undo: the row and where it was. */
data class DeletedRow(val row: FindingRow, val index: Int)

/**
 * Pure edits of the parts list (spec §4.3). Every function returns a new list; unknown ids leave it unchanged.
 * The view model saves the result to the local draft after each edit.
 */
object FindingsEdit {
    fun newLocalId(): String = "new-" + UUID.randomUUID().toString()

    /** Appends a new, empty row (`row_id: null`). */
    fun add(rows: List<FindingRow>, localId: String = newLocalId()): List<FindingRow> = rows + FindingRow(localId = localId)

    fun update(rows: List<FindingRow>, localId: String, change: (FindingRow) -> FindingRow): List<FindingRow> =
        rows.map { if (it.localId == localId) change(it) else it }

    fun setPart(rows: List<FindingRow>, localId: String, code: Code) = update(rows, localId) { it.copy(partCode = code) }
    fun setIssue(rows: List<FindingRow>, localId: String, code: Code) = update(rows, localId) { it.copy(issueCode = code) }
    fun setCause(rows: List<FindingRow>, localId: String, code: Code?) = update(rows, localId) { it.copy(causeCode = code) }

    /** Note, cut to [limit] characters (server `limits.note`, 4000). Line breaks stay `\n`. */
    fun setNote(rows: List<FindingRow>, localId: String, note: String, limit: Int) =
        update(rows, localId) { it.copy(note = note.take(limit)) }

    /** Moves the row at [from] to [to] (indices clamped). */
    fun move(rows: List<FindingRow>, from: Int, to: Int): List<FindingRow> {
        if (from !in rows.indices) return rows
        val target = to.coerceIn(0, rows.lastIndex)
        if (target == from) return rows
        val m = rows.toMutableList()
        val r = m.removeAt(from)
        m.add(target, r)
        return m
    }

    /** Removes a row; returns the new list and what Undo needs (null if the id is unknown). */
    fun delete(rows: List<FindingRow>, localId: String): Pair<List<FindingRow>, DeletedRow?> {
        val i = rows.indexOfFirst { it.localId == localId }
        if (i < 0) return rows to null
        return (rows.toMutableList().also { it.removeAt(i) }) to DeletedRow(rows[i], i)
    }

    /** Puts a deleted row back where it was (or at the end if the list got shorter). */
    fun undo(rows: List<FindingRow>, deleted: DeletedRow): List<FindingRow> {
        if (rows.any { it.localId == deleted.row.localId }) return rows
        val m = rows.toMutableList()
        m.add(deleted.index.coerceIn(0, m.size), deleted.row)
        return m
    }

    /** True when [rows] equal the server's rows (same order, same content) — no draft needed. */
    fun isUnchanged(rows: List<FindingRow>, base: List<PartRow>): Boolean {
        val b = base.toFindingRows()
        return rows.size == b.size && rows.zip(b).all { (a, c) -> a.sameContent(c) }
    }
}
