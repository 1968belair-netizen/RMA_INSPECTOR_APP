package com.bitzer.rmainspector.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bitzer.rmainspector.ui.workspace.HeaderField.CUSTOMER
import com.bitzer.rmainspector.ui.workspace.HeaderField.FAILURE
import com.bitzer.rmainspector.ui.workspace.HeaderField.MODEL
import com.bitzer.rmainspector.ui.workspace.HeaderField.RETURN_CODE
import com.bitzer.rmainspector.ui.workspace.HeaderField.SERIAL

/** Slim Report header fit (issue #81): which fields stay on the line. Widths are plain px; no Compose needed. */
class ReportHeaderFitTest {
    private val widths = listOf(
        FieldWidth(CUSTOMER, 300, shortPx = 150), FieldWidth(MODEL, 140), FieldWidth(SERIAL, 200),
        FieldWidth(RETURN_CODE, 160), FieldWidth(FAILURE, 280),
    )

    private fun params(avail: Int) = FitParams(
        availablePx = avail, gapPx = 16, rmaPx = 200, statusPx = 150, detailsPx = 130, chipPx = 50, twoLineBelowPx = 640,
    )

    private fun fit(avail: Int) = fitReportHeader(params(avail), widths)

    @Test fun everythingFitsOnWideLine() {
        val f = fit(1700) // needs 1672
        assertEquals(listOf(CUSTOMER, MODEL, SERIAL, RETURN_CODE, FAILURE), f.visible)
        assertEquals(0, f.hidden)
        assertFalse(f.twoLine)
        assertFalse(f.customerShort)
    }

    @Test fun justTooNarrowDropsSerialFirst() {
        val f = fit(1671)
        assertEquals(listOf(CUSTOMER, MODEL, RETURN_CODE, FAILURE), f.visible)
        assertEquals(1, f.hidden)
    }

    @Test fun dropOrderIsSerialReturnCodeFailureCustomerModel() {
        assertEquals(listOf(CUSTOMER, MODEL), fit(1200).visible)       // serial, return code, failure gone
        assertEquals(3, fit(1200).hidden)
        assertEquals(listOf(MODEL), fit(800).visible)                   // customer gone too
        assertEquals(4, fit(800).hidden)
        assertTrue(fit(200).visible.let { it.isEmpty() || it == listOf(MODEL) })
    }

    @Test fun customerIsEllipsizedBeforeItIsDropped() {
        val f = fit(1000)
        assertEquals(listOf(CUSTOMER, MODEL), f.visible)
        assertTrue(f.customerShort)
        assertEquals(3, f.hidden)
    }

    @Test fun veryNarrowWindowUsesTwoLinesBeforeDroppingAnything() {
        val wide = listOf(FieldWidth(CUSTOMER, 60, 40), FieldWidth(MODEL, 60), FieldWidth(SERIAL, 60), FieldWidth(RETURN_CODE, 60), FieldWidth(FAILURE, 60))
        val f = fitReportHeader(params(600), wide) // one line would need far more than 600, line 2 needs 364
        assertTrue(f.twoLine)
        assertEquals(0, f.hidden)
        assertEquals(5, f.visible.size)
    }

    @Test fun twoLineStillDropsInOrderWhenLineTwoIsTooShort() {
        val f = fit(600)
        assertTrue(f.twoLine)
        assertEquals(listOf(CUSTOMER, MODEL), f.visible)
        assertEquals(3, f.hidden)
    }

    @Test fun wideWindowsNeverUseTwoLines() {
        for (w in 640..2000 step 10) assertFalse("width $w", fit(w).twoLine)
    }

    @Test fun hiddenCountMatchesAndDropsAreAlwaysAPrefixOfTheDropOrder() {
        var previousHidden = 0
        for (w in 2000 downTo 100 step 5) {
            val f = fit(w)
            assertEquals("width $w", widths.size - f.visible.size, f.hidden)
            val dropped = HeaderField.DROP_ORDER.filter { it !in f.visible }
            assertEquals("width $w", HeaderField.DROP_ORDER.take(dropped.size), dropped)
            // on one line, a narrower window never shows more than a wider one (two-line windows start below 640)
            if (w >= 640) assertTrue("width $w", f.hidden >= previousHidden)
            previousHidden = f.hidden
        }
    }

    @Test fun visibleFieldsKeepDisplayOrder() {
        for (w in 700..1700 step 25) {
            val v = fit(w).visible
            assertEquals(HeaderField.DISPLAY_ORDER.filter { it in v }, v)
        }
    }
}
