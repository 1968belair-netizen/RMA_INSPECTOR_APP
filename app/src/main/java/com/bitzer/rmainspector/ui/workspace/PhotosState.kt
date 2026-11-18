package com.bitzer.rmainspector.ui.workspace

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.bitzer.rmainspector.data.LocalPhoto
import com.bitzer.rmainspector.data.PhotoGroup
import com.bitzer.rmainspector.data.PhotoGroups
import com.bitzer.rmainspector.data.PhotoItem
import com.bitzer.rmainspector.data.PhotoSource
import com.bitzer.rmainspector.data.ServerPhoto

/** What the camera is taking a photo for. */
sealed interface CaptureTarget {
    /** A part photo for the parts row [localId]. */
    data class Row(val localId: String) : CaptureTarget
    /** An RMA-level photo with its tag (and free text for "Other"). */
    data class Rma(val tag: String, val tagText: String?) : CaptureTarget
    /** Replace the not-yet-sent photo [photoId]; the camera closes after one shot. */
    data class Retake(val photoId: String) : CaptureTarget
}

/** The tag picker shown before an RMA-level photo is taken (As received, Nameplate, …, Other + text). */
data class TagPickerState(val tag: String? = null, val tagText: String = "")

/**
 * Photos tab state (spec §4.4).
 * - [local]: photos taken on this tablet (stored before anything is sent); [server]: `All Pics` as the server listed
 *   them (desk photos too). [serverError] is set when the list couldn't be loaded (offline) — local photos still work.
 * - [capture]: set while the camera is open; [takenThisSession] counts shots since it opened.
 */
data class PhotosState(
    val local: List<LocalPhoto> = emptyList(),
    val server: List<ServerPhoto> = emptyList(),
    val serverLoaded: Boolean = false,
    val serverError: String? = null,
    val capture: CaptureTarget? = null,
    val takenThisSession: Int = 0,
    val tagPicker: TagPickerState? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Loads thumbnails: local files from the device, desk photos from the server (api §8 `size=thumb`). */
interface PhotoThumbLoader {
    suspend fun load(source: PhotoSource, full: Boolean = false): ImageBitmap?
}

/** Default (previews, screenshot tests): no pictures, tiles show a placeholder. */
val LocalPhotoThumbs = compositionLocalOf<PhotoThumbLoader> { object : PhotoThumbLoader { override suspend fun load(source: PhotoSource, full: Boolean): ImageBitmap? = null } }

/** Photos can be added: not read only and the product type allows photos. */
val WorkspaceState.photosEditable: Boolean get() = !readOnly && rma?.capabilities?.photos == true

val WorkspaceState.photoGroups: List<PhotoGroup> get() = PhotoGroups.build(findingRows, photos.local, photos.server)

/** Photos per row for the "📷 n" badge. */
val WorkspaceState.photoCounts: Map<String, Int> get() = PhotoGroups.countsByRow(findingRows, photos.local, photos.server)

fun WorkspaceState.photoItem(id: String?): PhotoItem? = if (id == null) null else photoGroups.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.id == id } }
