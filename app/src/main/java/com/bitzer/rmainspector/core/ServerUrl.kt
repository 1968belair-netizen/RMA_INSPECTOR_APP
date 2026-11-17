package com.bitzer.rmainspector.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what the user typed into the API base URL, e.g.
 *  "192.168.1.20:8000"            -> "http://192.168.1.20:8000/api/v1"
 *  "https://rma-server.example.invalid" -> "https://rma-server.example.invalid/api/v1"
 * Returns null when it can't be a URL. No scheme means http (dev/mock on the local network);
 * the real server address set by IT includes https://.
 */
object ServerUrl {
    const val API_PATH = "/api/v1"

    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withoutScheme = s.replaceFirst(Regex("^https?:/*", RegexOption.IGNORE_CASE), "")
        if (withoutScheme.isEmpty() || withoutScheme.startsWith(":")) return null
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        if (!s.endsWith(API_PATH)) s += API_PATH
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        return url.toString().trimEnd('/')
    }
}
