package com.bitzer.rmainspector.core

/**
 * Natural ("human") order for code strings and filenames: `48` < `48a` < `100`, `03-2` < `03-10`.
 * Codes are always text — never parse them to numbers for storage (docs/spec.md §8).
 */
object NaturalSort : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a).map { it.value }.toList()
        val cb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val xd = x[0].isDigit()
            val yd = y[0].isDigit()
            val r = when {
                xd && yd -> compareDigits(x, y)
                xd -> -1 // digits sort before letters
                yd -> 1
                else -> x.lowercase().compareTo(y.lowercase()).let { if (it != 0) it else x.compareTo(y) }
            }
            if (r != 0) return r
        }
        return ca.size.compareTo(cb.size)
    }

    private fun compareDigits(x: String, y: String): Int {
        val xt = x.trimStart('0').ifEmpty { "0" }
        val yt = y.trimStart('0').ifEmpty { "0" }
        if (xt.length != yt.length) return xt.length.compareTo(yt.length)
        val r = xt.compareTo(yt)
        return if (r != 0) r else x.length.compareTo(y.length) // "7" before "07" only as a tiebreak
    }
}
