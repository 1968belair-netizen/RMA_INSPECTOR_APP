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

/**
 * Turns the camera's JPEG into the photo that is stored and later uploaded (spec §9.4): EXIF orientation applied to
 * the pixels, long edge scaled down to the server's `max_edge_px`, re-encoded at `jpeg_quality`. Re-encoding drops
 * every EXIF tag, so GPS and device data are gone; only the capture time is written back.
 */
object PhotoProcessor {
    fun process(source: File, dest: File, settings: PhotoSettings, now: Instant = Instant.now()): ProcessedPhoto {
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
        val opts = BitmapFactory.Options().apply {
            inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, settings.maxEdgePx)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: error("The photo couldn't be read.")

        val upright = applyOrientation(decoded, orientation)
        val (w, h) = PhotoMath.targetSize(upright.width, upright.height, settings.maxEdgePx)
        val scaled = if (w != upright.width || h != upright.height) Bitmap.createScaledBitmap(upright, w, h, true) else upright

        val outW = scaled.width
        val outH = scaled.height
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        FileOutputStream(tmp).use { check(scaled.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality.coerceIn(1, 100), it)) { "Couldn't save the photo." } }
        if (scaled !== upright) scaled.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()

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
