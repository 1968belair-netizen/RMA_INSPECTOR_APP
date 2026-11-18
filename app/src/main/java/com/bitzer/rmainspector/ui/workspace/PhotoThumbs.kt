package com.bitzer.rmainspector.ui.workspace

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bitzer.rmainspector.data.ApiClient
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.PhotoMath
import com.bitzer.rmainspector.data.PhotoSource
import com.bitzer.rmainspector.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnails for the Photos tab. Photos taken here are decoded from the device (small `inSampleSize`); desk photos and
 * earlier uploads come from the server as thumbnails (api §8 `size=thumb`). Decoded pictures are cached in memory.
 */
class AndroidPhotoThumbs(
    private val api: ApiClient,
    private val store: PhotoStore,
    private val rmaId: String,
    private val username: () -> String?,
    private val baseUrl: suspend () -> String,
    private val fileOf: (PhotoSource.Local) -> File?,
) : PhotoThumbLoader {
    private val cache = object : LruCache<String, ImageBitmap>(80) {}

    override suspend fun load(source: PhotoSource, full: Boolean): ImageBitmap? {
        val key = when (source) { is PhotoSource.Local -> "L:${source.photoId}:$full"; is PhotoSource.Server -> "S:${source.filename}:$full" }
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            when (source) {
                is PhotoSource.Local -> fileOf(source)?.takeIf { it.exists() }?.let { decodeFile(it, if (full) 1600 else 480) }
                is PhotoSource.Server -> when (val r = api.photoBytes(baseUrl(), rmaId, "all", source.filename, thumb = !full)) {
                    is ApiResult.Ok -> decodeBytes(r.value, if (full) 1600 else 480)
                    is ApiResult.Failure -> null
                }
            }
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Forget cached server pictures (after a refresh the same name can hold a new file). */
    fun clear() = cache.evictAll()

    private fun decodeFile(f: File, edge: Int): ImageBitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = PhotoMath.sampleSize(b.outWidth, b.outHeight, edge) }
        return BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
    }

    private fun decodeBytes(bytes: ByteArray, edge: Int): ImageBitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = PhotoMath.sampleSize(b.outWidth, b.outHeight, edge) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.asImageBitmap()
    }
}
