package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.data.ReportPick
import com.bitzer.rmainspector.ui.theme.BitzerColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What the Report-tab photo viewer shows (issue #82). Report photos are picked here (#90): an "Add to report" /
 * "Remove from report" button for the shown photo, only when [canPick] (report editable).
 * - [photos]: `All Pics` as the server listed them; [picks]: the report photos (`Report Pics ` set).
 * - [reportOnly]: false = "All photos" (default), true = "Report photos". Remembered per RMA by the view model.
 * - [selected]: [ViewerPhoto.key] of the photo to show; null = the first one. Changing it moves the pager.
 */
data class ReportPhotoViewerState(
    val photos: List<PhotoEntry> = emptyList(),
    val picks: List<ReportPick> = emptyList(),
    val reportOnly: Boolean = false,
    val selected: String? = null,
    val load: ReportPhotosLoad = ReportPhotosLoad.Loaded,
    val canPick: Boolean = false,
)

/** Viewer actions; defaults do nothing so previews and screenshots can pass `ReportPhotoViewerCallbacks()`. */
data class ReportPhotoViewerCallbacks(
    /** The visible photo settled on [ViewerPhoto.key] (swipe, arrows, filmstrip or keys). */
    val onSelect: (key: String) -> Unit = {},
    val onSetReportOnly: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** Add / remove the shown photo (its file name) from the report (#90). */
    val onTogglePick: (filename: String) -> Unit = {},
    /** Same loader as the rest of the Report tab (`ReportCallbacks.loadImage`); null = placeholder. */
    val loadImage: suspend (folder: String, filename: String, thumb: Boolean) -> ImageBitmap? = { _, _, _ -> null },
)

object ReportPhotoViewerTags {
    const val ROOT = "viewer_root"
    const val PAGER = "viewer_pager"
    const val FILMSTRIP = "viewer_filmstrip"
    const val COUNTER = "viewer_counter"
    const val CAPTION = "viewer_caption"
    const val PREV = "viewer_prev"
    const val NEXT = "viewer_next"
    const val TOGGLE_ALL = "viewer_toggle_all"
    const val TOGGLE_REPORT = "viewer_toggle_report"
    const val EMPTY = "viewer_empty"
    const val TOGGLE_PICK = "viewer_toggle_pick"
}

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Large photo (swipe, arrows, pinch / double-tap zoom), counter, caption chips and a filmstrip. Stateless. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReportPhotoViewerContent(state: ReportPhotoViewerState, callbacks: ReportPhotoViewerCallbacks, modifier: Modifier = Modifier) {
    val visible = remember(state.photos, state.picks, state.reportOnly) {
        ReportPhotoCaptions.viewerPhotos(state.photos, state.picks, state.reportOnly)
    }
    val focus = remember { FocusRequester() }
    val stepper = remember { object { var go: ((Int) -> Unit)? = null } }
    Column(
        modifier.fillMaxSize().testTag(ReportPhotoViewerTags.ROOT)
            .onKeyEvent { e ->
                // Only reached while the viewer (not a text field) has focus.
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionLeft -> stepper.go?.let { it(-1); true } ?: false
                    Key.DirectionRight -> stepper.go?.let { it(1); true } ?: false
                    else -> false
                }
            }
            .focusRequester(focus).focusable(),
    ) {
        ViewerToggle(state, callbacks)
        when {
            visible.isEmpty() && state.load is ReportPhotosLoad.Loading ->
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("  Loading photos…", style = MaterialTheme.typography.bodyMedium)
                }
            visible.isEmpty() && state.load is ReportPhotosLoad.Error -> Column(Modifier.padding(horizontal = 16.dp)) {
                Text(state.load.message, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Error)
                TextButton(onClick = callbacks.onRetry, modifier = Modifier.heightIn(min = 56.dp)) { Text("Retry") }
            }
            visible.isEmpty() -> Text(
                if (state.reportOnly) "No report photos picked yet. Choose All photos to see every photo."
                else "No photos for this RMA yet.",
                style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60,
                modifier = Modifier.padding(16.dp).testTag(ReportPhotoViewerTags.EMPTY),
            )
            // New pager per set, so the toggle starts on the same photo when it is in both sets.
            else -> key(state.reportOnly) {
                ViewerBody(visible, state, callbacks, focus, registerStep = { stepper.go = it })
            }
        }
    }
}

@Composable
private fun ViewerToggle(state: ReportPhotoViewerState, cb: ReportPhotoViewerCallbacks) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = !state.reportOnly, onClick = { cb.onSetReportOnly(false) },
            label = { Text("All photos (${state.photos.size})") },
            modifier = Modifier.heightIn(min = 56.dp).testTag(ReportPhotoViewerTags.TOGGLE_ALL),
        )
        FilterChip(
            selected = state.reportOnly, onClick = { cb.onSetReportOnly(true) },
            label = { Text("Report photos (${state.picks.distinctBy { it.filename }.size})") },
            modifier = Modifier.heightIn(min = 56.dp).testTag(ReportPhotoViewerTags.TOGGLE_REPORT),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ViewerBody(
    visible: List<ViewerPhoto>,
    state: ReportPhotoViewerState,
    cb: ReportPhotoViewerCallbacks,
    focus: FocusRequester,
    registerStep: (((Int) -> Unit)) -> Unit,
) {
    val latest by rememberUpdatedState(visible)
    val latestCb by rememberUpdatedState(cb)
    val pager = rememberPagerState(initialPage = ReportPhotoCaptions.indexOfKey(visible, state.selected).coerceAtLeast(0)) { latest.size }
    val scope = rememberCoroutineScope()
    var zoomedKey by remember { mutableStateOf<String?>(null) }
    val page = pager.currentPage.coerceIn(0, visible.lastIndex)
    val current = visible[page]
    val picked = remember(state.picks) { state.picks.flatMap { listOf(it.filename, it.targetName) }.toSet() }

    fun go(delta: Int) {
        val target = (pager.currentPage + delta).coerceIn(0, latest.lastIndex)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }
    registerStep { go(it) }

    // Pager -> owner (after the scroll has settled), owner -> pager (e.g. the parts list picked a photo).
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p -> latest.getOrNull(p)?.let { latestCb.onSelect(it.key) } }
    }
    LaunchedEffect(state.selected) {
        val idx = ReportPhotoCaptions.indexOfKey(latest, state.selected)
        if (state.selected != null && idx >= 0 && idx != pager.settledPage && !pager.isScrollInProgress) pager.animateScrollToPage(idx)
    }

    Box(Modifier.fillMaxWidth().weight(1f).background(BitzerColors.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = zoomedKey != current.key,
            pageSpacing = 8.dp,
            key = { latest.getOrNull(it)?.key ?: it },
            modifier = Modifier.fillMaxSize().testTag(ReportPhotoViewerTags.PAGER),
        ) { i ->
            val photo = latest.getOrNull(i)
            if (photo != null) {
                ZoomablePhoto(photo, cb, onZoomed = { k, z -> zoomedKey = if (z) k else if (zoomedKey == k) null else zoomedKey },
                    onTap = { runCatching { focus.requestFocus() } })
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).testTag(ReportPhotoViewerTags.COUNTER),
        ) {
            Text(ReportPhotoCaptions.counter(page, visible.size), color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        if (state.canPick) {
            val isPicked = current.filename in picked
            Button(
                onClick = { latestCb.onTogglePick(current.filename) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).heightIn(min = 56.dp)
                    .testTag(ReportPhotoViewerTags.TOGGLE_PICK),
                colors = ButtonDefaults.buttonColors(containerColor = if (isPicked) BitzerColors.Grey90 else BitzerColors.Green),
            ) { Text(if (isPicked) "Remove from report" else "Add to report") }
        }
        ArrowButton(left = true, enabled = page > 0, modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)) { go(-1) }
        ArrowButton(left = false, enabled = page < visible.lastIndex, modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)) { go(1) }
    }
    CaptionBar(current)
    Filmstrip(visible, page, picked, cb) { i -> scope.launch { pager.animateScrollToPage(i) } }
}

@Composable
private fun ArrowButton(left: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.size(56.dp).background(Color.Black.copy(alpha = if (enabled) 0.55f else 0.2f), CircleShape)
            .testTag(if (left) ReportPhotoViewerTags.PREV else ReportPhotoViewerTags.NEXT),
    ) {
        Icon(
            if (left) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (left) "Previous photo" else "Next photo",
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(36.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ Caption chips

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionBar(photo: ViewerPhoto) {
    val c = remember(photo.filename) { ReportPhotoCaptions.parse(photo.filename) }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ReportPhotoViewerTags.CAPTION),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            c.rmaLevel -> {
                CaptionChip("RMA-level photo", BitzerColors.DarkGreen, Color.White)
                c.rmaTag?.let { CaptionChip(it, BitzerColors.Green10, BitzerColors.DarkGreen) }
            }
            c.badge == null -> CaptionChip(c.text, BitzerColors.Grey10, BitzerColors.Grey90)
            else -> {
                val pos = c.badge.substringBefore('-')
                CaptionChip(listOfNotNull(pos, c.partDescription).joinToString("  "), BitzerColors.Green10, BitzerColors.DarkGreen)
                c.issueLabel?.let { CaptionChip(it, BitzerColors.Grey10, BitzerColors.Grey90) }
            }
        }
    }
}

@Composable
private fun CaptionChip(text: String, background: Color, content: Color) {
    Surface(color = background, shape = RoundedCornerShape(20.dp)) {
        Text(text, color = content, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ------------------------------------------------------------------------------------------------ Filmstrip

@Composable
private fun Filmstrip(visible: List<ViewerPhoto>, page: Int, picked: Set<String>, cb: ReportPhotoViewerCallbacks, onTap: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(page) {
        val shown = list.layoutInfo.visibleItemsInfo
        if (shown.isEmpty() || page <= shown.first().index || page >= shown.last().index) list.animateScrollToItem((page - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = list, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(ReportPhotoViewerTags.FILMSTRIP),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(visible, key = { _, p -> p.key }) { i, p ->
            val selected = i == page
            val badge = remember(p.filename) { ReportPhotoCaptions.parse(p.filename).badge ?: "" }
            Box(
                Modifier.width(104.dp).height(78.dp)
                    .border(if (selected) 3.dp else 1.dp, if (selected) BitzerColors.Green else BitzerColors.Grey30, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Show photo ${i + 1}") { onTap(i) }
                    .semantics { contentDescription = "Photo ${i + 1}" },
            ) {
                ViewerImage(p, thumbOnly = true, cb, Modifier.fillMaxSize().padding(if (selected) 3.dp else 1.dp), ContentScale.Crop)
                if (badge.isNotEmpty()) {
                    Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
                if (p.filename in picked) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "In the report", tint = BitzerColors.Green,
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.White, CircleShape))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Zoom + image

/** One page: the photo with pinch zoom, pan while zoomed and double-tap zoom. At scale 1 a single finger is left to the pager. */
@Composable
private fun ZoomablePhoto(photo: ViewerPhoto, cb: ReportPhotoViewerCallbacks, onZoomed: (String, Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember(photo.key) { mutableFloatStateOf(1f) }
    var offset by remember(photo.key) { mutableStateOf(Offset.Zero) }
    var box by remember(photo.key) { mutableStateOf(IntSize.Zero) }
    DisposableEffect(photo.key) { onDispose { onZoomed(photo.key, false) } }
    LaunchedEffect(photo.key, scale > 1f) { onZoomed(photo.key, scale > 1f) }

    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f) return Offset.Zero
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .pointerInput(photo.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            val c = Offset(box.width / 2f, box.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp((c - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(photo.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            if (centroid.isSpecified) {
                                val c = Offset(box.width / 2f, box.height / 2f)
                                val moved = (centroid - c) - ((centroid - c) - offset) * (newScale / scale)
                                offset = clamp(moved + pan, newScale)
                            } else offset = clamp(offset + pan, newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ViewerImage(
            photo, thumbOnly = false, cb,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            ContentScale.Fit, large = true,
        )
    }
}

private suspend fun safeLoad(cb: ReportPhotoViewerCallbacks, p: ViewerPhoto, thumb: Boolean): ImageBitmap? =
    try { cb.loadImage(p.folder, p.filename, thumb) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

/**
 * A photo through the Report tab's loader: thumbnail first, then the full picture; a grey tile while loading and a
 * placeholder (never a crash) when it can't be loaded, e.g. offline.
 */
@Composable
private fun ViewerImage(photo: ViewerPhoto, thumbOnly: Boolean, cb: ReportPhotoViewerCallbacks, modifier: Modifier, fit: ContentScale, large: Boolean = false) {
    var thumb by remember(photo.key) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(photo.key) { mutableStateOf<ImageBitmap?>(null) }
    var done by remember(photo.key) { mutableStateOf(false) }
    LaunchedEffect(photo.key, thumbOnly) {
        thumb = safeLoad(cb, photo, true)
        if (!thumbOnly) full = safeLoad(cb, photo, false)
        done = true
    }
    val img = full ?: thumb
    if (img != null) {
        Image(img, contentDescription = photo.filename.removeSuffix(".jpg"), contentScale = fit, modifier = modifier)
    } else {
        Box(modifier.background(BitzerColors.Grey10), contentAlignment = Alignment.Center) {
            when {
                !done -> CircularProgressIndicator(Modifier.size(if (large) 32.dp else 20.dp), strokeWidth = 2.dp)
                large -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = BitzerColors.Grey50, modifier = Modifier.size(64.dp))
                    Text("Photo not available", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60, textAlign = TextAlign.Center)
                    Text("Check the connection and try again.", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60, textAlign = TextAlign.Center)
                }
                else -> Icon(Icons.Filled.Image, contentDescription = photo.filename, tint = BitzerColors.Grey50, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** The viewer's state from the Report tab's state (used by the layout issue; the viewer itself stays stateless). */
fun ReportState.viewerState(readOnly: Boolean = true) = ReportPhotoViewerState(
    photos = photos, picks = draft.picks, reportOnly = viewerReportOnly, selected = viewerSelected, load = photosLoad,
    canPick = !readOnly,
)

fun ReportCallbacks.viewerCallbacks() = ReportPhotoViewerCallbacks(
    onSelect = onViewerSelect, onSetReportOnly = onViewerReportOnly, onRetry = onRetryPhotos, onTogglePick = onTogglePick, loadImage = loadImage,
)
