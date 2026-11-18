package com.bitzer.rmainspector.ui.workspace

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitzer.rmainspector.ui.theme.BitzerColors
import java.io.File

object CameraTags {
    const val OVERLAY = "camera_overlay"
    const val SHUTTER = "camera_shutter"
    const val DONE = "camera_done"
    const val ALLOW = "camera_allow"
}

/**
 * Test hook for emulator tests (CI has no camera): when [provider] is set, the camera overlay shows a plain shutter that
 * hands the file from [provider] to the normal capture path, so scaling, storage and the guided flow run for real.
 * Always null in the real app.
 */
object CameraTestHook {
    @Volatile var provider: (() -> File)? = null
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
    CameraTestHook.provider?.let { next ->
        Box(Modifier.fillMaxSize().background(BitzerColors.Black).testTag(CameraTags.OVERLAY)) {
            Button(onClick = { cb.onPhotoCaptured(next()) }, modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).heightIn(min = 56.dp).testTag(CameraTags.SHUTTER)) { Text("Take photo") }
        }
        return
    }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }

    Box(Modifier.fillMaxSize().background(BitzerColors.Black).testTag(CameraTags.OVERLAY)) {
        if (!granted) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (denied) "Camera permission was denied. Allow it in the tablet's app settings to take photos." else "The app needs the camera to take inspection photos.",
                    style = MaterialTheme.typography.bodyLarge, color = BitzerColors.White)
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 56.dp).testTag(CameraTags.ALLOW)) { Text("Allow camera") }
                TextButton(onClick = cb.onCloseCamera, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel", color = BitzerColors.White) }
            }
            return@Box
        }
        CameraPreviewAndShutter(photos, cb)
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
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            } catch (e: Exception) {
                error = "The camera isn't available on this tablet."
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
            val out = File.createTempFile("capture", ".jpg", context.cacheDir)
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { shooting = false; cb.onPhotoCaptured(out) }
                    override fun onError(exception: ImageCaptureException) { shooting = false; out.delete(); error = "The photo couldn't be taken. Try again." }
                },
            )
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
