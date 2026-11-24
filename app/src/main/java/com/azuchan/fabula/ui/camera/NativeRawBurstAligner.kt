package com.azuchan.fabula.ui.camera

import android.graphics.ImageFormat
import android.media.Image
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

data class RawFrameAlignment(
    val xCorrectionPixels: Double,
    val yCorrectionPixels: Double,
    val meanAbsoluteError: Double,
    val confidence: Double,
    val isReferenceFrame: Boolean,
    val reachedSearchLimit: Boolean,
    val localMotionSpreadPixels: Double,
    val meanLocalConfidence: Double,
    val reliableLocalRegionCount: Int,
    val evaluatedLocalRegionCount: Int,
    val localRegions: List<LocalMotionRegion>,
)

data class LocalMotionRegion(
    val row: Int,
    val column: Int,
    val xCorrectionPixels: Double,
    val yCorrectionPixels: Double,
    val confidence: Double,
    val reliable: Boolean,
)

data class RawFusionPreview(
    val width: Int,
    val height: Int,
    val contributingFrames: Int,
    val argbData: IntArray,
    val pixelOffset: Int,
)

data class RawFusionTiff(
    val width: Int,
    val height: Int,
    val contributingFrames: Int,
    val file: File? = null,
    val mediaUri: String? = null,
)

/** Estimates whole-frame translation against the first RAW frame using a bounded native proxy. */
@Keep
class NativeRawBurstAligner(scratchFile: File?) : Closeable {
    private var nativeHandle: Long
    private var previewWidth = 0
    private var previewHeight = 0

    init {
        System.loadLibrary("fabula")
        nativeHandle = createAligner(scratchFile?.absolutePath.orEmpty())
        check(nativeHandle != 0L) { "Could not create the native RAW burst aligner." }
    }

    fun addFrame(
        image: Image,
        cfaChannels: IntArray,
        blackLevels: DoubleArray,
        whiteLevel: Double,
        colorGains: DoubleArray,
        colorMatrix: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    ): RawFrameAlignment {
        check(nativeHandle != 0L) { "The native RAW burst aligner is closed." }
        require(image.format == ImageFormat.RAW_SENSOR) {
            "The aligner expects a RAW_SENSOR image."
        }
        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("The RAW_SENSOR image has no plane.")
        return addBuffer(
            buffer = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            cfaChannels = cfaChannels,
            blackLevels = blackLevels,
            whiteLevel = whiteLevel,
            colorGains = colorGains,
            colorMatrix = colorMatrix,
        )
    }

    internal fun addBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cfaChannels: IntArray = intArrayOf(0, 1, 1, 2),
        blackLevels: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
        whiteLevel: Double = 65535.0,
        colorGains: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
        colorMatrix: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    ): RawFrameAlignment {
        check(nativeHandle != 0L) { "The native RAW burst aligner is closed." }
        require(buffer.isDirect) {
            "The RAW_SENSOR plane is not backed by a direct buffer."
        }
        require(cfaChannels.size == CFA_PATTERN_LENGTH &&
            blackLevels.size == CFA_PATTERN_LENGTH &&
            colorGains.size == COLOR_CHANNEL_COUNT &&
            colorMatrix.size == COLOR_MATRIX_LENGTH
        ) {
            "RAW color metadata has invalid dimensions."
        }

        val values = nativeAddFrame(
            handle = nativeHandle,
            buffer = buffer.slice(),
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cfaChannels = cfaChannels,
            blackLevels = blackLevels,
            whiteLevel = whiteLevel,
            colorGains = colorGains,
            colorMatrix = colorMatrix,
        )
        check(values.size == RESULT_LENGTH) {
            "The native RAW burst aligner returned an invalid result."
        }
        previewWidth = (width + FUSION_SCALE - 1) / FUSION_SCALE
        previewHeight = (height + FUSION_SCALE - 1) / FUSION_SCALE
        return RawFrameAlignment(
            xCorrectionPixels = values[0],
            yCorrectionPixels = values[1],
            meanAbsoluteError = values[2],
            confidence = values[3],
            isReferenceFrame = values[4] != 0.0,
            reachedSearchLimit = values[5] != 0.0,
            localMotionSpreadPixels = values[6],
            meanLocalConfidence = values[7],
            reliableLocalRegionCount = values[8].toInt(),
            evaluatedLocalRegionCount = values[9].toInt(),
            localRegions = List(LOCAL_REGION_COUNT) { index ->
                val offset = RESULT_HEADER_LENGTH + index * LOCAL_REGION_STRIDE
                LocalMotionRegion(
                    row = index / LOCAL_GRID_COLUMNS,
                    column = index % LOCAL_GRID_COLUMNS,
                    xCorrectionPixels = values[offset],
                    yCorrectionPixels = values[offset + 1],
                    confidence = values[offset + 2],
                    reliable = values[offset + 3] != 0.0,
                )
            },
        )
    }

    fun finishFusionPreview(): RawFusionPreview? {
        check(nativeHandle != 0L) { "The native RAW burst aligner is closed." }
        if (previewWidth == 0 || previewHeight == 0) return null
        val argbData = nativeFinishFusionPreview(nativeHandle)
        if (argbData.size != previewWidth * previewHeight + PREVIEW_HEADER_LENGTH) return null
        val contributingFrames = argbData[0]
        if (contributingFrames <= 0) return null
        return RawFusionPreview(
            width = previewWidth,
            height = previewHeight,
            contributingFrames = contributingFrames,
            argbData = argbData,
            pixelOffset = PREVIEW_HEADER_LENGTH,
        )
    }

    fun writeSuperResolutionTiff(outputFile: File, orientationDegrees: Int): RawFusionTiff? {
        check(nativeHandle != 0L) { "The native RAW burst aligner is closed." }
        if (previewWidth == 0 || previewHeight == 0) return null
        val dimensions = nativeWriteSuperResolutionTiff(
            handle = nativeHandle,
            outputPath = outputFile.absolutePath,
            orientation = orientationDegrees,
        )
        if (dimensions.size != TIFF_RESULT_LENGTH) return null
        return RawFusionTiff(
            width = dimensions[0],
            height = dimensions[1],
            contributingFrames = dimensions[2],
            file = outputFile,
        )
    }

    fun writeSuperResolutionTiff(
        outputDescriptor: ParcelFileDescriptor,
        orientationDegrees: Int,
    ): RawFusionTiff? {
        check(nativeHandle != 0L) { "The native RAW burst aligner is closed." }
        if (previewWidth == 0 || previewHeight == 0) return null
        val dimensions = nativeWriteSuperResolutionTiffToDescriptor(
            handle = nativeHandle,
            outputDescriptor = outputDescriptor,
            orientation = orientationDegrees,
        )
        if (dimensions.size != TIFF_RESULT_LENGTH) return null
        return RawFusionTiff(
            width = dimensions[0],
            height = dimensions[1],
            contributingFrames = dimensions[2],
        )
    }

    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            destroyAligner(handle)
        }
    }

    private external fun createAligner(scratchPath: String): Long

    @Keep
    private external fun nativeAddFrame(
        handle: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cfaChannels: IntArray,
        blackLevels: DoubleArray,
        whiteLevel: Double,
        colorGains: DoubleArray,
        colorMatrix: DoubleArray,
    ): DoubleArray

    @Keep
    private external fun nativeFinishFusionPreview(handle: Long): IntArray

    @Keep
    private external fun nativeWriteSuperResolutionTiff(
        handle: Long,
        outputPath: String,
        orientation: Int,
    ): IntArray

    @Keep
    private external fun nativeWriteSuperResolutionTiffToDescriptor(
        handle: Long,
        outputDescriptor: ParcelFileDescriptor,
        orientation: Int,
    ): IntArray

    private external fun destroyAligner(handle: Long)

    private companion object {
        const val RESULT_HEADER_LENGTH = 10
        const val LOCAL_GRID_COLUMNS = 5
        const val LOCAL_GRID_ROWS = 4
        const val LOCAL_REGION_COUNT = LOCAL_GRID_COLUMNS * LOCAL_GRID_ROWS
        const val LOCAL_REGION_STRIDE = 4
        const val RESULT_LENGTH = RESULT_HEADER_LENGTH + LOCAL_REGION_COUNT * LOCAL_REGION_STRIDE
        const val CFA_PATTERN_LENGTH = 4
        const val COLOR_CHANNEL_COUNT = 3
        const val COLOR_MATRIX_LENGTH = 9
        const val PREVIEW_HEADER_LENGTH = 1
        const val TIFF_RESULT_LENGTH = 3
        const val FUSION_SCALE = 2
    }
}
