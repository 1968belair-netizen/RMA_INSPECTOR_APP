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
    private val allowed = Regex("""^[A-Za-z0-9][A-Za-z0-9\-/._ ]*$""")

    /** Barcode / QR payload -> search text, or null when there is nothing usable. First line only, trimmed, capped. */
    fun fromBarcode(raw: String?): String? {
        val line = raw?.lineSequence()?.map { clean(it) }?.firstOrNull { it.isNotEmpty() } ?: return null
        return stripLabel(line).take(MAX_LENGTH).trim().ifEmpty { null }
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

    private fun clean(s: String) = s.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()

    private fun stripLabel(s: String): String {
        val stripped = labelPrefix.replace(s, "").trim()
        // Never strip a line down to nothing (a plain "SN" is not a serial label + value).
        return if (stripped.isEmpty()) s else stripped
    }
}
