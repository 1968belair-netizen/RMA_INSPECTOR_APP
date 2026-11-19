package com.bitzer.rmainspector.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a submit does with the status (spec §6.1). */
enum class SubmitKind {
    /** Before Inspected → the app sets Inspected. */
    SET_INSPECTED,
    /** Inspected (or any other status that needs no warning) → save, keep the status. */
    KEEP,
    /** Report Complete (or later, not sent) → "Report already complete"; save, keep the status, rebuild the report. */
    REPORT_COMPLETE,
    /** Sent to RSM / Issued to Customer → same, plus the resend warning. */
    REPORT_SENT,
}

/** The status decision of a submit: [change] is the `status_change` sent (null = keep the status). */
data class StatusPlan(val kind: SubmitKind, val current: Code?, val change: StatusChangeWrite?, val target: Code?) {
    /** The status the RMA has after the submit. */
    val resultingStatus: String get() = (target ?: current)?.code.orEmpty()
    val rebuildsReport: Boolean get() = kind == SubmitKind.REPORT_COMPLETE || kind == SubmitKind.REPORT_SENT

    companion object {
        /**
         * Status rules from `status_roles` (spec §6.1, api §7): sent → [SubmitKind.REPORT_SENT]; Report Complete or
         * later in the workflow order of [statuses] → [SubmitKind.REPORT_COMPLETE]; Inspected → keep; anything else
         * (Received, Rework in Progress, …) → set Inspected.
         */
        fun decide(current: Code?, roles: StatusRoles, statuses: List<Code>): StatusPlan {
            val cur = current?.code.orEmpty()
            val order = statuses.map { it.code }
            val completeIdx = order.indexOf(roles.reportComplete)
            val curIdx = order.indexOf(cur)
            return when {
                cur in roles.reportSent -> StatusPlan(SubmitKind.REPORT_SENT, current, null, null)
                cur == roles.reportComplete || (completeIdx >= 0 && curIdx >= completeIdx) ->
                    StatusPlan(SubmitKind.REPORT_COMPLETE, current, null, null)
                cur == roles.inspected -> StatusPlan(SubmitKind.KEEP, current, null, null)
                else -> {
                    val to = statuses.firstOrNull { it.code == roles.inspected } ?: Code(null, roles.inspected, roles.inspected)
                    StatusPlan(SubmitKind.SET_INSPECTED, current,
                        StatusChangeWrite(current?.key ?: CodeKey(null, ""), to.key), to)
                }
            }
        }
    }
}

data class StatusChangeWrite(val from: CodeKey, val to: CodeKey)

/** Everything the review screen and the payload need — a snapshot taken when Submit is tapped. */
data class SubmitInput(
    val rma: RmaDetail,
    /** The RMA as the inspector loaded it (the draft's base): the "original values". */
    val base: RmaDetail,
    /** Sent as `base_signature`: the signature the draft was started from. */
    val baseSignature: String,
    val rows: List<FindingRow>,
    val pendingPhotos: List<LocalPhoto>,
    val codes: CodeLists?,
    /** Report tab edits (defect code, summary, by-fields); null when none exist. */
    val report: ReportDraft?,
    val username: String,
    val deviceId: String,
)

/** The review screen (spec §6.1): what changes, what is sent, and what stops the submit. */
data class SubmitReview(
    val displayNumber: String,
    val plan: StatusPlan,
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    val reordered: Boolean,
    /** Rows with no part or issue yet — they block the submit. */
    val incomplete: List<String>,
    val summaryChanged: Boolean,
    val photosToSend: Int,
    /** Photos that can't be filed yet (their row was removed, or an RMA photo has no tag); they stay on the tablet. */
    val photosSkipped: Int,
    val partsChanged: Boolean,
) {
    val hasWork: Boolean get() = partsChanged || summaryChanged || photosToSend > 0
    val canSubmit: Boolean get() = incomplete.isEmpty() && (plan.kind == SubmitKind.SET_INSPECTED || hasWork)

    companion object {
        fun rowText(row: FindingRow): String {
            val part = row.partCode?.let { "${it.code} ${it.description}".trim() } ?: "No part yet"
            val issue = row.issueCode?.let { "${it.code} ${it.description}".trim() } ?: "no issue yet"
            return "$part · $issue"
        }

        fun build(input: SubmitInput): SubmitReview {
            val codes = input.codes
            val plan = StatusPlan.decide(input.rma.header.inspectionStatus, codes?.statusRoles ?: StatusRoles(),
                codes?.inspectionStatuses.orEmpty())
            val baseRows = input.base.parts.toFindingRows()
            val baseById = baseRows.associateBy { it.rowId }
            val rows = input.rows
            val complete = rows.filter { it.toWrite() != null }
            val added = complete.filter { it.isNew }.map { rowText(it) }
            val changed = rows.filter { r -> r.rowId != null && baseById[r.rowId]?.let { !it.sameContent(r) } == true && r.toWrite() != null }
                .map { rowText(it) }
            val keptIds = rows.mapNotNull { it.rowId }.toSet()
            val removed = baseRows.filter { r -> r.rowId.let { id -> id == null || id !in keptIds } }.map { rowText(it) }
            val reordered = baseRows.mapNotNull { it.rowId }.filter { it in keptIds } != rows.mapNotNull { it.rowId }
            val incomplete = rows.withIndex().filter { it.value.toWrite() == null }
                .map { (i, r) -> "Row ${(i + 1).toString().padStart(2, '0')}: " + rowText(r) }
            val partsChanged = !FindingsEdit.isUnchanged(rows, input.base.parts)
            val metas = input.pendingPhotos.map { PhotoUpload.meta(it, rows) }
            return SubmitReview(
                displayNumber = input.rma.displayNumber, plan = plan,
                added = added, changed = changed, removed = removed, reordered = reordered && partsChanged,
                incomplete = incomplete,
                summaryChanged = SubmitPayload.summary(input.rma.summary, input.report, codes) != null,
                photosToSend = metas.count { it != null }, photosSkipped = metas.count { it == null },
                partsChanged = partsChanged,
            )
        }
    }
}

/** `POST /rmas/{id}/inspection` body (api §7): only what changed; unchanged parts / summary / status are null. */
object SubmitPayload {
    private fun codeJson(key: CodeKey?): kotlinx.serialization.json.JsonElement =
        if (key == null) JsonNull else buildJsonObject { put("group", key.group?.let { JsonPrimitive(it) } ?: JsonNull); put("code", key.code) }

    /**
     * Changed summary parts only (api §7): `defect_code` (null clears it), `blocks` that differ, `inspected_by`,
     * `summary_by`. Null when the Report tab holds no edits or nothing differs from the server.
     */
    fun summary(server: Summary, report: ReportDraft?, codes: CodeLists?): JsonObject? {
        if (report == null || !report.fieldsDirty) return null
        val blockIds = codes?.summaryBlocks?.map { it.blockId }?.takeIf { it.isNotEmpty() } ?: listOf("1")
        val serverBlocks = server.blocks.associate { it.blockId to it.text }
        val changedBlocks = blockIds.filter { id -> report.summary(id) != serverBlocks[id].orEmpty() }
        val defectChanged = report.defectCode?.key != server.defectCode?.key
        val by = report.inspectedBy != server.inspectedBy
        val sumBy = report.summaryBy != server.summaryBy
        if (!defectChanged && changedBlocks.isEmpty() && !by && !sumBy) return null
        return buildJsonObject {
            if (defectChanged) put("defect_code", codeJson(report.defectCode?.key))
            if (changedBlocks.isNotEmpty()) put("blocks", JsonArray(changedBlocks.map { id ->
                buildJsonObject { put("block_id", id); put("text", report.summary(id)) }
            }))
            if (by) put("inspected_by", report.inspectedBy)
            if (sumBy) put("summary_by", report.summaryBy)
        }
    }

    /** The request body. Call only when [SubmitReview.canSubmit] (every row has a part and issue). */
    fun build(input: SubmitInput, plan: StatusPlan, submissionId: String, capturedAt: String): JsonObject = buildJsonObject {
        put("submission_id", submissionId)
        put("base_signature", input.baseSignature)
        put("product_type_key", input.rma.productTypeKey)
        put("captured_at", capturedAt)
        put("device_id", input.deviceId)
        val change = plan.change
        put("status_change", if (change == null) JsonNull else buildJsonObject {
            put("from", codeJson(change.from)); put("to", codeJson(change.to))
        })
        put("summary", summary(input.rma.summary, input.report, input.codes) ?: JsonNull)
        val parts = if (FindingsEdit.isUnchanged(input.rows, input.base.parts)) null else input.rows.mapNotNull { it.toWrite() }
        put("parts", if (parts == null) JsonNull else JsonArray(parts.map { p ->
            buildJsonObject {
                put("row_id", p.rowId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("part_code", codeJson(p.partCode)); put("issue_code", codeJson(p.issueCode))
                put("cause_code", codeJson(p.causeCode)); put("note", p.note)
            }
        }))
    }

    /** Same request without the per-attempt fields — equal for a retry of the same submit (keeps `submission_id`). */
    fun fingerprint(payload: JsonObject): String =
        JsonObject(payload.filterKeys { it != "submission_id" && it != "captured_at" }).toString()
}

/** Result of one photo upload attempt. */
sealed interface PhotoOutcome {
    data class Sent(val filename: String) : PhotoOutcome
    /** The server said no for this photo; the next ones are still tried. */
    data class Rejected(val message: String) : PhotoOutcome
    /** The server can't be reached — stop, nothing is lost. */
    data class Offline(val message: String) : PhotoOutcome
}

data class PhotoUploadSummary(
    val sent: Int,
    val failed: List<String>,
    val skipped: Int,
    val stoppedOffline: Boolean,
) {
    val total: Int get() = sent + failed.size
    val allSent: Boolean get() = failed.isEmpty() && !stoppedOffline
}

/**
 * Uploads the unsent photos one by one, in capture order (spec §6.3). [markSent] runs **only** after the server
 * answered (`201`, or a replayed `200`) — everything else leaves the photo pending, so the next Submit retries it.
 */
object PhotoUploadRunner {
    suspend fun run(
        photos: List<LocalPhoto>,
        rows: List<FindingRow>,
        upload: suspend (PhotoMetaWrite, LocalPhoto) -> PhotoOutcome,
        markSent: (LocalPhoto, String) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PhotoUploadSummary {
        val work = photos.filter { !it.isSent }.mapNotNull { p -> PhotoUpload.meta(p, rows)?.let { p to it } }
        val skipped = photos.count { !it.isSent } - work.size
        var sent = 0
        val failed = mutableListOf<String>()
        var offline = false
        onProgress(0, work.size)
        for ((i, pair) in work.withIndex()) {
            val (photo, meta) = pair
            when (val o = upload(meta, photo)) {
                is PhotoOutcome.Sent -> { markSent(photo, o.filename); sent++ }
                is PhotoOutcome.Rejected -> failed += o.message
                is PhotoOutcome.Offline -> { offline = true; failed += o.message }
            }
            onProgress(i + 1, work.size)
            if (offline) break
        }
        return PhotoUploadSummary(sent, failed, skipped, offline)
    }
}

/** Failure → what the inspector reads (spec §6.4). Nothing is ever lost: the draft and photos stay. */
data class SubmitProblem(val title: String, val message: String, val offline: Boolean = false)

object SubmitErrors {
    const val KEPT = "Your findings and photos are still on this tablet."

    fun map(f: ApiResult.Failure): SubmitProblem = when {
        f.kind == ApiResult.Kind.UNREACHABLE ->
            SubmitProblem("Can't reach the server", "Nothing was lost. $KEPT Submit again when this tablet is back on the network.", offline = true)
        f.code == "RMA_CHANGED" ->
            SubmitProblem("Changed elsewhere", "${f.message}\n\nNothing was saved. $KEPT Tap Refresh and review the changes before saving again.")
        LockHandling.isLockFailure(f) ->
            LockHandling.noticeFor(f)!!.let { SubmitProblem(it.title, "${it.headline}\n\n${it.detail}") }
        f.httpStatus == 422 || f.code == "VALIDATION_FAILED" ->
            SubmitProblem("The server rejected the findings", "${f.message}\n\nNothing was saved. $KEPT Fix the item and submit again.")
        f.code == "RMA_NOT_OPEN" ->
            SubmitProblem("RMA is closed", "${f.message}\n\nNothing was saved. $KEPT Ask the RMA team.")
        else -> SubmitProblem("Couldn't save", "${f.message}\n\nNothing was saved. $KEPT")
    }
}
