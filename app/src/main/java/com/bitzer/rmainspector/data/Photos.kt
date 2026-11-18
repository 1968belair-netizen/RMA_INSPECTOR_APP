package com.bitzer.rmainspector.data

import com.bitzer.rmainspector.core.PhotoNaming
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A photo taken on this tablet (spec §4.4, §6.3, §9). It is stored on the device first and counts as **not sent**
 * until the server confirms the upload — only then does B3 call [PhotoStore.markSent].
 * - [kind]: `part` (belongs to the parts row [rowLocalId]) or `rma` (uncoded, [tag] / [tagText]).
 * - [file]: file name inside the RMA's photo folder (`<photo_id>.jpg`); the JPEG is already scaled, rotated upright,
 *   GPS-free (see [com.bitzer.rmainspector.data.PhotoProcessor]).
 * - [sha256] / [bytes]: of that file; sent as `meta.sha256` so the server can verify the upload.
 * - [sentAt] / [serverFilename]: set only after the server answered `201`/`200` for this [photoId].
 */
@Serializable
data class LocalPhoto(
    @SerialName("photo_id") val photoId: String,
    val kind: String,
    @SerialName("row_local_id") val rowLocalId: String? = null,
    val tag: String? = null,
    @SerialName("tag_text") val tagText: String? = null,
    @SerialName("captured_at") val capturedAt: String,
    val file: String,
    val bytes: Long = 0,
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("server_filename") val serverFilename: String? = null,
    /** Guided inspection photo slot (`overall`, `detail` …) this photo was taken for; null outside guided mode. */
    val slot: String? = null,
) {
    val isRma: Boolean get() = kind == KIND_RMA
    val isSent: Boolean get() = sentAt != null

    companion object {
        const val KIND_PART = "part"
        const val KIND_RMA = "rma"
    }
}

@Serializable
private data class PhotoIndex(val format: Int = 1, val photos: List<LocalPhoto> = emptyList())

/**
 * Photos on the device, per inspector and RMA, in app-private storage:
 * `<root>/<hex(username)>/<hex(rma_id)>/index.json` plus `<photo_id>.jpg`. Like [DraftStore], names are hex-encoded
 * and the index is written to a temp file and renamed, so a kill mid-write keeps the previous index. The list order
 * is capture order (it numbers the photos of a row: `n`). A photo's file is written **before** its index entry, so
 * an entry always has its file.
 *
 * B3 (submit) uses: [pending] → upload → [markSent] (only after the server confirmed). B4 (queue) keeps them across
 * restarts. Sent photos stay for [KEEP_SENT_DAYS] days (spec §6.3) and are then removed by [purgeSent].
 */
class PhotoStore(private val root: File) {
    private val lock = Any()

    private fun dir(username: String, rmaId: String) = File(File(root, DraftStore.hex(username)), DraftStore.hex(rmaId))
    private fun index(username: String, rmaId: String) = File(dir(username, rmaId), "index.json")

    /** Where the JPEG of [photoId] goes (the folder is created). Write the file, then call [add] / [replace]. */
    fun newFile(username: String, rmaId: String, photoId: String): File {
        val d = dir(username, rmaId)
        d.mkdirs()
        return File(d, "$photoId.jpg")
    }

    fun file(username: String, rmaId: String, photo: LocalPhoto): File = File(dir(username, rmaId), photo.file)

    /** Every photo of this inspector's RMA, sent or not, in capture order. */
    fun list(username: String, rmaId: String): List<LocalPhoto> = synchronized(lock) { read(username, rmaId) }

    /** Photos the server has not confirmed yet — what Submit uploads (B3). */
    fun pending(username: String, rmaId: String): List<LocalPhoto> = list(username, rmaId).filter { !it.isSent }

    fun add(username: String, rmaId: String, photo: LocalPhoto) = synchronized(lock) {
        write(username, rmaId, read(username, rmaId) + photo)
    }

    /** Retake: the new photo takes the old one's place in the order; the old file is deleted. */
    fun replace(username: String, rmaId: String, oldPhotoId: String, photo: LocalPhoto) = synchronized(lock) {
        val all = read(username, rmaId)
        val i = all.indexOfFirst { it.photoId == oldPhotoId }
        write(username, rmaId, if (i < 0) all + photo else all.toMutableList().also { it[i] = photo })
        if (i >= 0) File(dir(username, rmaId), all[i].file).delete()
        Unit
    }

    /** Deletes a photo taken on this tablet (never one on the server). */
    fun remove(username: String, rmaId: String, photoId: String) = synchronized(lock) {
        val all = read(username, rmaId)
        val gone = all.firstOrNull { it.photoId == photoId } ?: return@synchronized
        write(username, rmaId, all - gone)
        File(dir(username, rmaId), gone.file).delete()
        Unit
    }

    /**
     * B3 hook: call **only** after the server confirmed the upload (`201`, or `200` + `replayed`). The file stays
     * for [KEEP_SENT_DAYS] days as a safety net.
     */
    fun markSent(username: String, rmaId: String, photoId: String, serverFilename: String, now: String = Draft.nowIso()) =
        synchronized(lock) {
            val all = read(username, rmaId)
            if (all.none { it.photoId == photoId }) return@synchronized
            write(username, rmaId, all.map { if (it.photoId == photoId) it.copy(sentAt = now, serverFilename = serverFilename) else it })
        }

    /**
     * After a submit the server gives the parts rows new `row_id`s: photos of a row follow it. [mapping] = old
     * [LocalPhoto.rowLocalId] → new one; photos of other rows stay as they are.
     */
    fun remapRows(username: String, rmaId: String, mapping: Map<String, String>) = synchronized(lock) {
        if (mapping.isEmpty()) return@synchronized
        val all = read(username, rmaId)
        if (all.none { it.rowLocalId in mapping }) return@synchronized
        write(username, rmaId, all.map { p -> p.rowLocalId?.let { mapping[it] }?.let { p.copy(rowLocalId = it) } ?: p })
    }

    /** Removes sent photos older than [days] days (spec §6.3). Returns how many went. */
    fun purgeSent(username: String, rmaId: String, now: Instant = Instant.now(), days: Long = KEEP_SENT_DAYS): Int =
        synchronized(lock) {
            val cutoff = now.minusSeconds(days * 86_400)
            val all = read(username, rmaId)
            val old = all.filter { p -> p.sentAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }?.isBefore(cutoff) == true }
            if (old.isEmpty()) return@synchronized 0
            write(username, rmaId, all - old.toSet())
            old.forEach { File(dir(username, rmaId), it.file).delete() }
            old.size
        }

    /** Removes every photo of every inspector (emulator tests only). */
    fun clearAll() = synchronized(lock) { root.deleteRecursively(); Unit }

    private fun read(username: String, rmaId: String): List<LocalPhoto> = runCatching {
        val f = index(username, rmaId)
        if (!f.exists()) emptyList() else ApiClient.defaultJson.decodeFromString(PhotoIndex.serializer(), f.readText()).photos
    }.getOrDefault(emptyList())

    private fun write(username: String, rmaId: String, photos: List<LocalPhoto>) {
        val f = index(username, rmaId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ApiClient.writeJson.encodeToString(PhotoIndex.serializer(), PhotoIndex(photos = photos)))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Couldn't save the photo list." }
        }
    }

    companion object {
        const val KEEP_SENT_DAYS = 30L
    }
}

/** `meta` of `POST /rmas/{id}/photos` (api §8). B3 builds it with [PhotoUpload.meta]. */
@Serializable
data class PhotoMetaWrite(
    @SerialName("photo_id") val photoId: String,
    val kind: String,
    val seq: Int,
    @SerialName("row_id") val rowId: String?,
    @SerialName("part_code") val partCode: CodeKey?,
    @SerialName("issue_code") val issueCode: CodeKey?,
    val tag: String?,
    @SerialName("tag_text") val tagText: String?,
    @SerialName("captured_at") val capturedAt: String,
    val sha256: String,
)

/** The hook B3 (submit) uses to turn a stored photo into an upload. Nothing here talks to the server. */
object PhotoUpload {
    /**
     * Upload `meta` for [photo] given the rows **at submit time** (`seq` = the row's position, 1-based; `row_id` null
     * for rows not saved yet). Null when the photo can't be filed yet: its row was deleted, or the row has no part /
     * issue — B3's review screen lists these instead of sending them.
     */
    fun meta(photo: LocalPhoto, rows: List<FindingRow>): PhotoMetaWrite? {
        if (photo.isRma) {
            val tag = photo.tag?.takeIf { it.isNotBlank() } ?: return null
            return PhotoMetaWrite(photo.photoId, LocalPhoto.KIND_RMA, 0, null, null, null, tag, photo.tagText?.takeIf { it.isNotBlank() },
                photo.capturedAt, photo.sha256)
        }
        val i = rows.indexOfFirst { it.localId == photo.rowLocalId }
        if (i < 0) return null
        val row = rows[i]
        val part = row.partCode ?: return null
        val issue = row.issueCode ?: return null
        return PhotoMetaWrite(photo.photoId, LocalPhoto.KIND_PART, i + 1, row.rowId, part.key, issue.key, null, null,
            photo.capturedAt, photo.sha256)
    }
}

/** Where a thumbnail comes from: a file on this tablet, or the server (desk photos and earlier uploads). */
sealed interface PhotoSource {
    data class Local(val photoId: String, val file: String) : PhotoSource
    data class Server(val filename: String) : PhotoSource
}

/**
 * One tile of the Photos tab.
 * - [name]: the file name shown. For a photo taken here it is a **preview** ([isPreview]); the server makes the final
 *   name (spec §9). Null [name] = it can't be built yet (the row has no part or issue) → [nameHint] says why.
 */
data class PhotoItem(
    val id: String,
    val source: PhotoSource,
    val name: String?,
    val isPreview: Boolean,
    val nameHint: String? = null,
    val local: LocalPhoto? = null,
    val server: PhotoEntry? = null,
) {
    /** Only photos taken on this tablet and not yet sent can be retaken or deleted here. */
    val canEdit: Boolean get() = local != null
    val displayName: String get() = name ?: nameHint ?: "Not named yet"
}

/**
 * A group of the Photos tab (spec §4.4): the RMA-level photos first, then one group per parts row in the
 * inspector's order, then photos that belong to no row.
 */
data class PhotoGroup(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** The parts row this group is for ([FindingRow.localId]); null for RMA-level and unlinked groups. */
    val rowLocalId: String? = null,
    val isRma: Boolean = false,
    val items: List<PhotoItem> = emptyList(),
)

/** Builds the Photos tab groups from rows + local photos + the server's list. Pure — everything is passed in. */
object PhotoGroups {
    const val RMA_KEY = "rma"
    const val UNLINKED_KEY = "unlinked"
    const val SERVER_PREFIX = "srv:"

    private val numbered = Regex("^(\\d+)-(\\d+)\\b")

    fun rowLabel(index: Int, row: FindingRow): String {
        val no = (index + 1).toString().padStart(2, '0')
        val part = row.partCode?.let { it.description.ifBlank { it.code } } ?: "No part yet"
        val issue = row.issueCode?.let { "${it.code} ${it.description}".trim() }
        return "$no  $part" + if (issue != null) " · $issue" else ""
    }

    /** Highest photo number the server already uses for `<seq>-n …` (0 = none). The server's next name is max + 1. */
    internal fun maxServerNumber(server: List<PhotoEntry>, seq: Int): Int =
        server.mapNotNull { numbered.find(it.filename) }
            .filter { it.groupValues[1].toIntOrNull() == seq }
            .maxOfOrNull { it.groupValues[2].toIntOrNull() ?: 0 } ?: 0

    /** Which row a server photo belongs to (api §8): `row_id`, else part code + position, else part code alone. */
    internal fun rowIndexFor(photo: PhotoEntry, rows: List<FindingRow>): Int? {
        photo.rowId?.let { id -> rows.indexOfFirst { it.rowId == id }.takeIf { it >= 0 }?.let { return it } }
        val part = photo.partCode ?: return null
        val matches = rows.withIndex().filter { it.value.partCode?.key == part }
        if (matches.isEmpty()) return null
        return (matches.firstOrNull { photo.seq != null && it.index + 1 == photo.seq } ?: matches.first()).index
    }

    fun build(rows: List<FindingRow>, local: List<LocalPhoto>, server: List<PhotoEntry>): List<PhotoGroup> {
        val unsent = local.filter { !it.isSent }
        val rowIds = rows.mapTo(HashSet()) { it.localId }

        val serverByRow = HashMap<Int, MutableList<PhotoEntry>>()
        val serverRma = mutableListOf<PhotoEntry>()
        val serverUnlinked = mutableListOf<PhotoEntry>()
        for (p in server) {
            val idx = rowIndexFor(p, rows)
            when {
                idx != null -> serverByRow.getOrPut(idx) { mutableListOf() }.add(p)
                p.partCode == null && (p.seq == null || p.seq == 0) -> serverRma.add(p)
                else -> serverUnlinked.add(p)
            }
        }

        fun serverItem(p: PhotoEntry) = PhotoItem(SERVER_PREFIX + p.filename, PhotoSource.Server(p.filename), p.filename, isPreview = false, server = p)

        val groups = mutableListOf<PhotoGroup>()

        // RMA-level photos.
        val rmaLocal = unsent.filter { it.isRma }
        val rmaBase = maxServerNumber(server, 0)
        val rmaItems = serverRma.map(::serverItem) + rmaLocal.mapIndexed { i, p ->
            val label = p.tag.orEmpty()
            val name = if (label.isBlank()) null else PhotoNaming.rmaPhoto(rmaBase + i + 1, label, p.tagText)
            localItem(p, name, null)
        }
        groups += PhotoGroup(RMA_KEY, "RMA photos", "As received, nameplate, overview …", isRma = true, items = rmaItems)

        // One group per parts row.
        rows.forEachIndexed { index, row ->
            val mine = unsent.filter { !it.isRma && it.rowLocalId == row.localId }
            val base = maxServerNumber(server, index + 1)
            val items = serverByRow[index].orEmpty().map(::serverItem) + mine.mapIndexed { i, p ->
                val part = row.partCode
                val issue = row.issueCode
                if (part != null && issue != null) {
                    localItem(p, PhotoNaming.partPhoto(index + 1, base + i + 1, part.description.ifBlank { part.code }, issue.code, issue.description), null)
                } else {
                    localItem(p, null, "Choose the part and issue to name this photo")
                }
            }
            groups += PhotoGroup(row.localId, rowLabel(index, row), null, rowLocalId = row.localId, items = items)
        }

        // Photos of a row that was removed, and server photos nothing links to.
        val orphans = unsent.filter { !it.isRma && it.rowLocalId !in rowIds }
        val loose = serverUnlinked.map(::serverItem) + orphans.map { localItem(it, null, "Its part row was removed") }
        if (loose.isNotEmpty()) groups += PhotoGroup(UNLINKED_KEY, "Not linked to a part", "Photos with no parts row", items = loose)
        return groups
    }

    private fun localItem(p: LocalPhoto, name: String?, hint: String?) =
        PhotoItem(p.photoId, PhotoSource.Local(p.photoId, p.file), name, isPreview = true, nameHint = hint, local = p)

    /** Photos per row ([FindingRow.localId]) for the "📷 n" badge of the Findings list. */
    fun countsByRow(rows: List<FindingRow>, local: List<LocalPhoto>, server: List<PhotoEntry>): Map<String, Int> =
        build(rows, local, server).filter { it.rowLocalId != null && it.items.isNotEmpty() }.associate { it.rowLocalId!! to it.items.size }

    /** Preview name for a not-yet-taken RMA photo with [tag] (tag picker). */
    fun rmaPreview(local: List<LocalPhoto>, server: List<PhotoEntry>, tag: String, tagText: String?): String =
        PhotoNaming.rmaPhoto(maxServerNumber(server, 0) + local.count { !it.isSent && it.isRma } + 1, tag, tagText)
}

/** Pure helpers for scaling and metadata (unit-tested; [PhotoProcessor] does the bitmap work). */
object PhotoMath {
    /** Size after scaling the long edge down to [maxEdge] (never up). */
    fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (maxEdge <= 0 || longEdge <= maxEdge) return width to height
        val f = maxEdge.toDouble() / longEdge
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** Largest power-of-two `inSampleSize` that still leaves the long edge at least [maxEdge] (cheap first decode). */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var s = 1
        val longEdge = maxOf(width, height)
        while (maxEdge > 0 && longEdge / (s * 2) >= maxEdge) s *= 2
        return s
    }

    private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * EXIF capture time (`2026:11:17 10:49:02`, optional `+01:00` offset) → ISO-8601 with offset. Falls back to
     * [fallback] when missing or unreadable. The time is kept; no location or device data is.
     */
    fun capturedAt(exifDateTime: String?, exifOffset: String?, zone: ZoneId, fallback: Instant): String {
        val local = runCatching { java.time.LocalDateTime.parse(exifDateTime!!.trim(), exifFormat) }.getOrNull()
            ?: return fallback.atZone(zone).toOffsetDateTime().withNano(0).toString()
        val off = runCatching { ZoneOffset.of(exifOffset!!.trim()) }.getOrNull()
        val odt = if (off != null) local.atOffset(off) else local.atZone(zone).toOffsetDateTime()
        return odt.toString()
    }
}
