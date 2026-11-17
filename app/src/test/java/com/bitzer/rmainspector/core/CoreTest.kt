package com.bitzer.rmainspector.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    // Gradle runs unit tests with the module (app/) as working directory.
    private val cases: JSONObject by lazy {
        val f = listOf(File("../testdata/photo_naming_cases.json"), File("testdata/photo_naming_cases.json"))
            .first { it.exists() }
        JSONObject(f.readText())
    }

    @Test
    fun rmaNumbersAreDigitsOnly() {
        assertTrue(RmaNumber.isValid("912345"))
        assertTrue(RmaNumber.isValid("1"))
        assertFalse(RmaNumber.isValid("123456789"))
        assertFalse(RmaNumber.isValid("A12345"))
        assertFalse(RmaNumber.isValid(""))
    }

    @Test
    fun partPhotoNames() {
        val arr = cases.getJSONArray("part_photos")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val name = PhotoNaming.partPhoto(c.getInt("seq"), c.getInt("n"), c.getString("part"), c.getString("issue_cd"), c.getString("issue"))
            assertEquals("case $i", c.getString("expect"), name)
        }
    }

    @Test
    fun rmaPhotoNames() {
        val arr = cases.getJSONArray("rma_photos")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val text = if (c.isNull("tag_text")) null else c.getString("tag_text")
            assertEquals("case $i", c.getString("expect"), PhotoNaming.rmaPhoto(c.getInt("n"), c.getString("tag"), text))
        }
    }

    @Test
    fun naturalSortCodesAndFilenames() {
        for (key in listOf("natural_sort", "natural_sort_filenames")) {
            val o = cases.getJSONObject(key)
            val input = (0 until o.getJSONArray("input").length()).map { o.getJSONArray("input").getString(it) }
            val expect = (0 until o.getJSONArray("expect").length()).map { o.getJSONArray("expect").getString(it) }
            assertEquals(key, expect, input.sortedWith(NaturalSort))
        }
    }
}
