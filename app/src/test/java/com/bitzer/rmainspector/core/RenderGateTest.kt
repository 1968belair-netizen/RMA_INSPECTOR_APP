package com.bitzer.rmainspector.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderGateTest {
    @Test
    fun closeWaitsForRenderInFlight() = runTest(UnconfinedTestDispatcher()) {
        val gate = RenderGate(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val render = launch { gate.run { events += "render-start"; release.await(); events += "render-end"; "bmp" } }
        gate.close { events += "cleanup" }
        assertEquals(listOf("render-start"), events) // cleanup has not run under the render
        release.complete(Unit)
        render.join()
        assertEquals(listOf("render-start", "render-end", "cleanup"), events)
    }

    @Test
    fun rendersAfterCloseDoNothing() = runTest(UnconfinedTestDispatcher()) {
        val gate = RenderGate(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        var cleaned = 0
        gate.close { cleaned++ }
        gate.close { cleaned++ } // second close is ignored
        var ran = false
        val r = gate.run { ran = true; "bmp" }
        assertNull(r)
        assertTrue(!ran)
        assertEquals(1, cleaned)
    }

    @Test
    fun runReturnsBlockResultWhileOpen() = runTest(UnconfinedTestDispatcher()) {
        val gate = RenderGate(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals("x", gate.run { "x" })
    }
}
