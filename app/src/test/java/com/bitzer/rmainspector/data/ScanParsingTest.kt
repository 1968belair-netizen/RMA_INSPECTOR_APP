package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #19: scanner result parsing. Fake values only. */
class ScanParsingTest {
    @Test fun barcodeIsTrimmed() = assertEquals("912345", ScanParsing.fromBarcode("  912345 \n"))
    @Test fun barcodeUsesFirstNonBlankLine() = assertEquals("25X0000001", ScanParsing.fromBarcode("\n25X0000001\nextra"))
    @Test fun barcodeBlankOrNullIsNull() {
        assertNull(ScanParsing.fromBarcode(null))
        assertNull(ScanParsing.fromBarcode("   \n \t"))
    }
    @Test fun barcodeStripsControlCharacters() = assertEquals("912345", ScanParsing.fromBarcode("91\u00002345"))
    @Test fun barcodeIsCapped() = assertEquals(64, ScanParsing.fromBarcode("A".repeat(200))!!.length)
    @Test fun barcodeStripsSerialLabel() = assertEquals("25X0000001", ScanParsing.fromBarcode("S/N: 25X0000001"))
    @Test fun barcodeKeepsLabelOnlyLine() = assertEquals("SN", ScanParsing.fromBarcode("SN"))

    @Test fun plateKeepsLinesWithDigitsInOrder() {
        val text = "BITZER\nModel 4FE-28Y\nS/N: 25X0000001\nMade in USA\n912345"
        assertEquals(listOf("Model 4FE-28Y", "25X0000001", "912345"), ScanParsing.candidateLines(text))
    }
    @Test fun plateDropsShortAndSymbolLines() {
        assertEquals(listOf("25X0000001"), ScanParsing.candidateLines("12\n***** 1234\n|||\n25X0000001"))
    }
    @Test fun plateRemovesDuplicates() = assertEquals(listOf("25X0000001"), ScanParsing.candidateLines("25X0000001\nSerial No. 25X0000001"))
    @Test fun plateLabelVariants() {
        val out = ScanParsing.candidateLines("SN 25X0000002\nSerial # 25X0000003\nSerial Number: 25X0000004\nS / N - 25X0000005")
        assertEquals(listOf("25X0000002", "25X0000003", "25X0000004", "25X0000005"), out)
    }
    @Test fun plateEmptyInputs() {
        assertEquals(emptyList<String>(), ScanParsing.candidateLines(null))
        assertEquals(emptyList<String>(), ScanParsing.candidateLines("  \n "))
    }
}
