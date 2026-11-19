package com.bitzer.rmainspector.ui.workspace

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.ui.theme.BitzerColors
import kotlinx.coroutines.Dispatchers
import com.bitzer.rmainspector.core.RenderGate
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the pages of a PDF file with Android's built-in [PdfRenderer] (no extra library). One page is open at a
 * time (PdfRenderer's rule), so renders are serialised. Pages are drawn on white.
 */
class PdfPageSource private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val pageCount: Int get() = renderer.pageCount

    // PdfRenderer is not thread-safe: closing it while a page is rendering on another thread crashes the process
    // (native). The gate makes close wait for a render in flight and makes later renders do nothing.
    private val gate = RenderGate()

    suspend fun render(index: Int, widthPx: Int): ImageBitmap? = gate.run {
        withContext(Dispatchers.Default) {
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val h = (widthPx * page.height.toFloat() / page.width.toFloat()).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }

    /** Returns at once; the renderer and file are closed after any render in flight has finished. */
    fun close() {
        gate.close {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        /** Null when the file is not a PDF Android can open. */
        fun open(file: File): PdfPageSource? = runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfPageSource(pfd, PdfRenderer(pfd))
            } catch (e: Exception) {
                pfd.close()
                null
            }
        }.getOrNull()
    }
}

/**
 * Stateless PDF preview: pages scroll top to bottom, "Page x of n" follows the scroll, Done closes.
 * [pages] holds the rendered bitmaps by page index; [onNeedPage] asks for one that is about to show.
 */
@Composable
fun PdfPreviewContent(preview: PdfPreviewState, onNeedPage: (Int) -> Unit, onDone: () -> Unit) {
    val listState = rememberLazyListState()
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    Surface(Modifier.fillMaxSize().testTag(ReportSubmitTags.PREVIEW), color = BitzerColors.Grey30) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = BitzerColors.White, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(preview.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Page ${current.coerceAtMost(preview.pageCount.coerceAtLeast(1))} of ${preview.pageCount}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag(ReportSubmitTags.PAGE_COUNTER),
                            )
                        }
                        Button(
                            onClick = onDone,
                            modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.DONE),
                            colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
                        ) { Text("Done") }
                    }
                    preview.notes.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(preview.pageCount) { index ->
                    LaunchedEffect(index) { onNeedPage(index) }
                    val bmp = preview.pages[index]
                    Box(
                        Modifier.widthIn(max = 900.dp).fillMaxWidth()
                            .aspectRatio(bmp?.let { it.width.toFloat() / it.height } ?: (8.5f / 11f))
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(bmp, contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(color = BitzerColors.Green)
                        }
                    }
                }
            }
        }
    }
}
