package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.OffsetDateTime

/** Who made a draft (decision 2026-11-18: queued/draft work belongs to the inspector who made it, never re-attributed). */
@Serializable
data class DraftInspector(
    val username: String,
    @SerialName("display_name") val displayName: String = username,
    val initials: String = "",
)

fun User.toDraftInspector() = DraftInspector(username, displayName, initials)

/**
 * The local draft of one RMA's inspection (spec §4.3, §6, §8). Saved on every change; survives app kill.
 * - [baseSignature]: the RMA's `signature` when the draft was started — sent as `base_signature` on submit (B3);
 *   a different signature on the server means `409 RMA_CHANGED` → conflict review, never an overwrite.
 * - [base]: the RMA detail as it was loaded (rows, header, summary) — the "original values" for the review screen,
 *   the conflict screen ("Reload and keep my changes") and the work-list card when the RMA no longer matches.
 * - [rows]: the inspector's desired parts list, in order. Existing rows keep `row_id`; new rows have `row_id: null`.
 *
 * Later issues extend this file (new fields need defaults so older drafts still load): B2 photos (per-row and
 * RMA-level photos keyed by [FindingRow.localId]), B3 submit (`submission_id`, summary/status edits), B4 queue.
 */
@Serializable
data class Draft(
    @SerialName("format") val format: Int = 1,
    @SerialName("rma_id") val rmaId: String,
    @SerialName("display_number") val displayNumber: String,
    val inspector: DraftInspector,
    @SerialName("base_signature") val baseSignature: String,
    val base: RmaDetail,
    val rows: List<FindingRow> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** Card for the work list when the RMA no longer matches the filters (spec §3.3) — from the loaded snapshot. */
    fun toListItem(): RmaListItem = RmaListItem(
        rmaId = rmaId,
        displayNumber = displayNumber,
        model = base.header.model,
        failedSerialNo = base.header.failedSerialNo,
        productType = base.productType,
        productTypeKey = base.productTypeKey,
        returnCode = base.header.returnCode,
        inspectionStatus = base.header.inspectionStatus,
        statusSince = base.statusSince,
        statusSinceEstimated = base.statusSinceEstimated,
        flags = base.flags,
        isOpen = base.isOpen,
        inspectable = base.inspectable,
        locked = base.lock.locked,
    )

    companion object {
        /** A new draft from the RMA as loaded, owned by [inspector]. */
        fun start(rma: RmaDetail, inspector: DraftInspector, rows: List<FindingRow>, now: String = nowIso()): Draft =
            Draft(rmaId = rma.rmaId, displayNumber = rma.displayNumber, inspector = inspector, baseSignature = rma.signature,
                base = rma, rows = rows, createdAt = now, updatedAt = now)

        fun nowIso(): String = OffsetDateTime.now().withNano(0).toString()
    }
}

/**
 * Drafts on the device, one JSON file per inspector and RMA in app-private storage:
 * `<root>/<hex(username)>/<hex(rma_id)>.json`. Names are hex-encoded so any `rma_id` / username is a safe file name
 * (the tablet never parses either). Writes go to a temp file and are renamed over the old one, so a kill mid-write
 * leaves the previous draft intact. Reads always come from disk (no cache), so what is shown after a restart is
 * exactly what was saved. A file that can't be read is left in place (never silently deleted) and reported as null.
 */
class DraftStore(private val root: File) {
    private val lock = Any()

    private fun dir(username: String) = File(root, hex(username))
    private fun file(username: String, rmaId: String) = File(dir(username), hex(rmaId) + ".json")

    fun read(username: String, rmaId: String): Draft? = synchronized(lock) { decode(file(username, rmaId)) }

    fun write(draft: Draft) = synchronized(lock) {
        val f = file(draft.inspector.username, draft.rmaId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ApiClient.writeJson.encodeToString(Draft.serializer(), draft))
        if (!tmp.renameTo(f)) {
            // Some file systems refuse to rename over an existing file: replace it in two steps.
            f.delete()
            check(tmp.renameTo(f)) { "Couldn't save the draft." }
        }
    }

    fun delete(username: String, rmaId: String) = synchronized(lock) { file(username, rmaId).delete(); Unit }

    /** Every draft of [username], newest change first. */
    fun list(username: String): List<Draft> = synchronized(lock) {
        dir(username).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { decode(it) }
            .sortedByDescending { it.updatedAt }
    }

    /** Removes every draft of every inspector (emulator tests only). */
    fun clearAll() = synchronized(lock) { root.deleteRecursively(); Unit }

    private fun decode(f: File): Draft? = runCatching {
        if (!f.exists()) null else ApiClient.defaultJson.decodeFromString(Draft.serializer(), f.readText())
    }.getOrNull()

    companion object {
        fun hex(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    }
}

/** Work list + this inspector's drafts (spec §3.3): drafted RMAs that dropped out of the list stay, flagged. */
object WorkListDrafts {
    /** Returns the merged, sorted list and the ids to badge "Return code / status changed". */
    fun merge(items: List<RmaListItem>, drafts: List<Draft>): Pair<List<RmaListItem>, Set<String>> {
        val ids = items.mapTo(HashSet()) { it.rmaId }
        val missing = drafts.filter { it.rmaId !in ids }.distinctBy { it.rmaId }
        if (missing.isEmpty()) return items to emptySet()
        return WorkListRules.sort(items + missing.map { it.toListItem() }) to missing.mapTo(LinkedHashSet()) { it.rmaId }
    }
}
