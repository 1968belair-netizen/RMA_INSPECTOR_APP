package com.bitzer.rmainspector.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** What [PhotoProcessor.process] produced. */
data class ProcessedPhoto(val width: Int, val height: Int, val bytes: Long, val sha256: String, val capturedAt: String)

/** The tablet is (nearly) out of storage; the message is shown to the inspector as is. */
class StorageFullException(message: String = "The tablet is almost out of storage. Free some space, then take the photo again.") : java.io.IOException(message)

/**
 * Turns the camera's JPEG into the photo that is stored and later uploaded (spec §9.4): EXIF orientation applied to
 * the pixels, long edge scaled down to the server's `max_edge_px`, re-encoded at `jpeg_quality`. Re-encoding drops
 * every EXIF tag, so GPS and device data are gone; only the capture time is written back.
 */
object PhotoProcessor {
    /** Free space kept in reserve besides the photo itself (the store also writes its index). */
    private const val MIN_FREE_BYTES = 8L * 1024 * 1024

    fun process(
        source: File, dest: File, settings: PhotoSettings, now: Instant = Instant.now(),
        usableSpace: (File) -> Long = { it.usableSpace },
    ): ProcessedPhoto {
        dest.parentFile?.let { dir -> if (usableSpace(dir) < PhotoMath.requiredFreeBytes(source.length(), MIN_FREE_BYTES)) throw StorageFullException() }
        val exif = ExifInterface(source.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val taken = PhotoMath.capturedAt(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), ZoneId.systemDefault(),
            Instant.ofEpochMilli(source.lastModified().takeIf { it > 0 } ?: now.toEpochMilli()),
        )

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The photo couldn't be read." }
        // A big photo on a tablet with little memory can run out of heap; retry with a smaller decode (twice as coarse
        // each time, up to 3 times). The result is still at least as large as the server's max edge when possible.
        var sample = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, settings.maxEdgePx)
        var attempt = 0
        var decoded: Bitmap? = null
        var upright: Bitmap? = null
        var scaled: Bitmap? = null
        while (scaled == null) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: error("The photo couldn't be read.")
                upright = applyOrientation(decoded, orientation)
                val (w, h) = PhotoMath.targetSize(upright.width, upright.height, settings.maxEdgePx)
                scaled = if (w != upright.width || h != upright.height) Bitmap.createScaledBitmap(upright, w, h, true) else upright
            } catch (e: OutOfMemoryError) {
                if (upright !== decoded) upright?.recycle()
                decoded?.recycle(); decoded = null; upright = null
                if (++attempt > 3) throw java.io.IOException("The photo is too large to process on this tablet.", e)
                sample *= 2
            }
        }
        val decodedBmp = decoded!!
        val uprightBmp = upright!!
        val scaledBmp = scaled!!

        val outW = scaledBmp.width
        val outH = scaledBmp.height
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            FileOutputStream(tmp).use { check(scaledBmp.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality.coerceIn(1, 100), it)) { "Couldn't save the photo." } }
        } catch (e: java.io.IOException) {
            tmp.delete()
            // Writing failed part-way: that is almost always a full disk.
            throw if (usableSpace(tmp.parentFile ?: tmp) < MIN_FREE_BYTES) StorageFullException() else e
        } finally {
            if (scaledBmp !== uprightBmp) scaledBmp.recycle()
            if (uprightBmp !== decodedBmp) uprightBmp.recycle()
            decodedBmp.recycle()
        }

        // Keep the capture time only (no GPS, no device tags). Best effort: the photo is fine without it.
        runCatching {
            val out = ExifInterface(tmp.absolutePath)
            val exifTime = taken.take(19).replace('-', ':').replace('T', ' ')
            out.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTime)
            out.saveAttributes()
        }
        dest.delete()
        check(tmp.renameTo(dest)) { "Couldn't save the photo." }
        return ProcessedPhoto(outW, outH, dest.length(), sha256(dest), taken)
    }

    /** Rotates / mirrors so the pixels are upright, as the EXIF orientation says. */
    fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(8192); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
