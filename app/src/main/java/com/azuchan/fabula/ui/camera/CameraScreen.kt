package com.azuchan.fabula.ui.camera

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.IOException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.azuchan.fabula.camera.Camera2PreviewController
import com.azuchan.fabula.camera.CameraCaptureEvent
import com.azuchan.fabula.camera.CameraCapabilityReport
import com.azuchan.fabula.camera.CameraExposureControls
import com.azuchan.fabula.camera.CameraExposureMode
import com.azuchan.fabula.camera.CameraFocusControls
import com.azuchan.fabula.camera.CameraFocusMode
import com.azuchan.fabula.camera.CameraFocusTap
import com.azuchan.fabula.camera.CameraPreviewState
import com.azuchan.fabula.camera.RawBurstState
import com.azuchan.fabula.camera.PhysicalCameraReport
import com.azuchan.fabula.camera.CameraWhiteBalanceControls
import com.azuchan.fabula.camera.CameraWhiteBalanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (granted) {
                permissionPermanentlyDenied = false
            } else {
                permissionPermanentlyDenied = (context as? Activity)?.let { activity ->
                    permissionRequested && !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.CAMERA,
                    )
                } == true
            }
        },
    )

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCameraPermission) permissionPermanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasCameraPermission) {
        CameraPreview()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (permissionPermanentlyDenied) {
                    "Camera access is disabled for Fabula. Enable it in app settings to use the camera."
                } else {
                    "Fabula needs camera access to display the viewfinder."
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = {
                    if (permissionPermanentlyDenied) {
                        openAppSettings(context)
                    } else {
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            ) {
                Text(if (permissionPermanentlyDenied) "Open app settings" else "Grant camera access")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CameraPreview() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as Application
    val rawFrameWriter = remember(application) { MediaStoreRawFrameWriter(application) }
    val controller = remember(context, rawFrameWriter) {
        Camera2PreviewController(context, rawFrameWriter)
    }
    val diagnosticsViewModel: CameraDiagnosticsViewModel = viewModel(
        factory = remember(application) { CameraDiagnosticsViewModel.Factory(application) },
    )
    val diagnosticsState by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
    val exportState by diagnosticsViewModel.exportState.collectAsStateWithLifecycle()
    val previewState by controller.state.collectAsState()
    val rawBurstState by controller.rawBurstState.collectAsStateWithLifecycle()
    val exposureControls by controller.exposureControls.collectAsStateWithLifecycle()
    val whiteBalanceControls by controller.whiteBalanceControls.collectAsStateWithLifecycle()
    val focusControls by controller.focusControls.collectAsStateWithLifecycle()
    val focusTap by controller.focusTap.collectAsStateWithLifecycle()

    var currentHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var currentDisplayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    var currentViewWidth by remember { mutableIntStateOf(0) }
    var currentViewHeight by remember { mutableIntStateOf(0) }
    var rawBurstFrameCount by remember { mutableIntStateOf(8) }
    var captureMessage by remember { mutableStateOf<String?>(null) }
    // The 2x JPEG opens in any gallery; Android has no TIFF decoder, so the TIFF is only offered
    // when the JPEG could not be written.
    var savedSuperResolution by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var savedPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var outputOpenError by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showExposureControls by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.captureEvents.collect { event ->
            when (event) {
                is CameraCaptureEvent.JpegReady -> {
                    savedSuperResolution = null
                    savedPreviewUri = null
                    outputOpenError = null
                    captureMessage = "Saving photo…"
                    captureMessage = try {
                        saveJpegToMediaStore(context.applicationContext, event.bytes)
                        "Photo saved to Pictures/Fabula"
                    } catch (exception: Exception) {
                        "Failed to save photo: ${exception.message ?: "unknown error"}"
                    }
                }

                is CameraCaptureEvent.Failed -> {
                    captureMessage = "Capture failed: ${event.message}"
                }

                is CameraCaptureEvent.RawBurstProgress -> {
                    if (event.savedFrames == 0) {
                        savedSuperResolution = null
                        savedPreviewUri = null
                        outputOpenError = null
                    }
                }

                is CameraCaptureEvent.RawBurstFinished -> {
                    savedSuperResolution = event.superResolutionJpegUri?.let { Uri.parse(it) to "image/jpeg" }
                        ?: event.superResolutionUri?.let { Uri.parse(it) to "image/tiff" }
                    savedPreviewUri = event.fusionPreviewUri?.let(Uri::parse)
                    outputOpenError = null
                    val outputs = buildList {
                        if (event.superResolutionWidth > 0 && event.superResolutionHeight > 0) {
                            val megapixels = (event.superResolutionWidth.toLong() *
                                event.superResolutionHeight + 500_000L) / 1_000_000L
                            val formats = if (event.superResolutionJpegUri != null) "TIFF + JPEG" else "TIFF"
                            add("${megapixels} MP 2× $formats prototype (${event.superResolutionFrames} aligned frames)")
                        }
                        if (event.fusionPreviewSaved) add("fusion preview")
                    }
                    val fusionMessage = if (outputs.isNotEmpty()) {
                        " Fusion saved: ${outputs.joinToString(" + ")}."
                    } else " No fused output could be created."
                    captureMessage =
                        "RAW burst complete: ${event.savedFrames}/${event.totalFrames} DNG files saved." +
                            fusionMessage
                }
            }
        }
    }

    LaunchedEffect(focusTap?.sequence) {
        val tap = focusTap ?: return@LaunchedEffect
        delay(1_500)
        controller.clearFocusTap(tap.sequence)
    }

    DisposableEffect(lifecycleOwner, controller) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Read the snapshot state directly: when returning from the background the
                    // surface is often recreated just before ON_RESUME (so surfaceCreated skips the
                    // start), and a rememberUpdatedState copy would still hold null until the next
                    // recomposition, leaving the camera stopped.
                    currentHolder?.takeIf { it.surface.isValid && currentViewWidth > 0 && currentViewHeight > 0 }
                        ?.let { holder ->
                            controller.startPreview(
                                holder,
                                currentDisplayRotation,
                                currentViewWidth,
                                currentViewHeight,
                            )
                        }
                }
                Lifecycle.Event.ON_STOP -> controller.stopPreview()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            controller.close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val viewfinderAspectRatio =
                    (previewState as? CameraPreviewState.Streaming)?.displayAspectRatio
                        ?: if (maxWidth < maxHeight) 3f / 4f else 4f / 3f
                val viewportAspectRatio = maxWidth / maxHeight
                val viewfinderFitsWidth = viewportAspectRatio < viewfinderAspectRatio
                val viewfinderWidth = if (viewfinderFitsWidth) {
                    maxWidth
                } else {
                    maxHeight * viewfinderAspectRatio
                }
                val viewfinderHeight = if (viewfinderFitsWidth) {
                    maxWidth / viewfinderAspectRatio
                } else {
                    maxHeight
                }
                val viewfinderModifier = if (viewfinderFitsWidth) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(viewfinderAspectRatio)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(viewfinderAspectRatio)
                }

                AndroidView(
                    modifier = viewfinderModifier.align(Alignment.Center),
                    factory = { viewContext ->
                        val previewView = SurfaceView(viewContext)
                        previewView.setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP &&
                                view.width > 0 && view.height > 0
                            ) {
                                controller.tapToFocus(
                                    xFraction = event.x / view.width,
                                    yFraction = event.y / view.height,
                                )
                            }
                            true
                        }
                        previewView.holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    currentHolder = holder
                                    currentDisplayRotation =
                                        previewView.display?.rotation ?: Surface.ROTATION_0
                                    currentViewWidth = previewView.width
                                    currentViewHeight = previewView.height
                                    if (currentViewWidth > 0 && currentViewHeight > 0 &&
                                        lifecycleOwner.lifecycle.currentState
                                            .isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        controller.startPreview(
                                            holder,
                                            currentDisplayRotation,
                                            currentViewWidth,
                                            currentViewHeight,
                                        )
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    if (currentHolder === holder) {
                                        currentHolder = null
                                        controller.stopPreview()
                                    }
                                }
                            },
                        )
                        previewView.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                            val width = right - left
                            val height = bottom - top
                            if (width > 0 && height > 0) {
                                currentViewWidth = width
                                currentViewHeight = height
                                val holder = previewView.holder
                                if (holder.surface.isValid &&
                                    lifecycleOwner.lifecycle.currentState
                                        .isAtLeast(Lifecycle.State.RESUMED)
                                ) {
                                    controller.startPreview(
                                        holder,
                                        view.display?.rotation ?: Surface.ROTATION_0,
                                        width,
                                        height,
                                    )
                                }
                            }
                        }
                        previewView
                    },
                )

                focusTap?.let { tap ->
                    val markerSize = 64.dp
                    val markerX = (
                        (maxWidth - viewfinderWidth) / 2 + viewfinderWidth * tap.xFraction -
                            markerSize / 2
                        ).coerceIn(0.dp, (maxWidth - markerSize).coerceAtLeast(0.dp))
                    val markerY = (
                        (maxHeight - viewfinderHeight) / 2 + viewfinderHeight * tap.yFraction -
                            markerSize / 2
                        ).coerceIn(0.dp, (maxHeight - markerSize).coerceAtLeast(0.dp))
                    Box(
                        modifier = Modifier
                            .offset(markerX, markerY)
                            .size(markerSize)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = CircleShape,
                            ),
                    )
                }

                Text(
                    text = previewState.label(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )

                if (previewState is CameraPreviewState.Error) {
                    currentHolder?.let { holder ->
                        Button(
                            modifier = Modifier.align(Alignment.Center),
                            enabled = currentViewWidth > 0 && currentViewHeight > 0,
                            onClick = {
                                controller.startPreview(
                                    holder,
                                    currentDisplayRotation,
                                    currentViewWidth,
                                    currentViewHeight,
                                )
                            },
                        ) {
                            Text("Retry camera")
                        }
                    }
                }
            }

            captureMessage?.let { message ->
                Column(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (savedSuperResolution != null || savedPreviewUri != null) {
                        Row(horizontalArrangement = Arrangement.Center) {
                            savedSuperResolution?.let { (uri, mimeType) ->
                                TextButton(
                                    onClick = {
                                        outputOpenError = openSavedImage(context, uri, mimeType)
                                    },
                                ) { Text(if (mimeType == "image/jpeg") "Open 2× JPEG" else "Open 2× TIFF") }
                            }
                            savedPreviewUri?.let { uri ->
                                TextButton(
                                    onClick = {
                                        outputOpenError = openSavedImage(context, uri, "image/jpeg")
                                    },
                                ) { Text("Open preview") }
                            }
                        }
                    }
                    outputOpenError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            val streamingState = previewState as? CameraPreviewState.Streaming
            val rawCaptureSize = streamingState?.rawCaptureSize
            val rawBurstCapturing = rawBurstState is RawBurstState.Capturing
            val rawBurstStatus = when (val burst = rawBurstState) {
                RawBurstState.Idle -> if (rawCaptureSize != null) {
                    "RAW ${rawCaptureSize.width}×${rawCaptureSize.height} advertised"
                } else {
                    "RAW unavailable in this camera session"
                }
                is RawBurstState.Capturing -> if (burst.finalizing) {
                    if (burst.savedFrames < burst.totalFrames) {
                        "Processing RAW frames · ${burst.savedFrames}/${burst.totalFrames}"
                    } else {
                        "Reconstructing 2× TIFF + JPEG"
                    }
                } else {
                    "RAW burst · ${burst.savedFrames}/${burst.totalFrames}"
                }
                is RawBurstState.Completed ->
                    run {
                        val outputs = buildList {
                            if (burst.superResolutionWidth > 0 && burst.superResolutionHeight > 0) {
                                val megapixels = (burst.superResolutionWidth.toLong() *
                                    burst.superResolutionHeight + 500_000L) / 1_000_000L
                                val formats = if (burst.superResolutionJpegUri != null) "TIFF + JPEG" else "TIFF"
                                add("${megapixels} MP 2× $formats · ${burst.superResolutionFrames} frames")
                            }
                            if (burst.fusionPreviewSaved) add("preview")
                        }
                        "DNG files saved · ${burst.savedFrames}/${burst.totalFrames}" +
                            (if (outputs.isNotEmpty()) " · ${outputs.joinToString(" + ")}" else "") +
                            " — Pictures/Fabula/RAW_Burst_${burst.burstId}"
                    }
                is RawBurstState.Failed -> "RAW burst: ${burst.message}"
            }

            if (rawCaptureSize != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Frames",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(8, 12, 15).forEach { count ->
                        FilterChip(
                            selected = rawBurstFrameCount == count,
                            enabled = !rawBurstCapturing,
                            onClick = { rawBurstFrameCount = count },
                            label = { Text(count.toString()) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            FilledTonalButton(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                enabled = rawCaptureSize != null &&
                    !rawBurstCapturing,
                onClick = { controller.captureRawBurst(frameCount = rawBurstFrameCount) },
            ) {
                val label = (rawBurstState as? RawBurstState.Capturing)?.let {
                    if (it.finalizing) {
                        "Reconstructing 2× TIFF…"
                    } else {
                        "RAW burst · ${it.savedFrames}/${it.totalFrames}"
                    }
                } ?: "Capture $rawBurstFrameCount RAW frames"
                Text(label)
            }
            Text(
                text = rawBurstStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rawCaptureSize != null) {
                Text(
                    text = "Every DNG is kept; the TIFF uses frames with reliable alignment.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    enabled = previewState is CameraPreviewState.Streaming && !rawBurstCapturing,
                    onClick = { showExposureControls = true },
                ) {
                    Text("EV")
                }

                Button(
                    modifier = Modifier
                        .size(72.dp)
                        .semantics { contentDescription = "Take a photo" },
                    enabled = previewState is CameraPreviewState.Streaming && !rawBurstCapturing,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    onClick = controller::captureJpeg,
                ) {
                    Text("●", fontSize = 24.sp)
                }

                FilledTonalButton(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    onClick = {
                        showDiagnostics = true
                        diagnosticsViewModel.refresh()
                    },
                ) {
                    Text("i")
                }
            }
        }

        if (showDiagnostics) {
            ModalBottomSheet(onDismissRequest = { showDiagnostics = false }) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 700.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    item {
                        Text(
                            text = "Camera2 diagnostics",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "Capabilities reported by this device or emulator do not guarantee that a particular stream combination can open a session.",
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    item {
                        Button(
                            modifier = Modifier.padding(bottom = 16.dp),
                            enabled = diagnosticsState is CameraDiagnosticsUiState.Loaded &&
                                exportState !is CameraDiagnosticsExportState.Saving,
                            onClick = diagnosticsViewModel::exportToDownloads,
                        ) {
                            Text("Export report (.txt)")
                        }

                        when (val export = exportState) {
                            CameraDiagnosticsExportState.Idle,
                            CameraDiagnosticsExportState.Saving -> Unit
                            is CameraDiagnosticsExportState.Saved -> Text(
                                text = "Saved to Downloads/${export.fileName}",
                                modifier = Modifier.padding(bottom = 16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            is CameraDiagnosticsExportState.Failed -> Text(
                                text = export.message,
                                modifier = Modifier.padding(bottom = 16.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    when (val state = diagnosticsState) {
                        CameraDiagnosticsUiState.Idle -> Unit
                        CameraDiagnosticsUiState.Loading -> item {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                        is CameraDiagnosticsUiState.Failed -> item {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        is CameraDiagnosticsUiState.Loaded -> items(
                            state.reports,
                            key = { it.cameraId },
                        ) { report ->
                            CameraCapabilityCard(report)
                        }
                    }
                }
            }
        }

        if (showExposureControls) {
            ModalBottomSheet(onDismissRequest = { showExposureControls = false }) {
                ExposureControlsPanel(
                    controls = exposureControls,
                    whiteBalance = whiteBalanceControls,
                    focus = focusControls,
                    cameraReady = previewState is CameraPreviewState.Streaming,
                    onManualModeChange = controller::setManualExposureEnabled,
                    onExposureCompensationChange = controller::setExposureCompensation,
                    onManualExposureChange = controller::setManualExposure,
                    onAeLockChange = controller::setAutoExposureLock,
                    onWhiteBalancePresetChange = controller::setWhiteBalancePreset,
                    onWhiteBalanceLockChange = controller::setWhiteBalanceLock,
                    onWhiteBalanceCctChange = controller::setWhiteBalanceCct,
                    onFocusModeChange = controller::setFocusMode,
                    onFocusDistanceChange = controller::setManualFocusDistance,
                    onTriggerAutoFocus = controller::triggerAutoFocus,
                )
            }
        }
    }
}

@Composable
private fun ExposureControlsPanel(
    controls: CameraExposureControls,
    whiteBalance: CameraWhiteBalanceControls,
    focus: CameraFocusControls,
    cameraReady: Boolean,
    onManualModeChange: (Boolean) -> Unit,
    onExposureCompensationChange: (Int) -> Unit,
    onManualExposureChange: (iso: Int, exposureTimeNs: Long) -> Unit,
    onAeLockChange: (Boolean) -> Unit,
    onWhiteBalancePresetChange: (Int) -> Unit,
    onWhiteBalanceLockChange: (Boolean) -> Unit,
    onWhiteBalanceCctChange: (kelvin: Int, tint: Int) -> Unit,
    onFocusModeChange: (CameraFocusMode) -> Unit,
    onFocusDistanceChange: (Float) -> Unit,
    onTriggerAutoFocus: () -> Unit,
) {
    if (!cameraReady) {
        Text(
            text = "Camera controls are unavailable until the preview is active.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 700.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text("Exposure", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ranges come from CameraCharacteristics. Updates are sent to the Camera2 controller on its dedicated thread.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        controls.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Manual", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (controls.manualSensorSupported) {
                        "ISO and shutter-speed controls"
                    } else {
                        "MANUAL_SENSOR capability or request keys are not advertised"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = controls.mode == CameraExposureMode.MANUAL,
                enabled = controls.manualSensorSupported,
                onCheckedChange = onManualModeChange,
            )
        }

        if (controls.mode == CameraExposureMode.AUTO) {
            val range = controls.exposureCompensationRange
            val step = controls.exposureCompensationStep
            if (range != null && step != null) {
                val ev = controls.exposureCompensationIndex * step.toFloat()
                Text(
                    text = "EV ${formatSignedEv(ev)} · step $step",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = controls.exposureCompensationIndex.toFloat(),
                    onValueChange = { onExposureCompensationChange(it.roundToInt()) },
                    valueRange = range.lower.toFloat()..range.upper.toFloat(),
                    steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                )
                Text(
                    "Indices ${range.lower}…${range.upper} (EV ${formatSignedEv(range.lower * step.toFloat())}…${formatSignedEv(range.upper * step.toFloat())})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Exposure compensation is not advertised by this camera.",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (controls.autoExposureLockAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Lock AE", modifier = Modifier.weight(1f))
                    Switch(
                        checked = controls.autoExposureLocked,
                        onCheckedChange = onAeLockChange,
                    )
                }
            }
        } else {
            val isoRange = controls.isoRange
            if (isoRange != null) {
                Text(
                    "ISO ${controls.iso} · range ${isoRange.lower}–${isoRange.upper}",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = controls.iso.toFloat(),
                    onValueChange = {
                        onManualExposureChange(it.roundToInt(), controls.exposureTimeNs)
                    },
                    valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                )
            }

            val exposureRange = controls.exposureTimeRangeNs
            if (exposureRange != null) {
                Text(
                    "Shutter ${formatExposureTime(controls.exposureTimeNs)}",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = exposureSliderPosition(
                        controls.exposureTimeNs,
                        exposureRange.lower,
                        exposureRange.upper,
                    ),
                    onValueChange = { position ->
                        onManualExposureChange(
                            controls.iso,
                            exposureFromSliderPosition(
                                position,
                                exposureRange.lower,
                                exposureRange.upper,
                            ),
                        )
                    },
                    valueRange = 0f..1f,
                )
                Text(
                    "${formatExposureTime(exposureRange.lower)}…${formatExposureTime(exposureRange.upper)} (log scale)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "White balance",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        whiteBalance.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            whiteBalance.availableAwbModes
                .filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF }
                .forEach { mode ->
                    FilterChip(
                        selected = whiteBalance.mode == CameraWhiteBalanceMode.PRESET &&
                            whiteBalance.awbMode == mode,
                        onClick = { onWhiteBalancePresetChange(mode) },
                        label = { Text(awbModeLabel(mode)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            if (whiteBalance.cctSupported) {
                FilterChip(
                    selected = whiteBalance.mode == CameraWhiteBalanceMode.CCT,
                    onClick = {
                        onWhiteBalanceCctChange(
                            whiteBalance.colorTemperatureKelvin,
                            whiteBalance.colorTint,
                        )
                    },
                    label = { Text("Kelvin") },
                )
            }
        }

        if (whiteBalance.lockAvailable && whiteBalance.mode == CameraWhiteBalanceMode.PRESET) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lock AWB", modifier = Modifier.weight(1f))
                Switch(
                    checked = whiteBalance.locked,
                    onCheckedChange = onWhiteBalanceLockChange,
                )
            }
        }

        if (whiteBalance.mode == CameraWhiteBalanceMode.CCT) {
            val temperatureRange = whiteBalance.colorTemperatureRange
            if (temperatureRange != null) {
                Text(
                    "Temperature ${whiteBalance.colorTemperatureKelvin} K",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = whiteBalance.colorTemperatureKelvin.toFloat(),
                    onValueChange = { kelvin ->
                        onWhiteBalanceCctChange(kelvin.roundToInt(), whiteBalance.colorTint)
                    },
                    valueRange = temperatureRange.lower.toFloat()..temperatureRange.upper.toFloat(),
                )
                Text(
                    "Tint ${whiteBalance.colorTint}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = whiteBalance.colorTint.toFloat(),
                    onValueChange = { tint ->
                        onWhiteBalanceCctChange(
                            whiteBalance.colorTemperatureKelvin,
                            tint.roundToInt(),
                        )
                    },
                    valueRange = -50f..50f,
                    steps = 99,
                )
            }
        } else if (!whiteBalance.cctSupported) {
            Text(
                "CCT temperature/tint controls are not advertised by this camera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Focus",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        focus.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            val hasContinuousAf =
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in focus.availableAfModes ||
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in focus.availableAfModes
            if (hasContinuousAf) {
                FilterChip(
                    selected = focus.mode == CameraFocusMode.CONTINUOUS,
                    onClick = { onFocusModeChange(CameraFocusMode.CONTINUOUS) },
                    label = { Text("Continuous AF") },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (CameraMetadata.CONTROL_AF_MODE_AUTO in focus.availableAfModes) {
                FilterChip(
                    selected = focus.mode == CameraFocusMode.AUTO,
                    onClick = { onFocusModeChange(CameraFocusMode.AUTO) },
                    label = { Text("Single AF") },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (focus.manualFocusSupported) {
                FilterChip(
                    selected = focus.mode == CameraFocusMode.MANUAL,
                    onClick = { onFocusModeChange(CameraFocusMode.MANUAL) },
                    label = { Text("MF") },
                )
            }
        }
        Text(
            text = if (focus.tapFocusSupported) {
                "Tap the viewfinder to set AF/AE regions."
            } else {
                "Tap-to-focus regions are not advertised by this camera."
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (focus.mode == CameraFocusMode.AUTO &&
            CameraMetadata.CONTROL_AF_MODE_AUTO in focus.availableAfModes
        ) {
            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = onTriggerAutoFocus,
            ) {
                Text("Trigger autofocus")
            }
        }

        if (focus.mode == CameraFocusMode.MANUAL && focus.manualFocusSupported) {
            Text(
                "Focus ${formatFocusDistance(focus.focusDistanceDiopters)} · max ${formatFocusDistance(focus.minimumFocusDistanceDiopters)}",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = focus.focusDistanceDiopters,
                onValueChange = onFocusDistanceChange,
                valueRange = 0f..focus.minimumFocusDistanceDiopters,
            )
            Text(
                "0 D is infinity; the maximum value is the closest focus distance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!focus.manualFocusSupported) {
            Text(
                "Manual focus is unavailable (fixed-focus lens or missing Camera2 keys).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun awbModeLabel(mode: Int): String = when (mode) {
    CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Shade"
    else -> "Mode $mode"
}

private fun formatFocusDistance(diopters: Float): String = if (diopters <= 0f) {
    "∞"
} else {
    "${"%.1f".format(Locale.getDefault(), 1f / diopters * 100f)} cm (${"%.1f".format(Locale.getDefault(), diopters)} D)"
}

private fun exposureSliderPosition(valueNs: Long, minimumNs: Long, maximumNs: Long): Float {
    if (minimumNs <= 0L || maximumNs <= minimumNs) return 0f
    val value = valueNs.coerceIn(minimumNs, maximumNs).toDouble()
    return ((ln(value) - ln(minimumNs.toDouble())) /
        (ln(maximumNs.toDouble()) - ln(minimumNs.toDouble()))).toFloat().coerceIn(0f, 1f)
}

private fun exposureFromSliderPosition(position: Float, minimumNs: Long, maximumNs: Long): Long {
    if (minimumNs <= 0L || maximumNs <= minimumNs) return minimumNs
    val logMinimum = ln(minimumNs.toDouble())
    val logMaximum = ln(maximumNs.toDouble())
    return exp(logMinimum + (logMaximum - logMinimum) * position.coerceIn(0f, 1f))
        .roundToLong()
        .coerceIn(minimumNs, maximumNs)
}

private fun formatExposureTime(exposureTimeNs: Long): String {
    if (exposureTimeNs <= 0L) return "—"
    return if (exposureTimeNs >= 1_000_000_000L) {
        "${"%.2f".format(Locale.getDefault(), exposureTimeNs / 1_000_000_000.0)} s"
    } else {
        "1/${(1_000_000_000.0 / exposureTimeNs).roundToInt()} s"
    }
}

private fun formatSignedEv(value: Float): String =
    "${"%+.1f".format(Locale.getDefault(), value)} EV"

@Composable
private fun CameraCapabilityCard(report: CameraCapabilityReport) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Camera ${report.cameraId} · ${report.lensFacing}",
                style = MaterialTheme.typography.titleMedium,
            )

            report.error?.let { error ->
                DiagnosticRow("Erreur", error)
            } ?: run {
                DiagnosticRow("Niveau Camera2", report.hardwareLevel)
                DiagnosticRow("Capabilities", report.capabilities.ifEmpty { listOf("None reported") }.joinToString())
                DiagnosticRow("Physical camera IDs", report.physicalCameraIds.ifEmpty { listOf("None") }.joinToString())
                DiagnosticRow(
                    "Sensor array (may include calibration pixels)",
                    "${report.pixelArraySize.formatSize()} · active array ${report.activeArray ?: "not reported"} · orientation ${report.sensorOrientationDegrees ?: "?"}°",
                )
                DiagnosticRow("Focal lengths", report.focalLengthsMm.joinToString { "$it mm" }.ifBlank { "Not reported" })
                DiagnosticRow("ISO", report.isoRange?.let { "${it.lower}–${it.upper}" } ?: "Not reported")
                DiagnosticRow("Exposure time", report.exposureTimeRangeNs?.let { "${it.lower}–${it.upper} ns" } ?: "Not reported")
                DiagnosticRow("Minimum focus distance", report.minimumFocusDistanceDiopters?.let { "${"%.2f".format(Locale.getDefault(), it)} diopters" } ?: "Not reported / fixed")
                DiagnosticRow(
                    "Compensation AE",
                    report.exposureCompensationLabel(),
                )
                DiagnosticRow("Camera2 zoom", report.zoomRatioRange?.let { "${it.lower}×–${it.upper}×" } ?: "Not reported")
                DiagnosticRow("AF modes", report.afModes.ifEmpty { listOf("None") }.joinToString())
                DiagnosticRow("AWB modes", report.awbModes.ifEmpty { listOf("None") }.joinToString())
                DiagnosticRow("Optical stabilization (OIS)", report.opticalStabilizationModes.ifEmpty { listOf("None") }.joinToString())
                DiagnosticRow("Video stabilization (EIS)", report.videoStabilizationModes.ifEmpty { listOf("None") }.joinToString())
                DiagnosticRow("FPS ranges", report.fpsRanges.joinToString { "${it.lower}–${it.upper}" }.ifBlank { "Not reported" })
                DiagnosticRow("Standard JPEG sizes", report.jpegSizes.formatSizes())
                DiagnosticRow("High-resolution JPEG sizes (BURST)", report.highResolutionJpegSizes.formatSizes())
                DiagnosticRow("Video sizes", report.videoSizes.formatSizes())
                DiagnosticRow("RAW_SENSOR sizes", report.rawSizes.formatSizes())
                DiagnosticRow(
                    "Maximum-resolution mode",
                    if (report.supportsMaximumResolution) {
                        "Yes · array ${report.maximumResolutionPixelArraySize.formatSize()} · active ${report.maximumResolutionActiveArray ?: "not reported"} · JPEG ${report.maximumResolutionJpegSizes.formatSizes()}"
                    } else {
                        "Capability not reported"
                    },
                )
                if (report.physicalCameraReports.isEmpty()) {
                    DiagnosticRow("Physical camera characteristics", "No physical camera IDs reported")
                } else {
                    Text(
                        text = "Physical sensors",
                        modifier = Modifier.padding(top = 18.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    report.physicalCameraReports.forEach { physicalReport ->
                        PhysicalCameraCapabilityCard(physicalReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhysicalCameraCapabilityCard(report: PhysicalCameraReport) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Physical camera ID ${report.cameraId} · ${report.lensFacing}",
                style = MaterialTheme.typography.titleSmall,
            )
            report.error?.let { error ->
                DiagnosticRow("Unable to read", error)
            } ?: run {
                DiagnosticRow(
                    "Sensor",
                    "${report.pixelArraySize.formatSize()} · active array ${report.activeArray ?: "not reported"} · orientation ${report.sensorOrientationDegrees ?: "?"}°",
                )
                DiagnosticRow(
                    "Focal lengths",
                    report.focalLengthsMm.joinToString { "$it mm" }.ifBlank { "Not reported" },
                )
                DiagnosticRow(
                    "Capabilities",
                    report.capabilities.ifEmpty { listOf("None reported") }.joinToString(),
                )
                DiagnosticRow("Standard JPEG sizes", report.jpegSizes.formatSizes())
                DiagnosticRow("High-resolution JPEG sizes (BURST)", report.highResolutionJpegSizes.formatSizes())
                DiagnosticRow("RAW_SENSOR", report.rawSizes.formatSizes())
                DiagnosticRow(
                    "Maximum-resolution mode",
                    if (report.supportsMaximumResolution) {
                        "${report.maximumResolutionPixelArraySize.formatSize()} · JPEG ${report.maximumResolutionJpegSizes.formatSizes()}"
                    } else {
                        "Capability not reported"
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Size?.formatSize(): String = this?.let {
    "${it.width}×${it.height} (${formatMegapixels(it)})"
} ?: "Not reported"

private fun List<Size>.formatSizes(limit: Int = 10): String {
    if (isEmpty()) return "None reported"
    val shown = take(limit).joinToString { "${it.width}×${it.height} (${formatMegapixels(it)})" }
    return if (size > limit) "$shown, … (+${size - limit})" else shown
}

private fun CameraCapabilityReport.exposureCompensationLabel(): String {
    val range = exposureCompensationRange ?: return "Not reported"
    val step = exposureCompensationStep ?: return "Indices ${range.lower}…${range.upper} · step unknown"
    val stepValue = step.toFloat()
    val lowerEv = range.lower * stepValue
    val upperEv = range.upper * stepValue
    return "Indices ${range.lower}…${range.upper} · EV ${"%.1f".format(Locale.getDefault(), lowerEv)}…${"%+.1f".format(Locale.getDefault(), upperEv)} · step $step"
}

private fun formatMegapixels(size: Size): String =
    "${"%.1f".format(Locale.getDefault(), size.width.toLong() * size.height / 1_000_000.0)} MP"

private fun CameraPreviewState.label(): String = when (this) {
    CameraPreviewState.Idle -> "Camera stopped"
    CameraPreviewState.Opening -> "Opening camera…"
    is CameraPreviewState.Streaming -> "Preview active · ${previewSize.width}×${previewSize.height}"
    is CameraPreviewState.Error -> "Camera error · $message"
}

private fun openSavedImage(context: Context, uri: Uri, mimeType: String): String? = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    null
} catch (_: ActivityNotFoundException) {
    "No installed app can open this $mimeType file. The original remains saved in Pictures/Fabula."
} catch (exception: SecurityException) {
    "Could not grant access to the saved image: ${exception.message ?: "permission denied"}"
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private suspend fun saveJpegToMediaStore(context: Context, jpegBytes: ByteArray): Uri =
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Fabula_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Fabula",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create the photo entry.")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the output stream.")
            output.use { it.write(jpegBytes) }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the photo in MediaStore.")
            }
            uri
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }
