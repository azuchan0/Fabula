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
import android.content.res.Configuration
import android.media.MediaActionSound
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.IOException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.azuchan.fabula.camera.CameraPreviewState
import com.azuchan.fabula.camera.RawBurstState
import com.azuchan.fabula.camera.VideoRecordingState
import com.azuchan.fabula.camera.PhysicalCameraReport
import com.azuchan.fabula.camera.CameraWhiteBalanceControls
import com.azuchan.fabula.camera.CameraWhiteBalanceMode
import com.azuchan.fabula.ui.theme.FabulaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // The viewfinder keeps the last streamed aspect ratio while the camera reopens. Falling back to
    // the container shape instead resized the SurfaceView on every reopen, and each resize reopened
    // the camera again (endless "Opening camera" once a tall burst message made the area landscape).
    var lastViewfinderAspectRatio by remember { mutableStateOf<Float?>(null) }
    val streamingAspectRatio = (previewState as? CameraPreviewState.Streaming)?.displayAspectRatio
    LaunchedEffect(streamingAspectRatio) {
        if (streamingAspectRatio != null) lastViewfinderAspectRatio = streamingAspectRatio
    }
    val portraitScreen = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val settingsStore = remember(context) { CaptureSettingsStore.get(context) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    var lastCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var activeParam by remember { mutableStateOf<ProParam?>(null) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var previewSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var audioPermissionAsked by remember { mutableStateOf(false) }
    val videoState by controller.videoState.collectAsStateWithLifecycle()
    val zoom by controller.zoom.collectAsStateWithLifecycle()
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { },
    )
    val liveReadout by controller.liveReadout.collectAsStateWithLifecycle()
    var toast by remember { mutableStateOf<String?>(null) }
    var toastSequence by remember { mutableIntStateOf(0) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showExposureControls by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showGallery by rememberSaveable { mutableStateOf(false) }
    var galleryVersion by remember { mutableIntStateOf(0) }
    var latestGalleryUri by remember { mutableStateOf<Uri?>(null) }
    var timerRemaining by remember { mutableStateOf<Int?>(null) }
    var timerJob by remember { mutableStateOf<Job?>(null) }
    val screenScope = rememberCoroutineScope()
    var lookRenderer by remember { mutableStateOf<LookPreviewRenderer?>(null) }
    // Kept while their overlay animates out, after the live value has become null.
    var lastDial by remember { mutableStateOf<DialState?>(null) }
    var lastStatusMessage by remember { mutableStateOf("") }
    var veilImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var veilVisible by remember { mutableStateOf(false) }
    var veilJob by remember { mutableStateOf<Job?>(null) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    /** Holds a blurred last frame over the viewfinder until the next session streams. */
    fun veilUntilNextSession() {
        val view = previewSurfaceView ?: return
        val startedBefore = controller.sessionsStarted.value
        veilJob?.cancel()
        veilJob = screenScope.launch {
            veilImage = snapshotViewfinder(view) ?: return@launch
            veilVisible = true
            withTimeoutOrNull(1_500) { controller.sessionsStarted.first { it != startedBefore } }
            delay(120) // let the first new frames reach the screen before dissolving
            veilVisible = false
        }
    }

    /** Taps on a lens chip glide there on a log scale, crossing lenses mid-way like a real zoom. */
    fun glideZoom(target: Float) {
        zoomJob?.cancel()
        val start = controller.zoom.value.ratio
        zoomJob = screenScope.launch {
            val from = kotlin.math.ln(start)
            val to = kotlin.math.ln(target)
            animate(0f, 1f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { fraction, _ ->
                controller.setZoomRatio(kotlin.math.exp(from + (to - from) * fraction))
            }
        }
    }
    var shutterFlash by remember { mutableIntStateOf(0) }
    // Frames taken in the current CL/CH burst; -1 when no burst is running.
    var continuousShots by remember { mutableIntStateOf(-1) }
    var continuousHideJob by remember { mutableStateOf<Job?>(null) }
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(shutterFlash) {
        if (shutterFlash == 0) return@LaunchedEffect
        flashVisible = true
        delay(90)
        flashVisible = false
    }
    // Photos are rendered with the selected look off the event collector, so continuous drive
    // never makes the camera wait for encoding.
    val jpegQueue = remember { Channel<ByteArray>(Channel.UNLIMITED) }
    val shutterSound = remember { MediaActionSound() }
    DisposableEffect(shutterSound) { onDispose { shutterSound.release() } }

    fun showToast(message: String) {
        toast = message
        toastSequence++
    }

    LaunchedEffect(toastSequence) {
        if (toast != null) {
            delay(2_500)
            toast = null
        }
    }

    LaunchedEffect(jpegQueue) {
        // Two workers, so a continuous burst is rendered and saved about twice as fast.
        repeat(2) {
            launch {
                for (bytes in jpegQueue) {
                    try {
                        val look = settingsStore.settings.value.look()
                        val rendered = withContext(Dispatchers.Default) {
                            renderJpegWithLook(bytes, look, context.cacheDir)
                        }
                        lastCaptureUri = saveJpegToMediaStore(context.applicationContext, rendered)
                        Log.i("FabulaCapture", "Photo saved with look $look (${rendered.size} bytes)")
                        galleryVersion++
                    } catch (exception: Exception) {
                        showToast("Could not save the photo: ${exception.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    LaunchedEffect(galleryVersion) {
        latestGalleryUri = queryFabulaMedia(context, limit = 1).firstOrNull()?.uri
    }

    LaunchedEffect(controller) {
        controller.captureEvents.collect { event ->
            when (event) {
                is CameraCaptureEvent.JpegReady -> {
                    jpegQueue.trySend(event.bytes)
                    // In CL/CH every frame blinks the viewfinder, clicks, and counts.
                    if (continuousShots >= 0) {
                        continuousShots++
                        shutterFlash++
                        if (settingsStore.settings.value.shutterSound) shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    }
                }

                is CameraCaptureEvent.Failed -> {
                    showToast(event.message)
                    val orphan = pendingVideoUri
                    if (orphan != null && controller.videoState.value == VideoRecordingState.Idle) {
                        pendingVideoUri = null
                        withContext(Dispatchers.IO) { context.contentResolver.delete(orphan, null, null) }
                    }
                }

                is CameraCaptureEvent.RawBurstProgress -> Unit

                is CameraCaptureEvent.VideoFinished -> {
                    val uri = pendingVideoUri
                    pendingVideoUri = null
                    if (uri != null) {
                        if (event.saved && publishMediaEntry(context, uri)) {
                            lastCaptureUri = uri
                            galleryVersion++
                        } else {
                            withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) }
                            showToast("Video failed")
                        }
                    }
                }

                is CameraCaptureEvent.RawBurstFinished -> {
                    val jpegUri = event.superResolutionJpegUri?.let(Uri::parse)
                    if (jpegUri != null && event.superResolutionWidth > 0) {
                        lastCaptureUri = jpegUri
                        galleryVersion++
                        showToast("48MP · ${event.superResolutionWidth} × ${event.superResolutionHeight}")
                    } else {
                        val previewUri = event.fusionPreviewUri?.let(Uri::parse)
                        if (previewUri != null) lastCaptureUri = previewUri
                        showToast(
                            if (previewUri != null) {
                                "48MP failed · preview saved"
                            } else {
                                "48MP failed"
                            },
                        )
                    }
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

    val streaming = previewState is CameraPreviewState.Streaming
    val rawCaptureSize = (previewState as? CameraPreviewState.Streaming)?.rawCaptureSize
    val burstRun = rawBurstState as? RawBurstState.Capturing
    val burstBusy = burstRun != null
    val requestedMode = CaptureMode.entries.firstOrNull { it.name == settings.captureMode } ?: CaptureMode.BURST
    // A camera session without RAW falls back to photos rather than offering a dead shutter.
    val captureMode = if (requestedMode == CaptureMode.BURST && streaming && rawCaptureSize == null) {
        CaptureMode.PHOTO
    } else {
        requestedMode
    }
    val proControls = ProControls(
        exposureControls, whiteBalanceControls, focusControls, liveReadout, settings, captureMode,
        capabilitiesKnown = streaming,
    )
    // Modes this phone's camera cannot do stay visible, greyed, with the reason on tap.
    val unsupportedModes = buildMap {
        if (streaming && rawCaptureSize == null) put(CaptureMode.BURST, "48MP needs RAW, which this phone doesn't offer")
        if (streaming && (previewState as? CameraPreviewState.Streaming)?.videoCaptureSize == null) {
            put(CaptureMode.VIDEO, "Video isn't available on this phone")
        }
    }
    val drive = proControls.drive()
    // The shutter handlers outlive a composition (gesture coroutines, memoized references), so they
    // read the mode and drive through State: a captured value would still be the previous mode.
    val latestMode by rememberUpdatedState(captureMode)
    val latestDrive by rememberUpdatedState(drive)
    // Video is recorded without a look, so its viewfinder shows the plain rendering.
    val previewLook = if (captureMode == CaptureMode.VIDEO) ColorLook.STANDARD else settings.look()
    LaunchedEffect(previewLook, lookRenderer) { lookRenderer?.setLook(previewLook) }

    fun fireShutter() {
        if (settings.shutterSound) shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        // Blink the viewfinder like a shutter curtain: the photo itself is saved a moment later.
        shutterFlash++
        activeParam = null
        if (latestMode == CaptureMode.BURST) {
            controller.captureRawBurst(frameCount = settings.burstFrames)
        } else {
            controller.captureJpeg()
        }
    }

    fun onShutterTap() {
        Log.i("FabulaCapture", "Shutter tapped: mode=$latestMode drive=$latestDrive timer=${timerJob != null}")
        timerJob?.let { job ->
            // A second tap during the countdown cancels the self-timer.
            job.cancel()
            timerJob = null
            timerRemaining = null
            return
        }
        if (latestDrive.timerSeconds > 0) {
            activeParam = null
            timerJob = screenScope.launch {
                try {
                    for (second in latestDrive.timerSeconds downTo 1) {
                        timerRemaining = second
                        delay(1_000)
                    }
                    timerRemaining = null
                    fireShutter()
                } finally {
                    timerRemaining = null
                    timerJob = null
                }
            }
        } else {
            fireShutter()
        }
    }
    val dial = activeParam?.takeIf { streaming && !burstBusy }?.let(proControls::dial)
    val recording = videoState as? VideoRecordingState.Recording
    // The 48MP burst reads the main sensor's full RAW frame, so it always runs at 1x.
    val zoomAllowed = captureMode != CaptureMode.BURST && !burstBusy
    val pinchEnabled by rememberUpdatedState(zoomAllowed)
    var previousMode by remember { mutableStateOf<CaptureMode?>(null) }
    LaunchedEffect(captureMode) {
        // Entering or leaving 48MP rebuilds the session (RAW stream on/off): veil the pause.
        val before = previousMode
        if (before != null && (before == CaptureMode.BURST) != (captureMode == CaptureMode.BURST)) {
            veilUntilNextSession()
        }
        previousMode = captureMode
        // Only the 48MP mode needs the RAW stream; without it the camera can switch to the ultra-wide.
        controller.setRawStreamEnabled(captureMode == CaptureMode.BURST)
        if (captureMode == CaptureMode.BURST) controller.setZoomRatio(1f)
    }

    LaunchedEffect(captureMode) {
        if (captureMode == CaptureMode.VIDEO && !audioPermissionAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionAsked = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun toggleRecording() {
        if (videoState != VideoRecordingState.Idle) {
            veilUntilNextSession()
            controller.stopVideoRecording()
            return
        }
        val withAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        veilUntilNextSession()
        val entry = runCatching { createVideoEntry(context) }.getOrNull()
        if (entry == null) {
            showToast("Video failed")
            return
        }
        pendingVideoUri = entry.first
        controller.startVideoRecording(entry.second, withAudio)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FabulaColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReadoutStrip(
                readouts = proControls.readouts(),
                active = dial?.param,
                onSelect = { param -> activeParam = if (activeParam == param) null else param },
                onUnavailable = ::showToast,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val viewfinderAspectRatio =
                    (previewState as? CameraPreviewState.Streaming)?.displayAspectRatio
                        ?: lastViewfinderAspectRatio
                        ?: if (portraitScreen) 3f / 4f else 4f / 3f
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


                Box(modifier = viewfinderModifier.align(Alignment.Center)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            val previewView = SurfaceView(viewContext)
                            previewSurfaceView = previewView
                            var pinchRatio = 1f
                            var pinched = false
                            val pinchDetector = ScaleGestureDetector(
                                viewContext,
                                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                                        pinchRatio = controller.zoom.value.ratio
                                        return pinchEnabled
                                    }

                                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                                        val range = controller.zoom.value
                                        pinchRatio = (pinchRatio * detector.scaleFactor)
                                            .coerceIn(range.minRatio, range.maxRatio)
                                        controller.setZoomRatio(pinchRatio)
                                        return true
                                    }
                                },
                            )
                            previewView.setOnTouchListener { view, event ->
                                pinchDetector.onTouchEvent(event)
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
                                if (event.pointerCount > 1 || pinchDetector.isInProgress) pinched = true
                                if (event.actionMasked == MotionEvent.ACTION_UP && !pinched &&
                                    view.width > 0 && view.height > 0
                                ) {
                                    view.performClick()
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
                                        // The preview goes through GL so the colour look shows live;
                                        // without GL the camera draws straight into the view.
                                        lookRenderer?.release()
                                        lookRenderer = LookPreviewRenderer(holder.surface).takeIf { it.ready }
                                            ?.also { renderer ->
                                                renderer.setLook(previewLook)
                                                controller.previewSurfaceProvider = renderer::cameraSurface
                                            }
                                        if (lookRenderer == null) controller.previewSurfaceProvider = null
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
                                        controller.previewSurfaceProvider = null
                                        lookRenderer?.release()
                                        lookRenderer = null
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

                    if (captureMode == CaptureMode.VIDEO) {
                        // The recorder writes 16:9; show what it keeps.
                        val portrait = viewfinderAspectRatio < 1f
                        FrameMask(if (portrait) 9f / 16f else 16f / 9f, Modifier.fillMaxSize())
                    }
                    SwitchVeil(veilImage, veilVisible, Modifier.fillMaxSize())
                    if (settings.gridLines) GridOverlay(Modifier.fillMaxSize())
                    if (settings.histogram && streaming && dial == null && burstRun == null) {
                        previewSurfaceView?.let { view ->
                            HistogramOverlay(
                                view = view,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp),
                            )
                        }
                    }
                    val flashAlpha by animateFloatAsState(
                        if (flashVisible) 0.75f else 0f,
                        tween(if (flashVisible) 40 else 220),
                        label = "shutterFlash",
                    )
                    if (flashAlpha > 0f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = flashAlpha)),
                        )
                    }
                    AnimatedContent(
                        targetState = timerRemaining,
                        transitionSpec = {
                            (fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 1.4f)) togetherWith
                                (fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.7f))
                        },
                        modifier = Modifier.align(Alignment.Center),
                        label = "selfTimer",
                    ) { seconds ->
                        if (seconds != null) {
                            Text(
                                text = seconds.toString(),
                                color = FabulaColors.Text,
                                fontSize = 96.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (continuousShots >= 0) {
                        OverlayChip(
                            text = "${latestDrive.short} · $continuousShots",
                            color = FabulaColors.Accent,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                        )
                    }
                    recording?.let {
                        RecordingTimer(
                            startedAtElapsedMs = it.startedAtElapsedMs,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                        )
                    }
                    val overlay = when {
                        burstRun != null -> BottomOverlay.BURST
                        dial != null -> BottomOverlay.DIAL
                        zoomAllowed && streaming -> BottomOverlay.ZOOM
                        captureMode == CaptureMode.BURST && rawCaptureSize != null -> BottomOverlay.FRAMES
                        else -> BottomOverlay.NONE
                    }
                    if (dial != null) lastDial = dial
                    if (burstRun != null && !burstRun.finalizing) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .border(2.dp, FabulaColors.Accent),
                        )
                    }
                    AnimatedContent(
                        targetState = overlay,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                                (fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 3 })
                        },
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        label = "viewfinderOverlay",
                    ) { kind ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            when (kind) {
                                BottomOverlay.BURST -> burstRun?.let { run ->
                                    BurstProgressBar(
                                        done = run.savedFrames,
                                        total = run.totalFrames,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                                BottomOverlay.DIAL -> lastDial?.let { shownDial ->
                                    ProDial(
                                        state = shownDial,
                                        onIndexChange = { index ->
                                            proControls.select(shownDial.param, index, controller, settingsStore::update)
                                        },
                                        onAutoChange = { auto -> proControls.setAuto(shownDial.param, auto, controller) },
                                    )
                                }
                                BottomOverlay.ZOOM -> ZoomControl(
                                    ratio = zoom.ratio,
                                    minRatio = zoom.minRatio,
                                    maxRatio = zoom.maxRatio,
                                    onRatioChange = { ratio ->
                                        zoomJob?.cancel()
                                        controller.setZoomRatio(ratio)
                                    },
                                    onPresetSelected = ::glideZoom,
                                    onUnavailable = ::showToast,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                                BottomOverlay.FRAMES -> FrameCountPicker(
                                    selected = settings.burstFrames,
                                    onSelect = { count -> settingsStore.update { it.copy(burstFrames = count) } },
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                                BottomOverlay.NONE -> Unit
                            }
                        }
                    }
                }

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
                                color = FabulaColors.Accent,
                                shape = CircleShape,
                            ),
                    )
                }

                val statusMessage = if (!streaming) previewState.label() else toast
                if (statusMessage != null) lastStatusMessage = statusMessage
                StatusChip(
                    visible = statusMessage != null,
                    text = lastStatusMessage,
                    error = previewState is CameraPreviewState.Error,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(
                        description = "Pro controls",
                        enabled = streaming && !burstBusy,
                        active = dial != null,
                        outlined = true,
                        onClick = {
                            activeParam = if (dial != null) null else ProParam.ISO
                        },
                    ) { tint -> DialIcon(tint) }
                    if (captureMode == CaptureMode.VIDEO) {
                        RecordButton(
                            enabled = streaming && !burstBusy && videoState != VideoRecordingState.Starting,
                            recording = recording != null,
                            onClick = {
                                activeParam = null
                                toggleRecording()
                            },
                        )
                    } else ShutterButton(
                        mode = captureMode,
                        enabled = streaming && !burstBusy,
                        capturing = burstRun != null && !burstRun.finalizing,
                        processing = burstRun?.finalizing == true,
                        progress = burstRun?.let { it.savedFrames.toFloat() / it.totalFrames.coerceAtLeast(1) } ?: 0f,
                        onClick = { onShutterTap() },
                        onHoldStart = if (captureMode == CaptureMode.PHOTO && drive.intervalMs > 0) {
                            {
                                activeParam = null
                                continuousHideJob?.cancel()
                                continuousShots = 0
                                controller.startContinuousCapture(latestDrive.intervalMs)
                            }
                        } else {
                            null
                        },
                        onHoldEnd = {
                            controller.stopContinuousCapture()
                            // Keep the count on screen a moment, then clear it.
                            continuousHideJob = screenScope.launch {
                                delay(900)
                                continuousShots = -1
                            }
                        },
                    )
                    RoundControl(
                        description = "Settings",
                        enabled = true,
                        active = false,
                        onClick = { showSettings = true },
                    ) { tint -> DotsIcon(tint) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LastCaptureThumbnail(
                        uri = lastCaptureUri ?: latestGalleryUri,
                        highlighted = false,
                        onOpen = { showGallery = true },
                    )
                    ModeSwitch(
                        mode = captureMode,
                        unsupported = unsupportedModes,
                        enabled = !burstBusy && videoState == VideoRecordingState.Idle,
                        onModeChange = { mode -> settingsStore.update { it.copy(captureMode = mode.name) } },
                        onUnavailable = ::showToast,
                    )
                    RoundControl(
                        description = "Lock exposure",
                        enabled = streaming && !burstBusy && exposureControls.autoExposureLockAvailable &&
                            exposureControls.mode == CameraExposureMode.AUTO,
                        active = exposureControls.autoExposureLocked,
                        onClick = { controller.setAutoExposureLock(!exposureControls.autoExposureLocked) },
                    ) { tint ->
                        Text("AEL", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
            }
        }

        BackHandler(enabled = showGallery) { showGallery = false }
        AnimatedVisibility(
            visible = showGallery,
            enter = ScreenEnter,
            exit = ScreenExit,
            label = "gallery",
        ) {
            GalleryScreen(
                refreshKey = galleryVersion,
                onOpen = { item -> openSavedImage(context, item.uri, item.mimeType)?.let(::showToast) },
                onBack = { showGallery = false },
            )
        }

        BackHandler(enabled = showSettings) { showSettings = false }
        AnimatedVisibility(
            visible = showSettings,
            enter = ScreenEnter,
            exit = ScreenExit,
            label = "settings",
        ) {
            SettingsScreen(
                settings = settings,
                onChange = settingsStore::update,
                onDiagnostics = {
                    showDiagnostics = true
                    diagnosticsViewModel.refresh()
                },
                onBack = { showSettings = false },
                rawSupported = !streaming || rawCaptureSize != null,
            )
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

/** A pending MP4 entry in Movies/Fabula and a read-write descriptor the recorder can seek in. */
private fun createVideoEntry(context: Context): Pair<Uri, ParcelFileDescriptor> {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Fabula_${System.currentTimeMillis()}.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Fabula")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("MediaStore could not create the video entry.")
    val descriptor = try {
        resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the video file.")
    } catch (exception: Exception) {
        resolver.delete(uri, null, null)
        throw exception
    }
    return uri to descriptor
}

private suspend fun publishMediaEntry(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    runCatching { context.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
}

private enum class BottomOverlay { NONE, BURST, DIAL, ZOOM, FRAMES }

/** Status and toast messages fade in from the top and fade out once cleared. */
@Composable
private fun StatusChip(visible: Boolean, text: String, error: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { -it / 2 },
        exit = fadeOut(tween(220)),
        modifier = modifier,
        label = "statusChip",
    ) {
        OverlayChip(text = text, color = if (error) FabulaColors.Error else FabulaColors.Text)
    }
}

private val ScreenEnter = slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(200))
private val ScreenExit = slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(180))

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
