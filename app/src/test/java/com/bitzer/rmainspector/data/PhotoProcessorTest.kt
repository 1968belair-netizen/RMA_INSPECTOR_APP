package com.bitzer.rmainspector.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/** Camera JPEG → stored photo: orientation applied, scaled, GPS stripped, capture time kept (spec §9.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoProcessorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun camera(w: Int, h: Int, orientation: Int): File {
        val f = tmp.newFile()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.GRAY) }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:17 10:49:02")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "34/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "FakeCam")
            saveAttributes()
        }
        return f
    }

    @Test fun appliesOrientationScalesAndStripsGps() {
        val src = camera(400, 300, ExifInterface.ORIENTATION_ROTATE_90) // landscape pixels, portrait when displayed
        val dest = File(tmp.root, "out.jpg")
        val r = PhotoProcessor.process(src, dest, PhotoSettings(maxEdgePx = 200, jpegQuality = 80))
        assertEquals(150, r.width); assertEquals(200, r.height) // rotated upright, long edge 200
        val b = BitmapFactory.decodeFile(dest.absolutePath)
        assertEquals(150, b.width); assertEquals(200, b.height)
        val exif = ExifInterface(dest.absolutePath)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) <= 1)
        assertEquals("2026:11:17 10:49:02", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertTrue(r.capturedAt.startsWith("2026-11-17T10:49:02"))
        assertEquals(64, r.sha256.length)
    }

    @Test fun smallPhotosAreNotUpscaled() {
        val r = PhotoProcessor.process(camera(100, 80, ExifInterface.ORIENTATION_NORMAL), File(tmp.root, "o.jpg"), PhotoSettings())
        assertEquals(100, r.width); assertEquals(80, r.height)
    }

    // Issue #68: real-device failure paths.
    @Test fun lowStorageIsReportedBeforeAnythingIsWritten() {
        val dest = File(tmp.root, "full.jpg")
        try {
            PhotoProcessor.process(camera(100, 80, ExifInterface.ORIENTATION_NORMAL), dest, PhotoSettings(), usableSpace = { 1024L })
            org.junit.Assert.fail("expected StorageFullException")
        } catch (e: StorageFullException) {
            assertTrue(e.message!!.contains("storage"))
        }
        assertTrue(!dest.exists())
        assertTrue(!File(tmp.root, "full.jpg.tmp").exists())
    }

    @Test fun unreadableFileFailsCleanly() {
        val bad = tmp.newFile().also { it.writeText("not a jpeg") }
        try {
            PhotoProcessor.process(bad, File(tmp.root, "x.jpg"), PhotoSettings())
            org.junit.Assert.fail("expected failure")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            assertTrue(!File(tmp.root, "x.jpg").exists())
        }
    }

    @Test fun everyExifOrientationYieldsUprightSize() {
        // 400x300 pixels: orientations 5-8 swap width/height.
        val swapped = setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
        for (o in 1..8) {
            val r = PhotoProcessor.process(camera(400, 300, o), File(tmp.root, "o$o.jpg"), PhotoSettings(maxEdgePx = 4000))
            if (o in swapped) { assertEquals("orientation $o", 300, r.width); assertEquals(400, r.height) }
            else { assertEquals("orientation $o", 400, r.width); assertEquals(300, r.height) }
        }
    }

    @Test fun requiredFreeBytesHasAFloor() {
        assertEquals(8L, PhotoMath.requiredFreeBytes(1, 8))
        assertEquals(20L, PhotoMath.requiredFreeBytes(10, 8))
    }
}
