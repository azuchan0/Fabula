package com.azuchan.fabula.ui.camera

import androidx.annotation.Keep
import com.azuchan.fabula.camera.RawFrameData
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

/** Reads a sparse sample of a RAW_SENSOR frame in native code without copying its pixels. */
@Keep
object NativeRawFrameAnalyzer {
    private const val SAMPLE_STEP = 8
    private const val RESULT_LENGTH = 4

    init {
        System.loadLibrary("fabula")
    }

    fun analyze(frame: RawFrameData): RawFrameStats {
        val buffer = frame.buffer
        require(buffer.isDirect) {
            "The RAW frame is not backed by a direct buffer."
        }

        val values = analyzeRawBuffer(
            buffer = buffer.duplicate().apply { position(0) }.slice(),
            width = frame.width,
            height = frame.height,
            rowStride = frame.rowStride,
            pixelStride = frame.pixelStride,
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
