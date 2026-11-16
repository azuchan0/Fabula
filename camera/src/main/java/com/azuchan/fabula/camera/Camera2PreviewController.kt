package com.azuchan.fabula.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Size
import android.util.Range
import android.util.Rational
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface CameraPreviewState {
    data object Idle : CameraPreviewState
    data object Opening : CameraPreviewState
    data class Streaming(
        val cameraId: String,
        val previewSize: Size,
        /** Width / height after accounting for sensor-to-display rotation. */
        val displayAspectRatio: Float,
        val rawCaptureSize: Size?,
    ) : CameraPreviewState
    data class Error(val message: String) : CameraPreviewState
}

sealed interface CameraCaptureEvent {
    data class JpegReady(val bytes: ByteArray) : CameraCaptureEvent
    data class Failed(val message: String) : CameraCaptureEvent
    data class RawBurstProgress(val burstId: String, val savedFrames: Int, val totalFrames: Int) : CameraCaptureEvent
    data class RawBurstFinished(val burstId: String, val savedFrames: Int, val totalFrames: Int) : CameraCaptureEvent
}

fun interface RawFrameWriter {
    fun writeDngFrame(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        burstId: String,
        frameIndex: Int,
        orientationDegrees: Int,
    ): String
}

sealed interface RawBurstState {
    data object Idle : RawBurstState
    data class Capturing(val burstId: String, val savedFrames: Int, val totalFrames: Int) : RawBurstState
    data class Completed(val burstId: String, val savedFrames: Int, val totalFrames: Int) : RawBurstState
    data class Failed(val message: String) : RawBurstState
}

enum class CameraExposureMode {
    AUTO,
    MANUAL,
}

enum class CameraWhiteBalanceMode {
    PRESET,
    CCT,
}

enum class CameraFocusMode {
    CONTINUOUS,
    AUTO,
    MANUAL,
}

data class CameraExposureControls(
    val manualSensorSupported: Boolean = false,
    val autoExposureLockAvailable: Boolean = false,
    val autoExposureLocked: Boolean = false,
    val isoRange: Range<Int>? = null,
    val exposureTimeRangeNs: Range<Long>? = null,
    val exposureCompensationRange: Range<Int>? = null,
    val exposureCompensationStep: Rational? = null,
    val mode: CameraExposureMode = CameraExposureMode.AUTO,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L,
    val exposureCompensationIndex: Int = 0,
    val error: String? = null,
)

data class CameraWhiteBalanceControls(
    val availableAwbModes: List<Int> = emptyList(),
    val mode: CameraWhiteBalanceMode = CameraWhiteBalanceMode.PRESET,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val lockAvailable: Boolean = false,
    val locked: Boolean = false,
    val cctSupported: Boolean = false,
    val colorTemperatureRange: Range<Int>? = null,
    val colorTemperatureKelvin: Int = 5000,
    val colorTint: Int = 0,
    val error: String? = null,
)

data class CameraFocusControls(
    val availableAfModes: List<Int> = emptyList(),
    val mode: CameraFocusMode = CameraFocusMode.CONTINUOUS,
    val manualFocusSupported: Boolean = false,
    val tapFocusSupported: Boolean = false,
    val minimumFocusDistanceDiopters: Float = 0f,
    val focusDistanceDiopters: Float = 0f,
    val error: String? = null,
)

data class CameraFocusTap(
    val xFraction: Float,
    val yFraction: Float,
    val sequence: Long,
)

private data class RawCaptureMetadata(
    val frameIndex: Int,
    val result: TotalCaptureResult,
)

private class RawBurstRun(
    val burstId: String,
    val totalFrames: Int,
) {
    val pendingResults = mutableMapOf<Long, RawCaptureMetadata>()
    val pendingImages = mutableMapOf<Long, Image>()
    var savedFrames = 0
    var failedFrames = 0
    var sequenceCompleted = false
    var completionTimeoutScheduled = false
}

/** Owns Camera2 objects and confines their state callbacks to one camera thread. */
class Camera2PreviewController(
    context: Context,
    private val rawFrameWriter: RawFrameWriter? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("FabulaCamera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val mutableState = MutableStateFlow<CameraPreviewState>(CameraPreviewState.Idle)
    val state: StateFlow<CameraPreviewState> = mutableState.asStateFlow()
    private val mutableCaptureEvents = MutableSharedFlow<CameraCaptureEvent>(extraBufferCapacity = 2)
    val captureEvents: SharedFlow<CameraCaptureEvent> = mutableCaptureEvents.asSharedFlow()
    private val mutableExposureControls = MutableStateFlow(CameraExposureControls())
    val exposureControls: StateFlow<CameraExposureControls> = mutableExposureControls.asStateFlow()
    private val mutableWhiteBalanceControls = MutableStateFlow(CameraWhiteBalanceControls())
    val whiteBalanceControls: StateFlow<CameraWhiteBalanceControls> =
        mutableWhiteBalanceControls.asStateFlow()
    private val mutableFocusControls = MutableStateFlow(CameraFocusControls())
    val focusControls: StateFlow<CameraFocusControls> = mutableFocusControls.asStateFlow()
    private val mutableFocusTap = MutableStateFlow<CameraFocusTap?>(null)
    val focusTap: StateFlow<CameraFocusTap?> = mutableFocusTap.asStateFlow()
    private val mutableRawBurstState = MutableStateFlow<RawBurstState>(RawBurstState.Idle)
    val rawBurstState: StateFlow<RawBurstState> = mutableRawBurstState.asStateFlow()

    // The following fields are read and written only on cameraHandler's thread.
    private var generation = 0L
    private var desiredHolder: SurfaceHolder? = null
    private var desiredDisplayRotation = Surface.ROTATION_0
    private var desiredViewWidth = 0
    private var desiredViewHeight = 0
    private var cameraOpenPending = false
    private var pendingSessionConfigurations = 0
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var activePreviewConfig: PreviewConfig? = null
    private var activeAfRegion: MeteringRectangle? = null
    private var activeAeRegion: MeteringRectangle? = null
    private var focusTapSequence = 0L
    private var activeRawBurst: RawBurstRun? = null
    private var lastAutoIso = 100
    private var lastAutoExposureTimeNs = 10_000_000L
    private var lastAutoFocusDistanceDiopters = 0f
    private var recoveryGeneration: Long? = null
    private var recoveryAttempts = 0
    private var recoveryRetryScheduled = false
    private var recoveryRetryToken = 0L
    private var workerShutdown = false

    @Volatile
    private var releaseRequested = false

    fun startPreview(
        holder: SurfaceHolder,
        displayRotation: Int,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (releaseRequested) return

        cameraHandler.post {
            if (releaseRequested || !holder.surface.isValid) return@post

            if (desiredHolder === holder &&
                desiredDisplayRotation == displayRotation &&
                desiredViewWidth == viewWidth &&
                desiredViewHeight == viewHeight &&
                (cameraOpenPending || cameraDevice != null || captureSession != null ||
                    pendingSessionConfigurations > 0)
            ) {
                return@post
            }

            desiredHolder = holder
            desiredDisplayRotation = displayRotation
            desiredViewWidth = viewWidth
            desiredViewHeight = viewHeight
            generation++
            recoveryGeneration = null
            recoveryAttempts = 0
            recoveryRetryScheduled = false
            recoveryRetryToken++
            closeActiveResources()
            openDesiredPreviewWhenReady()
        }
    }

    fun stopPreview() {
        if (releaseRequested) return

        cameraHandler.post {
            desiredHolder = null
            generation++
            recoveryGeneration = null
            recoveryAttempts = 0
            recoveryRetryScheduled = false
            recoveryRetryToken++
            closeActiveResources()
            mutableState.value = CameraPreviewState.Idle
            shutdownWorkerIfReleased()
        }
    }

    fun captureJpeg() {
        if (releaseRequested) return

        cameraHandler.post {
            val device = cameraDevice
            val session = captureSession
            val reader = imageReader
            val config = activePreviewConfig
            if (device == null || session == null || reader == null || config == null) {
                mutableCaptureEvents.tryEmit(
                    CameraCaptureEvent.Failed("The preview is not ready."),
                )
                return@post
            }

            try {
                val request = device
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    .apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, config.jpegOrientation)
                        configureFocusRequest(this, config)
                        configureExposureRequest(this, config)
                        configureWhiteBalanceRequest(this, config)
                    }
                    .build()

                session.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            mutableCaptureEvents.tryEmit(
                                CameraCaptureEvent.Failed(
                                    "Camera capture failed (reason ${failure.reason}).",
                                ),
                            )
                        }
                    },
                    cameraHandler,
                )
            } catch (exception: Exception) {
                mutableCaptureEvents.tryEmit(
                    CameraCaptureEvent.Failed(
                        exception.message ?: "Could not capture the photo.",
                    ),
                )
            }
        }
    }

    fun captureRawBurst(frameCount: Int = 8) {
        if (releaseRequested) return

        cameraHandler.post {
            if (activeRawBurst != null) return@post
            val device = cameraDevice
            val session = captureSession
            val reader = rawImageReader
            val config = activePreviewConfig
            val writer = rawFrameWriter
            if (device == null || session == null || reader == null || config?.rawSize == null || writer == null) {
                mutableRawBurstState.value = RawBurstState.Failed(
                    "This camera does not provide a usable RAW_SENSOR stream.",
                )
                return@post
            }

            val totalFrames = frameCount.coerceIn(8, 15)
            val burstId = System.currentTimeMillis().toString()
            val burst = RawBurstRun(burstId, totalFrames)
            activeRawBurst = burst
            mutableRawBurstState.value = RawBurstState.Capturing(burstId, 0, totalFrames)
            mutableCaptureEvents.tryEmit(
                CameraCaptureEvent.RawBurstProgress(burstId, 0, totalFrames),
            )

            try {
                val requests = (0 until totalFrames).map { frameIndex ->
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        setTag(frameIndex)
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        configureExposureRequest(this, config, lockForBurst = true)
                        configureWhiteBalanceRequest(this, config, lockForBurst = true)
                        configureFocusRequest(this, config, forceManualForBurst = true)
                        configureOpticalStabilizationForBurst(this, config)
                    }.build()
                }

                session.captureBurst(
                    requests,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val frameIndex = request.tag as? Int
                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            if (frameIndex == null || timestamp == null) {
                                burst.failedFrames++
                                updateRawBurstProgress(burst)
                                maybeFinishRawBurst(burst)
                                return
                            }

                            val waitingImage = burst.pendingImages.remove(timestamp)
                            if (waitingImage != null) {
                                saveRawFrame(burst, waitingImage, frameIndex, result, config)
                            } else {
                                burst.pendingResults[timestamp] = RawCaptureMetadata(frameIndex, result)
                            }
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            burst.failedFrames++
                            mutableCaptureEvents.tryEmit(
                                CameraCaptureEvent.Failed(
                                    "RAW frame ${burst.failedFrames + burst.savedFrames} failed (reason ${failure.reason}).",
                                ),
                            )
                            updateRawBurstProgress(burst)
                            maybeFinishRawBurst(burst)
                        }

                        override fun onCaptureSequenceCompleted(
                            session: CameraCaptureSession,
                            sequenceId: Int,
                            frameNumber: Long,
                        ) {
                            burst.sequenceCompleted = true
                            maybeFinishRawBurst(burst)
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int,
                        ) {
                            burst.sequenceCompleted = true
                            maybeFinishRawBurst(burst)
                        }
                    },
                    cameraHandler,
                )
            } catch (exception: Exception) {
                activeRawBurst = null
                burst.pendingImages.values.forEach(Image::close)
                burst.pendingImages.clear()
                burst.pendingResults.clear()
                mutableRawBurstState.value = RawBurstState.Failed(
                    exception.message ?: "Could not start the RAW burst.",
                )
            }
        }
    }

    fun setManualExposureEnabled(enabled: Boolean) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableExposureControls.value
            if (enabled && !current.manualSensorSupported) return@post

            val isoRange = current.isoRange
            val exposureRange = current.exposureTimeRangeNs
            mutableExposureControls.value = current.copy(
                mode = if (enabled) CameraExposureMode.MANUAL else CameraExposureMode.AUTO,
                autoExposureLocked = if (enabled) false else current.autoExposureLocked,
                iso = if (enabled && isoRange != null) {
                    lastAutoIso.coerceIn(isoRange.lower, isoRange.upper)
                } else {
                    current.iso
                },
                exposureTimeNs = if (enabled && exposureRange != null) {
                    lastAutoExposureTimeNs.coerceIn(exposureRange.lower, exposureRange.upper)
                } else {
                    current.exposureTimeNs
                },
                error = null,
            )
            applyExposureControls()
        }
    }

    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableExposureControls.value
            if (current.mode != CameraExposureMode.MANUAL) return@post
            val isoRange = current.isoRange ?: return@post
            val exposureRange = current.exposureTimeRangeNs ?: return@post
            mutableExposureControls.value = current.copy(
                iso = iso.coerceIn(isoRange.lower, isoRange.upper),
                exposureTimeNs = exposureTimeNs.coerceIn(
                    exposureRange.lower,
                    exposureRange.upper,
                ),
                error = null,
            )
            applyExposureControls()
        }
    }

    fun setExposureCompensation(index: Int) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableExposureControls.value
            if (current.mode != CameraExposureMode.AUTO) return@post
            val range = current.exposureCompensationRange ?: return@post
            mutableExposureControls.value = current.copy(
                exposureCompensationIndex = index.coerceIn(range.lower, range.upper),
                error = null,
            )
            applyExposureControls()
        }
    }

    fun setAutoExposureLock(locked: Boolean) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableExposureControls.value
            if (current.mode != CameraExposureMode.AUTO || !current.autoExposureLockAvailable) {
                return@post
            }
            mutableExposureControls.value = current.copy(autoExposureLocked = locked, error = null)
            applyExposureControls()
        }
    }

    fun setWhiteBalancePreset(awbMode: Int) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableWhiteBalanceControls.value
            if (awbMode !in current.availableAwbModes) return@post
            mutableWhiteBalanceControls.value = current.copy(
                mode = CameraWhiteBalanceMode.PRESET,
                awbMode = awbMode,
                locked = false,
                error = null,
            )
            applyExposureControls()
        }
    }

    fun setWhiteBalanceLock(locked: Boolean) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableWhiteBalanceControls.value
            if (!current.lockAvailable || current.mode == CameraWhiteBalanceMode.CCT) return@post
            mutableWhiteBalanceControls.value = current.copy(locked = locked, error = null)
            applyExposureControls()
        }
    }

    fun setWhiteBalanceCct(kelvin: Int, tint: Int) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableWhiteBalanceControls.value
            val range = current.colorTemperatureRange
            if (!current.cctSupported || range == null) return@post
            mutableWhiteBalanceControls.value = current.copy(
                mode = CameraWhiteBalanceMode.CCT,
                colorTemperatureKelvin = kelvin.coerceIn(range.lower, range.upper),
                colorTint = tint.coerceIn(-50, 50),
                locked = false,
                error = null,
            )
            applyExposureControls()
        }
    }

    fun setFocusMode(mode: CameraFocusMode) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableFocusControls.value
            val supported = when (mode) {
                CameraFocusMode.CONTINUOUS ->
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in current.availableAfModes ||
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in current.availableAfModes
                CameraFocusMode.AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO in current.availableAfModes
                CameraFocusMode.MANUAL -> current.manualFocusSupported
            }
            if (!supported) return@post

            mutableFocusControls.value = current.copy(
                mode = mode,
                focusDistanceDiopters = if (mode == CameraFocusMode.MANUAL) {
                    lastAutoFocusDistanceDiopters.coerceIn(
                        0f,
                        current.minimumFocusDistanceDiopters,
                    )
                } else {
                    current.focusDistanceDiopters
                },
                error = null,
            )
            if (mode != CameraFocusMode.AUTO) {
                activeAfRegion = null
                activeAeRegion = null
                mutableFocusTap.value = null
            }
            applyExposureControls()
        }
    }

    fun setManualFocusDistance(distanceDiopters: Float) {
        if (releaseRequested) return

        cameraHandler.post {
            val current = mutableFocusControls.value
            if (!current.manualFocusSupported || current.mode != CameraFocusMode.MANUAL) return@post
            mutableFocusControls.value = current.copy(
                focusDistanceDiopters = distanceDiopters.coerceIn(
                    0f,
                    current.minimumFocusDistanceDiopters,
                ),
                error = null,
            )
            applyExposureControls()
        }
    }

    fun tapToFocus(xFraction: Float, yFraction: Float) {
        if (releaseRequested) return

        cameraHandler.post {
            val config = activePreviewConfig ?: return@post
            val current = mutableFocusControls.value
            if (!current.tapFocusSupported) {
                mutableFocusControls.value = current.copy(
                    error = "This camera does not advertise tap-to-focus regions.",
                )
                return@post
            }
            if (current.mode == CameraFocusMode.MANUAL) {
                mutableFocusControls.value = current.copy(
                    error = "Switch to autofocus before tapping the viewfinder.",
                )
                return@post
            }

            val activeArray = config.characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
            ) ?: run {
                mutableFocusControls.value = current.copy(
                    error = "The sensor active array is not reported.",
                )
                return@post
            }
            val normalizedX = xFraction.coerceIn(0f, 1f)
            val normalizedY = yFraction.coerceIn(0f, 1f)
            val (sensorX, sensorY) = mapPreviewPointToSensor(
                normalizedX,
                normalizedY,
                config,
                desiredDisplayRotation,
            )
            val region = meteringRectangle(activeArray, sensorX, sensorY)
            val requestKeys = config.characteristics.availableCaptureRequestKeys.toSet()
            val maxAfRegions = config.characteristics.get(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
            ) ?: 0
            val maxAeRegions = config.characteristics.get(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AE,
            ) ?: 0

            activeAfRegion = if (
                maxAfRegions > 0 && CaptureRequest.CONTROL_AF_REGIONS in requestKeys
            ) region else null
            activeAeRegion = if (
                maxAeRegions > 0 && CaptureRequest.CONTROL_AE_REGIONS in requestKeys
            ) region else null

            val autoAfAvailable =
                CameraMetadata.CONTROL_AF_MODE_AUTO in current.availableAfModes
            if (autoAfAvailable) {
                mutableFocusControls.value = current.copy(
                    mode = CameraFocusMode.AUTO,
                    error = null,
                )
            } else {
                mutableFocusControls.value = current.copy(error = null)
            }

            focusTapSequence++
            mutableFocusTap.value = CameraFocusTap(
                xFraction = normalizedX,
                yFraction = normalizedY,
                sequence = focusTapSequence,
            )
            applyExposureControls()
            if (autoAfAvailable) triggerAutoFocus()
        }
    }

    fun clearFocusTap(sequence: Long) {
        cameraHandler.post {
            if (mutableFocusTap.value?.sequence == sequence) mutableFocusTap.value = null
        }
    }

    fun triggerAutoFocus() {
        if (releaseRequested) return

        cameraHandler.post {
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val holder = desiredHolder ?: return@post
            val config = activePreviewConfig ?: return@post
            if (mutableFocusControls.value.mode == CameraFocusMode.MANUAL ||
                CameraMetadata.CONTROL_AF_MODE_AUTO !in mutableFocusControls.value.availableAfModes
            ) {
                return@post
            }

            try {
                val request = buildPreviewRequest(
                    device = device,
                    holder = holder,
                    config = config,
                    focusModeOverride = CameraFocusMode.AUTO,
                    afTrigger = CameraMetadata.CONTROL_AF_TRIGGER_START,
                )
                session.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            mutableFocusControls.value = mutableFocusControls.value.copy(
                                error = "Autofocus failed (reason ${failure.reason}).",
                            )
                        }

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            applyExposureControls()
                        }
                    },
                    cameraHandler,
                )
            } catch (exception: Exception) {
                mutableFocusControls.value = mutableFocusControls.value.copy(
                    error = exception.message ?: "Could not trigger autofocus.",
                )
            }
        }
    }

    override fun close() {
        if (releaseRequested) return
        releaseRequested = true

        cameraHandler.post {
            desiredHolder = null
            generation++
            closeActiveResources()
            mutableState.value = CameraPreviewState.Idle
            shutdownWorkerIfReleased()
        }
    }

    private fun openDesiredPreviewWhenReady() {
        val holder = desiredHolder ?: return
        if (releaseRequested || cameraOpenPending || cameraDevice != null ||
            captureSession != null || pendingSessionConfigurations > 0
        ) {
            shutdownWorkerIfReleased()
            return
        }

        if (!holder.surface.isValid) {
            mutableState.value = CameraPreviewState.Error("The preview surface is invalid.")
            return
        }

        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            desiredHolder = null
            mutableState.value = CameraPreviewState.Error("Camera permission has not been granted.")
            return
        }

        val requestGeneration = generation
        val config = try {
            findPreviewConfig(
                holder,
                desiredDisplayRotation,
                desiredViewWidth,
                desiredViewHeight,
            )
        } catch (exception: Exception) {
            failCurrentRequest(requestGeneration, exception.message ?: "Could not configure the camera.")
            return
        }

        val reader = try {
            ImageReader.newInstance(
                config.jpegSize.width,
                config.jpegSize.height,
                ImageFormat.JPEG,
                2,
            ).apply {
                setOnImageAvailableListener({ source ->
                    val image = try {
                        source.acquireNextImage()
                    } catch (_: IllegalStateException) {
                        null
                    } ?: return@setOnImageAvailableListener

                    image.use { jpegImage ->
                        val buffer = jpegImage.planes.firstOrNull()?.buffer
                            ?: return@use
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        mutableCaptureEvents.tryEmit(CameraCaptureEvent.JpegReady(bytes))
                    }
                }, cameraHandler)
            }
        } catch (exception: Exception) {
            failCurrentRequest(
                requestGeneration,
                exception.message ?: "Could not prepare the JPEG output.",
            )
            return
        }
        imageReader = reader

        rawImageReader = config.rawSize?.takeIf { rawFrameWriter != null }?.let { rawSize ->
            try {
                ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    3,
                ).apply {
                    setOnImageAvailableListener(::handleRawImageAvailable, cameraHandler)
                }
            } catch (exception: Exception) {
                mutableRawBurstState.value = RawBurstState.Failed(
                    exception.message ?: "Could not initialize the RAW_SENSOR ImageReader.",
                )
                null
            }
        }
        activePreviewConfig = config
        updateExposureCapabilities(config)
        updateWhiteBalanceAndFocusCapabilities(config)

        // Match the buffer dimensions to a stream size advertised by Camera2.
        holder.setFixedSize(config.previewSize.width, config.previewSize.height)
        cameraOpenPending = true
        mutableState.value = CameraPreviewState.Opening

        try {
            cameraManager.openCamera(
                config.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraOpenPending = false
                        cameraDevice = device

                        if (!isCurrentRequest(requestGeneration, holder)) {
                            device.close()
                            shutdownWorkerIfReleased()
                            return
                        }

                        createPreviewSession(device, holder, config, requestGeneration)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraOpenPending = false
                        cameraDevice = device
                        device.close()
                        if (requestGeneration == generation && !releaseRequested) {
                            recoveryGeneration = requestGeneration
                            mutableState.value = CameraPreviewState.Error("The camera disconnected.")
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraOpenPending = false
                        cameraDevice = device
                        device.close()
                        if (requestGeneration == generation && !releaseRequested) {
                            recoveryGeneration = requestGeneration
                            mutableState.value = CameraPreviewState.Error(
                                "Camera2 error (code $error).",
                            )
                        }
                    }

                    override fun onClosed(device: CameraDevice) {
                        if (cameraDevice === device) cameraDevice = null
                        resumeDesiredPreviewAfterClose()
                        shutdownWorkerIfReleased()
                    }
                },
                cameraHandler,
            )
        } catch (exception: Exception) {
            cameraOpenPending = false
            if (requestGeneration == generation && !releaseRequested) {
                recoveryGeneration = requestGeneration
                mutableState.value = CameraPreviewState.Error(
                    exception.message ?: "Opening the camera failed.",
                )
                resumeDesiredPreviewAfterClose()
            } else {
                failCurrentRequest(requestGeneration, exception.message ?: "Opening the camera failed.")
            }
        }
    }

    private fun createPreviewSession(
        device: CameraDevice,
        holder: SurfaceHolder,
        config: PreviewConfig,
        requestGeneration: Long,
    ) {
        pendingSessionConfigurations++
        val executor = java.util.concurrent.Executor { command ->
            cameraHandler.post(command)
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                pendingSessionConfigurations--
                captureSession = session

                if (!isCurrentRequest(requestGeneration, holder) || cameraDevice !== device) {
                    session.close()
                    openDesiredPreviewWhenReady()
                    shutdownWorkerIfReleased()
                    return
                }

                try {
                    session.setRepeatingRequest(
                        buildPreviewRequest(device, holder, config),
                        previewCaptureCallback,
                        cameraHandler,
                    )
                    mutableState.value = CameraPreviewState.Streaming(
                        cameraId = config.cameraId,
                        previewSize = config.previewSize,
                        displayAspectRatio = config.displayAspectRatio,
                        rawCaptureSize = rawImageReader?.let { config.rawSize },
                    )
                    recoveryGeneration = null
                    recoveryAttempts = 0
                    recoveryRetryScheduled = false
                    recoveryRetryToken++
                } catch (exception: Exception) {
                    failCurrentRequest(
                        requestGeneration,
                        exception.message ?: "Could not start the preview stream.",
                    )
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                pendingSessionConfigurations--
                captureSession = session
                if (rawImageReader != null && isCurrentRequest(requestGeneration, holder) &&
                    cameraDevice === device
                ) {
                    closeRawImageReader()
                    val fallbackConfig = config.copy(rawSize = null)
                    activePreviewConfig = fallbackConfig
                    mutableRawBurstState.value = RawBurstState.Failed(
                        "RAW session setup failed; preview and JPEG remain available.",
                    )
                    session.close()
                    createPreviewSession(device, holder, fallbackConfig, requestGeneration)
                    return
                }
                if (requestGeneration == generation && !releaseRequested) {
                    desiredHolder = null
                    mutableState.value = CameraPreviewState.Error("Camera2 could not configure the session.")
                }
                session.close()
                device.close()
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null
                closeRawImageReader()
                activePreviewConfig = null
            }

            override fun onClosed(session: CameraCaptureSession) {
                if (captureSession === session) captureSession = null
                resumeDesiredPreviewAfterClose()
                shutdownWorkerIfReleased()
            }
        }

        try {
            fun sessionConfiguration(includeRaw: Boolean) = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                buildList {
                    add(OutputConfiguration(holder.surface))
                    add(OutputConfiguration(requireNotNull(imageReader).surface))
                    if (includeRaw) rawImageReader?.let { add(OutputConfiguration(it.surface)) }
                },
                executor,
                callback,
            )

            val wantsRaw = rawImageReader != null
            var sessionConfig = sessionConfiguration(includeRaw = wantsRaw)
            if (wantsRaw && !device.isSessionConfigurationSupported(sessionConfig)) {
                closeRawImageReader()
                mutableRawBurstState.value = RawBurstState.Failed(
                    "The camera does not support preview, JPEG, and RAW outputs in one session.",
                )
                sessionConfig = sessionConfiguration(includeRaw = false)
            }
            if (!device.isSessionConfigurationSupported(sessionConfig)) {
                throw IllegalStateException("The camera does not support the preview/JPEG stream combination.")
            }
            device.createCaptureSession(sessionConfig)
        } catch (exception: Exception) {
            pendingSessionConfigurations--
            failCurrentRequest(
                requestGeneration,
                exception.message ?: "Could not create the Camera2 session.",
            )
        }
    }

    private fun findPreviewConfig(
        holder: SurfaceHolder,
        displayRotation: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): PreviewConfig {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("No camera is available.")

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Camera2 did not provide a stream configuration.")
        val outputSizes = streamMap.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty()
        if (outputSizes.isEmpty()) {
            throw IllegalStateException("No SurfaceHolder output sizes are available.")
        }

        // Use the actual AndroidView bounds. SurfaceHolder.surfaceFrame may reflect the
        // producer buffer dimensions (or an old fixed size), not the visible preview viewport.
        val frame = holder.surfaceFrame
        val targetAspectRatio = if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toDouble() / viewHeight
        } else if (frame.width() > 0 && frame.height() > 0) {
            frame.width().toDouble() / frame.height()
        } else {
            9.0 / 16.0
        }

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relativeRotation = (sensorOrientation - displayDegrees + 360) % 360
        val swapDimensions = relativeRotation == 90 || relativeRotation == 270

        fun displayAspectRatio(size: Size): Double = if (swapDimensions) {
            size.height.toDouble() / size.width
        } else {
            size.width.toDouble() / size.height
        }

        // Keep preview work bounded; fall back to all advertised sizes if necessary.
        val boundedSizes = outputSizes.filter { maxOf(it.width, it.height) <= 1920 }
            .ifEmpty { outputSizes }
        val bestAspectError = boundedSizes.minOf { abs(displayAspectRatio(it) - targetAspectRatio) }
        val matchingSizes = boundedSizes.filter {
            abs(displayAspectRatio(it) - targetAspectRatio) <= bestAspectError + 0.02
        }
        val previewSize = matchingSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("Could not select a preview size.")
        val previewDisplayAspectRatio = displayAspectRatio(previewSize).toFloat()

        val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (jpegSizes.isEmpty()) {
            throw IllegalStateException("No JPEG output sizes are available.")
        }
        // Start with regular JPEG outputs up to roughly 12 MP; maximum-resolution modes
        // need separate capability checks and are handled in the diagnostic milestone.
        val regularJpegSizes = jpegSizes.filter {
            it.width.toLong() * it.height <= 12_000_000L
        }.ifEmpty { jpegSizes }
        val jpegSize = regularJpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("Could not select a JPEG size.")

        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val rawSize = if (
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        ) {
            try {
                streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }

        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val jpegOrientation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }

        return PreviewConfig(
            cameraId = cameraId,
            characteristics = characteristics,
            previewSize = previewSize,
            jpegSize = jpegSize,
            jpegOrientation = jpegOrientation,
            displayAspectRatio = previewDisplayAspectRatio,
            rawSize = rawSize,
        )
    }

    private fun updateExposureCapabilities(config: PreviewConfig) {
        val characteristics = config.characteristics
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val requestKeys = characteristics.availableCaptureRequestKeys.toSet()
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val manualSensorSupported =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
                CaptureRequest.SENSOR_SENSITIVITY in requestKeys &&
                CaptureRequest.SENSOR_EXPOSURE_TIME in requestKeys &&
                isoRange != null && exposureRange != null
        val compensationRange = if (
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION in requestKeys
        ) {
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        } else {
            null
        }
        val autoExposureLockAvailable =
            characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true &&
                CaptureRequest.CONTROL_AE_LOCK in requestKeys

        if (isoRange != null) {
            lastAutoIso = lastAutoIso.coerceIn(isoRange.lower, isoRange.upper)
        }
        if (exposureRange != null) {
            lastAutoExposureTimeNs = lastAutoExposureTimeNs.coerceIn(
                exposureRange.lower,
                exposureRange.upper,
            )
        }

        mutableExposureControls.value = CameraExposureControls(
            manualSensorSupported = manualSensorSupported,
            autoExposureLockAvailable = autoExposureLockAvailable,
            isoRange = isoRange,
            exposureTimeRangeNs = exposureRange,
            exposureCompensationRange = compensationRange,
            exposureCompensationStep = if (compensationRange != null) {
                characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            } else {
                null
            },
            mode = CameraExposureMode.AUTO,
            iso = lastAutoIso,
            exposureTimeNs = lastAutoExposureTimeNs,
            exposureCompensationIndex = compensationRange?.let { 0.coerceIn(it.lower, it.upper) } ?: 0,
        )
    }

    private fun updateWhiteBalanceAndFocusCapabilities(config: PreviewConfig) {
        val characteristics = config.characteristics
        val requestKeys = characteristics.availableCaptureRequestKeys.toSet()
        val awbModes = characteristics
            .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toList()
            .orEmpty()
        val awbLockAvailable =
            characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true &&
                CaptureRequest.CONTROL_AWB_LOCK in requestKeys

        var colorTemperatureRange: Range<Int>? = null
        var cctSupported = false
        if (Build.VERSION.SDK_INT >= 36) {
            val colorCorrectionModes = characteristics
                .get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                ?.toSet()
                .orEmpty()
            colorTemperatureRange = characteristics.get(
                CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
            )
            cctSupported = CameraMetadata.COLOR_CORRECTION_MODE_CCT in colorCorrectionModes &&
                CaptureRequest.COLOR_CORRECTION_MODE in requestKeys &&
                CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE in requestKeys &&
                CaptureRequest.COLOR_CORRECTION_COLOR_TINT in requestKeys &&
                colorTemperatureRange != null &&
                CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes
        }

        val defaultAwbMode = if (CameraMetadata.CONTROL_AWB_MODE_AUTO in awbModes) {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        } else {
            awbModes.firstOrNull() ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        val temperatureRange = if (cctSupported) colorTemperatureRange else null
        val initialTemperature = temperatureRange?.let { 5000.coerceIn(it.lower, it.upper) } ?: 5000
        mutableWhiteBalanceControls.value = CameraWhiteBalanceControls(
            availableAwbModes = awbModes,
            mode = CameraWhiteBalanceMode.PRESET,
            awbMode = defaultAwbMode,
            lockAvailable = awbLockAvailable,
            cctSupported = cctSupported,
            colorTemperatureRange = temperatureRange,
            colorTemperatureKelvin = initialTemperature,
        )

        val afModes = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toList()
            .orEmpty()
        val minimumFocusDistance =
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val manualFocusSupported =
            minimumFocusDistance > 0f &&
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
                CameraMetadata.CONTROL_AF_MODE_OFF in afModes &&
                CaptureRequest.LENS_FOCUS_DISTANCE in requestKeys
        val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val tapFocusSupported = maxAfRegions > 0 &&
            CaptureRequest.CONTROL_AF_REGIONS in requestKeys &&
            (CameraMetadata.CONTROL_AF_MODE_AUTO in afModes ||
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ||
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes)
        val initialFocusMode = when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                CameraFocusMode.CONTINUOUS
            CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> CameraFocusMode.AUTO
            manualFocusSupported -> CameraFocusMode.MANUAL
            else -> CameraFocusMode.CONTINUOUS
        }
        mutableFocusControls.value = CameraFocusControls(
            availableAfModes = afModes,
            mode = initialFocusMode,
            manualFocusSupported = manualFocusSupported,
            tapFocusSupported = tapFocusSupported,
            minimumFocusDistanceDiopters = minimumFocusDistance,
            focusDistanceDiopters = lastAutoFocusDistanceDiopters.coerceIn(
                0f,
                minimumFocusDistance,
            ),
        )
    }

    private fun buildPreviewRequest(
        device: CameraDevice,
        holder: SurfaceHolder,
        config: PreviewConfig,
        focusModeOverride: CameraFocusMode? = null,
        afTrigger: Int? = null,
    ): CaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(holder.surface)
        configureFocusRequest(this, config, focusModeOverride, afTrigger)
        configureExposureRequest(this, config)
        configureWhiteBalanceRequest(this, config)
    }.build()

    private fun configureFocusRequest(
        builder: CaptureRequest.Builder,
        config: PreviewConfig,
        modeOverride: CameraFocusMode? = null,
        afTrigger: Int? = null,
        forceManualForBurst: Boolean = false,
    ) {
        val controls = mutableFocusControls.value
        val supportedModes = controls.availableAfModes
        val mode = if (forceManualForBurst && controls.manualFocusSupported) {
            CameraFocusMode.MANUAL
        } else {
            modeOverride ?: controls.mode
        }

        when (mode) {
            CameraFocusMode.CONTINUOUS -> {
                val continuousMode = when {
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in supportedModes ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in supportedModes ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    CameraMetadata.CONTROL_AF_MODE_AUTO in supportedModes ->
                        CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> null
                }
                if (continuousMode != null) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, continuousMode)
                }
            }

            CameraFocusMode.AUTO -> {
                if (CameraMetadata.CONTROL_AF_MODE_AUTO in supportedModes) {
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_AUTO,
                    )
                }
            }

            CameraFocusMode.MANUAL -> {
                if (controls.manualFocusSupported) {
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_OFF,
                    )
                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        if (forceManualForBurst && controls.mode != CameraFocusMode.MANUAL) {
                            lastAutoFocusDistanceDiopters
                        } else {
                            controls.focusDistanceDiopters
                        },
                    )
                }
            }
        }

        if (afTrigger != null && CaptureRequest.CONTROL_AF_TRIGGER in
            config.characteristics.availableCaptureRequestKeys
        ) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        }

        val requestKeys = config.characteristics.availableCaptureRequestKeys.toSet()
        activeAfRegion?.let { region ->
            if (CameraCharacteristics.CONTROL_MAX_REGIONS_AF.let {
                    (config.characteristics.get(it) ?: 0) > 0
                } && CaptureRequest.CONTROL_AF_REGIONS in requestKeys
            ) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
        }
    }

    private fun configureWhiteBalanceRequest(
        builder: CaptureRequest.Builder,
        config: PreviewConfig,
        lockForBurst: Boolean = false,
    ) {
        val controls = mutableWhiteBalanceControls.value
        if (controls.mode == CameraWhiteBalanceMode.CCT && controls.cctSupported) {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF,
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_CCT,
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE,
                controls.colorTemperatureKelvin,
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, controls.colorTint)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, controls.awbMode)
            if ((controls.locked || lockForBurst) && controls.lockAvailable && CaptureRequest.CONTROL_AWB_LOCK in
                config.characteristics.availableCaptureRequestKeys
            ) {
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }
    }

    private fun mapPreviewPointToSensor(
        xFraction: Float,
        yFraction: Float,
        config: PreviewConfig,
        displayRotation: Int,
    ): Pair<Float, Float> {
        val sensorOrientation =
            config.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val lensFacing = config.characteristics.get(CameraCharacteristics.LENS_FACING)
        val relativeRotation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }

        val (sensorX, sensorY) = when (relativeRotation) {
            90 -> yFraction to (1f - xFraction)
            180 -> (1f - xFraction) to (1f - yFraction)
            270 -> (1f - yFraction) to xFraction
            else -> xFraction to yFraction
        }

        // The front preview is mirrored for the user; map that view coordinate back to sensor space.
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (1f - sensorX) to sensorY
        } else {
            sensorX to sensorY
        }
    }

    private fun meteringRectangle(
        activeArray: Rect,
        xFraction: Float,
        yFraction: Float,
    ): MeteringRectangle {
        val regionWidth = (activeArray.width() * 0.12f).toInt().coerceIn(1, activeArray.width())
        val regionHeight = (activeArray.height() * 0.12f).toInt().coerceIn(1, activeArray.height())
        val centerX = activeArray.left + (xFraction * activeArray.width()).roundToInt()
        val centerY = activeArray.top + (yFraction * activeArray.height()).roundToInt()
        val left = (centerX - regionWidth / 2).coerceIn(
            activeArray.left,
            activeArray.right - regionWidth,
        )
        val top = (centerY - regionHeight / 2).coerceIn(
            activeArray.top,
            activeArray.bottom - regionHeight,
        )
        return MeteringRectangle(
            Rect(left, top, left + regionWidth, top + regionHeight),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    private fun configureExposureRequest(
        builder: CaptureRequest.Builder,
        config: PreviewConfig,
        lockForBurst: Boolean = false,
    ) {
        val controls = mutableExposureControls.value
        val requestKeys = config.characteristics.availableCaptureRequestKeys.toSet()

        if (controls.mode == CameraExposureMode.MANUAL && controls.manualSensorSupported) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, controls.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, controls.exposureTimeNs)
        } else if (lockForBurst && controls.manualSensorSupported &&
            CaptureRequest.SENSOR_SENSITIVITY in requestKeys &&
            CaptureRequest.SENSOR_EXPOSURE_TIME in requestKeys
        ) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, lastAutoIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lastAutoExposureTimeNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (controls.exposureCompensationRange != null &&
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION in requestKeys
            ) {
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    controls.exposureCompensationIndex,
                )
            }
            if ((controls.autoExposureLocked || lockForBurst) &&
                controls.autoExposureLockAvailable && CaptureRequest.CONTROL_AE_LOCK in requestKeys
            ) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            val maxAeRegions = config.characteristics.get(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AE,
            ) ?: 0
            if (activeAeRegion != null && maxAeRegions > 0 &&
                CaptureRequest.CONTROL_AE_REGIONS in requestKeys
            ) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(activeAeRegion!!))
            }
        }
    }

    private fun configureOpticalStabilizationForBurst(
        builder: CaptureRequest.Builder,
        config: PreviewConfig,
    ) {
        val supportedModes = config.characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
        )?.toSet().orEmpty()
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in supportedModes &&
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in
            config.characteristics.availableCaptureRequestKeys
        ) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
    }

    private fun handleRawImageAvailable(reader: ImageReader) {
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: IllegalStateException) {
                null
            } ?: break

            val burst = activeRawBurst
            if (burst == null) {
                image.close()
                continue
            }

            val timestamp = image.timestamp
            val metadata = burst.pendingResults.remove(timestamp)
            if (metadata == null) {
                burst.pendingImages.put(timestamp, image)?.close()
            } else {
                val config = activePreviewConfig
                if (config == null) {
                    image.close()
                    burst.failedFrames++
                    updateRawBurstProgress(burst)
                } else {
                    saveRawFrame(burst, image, metadata.frameIndex, metadata.result, config)
                }
            }
            maybeFinishRawBurst(burst)
        }
    }

    private fun saveRawFrame(
        burst: RawBurstRun,
        image: Image,
        frameIndex: Int,
        result: TotalCaptureResult,
        config: PreviewConfig,
    ) {
        try {
            val writer = rawFrameWriter
                ?: throw IllegalStateException("The DNG writer is unavailable.")
            writer.writeDngFrame(
                image = image,
                characteristics = config.characteristics,
                captureResult = result,
                burstId = burst.burstId,
                frameIndex = frameIndex,
                orientationDegrees = config.jpegOrientation,
            )
            burst.savedFrames++
        } catch (exception: Exception) {
            burst.failedFrames++
            mutableCaptureEvents.tryEmit(
                CameraCaptureEvent.Failed(
                    "Failed to write DNG ${frameIndex + 1}: " +
                        (exception.message ?: "unknown error"),
                ),
            )
        } finally {
            image.close()
        }
        updateRawBurstProgress(burst)
        maybeFinishRawBurst(burst)
    }

    private fun updateRawBurstProgress(burst: RawBurstRun) {
        if (activeRawBurst !== burst) return
        val saved = burst.savedFrames.coerceAtMost(burst.totalFrames)
        mutableRawBurstState.value = RawBurstState.Capturing(
            burstId = burst.burstId,
            savedFrames = saved,
            totalFrames = burst.totalFrames,
        )
        mutableCaptureEvents.tryEmit(
            CameraCaptureEvent.RawBurstProgress(burst.burstId, saved, burst.totalFrames),
        )
    }

    private fun maybeFinishRawBurst(burst: RawBurstRun) {
        if (activeRawBurst !== burst) return

        val accountedFrames = burst.savedFrames + burst.failedFrames
        if (burst.sequenceCompleted && accountedFrames >= burst.totalFrames &&
            burst.pendingImages.isEmpty() && burst.pendingResults.isEmpty()
        ) {
            finishRawBurst(burst)
            return
        }

        if (burst.sequenceCompleted && !burst.completionTimeoutScheduled) {
            burst.completionTimeoutScheduled = true
            cameraHandler.postDelayed({
                if (activeRawBurst !== burst) return@postDelayed
                val missingFrames = (burst.totalFrames - burst.savedFrames - burst.failedFrames)
                    .coerceAtLeast(0)
                burst.pendingImages.values.forEach(Image::close)
                burst.pendingImages.clear()
                burst.pendingResults.clear()
                burst.failedFrames += missingFrames
                finishRawBurst(burst)
            }, RAW_BURST_DRAIN_TIMEOUT_MS)
        }
    }

    private fun finishRawBurst(burst: RawBurstRun) {
        if (activeRawBurst !== burst) return
        burst.pendingImages.values.forEach(Image::close)
        burst.pendingImages.clear()
        burst.pendingResults.clear()
        activeRawBurst = null

        val savedFrames = burst.savedFrames.coerceAtMost(burst.totalFrames)
        mutableRawBurstState.value = if (savedFrames > 0) {
            RawBurstState.Completed(burst.burstId, savedFrames, burst.totalFrames)
        } else {
            RawBurstState.Failed("No DNG files could be saved.")
        }
        mutableCaptureEvents.tryEmit(
            CameraCaptureEvent.RawBurstFinished(burst.burstId, savedFrames, burst.totalFrames),
        )
    }

    private fun resumeDesiredPreviewAfterClose() {
        if (releaseRequested) {
            shutdownWorkerIfReleased()
            return
        }

        val holder = desiredHolder ?: return
        if (cameraOpenPending || cameraDevice != null || captureSession != null ||
            pendingSessionConfigurations > 0
        ) {
            return
        }

        val failedGeneration = recoveryGeneration
        if (failedGeneration == null || failedGeneration != generation) {
            openDesiredPreviewWhenReady()
            return
        }
        if (recoveryRetryScheduled) return

        if (recoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            recoveryGeneration = null
            desiredHolder = null
            mutableState.value = CameraPreviewState.Error(
                "The camera could not recover automatically. Tap Retry camera to try again.",
            )
            return
        }

        recoveryAttempts++
        recoveryRetryScheduled = true
        val retryToken = ++recoveryRetryToken
        val retryGeneration = generation
        val retryDelayMs = recoveryAttempts * CAMERA_RECOVERY_DELAY_MS
        mutableState.value = CameraPreviewState.Error(
            "Camera connection lost. Retrying (${recoveryAttempts}/$MAX_CAMERA_RECOVERY_ATTEMPTS)…",
        )
        cameraHandler.postDelayed({
            if (retryToken != recoveryRetryToken) return@postDelayed
            recoveryRetryScheduled = false
            if (retryGeneration != generation || desiredHolder !== holder || releaseRequested) {
                return@postDelayed
            }
            if (cameraOpenPending || cameraDevice != null || captureSession != null ||
                pendingSessionConfigurations > 0
            ) {
                resumeDesiredPreviewAfterClose()
            } else {
                recoveryGeneration = null
                openDesiredPreviewWhenReady()
            }
        }, retryDelayMs)
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (mutableExposureControls.value.mode == CameraExposureMode.AUTO) {
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAutoIso = it }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                    lastAutoExposureTimeNs = it
                }
            }
            if (mutableFocusControls.value.mode != CameraFocusMode.MANUAL) {
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                    lastAutoFocusDistanceDiopters = it
                }
            }
        }
    }

    private fun applyExposureControls() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val holder = desiredHolder ?: return
        val config = activePreviewConfig ?: return

        try {
            session.setRepeatingRequest(
                buildPreviewRequest(device, holder, config),
                previewCaptureCallback,
                cameraHandler,
            )
        } catch (exception: Exception) {
            mutableExposureControls.value = mutableExposureControls.value.copy(
                error = exception.message ?: "Camera2 rejected the exposure update.",
            )
        }
    }

    private fun isCurrentRequest(requestGeneration: Long, holder: SurfaceHolder): Boolean =
        !releaseRequested && requestGeneration == generation && desiredHolder === holder &&
            holder.surface.isValid

    private fun failCurrentRequest(requestGeneration: Long, message: String) {
        if (requestGeneration == generation && !releaseRequested) {
            desiredHolder = null
            mutableState.value = CameraPreviewState.Error(message)
        }
        closeActiveResources()
        shutdownWorkerIfReleased()
    }

    private fun closeActiveResources() {
        activeRawBurst?.let { burst ->
            burst.pendingImages.values.forEach(Image::close)
            burst.pendingImages.clear()
            burst.pendingResults.clear()
            activeRawBurst = null
            mutableRawBurstState.value = RawBurstState.Failed("The RAW burst was interrupted.")
        }
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        closeRawImageReader()
        activePreviewConfig = null
        mutableExposureControls.value = CameraExposureControls()
        mutableWhiteBalanceControls.value = CameraWhiteBalanceControls()
        mutableFocusControls.value = CameraFocusControls()
        activeAfRegion = null
        activeAeRegion = null
        mutableFocusTap.value = null
    }

    private fun closeRawImageReader() {
        rawImageReader?.setOnImageAvailableListener(null, null)
        rawImageReader?.close()
        rawImageReader = null
    }

    private fun shutdownWorkerIfReleased() {
        if (releaseRequested && !workerShutdown && !cameraOpenPending &&
            pendingSessionConfigurations == 0 && captureSession == null && cameraDevice == null
        ) {
            workerShutdown = true
            cameraThread.quitSafely()
        }
    }

    private data class PreviewConfig(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val previewSize: Size,
        val jpegSize: Size,
        val jpegOrientation: Int,
        val displayAspectRatio: Float,
        val rawSize: Size?,
    )

    private companion object {
        const val CAMERA_RECOVERY_DELAY_MS = 500L
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 2
        const val RAW_BURST_DRAIN_TIMEOUT_MS = 15_000L
    }
}
