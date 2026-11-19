package com.bitzer.rmainspector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises use of a non-thread-safe native resource (PdfRenderer) and makes closing safe.
 * [run] blocks execute one at a time and return null once closed. [close] never blocks the caller: the cleanup runs
 * after the block in flight (if any) has finished, so the resource is never closed under a running render.
 */
class RenderGate(private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val mutex = Mutex()
    @Volatile private var closed = false

    val isClosed: Boolean get() = closed

    suspend fun <T> run(block: suspend () -> T?): T? = mutex.withLock {
        if (closed) null else block()
    }

    fun close(cleanup: () -> Unit) {
        if (closed) return
        closed = true
        cleanupScope.launch { mutex.withLock { cleanup() } }
    }
}
