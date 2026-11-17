package com.bitzer.rmainspector.core

/**
 * Photo filename = report caption (docs/spec.md §5). The **server** makes the final name (it also resolves
 * collisions); the tablet uses this to show a preview. Keep in step with mock-server/mock_server/naming.py —
 * both are tested against testdata/photo_naming_cases.json.
 */
object PhotoNaming {
    const val PART_MAX = 40
    const val ISSUE_MAX = 30
    const val TAG_MAX = 40
    const val NAME_MAX = 120
    const val EXT = ".jpg"

    private val illegal = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
    private val withOut = Regex("\\bw/(?=[A-Za-z])", RegexOption.IGNORE_CASE) // "w/out" -> "without"
    private val with = Regex("\\bw/", RegexOption.IGNORE_CASE)                // "w/ Conn" -> "with Conn"
    private val commaSpaces = Regex("\\s*,[\\s,]*")
    private val spaces = Regex("\\s+")

    /** §5.2 sanitising rules, then truncate to [max] characters. */
    fun sanitize(text: String, max: Int = Int.MAX_VALUE): String {
        var s = text
        s = withOut.replace(s, "with")
        s = with.replace(s, "with")
        s = s.replace("/", ", ")
        s = illegal.replace(s, "")
        s = commaSpaces.replace(s, ", ")
        s = spaces.replace(s, " ")
        s = trimEnds(s)
        if (s.length > max) s = trimEnds(s.substring(0, max))
        return s
    }

    private fun trimEnds(s: String): String = s.trim().trimStart(',', ' ').trimEnd(',', ' ', '.')

    private fun seqLabel(seq: Int): String = seq.toString().padStart(2, '0')

    fun partPhoto(seq: Int, n: Int, partDescription: String, issueCode: String, issueDescription: String): String {
        val part = sanitize(partDescription, PART_MAX)
        val code = sanitize(issueCode)
        val issue = sanitize(issueDescription, ISSUE_MAX)
        val stem = "${seqLabel(seq)}-$n $part - $code" + if (issue.isNotEmpty()) " $issue" else ""
        return fit(stem)
    }

    /** RMA-level photo; [tagText] is used when the tag is "Other". */
    fun rmaPhoto(n: Int, tag: String, tagText: String? = null): String {
        val label = if (tag.equals("Other", ignoreCase = true) && !tagText.isNullOrBlank()) tagText else tag
        return fit("00-$n ${sanitize(label, TAG_MAX)}")
    }

    private fun fit(stem: String): String {
        val max = NAME_MAX - EXT.length
        val s = if (stem.length > max) trimEnds(stem.substring(0, max)) else stem
        return s + EXT
    }
}
