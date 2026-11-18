package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.PhotoGroup
import com.bitzer.rmainspector.data.PhotoGroups
import com.bitzer.rmainspector.data.PhotoItem
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** Test tags of the Photos tab. */
object PhotosTags {
    const val ADD_RMA = "photos_add_rma"
    const val TAG_PICKER = "photos_tag_picker"
    const val TAG_OPEN_CAMERA = "photos_tag_open_camera"
    const val TAG_TEXT = "photos_tag_text"
    const val RETAKE = "photo_retake"
    const val DELETE = "photo_delete"
    const val ROW_TAKE = "finding_row_take_photo"
    fun group(key: String) = "photos_group_$key"
    fun item(id: String) = "photo_item_$id"
    fun tag(tag: String) = "photos_tag_$tag"
}

/** Tags offered when the server sends none (api §4 `photo_tags`). */
val DEFAULT_PHOTO_TAGS = listOf("As received", "Nameplate", "Overview", "Packaging/Freight", "Other")

private const val OTHER_TAG = "Other"
private const val TAG_TEXT_LIMIT = 40

// ------------------------------------------------------------------------------------------------ Tile

/** One picture: loaded from the device or the server; a placeholder while loading, offline or in previews. */
@Composable
internal fun PhotoThumb(item: PhotoItem, modifier: Modifier = Modifier, full: Boolean = false) {
    val loader = LocalPhotoThumbs.current
    val bitmap by produceState<ImageBitmap?>(null, item.id, full) { value = runCatching { loader.load(item.source, full) }.getOrNull() }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(BitzerColors.Grey10), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = item.displayName, contentScale = if (full) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Image, contentDescription = item.displayName, tint = BitzerColors.Grey50, modifier = Modifier.size(36.dp))
        }
        if (item.local != null) {
            Text("NOT SENT", style = MaterialTheme.typography.labelSmall, color = BitzerColors.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(BitzerColors.Warning, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ List

/** Photos grouped by part (spec §4.4): RMA photos first, then one group per parts row; "Add RMA photo" is always visible. */
@Composable
internal fun PhotosList(state: WorkspaceState, cb: WorkspaceCallbacks) {
    val groups = remember(state.findingRows, state.photos.local, state.photos.server) { state.photoGroups }
    val editable = state.photosEditable
    val total = groups.sumOf { it.items.size }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PHOTOS", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
            Text("   $total", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60, modifier = Modifier.weight(1f))
            if (editable) {
                Button(onClick = cb.onAddRmaPhoto, modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.ADD_RMA)) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null); Text("  RMA photo")
                }
            }
        }
        state.photos.serverError?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Server photos not shown: $it", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Warning, modifier = Modifier.weight(1f))
                TextButton(onClick = cb.onReloadPhotos) { Icon(Icons.Filled.Refresh, contentDescription = null); Text(" Retry") }
            }
        }
        state.photos.message?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = BitzerColors.Error, modifier = Modifier.weight(1f))
                TextButton(onClick = cb.onDismissPhotoMessage) { Text("OK") }
            }
        }
        HorizontalDivider(color = BitzerColors.Grey30)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { g ->
                item(key = "h-" + g.key, span = { GridItemSpan(maxLineSpan) }) { GroupHeader(g, editable, cb) }
                if (g.items.isEmpty()) {
                    item(key = "e-" + g.key, span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (g.isRma) "No RMA photos yet." else "No photos for this part yet.",
                            style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    }
                } else {
                    items(g.items, key = { g.key + "/" + it.id }) { item -> PhotoTile(item, item.id == state.selection) { cb.onSelect(item.id) } }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(g: PhotoGroup, editable: Boolean, cb: WorkspaceCallbacks) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(PhotosTags.group(g.key)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.title, style = MaterialTheme.typography.titleSmall, color = BitzerColors.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            g.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60) }
        }
        if (editable && g.rowLocalId != null) {
            OutlinedButton(onClick = { cb.onTakePhoto(g.rowLocalId) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null); Text("  Take photo")
            }
        }
    }
}

@Composable
private fun PhotoTile(item: PhotoItem, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).testTag(PhotosTags.item(item.id))) {
        PhotoThumb(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f)
            .border(if (selected) 3.dp else 1.dp, if (selected) BitzerColors.Green else BitzerColors.Grey30, RoundedCornerShape(6.dp)))
        Text(item.displayName, style = MaterialTheme.typography.bodySmall, color = if (item.name == null) BitzerColors.Warning else BitzerColors.Grey90,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------------------------------------- Detail

@Composable
internal fun PhotosDetail(state: WorkspaceState, cb: WorkspaceCallbacks) {
    val item = state.photoItem(state.selection)
    if (item == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Select a photo to see it here.", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
            Spacer(Modifier.size(8.dp))
            Text(if (state.photosEditable) "Take part photos from the group headers, or tap RMA photo." else "This RMA is read only.",
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhotoThumb(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f), full = true)
        Column {
            Text(if (item.isPreview) "FILE NAME (PREVIEW — THE SERVER MAKES THE FINAL NAME)" else "FILE NAME", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
            Text(item.displayName, style = MaterialTheme.typography.bodyLarge, color = if (item.name == null) BitzerColors.Warning else BitzerColors.Black)
        }
        Text(if (item.local != null) "Stored on this tablet — not sent yet. It is sent when you submit the inspection."
        else "On the server (taken at the desk or sent earlier).", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
        if (item.canEdit && state.photosEditable) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { cb.onRetakePhoto(item.id) }, modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.RETAKE)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null); Text("  Retake")
                }
                OutlinedButton(
                    onClick = { cb.onDeletePhoto(item.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BitzerColors.Error),
                    border = BorderStroke(1.dp, BitzerColors.Error),
                    modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.DELETE),
                ) { Icon(Icons.Filled.DeleteOutline, contentDescription = null); Text("  Delete") }
            }
        }
    }
}

// ----------------------------------------------------------------------------------- Row photos (sheet)

/** Photos of one parts row inside the part sheet: thumbnails, filename previews, Take photo (spec §4.3 / §4.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RowPhotos(state: WorkspaceState, rowLocalId: String, cb: WorkspaceCallbacks) {
    val items = remember(state.findingRows, state.photos.local, state.photos.server) {
        state.photoGroups.firstOrNull { it.rowLocalId == rowLocalId }?.items.orEmpty()
    }
    val editable = state.photosEditable
    Column(Modifier.testTag(FindingsTags.ROW_PHOTOS)) {
        Text("PHOTOS FOR THIS ROW", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
        Spacer(Modifier.size(4.dp))
        Column(
            Modifier.fillMaxWidth().border(1.dp, BitzerColors.Grey30, RoundedCornerShape(4.dp)).background(BitzerColors.Grey10).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) {
                Text(if (editable) "No photos yet." else "No photos for this row.", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Column(Modifier.width(120.dp)) {
                            PhotoThumb(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                            Text(item.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (item.name == null) BitzerColors.Warning else BitzerColors.Grey90)
                        }
                    }
                }
            }
            if (editable) {
                Button(onClick = { cb.onTakePhoto(rowLocalId) }, modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.ROW_TAKE)) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null); Text("  Take photo")
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Tag picker

/**
 * Tag picker for an RMA-level photo, as an overlay over the workspace (not a system dialog, so screenshots show it).
 * Shows the preview of the file name; "Other" asks for a short text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagPickerOverlay(state: WorkspaceState, cb: WorkspaceCallbacks) {
    val picker = state.photos.tagPicker ?: return
    val tags = state.findings.codes?.photoTags.orEmpty().ifEmpty { DEFAULT_PHOTO_TAGS }
    val limit = state.findings.codes?.limits?.get("photo_tag_text") ?: TAG_TEXT_LIMIT
    val isOther = picker.tag.equals(OTHER_TAG, ignoreCase = true)
    val ready = picker.tag != null && (!isOther || picker.tagText.isNotBlank())
    val preview = if (ready) PhotoGroups.rmaPreview(state.photos.local, state.photos.server, picker.tag!!, picker.tagText.takeIf { isOther }) else null
    Box(Modifier.fillMaxSize().background(BitzerColors.Black.copy(alpha = 0.45f)).clickable(onClick = cb.onCancelTag), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = BitzerColors.White, shadowElevation = 8.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).clickable(enabled = false) {}.testTag(PhotosTags.TAG_PICKER),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("RMA PHOTO", style = MaterialTheme.typography.headlineSmall, color = BitzerColors.Black)
                Text("What is this a photo of?", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        FilterChip(selected = picker.tag == t, onClick = { cb.onSelectTag(t) }, label = { Text(t, style = MaterialTheme.typography.titleSmall) },
                            modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.tag(t)))
                    }
                }
                if (isOther) {
                    OutlinedTextField(
                        value = picker.tagText, onValueChange = { cb.onTagText(it.take(limit)) }, singleLine = true,
                        label = { Text("Describe the photo") },
                        supportingText = { Text("${picker.tagText.length} / $limit") },
                        modifier = Modifier.fillMaxWidth().testTag(PhotosTags.TAG_TEXT),
                    )
                }
                Column {
                    Text("FILE NAME (PREVIEW)", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                    Text(preview ?: "Choose a tag to see the name", style = MaterialTheme.typography.bodyLarge,
                        color = if (preview == null) BitzerColors.Grey60 else BitzerColors.Black)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = cb.onCancelTag, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = cb.onConfirmTag, enabled = ready, modifier = Modifier.heightIn(min = 56.dp).testTag(PhotosTags.TAG_OPEN_CAMERA)) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null); Text("  Open camera")
                    }
                }
            }
        }
    }
}
