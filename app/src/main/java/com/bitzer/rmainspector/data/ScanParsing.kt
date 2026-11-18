package com.bitzer.rmainspector.data

/**
 * Turns raw scanner output into search text (docs/spec.md §3.1). Pure Kotlin so it is unit-tested on the JVM.
 * Only the resulting text is ever used; images and raw frames never leave the tablet.
 */
object ScanParsing {
    private const val MAX_LENGTH = 64
    private const val MIN_LINE_LENGTH = 4

    /** "S/N: 123", "SN 123", "Serial No. 123", "Serial # 123" — a label in front of the value. */
    private val labelPrefix = Regex(
        """^(?:s\s*/\s*n|s\.n\.|sn|serial\s*(?:no\.?|number|num\.?|#)?)(?:\s*[:#.\-]\s*|\s+)""",
        RegexOption.IGNORE_CASE,
    )
    /** "RMA 912345", "RMA#912345", "RMA No: 912345", "RMA912345" — the document type in front of the number. */
    private val rmaPrefix = Regex(
        """^rma(?:\s*(?:no\.?|number|num\.?|#))?(?:\s*[:#.\-]\s*|\s+)|^rma(?=\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val urlStart = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
    /** Query keys a QR link may carry the number in (first match wins, in this order). */
    private val urlKeys = listOf("rma", "rmano", "rma_no", "rmanumber", "serial", "sn", "id")
    private val allowed = Regex("""^[A-Za-z0-9][A-Za-z0-9\-/._ ]*$""")

    /** Barcode / QR payload -> search text, or null when there is nothing usable. First line only, trimmed, capped. */
    fun fromBarcode(raw: String?): String? {
        val line = raw?.lineSequence()?.map { clean(it) }?.firstOrNull { it.isNotEmpty() } ?: return null
        val value = if (urlStart.containsMatchIn(line)) fromUrl(line) ?: return null else line
        return stripLabel(value).take(MAX_LENGTH).trim().ifEmpty { null }
    }

    /**
     * Recognised plate text -> lines the inspector can pick from. Drops short / symbol-only lines and lines
     * without a digit (a serial always has one), removes a leading "S/N:" style label, keeps order, no duplicates.
     */
    fun candidateLines(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .map { stripLabel(clean(it)) }
            .filter { it.length >= MIN_LINE_LENGTH && it.any(Char::isDigit) && allowed.matches(it) }
            .map { it.take(MAX_LENGTH).trim() }
            .distinct()
            .toList()
    }

    /**
     * A QR code that holds a link: use the number in a known query key (`?rma=912345`), else the last path segment
     * (`/rma/912345`). A bare host (`https://example.invalid/`) has nothing to search for -> null.
     */
    private fun fromUrl(url: String): String? {
        val noFragment = url.substringBefore('#')
        val afterScheme = noFragment.substringAfter("://")
        val query = afterScheme.substringAfter('?', "")
        val fromQuery = query.split('&', ';').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (key in urlKeys && value.isNotBlank()) key to decode(value) else null
        }.minByOrNull { urlKeys.indexOf(it.first) }?.second
        if (fromQuery != null) return clean(fromQuery).ifEmpty { null }
        val path = afterScheme.substringBefore('?').substringAfter('/', "")
        val segment = path.split('/').lastOrNull { it.isNotBlank() } ?: return null
        return clean(decode(segment)).ifEmpty { null }
    }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // Also drops zero-width characters and the byte-order mark some label printers / scanner apps prepend.
    private fun clean(s: String) = s.filter { !it.isISOControl() && it !in "\u200B\u200C\u200D\u2060\uFEFF" }.replace(Regex("\\s+"), " ").trim()

    private fun stripLabel(s: String): String {
        val stripped = rmaPrefix.replace(labelPrefix.replace(s, ""), "").trim()
        // Never strip a line down to nothing (a plain "SN" is not a serial label + value).
        return if (stripped.isEmpty()) s else stripped
    }
}
