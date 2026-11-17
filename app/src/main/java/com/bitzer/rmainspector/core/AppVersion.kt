package com.bitzer.rmainspector.core

/** Compares dotted versions like "0.1.12" numerically. Non-numeric parts count as 0. */
object AppVersion {
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isAtLeast(current: String, minimum: String): Boolean = compare(current, minimum) >= 0
}
