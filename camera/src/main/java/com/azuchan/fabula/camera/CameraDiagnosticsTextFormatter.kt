package com.azuchan.fabula.camera

import android.os.Build
import android.util.Range
import android.util.Size
import java.util.Locale

fun List<CameraCapabilityReport>.toDiagnosticText(): String {
    val reports = this
    return buildString {
        appendLine("Fabula — Camera2 report")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android API: ${Build.VERSION.SDK_INT}")
        appendLine("Logical cameras found: ${reports.size}")

        reports.forEach reportsLoop@{ report ->
            appendLine()
            appendLine("=".repeat(72))
            appendLine("LOGICAL CAMERA ${report.cameraId} — ${report.lensFacing}")
            appendLine("=".repeat(72))

            report.error?.let { error ->
                appendLine("Read error: $error")
                return@reportsLoop
            }

            appendLine("Camera2 hardware level: ${report.hardwareLevel}")
            appendLine("Sensor orientation: ${report.sensorOrientationDegrees ?: "not reported"}°")
            appendLine("Pixel array: ${report.pixelArraySize.toReportSize()}; active array: ${report.activeArray ?: "not reported"}")
            appendLine("Focal lengths (mm): ${report.focalLengthsMm.joinToString().ifEmpty { "none reported" }}")
            appendLine("Capabilities: ${report.capabilities.joinToString().ifEmpty { "none reported" }}")
            appendLine("Physical camera IDs: ${report.physicalCameraIds.joinToString().ifEmpty { "none" }}")
            appendLine("ISO range: ${report.isoRange.toReportRange()}")
            appendLine("Exposure time range (ns): ${report.exposureTimeRangeNs.toReportRange()}")
            appendLine("Minimum focus distance (diopters): ${report.minimumFocusDistanceDiopters ?: "not reported / fixed focus"}")
            appendLine("AE compensation range (indices): ${report.exposureCompensationRange.toReportRange()}")
            appendLine("AE compensation step: ${report.exposureCompensationStep ?: "not reported"}")
            appendLine("Zoom ratio range: ${report.zoomRatioRange.toReportRange()}")
            appendLine("AF modes: ${report.afModes.joinToString().ifEmpty { "none reported" }}")
            appendLine("AWB modes: ${report.awbModes.joinToString().ifEmpty { "none reported" }}")
            appendLine("OIS modes: ${report.opticalStabilizationModes.joinToString().ifEmpty { "none reported" }}")
            appendLine("Video stabilization modes: ${report.videoStabilizationModes.joinToString().ifEmpty { "none reported" }}")
            appendLine("AE FPS ranges: ${report.fpsRanges.joinToString().ifEmpty { "none reported" }}")
            appendLine("Video sizes: ${report.videoSizes.toReportSizes()}")
            appendLine("Standard JPEG sizes: ${report.jpegSizes.toReportSizes()}")
            appendLine("High-resolution JPEG sizes (BURST): ${report.highResolutionJpegSizes.toReportSizes()}")
            appendLine("RAW_SENSOR sizes: ${report.rawSizes.toReportSizes()}")
            appendLine("Maximum-resolution mode advertised: ${report.supportsMaximumResolution}")
            appendLine("Maximum-resolution pixel array: ${report.maximumResolutionPixelArraySize.toReportSize()}")
            appendLine("Maximum-resolution active array: ${report.maximumResolutionActiveArray ?: "not reported"}")
            appendLine("Maximum-resolution JPEG sizes: ${report.maximumResolutionJpegSizes.toReportSizes()}")

            report.physicalCameraReports.forEach physicalLoop@{ physical ->
                appendLine()
                appendLine("  -- PHYSICAL CAMERA ${physical.cameraId} — ${physical.lensFacing} --")
                physical.error?.let { error ->
                    appendLine("  Read error: $error")
                    return@physicalLoop
                }
                appendLine("  Sensor orientation: ${physical.sensorOrientationDegrees ?: "not reported"}°")
                appendLine("  Pixel array: ${physical.pixelArraySize.toReportSize()}; active array: ${physical.activeArray ?: "not reported"}")
                appendLine("  Focal lengths (mm): ${physical.focalLengthsMm.joinToString().ifEmpty { "none reported" }}")
                appendLine("  Capabilities: ${physical.capabilities.joinToString().ifEmpty { "none reported" }}")
                appendLine("  Standard JPEG sizes: ${physical.jpegSizes.toReportSizes()}")
                appendLine("  High-resolution JPEG sizes (BURST): ${physical.highResolutionJpegSizes.toReportSizes()}")
                appendLine("  RAW_SENSOR sizes: ${physical.rawSizes.toReportSizes()}")
                appendLine("  Maximum-resolution mode advertised: ${physical.supportsMaximumResolution}")
                appendLine("  Maximum-resolution pixel array: ${physical.maximumResolutionPixelArraySize.toReportSize()}")
                appendLine("  Maximum-resolution JPEG sizes: ${physical.maximumResolutionJpegSizes.toReportSizes()}")
            }
        }
    }
}

private fun Range<*>?.toReportRange(): String =
    this?.let { "${it.lower} .. ${it.upper}" } ?: "not reported"

private fun Size?.toReportSize(): String = this?.let {
    val megapixels = it.width.toLong() * it.height / 1_000_000.0
    "${it.width}x${it.height} (${String.format(Locale.US, "%.2f", megapixels)} MP)"
} ?: "not reported"

private fun List<Size>.toReportSizes(): String =
    if (isEmpty()) "none reported"
    else joinToString { it.toReportSize() }
