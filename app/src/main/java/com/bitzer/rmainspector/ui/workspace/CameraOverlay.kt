package com.bitzer.rmainspector.ui.workspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitzer.rmainspector.ui.theme.BitzerColors
import java.io.File

object CameraTags {
    const val OVERLAY = "camera_overlay"
    const val SHUTTER = "camera_shutter"
    const val DONE = "camera_done"
    const val ALLOW = "camera_allow"
    const val SETTINGS = "camera_settings"
}

/**
 * Full-screen camera (CameraX) over the workspace. The shutter hands the raw JPEG to [WorkspaceCallbacks.onPhotoCaptured];
 * the view model scales, rotates and stores it. The camera stays open for more shots (a row often needs several);
 * Done closes it. A retake closes itself after one shot.
 */
@Composable
internal fun CameraOverlay(photos: PhotosState, cb: WorkspaceCallbacks) {
    val context = LocalContext.current
    BackHandler(onBack = cb.onCloseCamera)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }

    Box(Modifier.fillMaxSize().background(BitzerColors.Black).testTag(CameraTags.OVERLAY)) {
        // Back from the system settings screen: pick up a permission that was turned on there.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val now = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (now != granted) { granted = now; if (now) denied = false }
        }
        if (!granted) {
            CameraPermissionPrompt(
                denied = denied,
                onAllow = { ask.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onCancel = cb.onCloseCamera,
            )
            return@Box
        }
        CameraPreviewAndShutter(photos, cb)
    }
}

/** Permission screen. After a denial the system may no longer show its dialog ("don't ask again"), so Settings is offered too. */
@Composable
internal fun BoxScope.CameraPermissionPrompt(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (denied) "Camera permission was denied. Turn on Camera for RMA Inspector in the tablet's app settings, then come back to take photos." else "The app needs the camera to take inspection photos.",
            style = MaterialTheme.typography.bodyLarge, color = BitzerColors.White)
        if (denied) Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 56.dp).testTag(CameraTags.SETTINGS)) { Text("Open app settings") }
        else Button(onClick = onAllow, modifier = Modifier.heightIn(min = 56.dp).testTag(CameraTags.ALLOW)) { Text("Allow camera") }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel", color = BitzerColors.White) }
    }
}

@Composable
private fun BoxScope.CameraPreviewAndShutter(photos: PhotosState, cb: WorkspaceCallbacks) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var error by remember { mutableStateOf<String?>(null) }
    var shooting by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // Back camera; a tablet with only a front camera uses that.
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            } catch (e: Exception) {
                error = "The camera couldn't be started. Another app may be using it; close it and reopen the camera."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { if (future.isDone) future.get().unbindAll() } }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    Row(Modifier.fillMaxWidth().align(Alignment.TopStart).background(BitzerColors.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = cb.onCloseCamera, modifier = Modifier.heightIn(min = 56.dp).testTag(CameraTags.DONE)) {
            Text(if (photos.capture is CaptureTarget.Retake) "Cancel" else "Done", color = BitzerColors.White, style = MaterialTheme.typography.titleSmall)
        }
        Text(captureTitle(photos), style = MaterialTheme.typography.bodyLarge, color = BitzerColors.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (photos.takenThisSession > 0) Text("${photos.takenThisSession} saved", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Green50, modifier = Modifier.padding(end = 12.dp))
    }
    (error ?: photos.message)?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.White,
            modifier = Modifier.align(Alignment.Center).background(BitzerColors.Error).padding(12.dp))
    }
    IconButton(
        onClick = {
            if (shooting || photos.busy) return@IconButton
            shooting = true
            error = null
            // No space for even the temporary file, or the camera was lost: say so instead of crashing.
            val out = try { File.createTempFile("capture", ".jpg", context.cacheDir) } catch (e: java.io.IOException) {
                shooting = false; error = "The tablet is almost out of storage. Free some space, then try again."; return@IconButton
            }
            try {
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { shooting = false; cb.onPhotoCaptured(out) }
                        override fun onError(exception: ImageCaptureException) { shooting = false; out.delete(); error = "The photo couldn't be taken. Try again." }
                    },
                )
            } catch (e: Exception) {
                shooting = false; out.delete(); error = "The photo couldn't be taken. Try again."
            }
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(80.dp).background(BitzerColors.White, CircleShape).testTag(CameraTags.SHUTTER),
    ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo", tint = BitzerColors.Black, modifier = Modifier.size(40.dp)) }
}

private fun captureTitle(p: PhotosState): String = when (val c = p.capture) {
    is CaptureTarget.Row -> "Part photo"
    is CaptureTarget.Rma -> c.tag + (c.tagText?.let { " – $it" } ?: "")
    is CaptureTarget.Retake -> "Retake"
    null -> ""
}
