package com.bitzer.rmainspector.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bitzer.rmainspector.data.ScanParsing
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera search (docs/spec.md §3.1). CameraX preview + ML Kit **bundled** barcode and text models: everything runs on
 * the tablet, works offline, and only the resulting search text is handed back via [onResult].
 */
@Composable
fun ScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(hasPermission(context)) }
    var blocked by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(ScanMode.BARCODE) } // survives a tablet rotation
    var cameraError by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var override by remember { mutableStateOf<ScannerState?>(null) } // Found / PickLine / NothingRead
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) blocked = true
    }
    LaunchedEffect(Unit) { if (hasCamera && !granted) launcher.launch(Manifest.permission.CAMERA) }
    // Back from the system settings screen: pick up a permission that was turned on there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = hasPermission(context)
        if (now != granted) { granted = now; if (now) blocked = false }
    }

    // Barcode found: show it for a moment, then hand it back so the search runs.
    val found = override as? ScannerState.Found
    val latestResult by rememberUpdatedState(onResult)
    LaunchedEffect(found) {
        if (found != null) { delay(600); latestResult(found.value) }
    }

    val state: ScannerState = when {
        !hasCamera -> ScannerState.NoCamera
        !granted -> ScannerState.NeedsPermission(blocked)
        override != null -> override!!
        cameraError -> ScannerState.CameraUnavailable
        else -> ScannerState.Scanning(mode, reading)
    }

    val capture = remember { AtomicBoolean(false) }
    val currentMode = remember { java.util.concurrent.atomic.AtomicReference(ScanMode.BARCODE) }
    currentMode.set(mode)
    val active = granted && hasCamera && (override == null)

    ScannerContent(
        state = state,
        onModeChange = { mode = it },
        onCapture = { reading = true; capture.set(true) },
        onPickLine = onResult,
        onRetry = { override = null; reading = false; if (cameraError) { cameraError = false; attempt++ } },
        onGrantPermission = { launcher.launch(Manifest.permission.CAMERA) },
        onOpenAppSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onClose = onClose,
        preview = {
            if (active && !cameraError) key(attempt) { CameraPreview(
                currentMode = currentMode, capture = capture,
                onCameraError = { cameraError = true },
                onBarcode = { override = ScannerState.Found(it) },
                onPlate = { lines ->
                    reading = false
                    override = if (lines.isEmpty()) ScannerState.NothingRead else ScannerState.PickLine(lines)
                },
            ) }
        },
    )
}

private fun hasPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraPreview(
    currentMode: java.util.concurrent.atomic.AtomicReference<ScanMode>,
    capture: AtomicBoolean,
    onBarcode: (String) -> Unit,
    onPlate: (List<String>) -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Bundled models: no download, no network.
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val done = remember { AtomicBoolean(false) }
    val onBarcodeNow by rememberUpdatedState(onBarcode)
    val onPlateNow by rememberUpdatedState(onPlate)
    val onCameraErrorNow by rememberUpdatedState(onCameraError)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = try { providerFuture.get() } catch (e: Exception) { onCameraErrorNow(); return@addListener }
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                val media = proxy.image
                if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                if (currentMode.get() == ScanMode.BARCODE) {
                    barcodeScanner.process(image)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstNotNullOfOrNull { ScanParsing.fromBarcode(it.rawValue) }
                            if (value != null && done.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(context).execute { onBarcodeNow(value) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } else if (capture.compareAndSet(true, false)) {
                    textRecognizer.process(image)
                        .addOnSuccessListener { text ->
                            val lines = ScanParsing.candidateLines(text.text)
                            ContextCompat.getMainExecutor(context).execute { onPlateNow(lines) }
                        }
                        .addOnFailureListener {
                            ContextCompat.getMainExecutor(context).execute { onPlateNow(emptyList()) }
                        }
                        .addOnCompleteListener { proxy.close() }
                } else {
                    proxy.close()
                }
            }
            // Back camera; a tablet with only a front camera uses that. Busy / missing camera -> error screen with Try again.
            val bound = runCatching {
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("no camera")
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
            if (bound.isFailure) onCameraErrorNow()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            barcodeScanner.close()
            textRecognizer.close()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun Modifier.fillMaxSize(): Modifier = this.then(Modifier.fillMaxSize())
