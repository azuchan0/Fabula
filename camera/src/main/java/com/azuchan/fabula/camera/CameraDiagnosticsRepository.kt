package com.azuchan.fabula.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Range
import android.util.Rational
import android.util.Size

data class CameraCapabilityReport(
    val cameraId: String,
    val lensFacing: String,
    val hardwareLevel: String,
    val sensorOrientationDegrees: Int?,
    val pixelArraySize: Size?,
    val activeArray: Rect?,
    val focalLengthsMm: List<Float>,
    val capabilities: List<String>,
    val physicalCameraIds: List<String>,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val minimumFocusDistanceDiopters: Float?,
    val exposureCompensationRange: Range<Int>?,
    val exposureCompensationStep: Rational?,
    val zoomRatioRange: Range<Float>?,
    val afModes: List<String>,
    val awbModes: List<String>,
    val opticalStabilizationModes: List<String>,
    val videoStabilizationModes: List<String>,
    val fpsRanges: List<Range<Int>>,
    val videoSizes: List<Size>,
    val jpegSizes: List<Size>,
    val highResolutionJpegSizes: List<Size>,
    val rawSizes: List<Size>,
    val supportsMaximumResolution: Boolean,
    val maximumResolutionPixelArraySize: Size?,
    val maximumResolutionActiveArray: Rect?,
    val maximumResolutionJpegSizes: List<Size>,
    val physicalCameraReports: List<PhysicalCameraReport>,
    val error: String? = null,
)

data class PhysicalCameraReport(
    val cameraId: String,
    val lensFacing: String,
    val sensorOrientationDegrees: Int?,
    val pixelArraySize: Size?,
    val activeArray: Rect?,
    val focalLengthsMm: List<Float>,
    val capabilities: List<String>,
    val jpegSizes: List<Size>,
    val highResolutionJpegSizes: List<Size>,
    val rawSizes: List<Size>,
    val supportsMaximumResolution: Boolean,
    val maximumResolutionPixelArraySize: Size?,
    val maximumResolutionJpegSizes: List<Size>,
    val error: String? = null,
)

/** Reads Camera2's advertised metadata only; it does not open or configure a camera. */
class CameraDiagnosticsRepository(context: Context) {
    private val cameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun loadReports(): List<CameraCapabilityReport> = cameraManager.cameraIdList.map { cameraId ->
        try {
            inspectCamera(cameraId)
        } catch (exception: Exception) {
            CameraCapabilityReport(
                cameraId = cameraId,
                lensFacing = "Unknown",
                hardwareLevel = "Unknown",
                sensorOrientationDegrees = null,
                pixelArraySize = null,
                activeArray = null,
                focalLengthsMm = emptyList(),
                capabilities = emptyList(),
                physicalCameraIds = emptyList(),
                isoRange = null,
                exposureTimeRangeNs = null,
                minimumFocusDistanceDiopters = null,
                exposureCompensationRange = null,
                exposureCompensationStep = null,
                zoomRatioRange = null,
                afModes = emptyList(),
                awbModes = emptyList(),
                opticalStabilizationModes = emptyList(),
                videoStabilizationModes = emptyList(),
                fpsRanges = emptyList(),
                videoSizes = emptyList(),
                jpegSizes = emptyList(),
                highResolutionJpegSizes = emptyList(),
                rawSizes = emptyList(),
                supportsMaximumResolution = false,
                maximumResolutionPixelArraySize = null,
                maximumResolutionActiveArray = null,
                maximumResolutionJpegSizes = emptyList(),
                physicalCameraReports = emptyList(),
                error = exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    private fun inspectCamera(cameraId: String): CameraCapabilityReport {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
            .orEmpty()
        val capabilitySet = capabilities.toSet()
        val regularMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val maximumResolutionMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
        )
        val supportsMaximumResolution =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilitySet

        return CameraCapabilityReport(
            cameraId = cameraId,
            lensFacing = lensFacingName(
                characteristics.get(CameraCharacteristics.LENS_FACING),
            ),
            hardwareLevel = hardwareLevelName(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            sensorOrientationDegrees = characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ),
            pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            focalLengthsMm = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList()
                .orEmpty(),
            capabilities = capabilities.map(::capabilityName).sorted(),
            physicalCameraIds = characteristics.physicalCameraIds.sorted(),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            ),
            minimumFocusDistanceDiopters = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ),
            exposureCompensationRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
            ),
            exposureCompensationStep = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
            ),
            zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
            afModes = characteristics
                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toList()
                .orEmpty()
                .map(::afModeName),
            awbModes = characteristics
                .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toList()
                .orEmpty()
                .map(::awbModeName),
            opticalStabilizationModes = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.toList()
                .orEmpty()
                .map(::oisModeName),
            videoStabilizationModes = characteristics
                .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.toList()
                .orEmpty()
                .map(::videoStabilizationModeName),
            fpsRanges = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty()
                .toList()
                .sortedBy { it.lower },
            videoSizes = try {
                regularMap?.getOutputSizes(MediaRecorder::class.java)
                    ?.toList()
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    .orEmpty()
            } catch (_: IllegalArgumentException) {
                emptyList()
            },
            jpegSizes = outputSizes(regularMap, ImageFormat.JPEG),
            highResolutionJpegSizes = highResolutionSizes(regularMap, ImageFormat.JPEG),
            rawSizes = if (
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilitySet
            ) {
                outputSizes(regularMap, ImageFormat.RAW_SENSOR)
            } else {
                emptyList()
            },
            supportsMaximumResolution = supportsMaximumResolution,
            maximumResolutionPixelArraySize = if (supportsMaximumResolution) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            } else {
                null
            },
            maximumResolutionActiveArray = if (supportsMaximumResolution) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            } else {
                null
            },
            maximumResolutionJpegSizes = if (supportsMaximumResolution) {
                outputSizes(maximumResolutionMap, ImageFormat.JPEG)
            } else {
                emptyList()
            },
            physicalCameraReports = characteristics.physicalCameraIds
                .sorted()
                .map(::inspectPhysicalCamera),
        )
    }

    /** API 29+ permits querying characteristics of physical IDs in a logical camera. */
    private fun inspectPhysicalCamera(physicalCameraId: String): PhysicalCameraReport = try {
        val characteristics = cameraManager.getCameraCharacteristics(physicalCameraId)
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
            .orEmpty()
        val capabilitySet = capabilities.toSet()
        val regularMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val maximumResolutionMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
        )
        val supportsMaximumResolution =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilitySet

        PhysicalCameraReport(
            cameraId = physicalCameraId,
            lensFacing = lensFacingName(
                characteristics.get(CameraCharacteristics.LENS_FACING),
            ),
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
            pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            focalLengthsMm = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList()
                .orEmpty(),
            capabilities = capabilities.map(::capabilityName).sorted(),
            jpegSizes = outputSizes(regularMap, ImageFormat.JPEG),
            highResolutionJpegSizes = highResolutionSizes(regularMap, ImageFormat.JPEG),
            rawSizes = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilitySet) {
                outputSizes(regularMap, ImageFormat.RAW_SENSOR)
            } else {
                emptyList()
            },
            supportsMaximumResolution = supportsMaximumResolution,
            maximumResolutionPixelArraySize = if (supportsMaximumResolution) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            } else {
                null
            },
            maximumResolutionJpegSizes = if (supportsMaximumResolution) {
                outputSizes(maximumResolutionMap, ImageFormat.JPEG)
            } else {
                emptyList()
            },
        )
    } catch (exception: Exception) {
        PhysicalCameraReport(
            cameraId = physicalCameraId,
            lensFacing = "Unknown",
            sensorOrientationDegrees = null,
            pixelArraySize = null,
            activeArray = null,
            focalLengthsMm = emptyList(),
            capabilities = emptyList(),
            jpegSizes = emptyList(),
            highResolutionJpegSizes = emptyList(),
            rawSizes = emptyList(),
            supportsMaximumResolution = false,
            maximumResolutionPixelArraySize = null,
            maximumResolutionJpegSizes = emptyList(),
            error = exception.message ?: exception.javaClass.simpleName,
        )
    }

    private fun outputSizes(map: StreamConfigurationMap?, format: Int): List<Size> = try {
        map?.getOutputSizes(format)?.toList().orEmpty().sortedByDescending {
            it.width.toLong() * it.height
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun highResolutionSizes(map: StreamConfigurationMap?, format: Int): List<Size> = try {
        map?.getHighResolutionOutputSizes(format)?.toList().orEmpty().sortedByDescending {
            it.width.toLong() * it.height
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun lensFacingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "Back"
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "Unknown"
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "CAPABILITY_$value"
    }

    private fun afModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "MODE_$value"
    }

    private fun awbModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "MODE_$value"
    }

    private fun oisModeName(value: Int): String = when (value) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "MODE_$value"
    }

    private fun videoStabilizationModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"
        else -> "MODE_$value"
    }
}
