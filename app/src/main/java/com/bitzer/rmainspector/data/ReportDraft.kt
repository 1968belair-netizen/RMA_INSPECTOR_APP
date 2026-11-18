package com.bitzer.rmainspector.data

import com.bitzer.rmainspector.core.NaturalSort
import com.bitzer.rmainspector.core.PhotoNaming
import kotlinx.serialization.Serializable
import java.io.File

/** One report photo pick: [source] `all_pics` or `report_pics` (api §9.2); the caption becomes the filename. */
@Serializable
data class ReportPick(val source: String, val filename: String, val caption: String) {
    /** Filename the server will give the copy (caption + .jpg). */
    val targetName: String get() = PhotoNaming.sanitize(caption) + PhotoNaming.EXT
}

/** Why a caption can't be saved (mirrors the server's 422s so the inspector sees it before sending). */
enum class CaptionProblem(val text: String) {
    EMPTY("Caption can't be empty."),
    TOO_LONG("Caption is too long."),
    DUPLICATE("Two photos would get the same name."),
}

/**
 * What the Report tab edits for one RMA: General Defect Code, summary text, Inspected by / Summary by and the
 * report-photo picks. It is kept on the tablet (see [ReportDraftStore]) until the report is submitted.
 *
 * **Submit (B3 / C2) picks this up with `ReportDraftStore.get(rmaId)`:** send [defectCode]?.key, [summaries]
 * (block_id → text), [inspectedBy] and [summaryBy] with the inspection submit (api §7 `summary`), and use
 * [picks] / [picksBaseSignature] via `ApiClient.putReportPics` (`toRequest`). [picksDirty] = report photos not
 * yet saved to the server.
 */
@Serializable
data class ReportDraft(
    val rmaId: String = "",
    val defectCode: Code? = null,
    val summaries: Map<String, String> = emptyMap(),
    val inspectedBy: String = "",
    val summaryBy: String = "",
    val picks: List<ReportPick> = emptyList(),
    /** Picks as last known on the server (report_pics folder after the last load or save). */
    val savedPicks: List<ReportPick> = emptyList(),
    val picksBaseSignature: String = "",
    /** The summary / defect / by-fields were edited on the tablet and not yet submitted. */
    val fieldsDirty: Boolean = false,
    /** RMA `signature` the fields were seeded from. */
    val rmaSignature: String = "",
) {
    val picksDirty: Boolean get() = picks != savedPicks

    fun summary(blockId: String): String = summaries[blockId].orEmpty()

    fun withSummary(blockId: String, text: String) = copy(summaries = summaries + (blockId to text), fieldsDirty = true)
    fun withDefect(code: Code?) = copy(defectCode = code, fieldsDirty = true)
    fun withInspectedBy(v: String) = copy(inspectedBy = v, fieldsDirty = true)
    fun withSummaryBy(v: String) = copy(summaryBy = v, fieldsDirty = true)

    /** Is this `All Pics` / `Report Pics ` file already in the report? */
    fun isPicked(filename: String): Boolean = picks.any { it.filename == filename || it.targetName == filename }

    /** Adds a photo from `All Pics` (caption = filename). No-op when it is already picked. */
    fun add(filename: String): ReportDraft =
        if (isPicked(filename)) this else copy(picks = picks + ReportPick("all_pics", filename, captionOf(filename)))

    fun remove(filename: String): ReportDraft = copy(picks = picks.filterNot { it.filename == filename || it.targetName == filename })

    fun toggle(filename: String): ReportDraft = if (isPicked(filename)) remove(filename) else add(filename)

    fun withCaption(index: Int, caption: String): ReportDraft =
        copy(picks = picks.mapIndexed { i, p -> if (i == index) p.copy(caption = caption) else p })

    fun removeAt(index: Int): ReportDraft = copy(picks = picks.filterIndexed { i, _ -> i != index })

    /** Pick list in report order (natural order of the final filenames). */
    fun inReportOrder(): ReportDraft = copy(picks = picks.sortedWith { a, b -> NaturalSort.compare(a.targetName, b.targetName) })

    /** 1-based position of each pick in the report (natural order of caption names). */
    fun reportPositions(): List<Int> {
        val order = picks.indices.sortedWith { a, b -> NaturalSort.compare(picks[a].targetName, picks[b].targetName) }
        val pos = IntArray(picks.size)
        order.forEachIndexed { rank, idx -> pos[idx] = rank + 1 }
        return pos.toList()
    }

    /** Problems per pick index; empty = good to save. */
    fun captionProblems(): Map<Int, CaptionProblem> {
        val out = mutableMapOf<Int, CaptionProblem>()
        val names = picks.map { it.targetName.lowercase() }
        picks.forEachIndexed { i, p ->
            val clean = PhotoNaming.sanitize(p.caption)
            when {
                clean.isEmpty() -> out[i] = CaptionProblem.EMPTY
                clean.length + PhotoNaming.EXT.length > PhotoNaming.NAME_MAX - 4 -> out[i] = CaptionProblem.TOO_LONG
                names.count { it == names[i] } > 1 -> out[i] = CaptionProblem.DUPLICATE
            }
        }
        return out
    }

    fun toRequest(submissionId: String) =
        ReportPicsRequest(submissionId, picksBaseSignature, picks.map { ReportPickWrite(it.source, it.filename, it.caption) })

    /** The server's `Report Pics ` list becomes the picks (after a save, or when the inspector takes the server's list). */
    fun adoptServer(files: List<String>, signature: String): ReportDraft {
        val list = files.map { ReportPick("report_pics", it, captionOf(it)) }
        return copy(picks = list, savedPicks = list, picksBaseSignature = signature)
    }

    /** Keep the inspector's picks but treat [signature] as the server state they are being saved over (explicit choice). */
    fun rebase(signature: String): ReportDraft = copy(picksBaseSignature = signature)

    companion object {
        fun captionOf(filename: String): String = filename.removeSuffix(PhotoNaming.EXT).removeSuffix(".JPG")

        /** Pages ≈ fixed pages + ⌈photos ÷ photos per page⌉ (api §4 `report`). */
        fun pageCount(photos: Int, settings: ReportSettings): Int {
            val per = settings.photosPerPage.coerceAtLeast(1)
            return settings.fixedPages + (photos + per - 1) / per
        }

        /**
         * Draft for an RMA after (re)loading it: no stored draft → [seed]; otherwise untouched fields follow the
         * server and untouched picks follow `Report Pics `. Edited picks are never replaced — if the server's list
         * changed meanwhile, [DraftRefresh.serverChanged] is true so the inspector reviews it (spec §6.5).
         */
        fun refresh(existing: ReportDraft?, rma: RmaDetail, listing: PhotoListing?, initials: String): DraftRefresh {
            if (existing == null) return DraftRefresh(seed(rma, listing, initials), false)
            var d = existing
            if (!d.fieldsDirty) {
                val s = seed(rma, null, initials)
                d = d.copy(defectCode = s.defectCode, summaries = s.summaries, inspectedBy = s.inspectedBy,
                    summaryBy = s.summaryBy, rmaSignature = rma.signature)
            }
            var changed = false
            if (listing != null) {
                val sig = listing.reportPics.signature
                if (!d.picksDirty) d = d.adoptServer(listing.reportPics.files, sig)
                else if (d.picksBaseSignature != sig) changed = true
            }
            return DraftRefresh(d, changed)
        }

        /**
         * First draft for an RMA from the server: summary, defect code and initials (Inspected by / Summary by are
         * independent; each gets the signed-in user's [initials] only when empty).
         */
        fun seed(rma: RmaDetail, listing: PhotoListing?, initials: String): ReportDraft {
            val s = rma.summary
            val files = listing?.reportPics?.files.orEmpty()
            val picks = files.map { ReportPick("report_pics", it, captionOf(it)) }
            return ReportDraft(
                rmaId = rma.rmaId,
                defectCode = s.defectCode,
                summaries = s.blocks.associate { it.blockId to it.text },
                inspectedBy = s.inspectedBy.ifBlank { initials },
                summaryBy = s.summaryBy.ifBlank { initials },
                picks = picks, savedPicks = picks,
                picksBaseSignature = listing?.reportPics?.signature.orEmpty(),
                rmaSignature = rma.signature,
            )
        }
    }
}

data class DraftRefresh(val draft: ReportDraft, val serverChanged: Boolean)

/** Drafts on the device, one JSON file for all RMAs (small: text + pick lists). */
class ReportDraftStore(private val file: File) {
    @Serializable
    private data class Data(val drafts: Map<String, ReportDraft> = emptyMap())

    private val lock = Any()
    private var data: Data? = null

    private fun load(): Data = data ?: runCatching {
        if (file.exists()) ApiClient.defaultJson.decodeFromString(Data.serializer(), file.readText()) else Data()
    }.getOrDefault(Data()).also { data = it }

    fun get(rmaId: String): ReportDraft? = synchronized(lock) { load().drafts[rmaId] }

    fun put(draft: ReportDraft) = synchronized(lock) {
        val nd = load().let { it.copy(drafts = it.drafts + (draft.rmaId to draft)) }
        data = nd
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ApiClient.writeJson.encodeToString(Data.serializer(), nd))
        }
        Unit
    }

    /** After the report data was submitted (B3 / C2). */
    fun clear(rmaId: String) = synchronized(lock) {
        val nd = load().let { it.copy(drafts = it.drafts - rmaId) }
        data = nd
        runCatching { file.writeText(ApiClient.writeJson.encodeToString(Data.serializer(), nd)) }
        Unit
    }
}
