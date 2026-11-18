package com.bitzer.rmainspector.data

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Guided inspection engine (spec §5, api §4 `checklists`) — pure functions, no Android. The UI shows what
 * [GuidedEngine.status] says; the view model writes the rows. Requirements exist only in guided mode.
 */

/** Where the inspector is in a checklist, saved per inspector and RMA so it survives an app kill. */
@Serializable
data class GuidedProgress(
    /** The checklist's `version` when it was started; a different version on the server starts over. */
    val version: String = "",
    /** Index of the current step (0-based). */
    val step: Int = 0,
    val finished: Boolean = false,
)

/** One photo slot of a step: its rule outcome for the current issue and the photo filling it (if any). */
data class SlotStatus(
    val slot: String,
    val label: String,
    val required: Boolean,
    /** The photo taken here for this slot (not sent yet). */
    val photo: LocalPhoto? = null,
    /** True when an older photo of the row (desk / earlier upload / manual) counts for this slot. */
    val coveredByExisting: Boolean = false,
) {
    val done: Boolean get() = photo != null || coveredByExisting
}

/** A step as the inspector sees it: the row it writes to, the effective issue and the photo slots. */
data class StepStatus(
    val index: Int,
    val total: Int,
    val step: ChecklistStep,
    /** The Findings row of this step (null until the inspector touches the step). */
    val row: FindingRow?,
    /** The row's issue, or the default `50` Normal while nothing is chosen; null only when the code lists are missing. */
    val issue: Code?,
    val slots: List<SlotStatus>,
    val isNormal: Boolean,
) {
    val isLast: Boolean get() = index == total - 1
    val missingRequired: Int get() = slots.count { it.required && !it.done }

    /** Next is disabled until the issue is known and every required photo is there. */
    val canNext: Boolean get() = issue != null && missingRequired == 0

    /** Skip is disabled while a required photo is missing. */
    val canSkip: Boolean get() = missingRequired == 0
}

object GuidedEngine {
    const val ALWAYS = "always"
    const val NON_NORMAL = "non_normal"

    fun isNormal(issue: CodeKey?, normal: List<CodeKey>): Boolean = issue != null && issue in normal

    /** `required_when`: always · non_normal (issue not in `normal_issue_codes`) · anything else = optional. */
    fun slotRequired(photo: ChecklistPhoto, issue: CodeKey?, normal: List<CodeKey>): Boolean = when (photo.requiredWhen) {
        ALWAYS -> true
        NON_NORMAL -> issue != null && !isNormal(issue, normal)
        else -> false
    }

    /** The code a new step starts with: the first `normal_issue_codes` entry found in the issue list. */
    fun defaultIssue(codes: CodeLists?): Code? {
        val c = codes ?: return null
        return c.normalIssueCodes.firstNotNullOfOrNull { n -> c.issues.firstOrNull { it.key == n } }
    }

    /** The row a step writes to: the n-th row with the step's part, where n = earlier steps with the same part. */
    fun rowFor(steps: List<ChecklistStep>, index: Int, rows: List<FindingRow>): FindingRow? {
        val step = steps.getOrNull(index) ?: return null
        val earlier = steps.take(index).count { it.partCode == step.partCode }
        return rows.filter { it.partCode?.key == step.partCode }.getOrNull(earlier)
    }

    /**
     * Status of step [index]. [existingUnslotted] = photos of the row that belong to no slot (desk / earlier uploads /
     * manual photos); they cover open slots in order, so existing rows are not asked for photos again.
     */
    fun status(
        steps: List<ChecklistStep>, index: Int, rows: List<FindingRow>, local: List<LocalPhoto>,
        existingUnslotted: Int, codes: CodeLists?,
    ): StepStatus? {
        val step = steps.getOrNull(index) ?: return null
        val normal = codes?.normalIssueCodes.orEmpty()
        val row = rowFor(steps, index, rows)
        val issue = row?.issueCode ?: defaultIssue(codes)
        val mine = if (row == null) emptyList() else local.filter { !it.isSent && !it.isRma && it.rowLocalId == row.localId }
        var spare = existingUnslotted
        val slots = step.photos.map { p ->
            val photo = mine.lastOrNull { it.slot == p.slot }
            val covered = photo == null && spare > 0
            if (covered) spare--
            SlotStatus(p.slot, p.label.ifBlank { p.slot }, slotRequired(p, issue?.key, normal), photo, covered)
        }
        return StepStatus(index, steps.size, step, row, issue, slots, isNormal(issue?.key, normal))
    }

    /** Appends a new row (`row_id: null`) for [part] with [issue] (may be null); returns the rows and its local id. */
    fun addRow(rows: List<FindingRow>, part: Code, issue: Code?, localId: String = FindingsEdit.newLocalId()): Pair<List<FindingRow>, String> =
        (rows + FindingRow(localId = localId, partCode = part, issueCode = issue)) to localId
}

/** Guided progress on the device, one small JSON file per inspector and RMA (same layout as [DraftStore]). */
class GuidedProgressStore(private val root: File) {
    private val lock = Any()
    private fun file(username: String, rmaId: String) = File(File(root, DraftStore.hex(username)), DraftStore.hex(rmaId) + ".json")

    fun read(username: String, rmaId: String): GuidedProgress? = synchronized(lock) {
        runCatching {
            val f = file(username, rmaId)
            if (!f.exists()) null else ApiClient.defaultJson.decodeFromString(GuidedProgress.serializer(), f.readText())
        }.getOrNull()
    }

    fun write(username: String, rmaId: String, progress: GuidedProgress) = synchronized(lock) {
        val f = file(username, rmaId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ApiClient.writeJson.encodeToString(GuidedProgress.serializer(), progress))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Couldn't save the guided progress." }
        }
    }

    fun delete(username: String, rmaId: String) = synchronized(lock) { file(username, rmaId).delete(); Unit }

    /** Removes everything (emulator tests only). */
    fun clearAll() = synchronized(lock) { root.deleteRecursively(); Unit }
}
