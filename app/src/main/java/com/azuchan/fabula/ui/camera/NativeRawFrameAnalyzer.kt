package com.azuchan.fabula.ui.camera

import android.graphics.ImageFormat
import android.media.Image
import androidx.annotation.Keep
import java.nio.ByteBuffer

data class RawFrameStats(
    val sampledPixelCount: Long,
    val minimumSample: Int,
    val maximumSample: Int,
    val sumOfSamples: Long,
    val sampleStep: Int,
) {
    val meanSample: Double
        get() = if (sampledPixelCount == 0L) 0.0 else sumOfSamples.toDouble() / sampledPixelCount
}

/** Reads a sparse sample of a Camera2 RAW_SENSOR plane in native code without copying its pixels. */
@Keep
object NativeRawFrameAnalyzer {
    private const val SAMPLE_STEP = 8
    private const val RESULT_LENGTH = 4

    init {
        System.loadLibrary("fabula")
    }

    fun analyze(image: Image): RawFrameStats {
        require(image.format == ImageFormat.RAW_SENSOR) {
            "The native analyzer expects a RAW_SENSOR image."
        }
        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("The RAW_SENSOR image has no plane.")
        val buffer = plane.buffer
        require(buffer.isDirect) {
            "The RAW_SENSOR plane is not backed by a direct buffer."
        }

        val values = analyzeRawBuffer(
            buffer = buffer.slice(),
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            sampleStep = SAMPLE_STEP,
        )
        check(values.size == RESULT_LENGTH) {
            "The native RAW analyzer returned an invalid result."
        }
        return RawFrameStats(
            sampledPixelCount = values[0],
            minimumSample = values[1].toInt(),
            maximumSample = values[2].toInt(),
            sumOfSamples = values[3],
            sampleStep = SAMPLE_STEP,
        )
    }

    @Keep
    private external fun analyzeRawBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        sampleStep: Int,
    ): LongArray
}
