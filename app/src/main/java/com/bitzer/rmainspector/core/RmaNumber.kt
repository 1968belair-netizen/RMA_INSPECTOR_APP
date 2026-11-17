package com.bitzer.rmainspector.core

/** RMA numbers are digits only, 1–8 long (decision 2026-11-17). Sent to the server unpadded. */
object RmaNumber {
    private val pattern = Regex("^[0-9]{1,8}$")
    fun isValid(text: String): Boolean = pattern.matches(text.trim())
}
