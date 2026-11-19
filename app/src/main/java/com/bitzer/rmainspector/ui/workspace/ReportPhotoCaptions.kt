package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.core.NaturalSort
import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.data.ReportPick

/**
 * One photo in the Report-tab viewer. [folder] is `all` (`All Pics`) or `report` (`Report Pics `), the same values the
 * server's photo endpoint takes (api §8). [key] is unique across both folders.
 */
data class ViewerPhoto(val filename: String, val folder: String = "all") {
    val key: String get() = "$folder/$filename"
}

/**
 * What a photo filename says (spec §9.1): `<SS>-<n> <Part description> - <Issue code> <Issue description>.jpg`, or
 * `00-<n> <Tag>.jpg` for RMA-level photos. Anything that doesn't match keeps its whole text in [text].
 * - [badge]: the `SS-n` prefix as written (`03-1`), null when the name doesn't follow the pattern.
 * - [rmaLevel]: `00-n` photo; [rmaTag] is its tag (As received, Nameplate, …).
 * - [partDescription], [issueCode] (text, never a number) and [issueDescription] for part photos.
 * - [text]: the name without the extension, used when nothing could be parsed.
 */
data class PhotoCaption(
    val text: String,
    val badge: String? = null,
    val rmaLevel: Boolean = false,
    val rmaTag: String? = null,
    val partDescription: String? = null,
    val issueCode: String? = null,
    val issueDescription: String? = null,
) {
    /** Issue chip text: `<code> <description>`; null when the name has no issue. */
    val issueLabel: String? get() = issueCode?.let { c -> if (issueDescription.isNullOrBlank()) c else "$c $issueDescription" }
}

object ReportPhotoCaptions {
    private val prefix = Regex("""^(\d{2,3})-(\d+)\s+(.+)$""")
    private val issue = Regex("""^(.+?) - (\S+)(?:\s+(.*))?$""")

    /** Filename → caption pieces for the chips. Pure; never throws. */
    fun parse(filename: String): PhotoCaption {
        val name = filename.trim().removeSuffix(".jpg").removeSuffix(".JPG").removeSuffix(".jpeg").trim()
        val m = prefix.matchEntire(name) ?: return PhotoCaption(text = name)
        val badge = "${m.groupValues[1]}-${m.groupValues[2]}"
        val rest = m.groupValues[3].trim()
        if (m.groupValues[1].trimStart('0').isEmpty()) {
            return PhotoCaption(text = name, badge = badge, rmaLevel = true, rmaTag = rest)
        }
        val i = issue.matchEntire(rest) ?: return PhotoCaption(text = name, badge = badge, partDescription = rest)
        return PhotoCaption(
            text = name, badge = badge, partDescription = i.groupValues[1].trim(),
            issueCode = i.groupValues[2], issueDescription = i.groupValues[3].trim().ifEmpty { null },
        )
    }

    /**
     * The photos the viewer pages through. All = every `All Pics` photo in the server's order. Report = the report
     * picks (spec §7) in report order (natural sort of the target name, spec decision 18); a pick from `Report Pics `
     * is shown from that folder.
     */
    fun viewerPhotos(all: List<PhotoEntry>, picks: List<ReportPick>, reportOnly: Boolean): List<ViewerPhoto> =
        if (!reportOnly) all.map { ViewerPhoto(it.filename, "all") }
        else picks.sortedWith { a, b -> NaturalSort.compare(a.targetName, b.targetName) }
            .map { ViewerPhoto(it.filename, if (it.source == "report_pics") "report" else "all") }
            .distinctBy { it.key }

    /** Index of the photo with [key] in [photos], or 0 when it isn't there (or [key] is null); -1 for an empty list. */
    fun indexOfKey(photos: List<ViewerPhoto>, key: String?): Int =
        if (photos.isEmpty()) -1 else photos.indexOfFirst { it.key == key }.coerceAtLeast(0)

    /** "3 / 12" counter. */
    fun counter(index: Int, total: Int): String = "${index + 1} / $total"
}
