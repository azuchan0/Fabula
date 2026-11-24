package com.azuchan.fabula.ui.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.azuchan.fabula.camera.RawBurstFinishResult
import com.azuchan.fabula.camera.RawFrameWriter
import java.io.File
import java.io.IOException
import java.util.Locale

class MediaStoreRawFrameWriter(context: Context) : RawFrameWriter {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private var activeBurstId: String? = null
    private var burstAligner: NativeRawBurstAligner? = null
    private var activeBurstOrientationDegrees = 0
    private val burstAlignments = mutableMapOf<String, MutableMap<Int, RawFrameAlignment>>()

    init {
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith("Fabula_") && it.name.endsWith("_superres.accum") }
            ?.forEach(File::delete)
    }

    override fun writeDngFrame(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        burstId: String,
        frameIndex: Int,
        orientationDegrees: Int,
    ): String {
        require(image.format == ImageFormat.RAW_SENSOR) {
            "The burst expects RAW_SENSOR buffers."
        }
        if (activeBurstId != burstId || frameIndex == 0) {
            activeBurstOrientationDegrees = orientationDegrees
        }
        val rawStats = runCatching { NativeRawFrameAnalyzer.analyze(image) }
            .onFailure { Log.e(TAG, "Could not analyze RAW frame $frameIndex of $burstId.", it) }
            .getOrNull()

        val frameNumber = String.format(Locale.US, "%02d", frameIndex + 1)
        val displayName = "Fabula_${burstId}_$frameNumber.dng"
        val relativePath = "${Environment.DIRECTORY_PICTURES}/Fabula/RAW_Burst_$burstId"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create the DNG file.")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the DNG file.")
            output.use { stream ->
                DngCreator(characteristics, captureResult).use { creator ->
                    creator
                        .setOrientation(exifOrientation(orientationDegrees))
                        .setDescription(
                            buildString {
                                append("Fabula RAW burst $burstId, frame ${frameIndex + 1}")
                                rawStats?.let { stats ->
                                    append("; sampled mean ")
                                    append(String.format(Locale.US, "%.1f", stats.meanSample))
                                    append(", range ${stats.minimumSample}-${stats.maximumSample}")
                                }
                            },
                        )
                        .writeImage(stream, image)
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the DNG in MediaStore.")
            }
            runCatching {
                alignerForBurst(burstId, frameIndex, image).addFrame(
                    image = image,
                    cfaChannels = cfaChannels(characteristics),
                    blackLevels = blackLevels(characteristics),
                    whiteLevel = characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                        ?.toDouble()
                        ?: 65535.0,
                    colorGains = colorGains(captureResult),
                    colorMatrix = colorCorrectionMatrix(captureResult),
                )
            }.onSuccess { alignment ->
                burstAlignments.getOrPut(burstId) { mutableMapOf() }[frameIndex] = alignment
            }.onFailure {
                Log.e(TAG, "Could not align RAW frame $frameIndex of $burstId; the DNG was saved.", it)
            }
            return uri.toString()
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    override fun finishBurst(burstId: String): RawBurstFinishResult {
        var fusionPreview: RawFusionPreview? = null
        var fusionPreviewUri: Uri? = null
        var superResolutionTiff: RawFusionTiff? = null
        val orientationDegrees = activeBurstOrientationDegrees
        if (activeBurstId == burstId) {
            try {
                val aligner = burstAligner
                    ?: throw IOException("The RAW super-resolution aligner is unavailable.")
                superResolutionTiff = writeSuperResolutionTiff(burstId, aligner, orientationDegrees)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not build the RAW super-resolution TIFF for $burstId.", exception)
            }
            try {
                fusionPreview = burstAligner?.finishFusionPreview()
            } catch (exception: Exception) {
                Log.e(TAG, "Could not build the RAW fusion preview for $burstId.", exception)
            }
            burstAligner?.close()
            burstAligner = null
            activeBurstId = null
            activeBurstOrientationDegrees = 0
            File(appContext.cacheDir, "Fabula_${burstId}_superres.accum").delete()
        }

        fusionPreview?.let { preview ->
            try {
                fusionPreviewUri = writeFusionPreviewJpeg(burstId, preview, orientationDegrees)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save the RAW fusion preview for $burstId.", exception)
            }
        }

        val frames = burstAlignments.remove(burstId)
        if (frames != null) {
            try {
                writeAlignmentCsv(burstId, frames)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save the RAW alignment report for $burstId.", exception)
            }
        }
        return RawBurstFinishResult(
            fusionPreviewSaved = fusionPreviewUri != null,
            superResolutionWidth = superResolutionTiff?.width ?: 0,
            superResolutionHeight = superResolutionTiff?.height ?: 0,
            superResolutionFrames = superResolutionTiff?.contributingFrames ?: 0,
            superResolutionUri = superResolutionTiff?.mediaUri,
            fusionPreviewUri = fusionPreviewUri?.toString(),
        )
    }

    private fun writeSuperResolutionTiff(
        burstId: String,
        aligner: NativeRawBurstAligner,
        orientationDegrees: Int,
    ): RawFusionTiff {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Fabula_${burstId}_RAW_SuperResolution_2x.tiff")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Fabula/RAW_Burst_$burstId",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create the RAW super-resolution TIFF.")

        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open the RAW super-resolution TIFF.")
            val tiff = descriptor.use { aligner.writeSuperResolutionTiff(it, orientationDegrees) }
                ?: throw IOException("Could not encode the RAW super-resolution TIFF.")
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the RAW super-resolution TIFF.")
            }
            return tiff.copy(mediaUri = uri.toString())
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun alignerForBurst(
        burstId: String,
        frameIndex: Int,
        image: Image,
    ): NativeRawBurstAligner {
        if (activeBurstId != burstId || frameIndex == 0) {
            burstAligner?.close()
            val scratchFile = superResolutionScratchFile(burstId, image)
            scratchFile?.delete()
            if (scratchFile == null) {
                Log.w(TAG, "Not enough free storage for a disk-backed 2x RAW reconstruction.")
            }
            burstAligner = NativeRawBurstAligner(scratchFile)
            activeBurstId = burstId
        }
        return requireNotNull(burstAligner)
    }

    private fun superResolutionScratchFile(burstId: String, image: Image): File? {
        val plane = image.planes.firstOrNull() ?: return null
        val rawPixels = image.width.toLong() * image.height
        val outputPixels = rawPixels * 4L
        if (outputPixels > MAX_SUPER_RESOLUTION_PIXELS) return null
        val rawFrameBytes = rawPixels * plane.pixelStride
        // Keep room for the mapped 12-byte CFA accumulator, TIFF, and remaining DNGs.
        val requiredBytes = outputPixels * 15L + rawFrameBytes * MAX_RAW_BURST_FRAMES +
            MINIMUM_STORAGE_HEADROOM_BYTES
        if (appContext.cacheDir.usableSpace < requiredBytes) return null
        return File(appContext.cacheDir, "Fabula_${burstId}_superres.accum")
    }

    private fun cfaChannels(characteristics: CameraCharacteristics): IntArray = when (
        characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
    ) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 1, 1, 2)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
        else -> intArrayOf(1, 1, 1, 1)
    }

    private fun blackLevels(characteristics: CameraCharacteristics): DoubleArray {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(
            pattern.getOffsetForIndex(0, 0).toDouble(),
            pattern.getOffsetForIndex(1, 0).toDouble(),
            pattern.getOffsetForIndex(0, 1).toDouble(),
            pattern.getOffsetForIndex(1, 1).toDouble(),
        )
    }

    private fun colorGains(result: TotalCaptureResult): DoubleArray {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return doubleArrayOf(1.0, 1.0, 1.0)
        val greenGain = (gains.greenEven + gains.greenOdd) / 2.0
        return doubleArrayOf(gains.red.toDouble(), greenGain, gains.blue.toDouble())
    }

    private fun colorCorrectionMatrix(result: TotalCaptureResult): DoubleArray {
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return IDENTITY_COLOR_MATRIX
        val matrix = DoubleArray(9) { index ->
            val value = transform.getElement(index % 3, index / 3)
            if (value.denominator == 0) IDENTITY_COLOR_MATRIX[index] else value.toDouble()
        }
        return if (matrix.all { it.isFinite() }) matrix else IDENTITY_COLOR_MATRIX
    }

    private fun alignmentDescription(alignment: RawFrameAlignment): String =
        if (alignment.isReferenceFrame) {
            "global alignment reference"
        } else {
            val x = String.format(Locale.US, "%.2f", alignment.xCorrectionPixels)
            val y = String.format(Locale.US, "%.2f", alignment.yCorrectionPixels)
            val error = String.format(Locale.US, "%.1f", alignment.meanAbsoluteError)
            val confidence = String.format(Locale.US, "%.3f", alignment.confidence)
            val localSpread = String.format(Locale.US, "%.2f", alignment.localMotionSpreadPixels)
            val localConfidence = String.format(Locale.US, "%.3f", alignment.meanLocalConfidence)
            "global alignment dx=${x}px, dy=${y}px, MAE=$error, confidence=$confidence" +
                "; local regions ${alignment.reliableLocalRegionCount}/" +
                "${alignment.evaluatedLocalRegionCount}, spread=${localSpread}px, " +
                "confidence=$localConfidence" +
                (if (alignment.confidence < 0.05) " (low confidence)" else "") +
                (if (alignment.reachedSearchLimit) " (search limit)" else "")
        }

    private fun writeFusionPreviewJpeg(
        burstId: String,
        preview: RawFusionPreview,
        orientationDegrees: Int,
    ): Uri {
        val sensorBitmap = Bitmap.createBitmap(
            preview.argbData,
            preview.pixelOffset,
            preview.width,
            preview.width,
            preview.height,
            Bitmap.Config.ARGB_8888,
        )
        val bitmap = if (orientationDegrees % 360 == 0) {
            sensorBitmap
        } else {
            val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
            Bitmap.createBitmap(
                sensorBitmap,
                0,
                0,
                sensorBitmap.width,
                sensorBitmap.height,
                matrix,
                true,
            ).also { sensorBitmap.recycle() }
        }
        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                "Fabula_${burstId}_RAW_FusionPreview_${preview.contributingFrames}frames.jpg",
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Fabula/RAW_Burst_$burstId",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            bitmap.recycle()
            throw IOException("MediaStore could not create the RAW fusion preview.")
        }

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the RAW fusion preview.")
            output.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                    throw IOException("Could not encode the RAW fusion preview as JPEG.")
                }
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the RAW fusion preview.")
            }
            return uri
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        } finally {
            bitmap.recycle()
        }
    }

    internal fun writeAlignmentCsv(
        burstId: String,
        frames: Map<Int, RawFrameAlignment>,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Fabula_${burstId}_alignment.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Fabula/RAW_Burst_$burstId",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create the alignment report.")

        try {
            val csv = buildString {
                appendLine(
                    "frame,row,column,global_dx_px,global_dy_px,global_confidence," +
                        "local_dx_px,local_dy_px,local_confidence,local_reliable",
                )
                frames.toSortedMap().forEach { (frameIndex, alignment) ->
                    alignment.localRegions.forEach { region ->
                        val localX = if (alignment.isReferenceFrame) 0.0 else region.xCorrectionPixels
                        val localY = if (alignment.isReferenceFrame) 0.0 else region.yCorrectionPixels
                        val localConfidence = if (alignment.isReferenceFrame) 1.0 else region.confidence
                        val reliable = alignment.isReferenceFrame || region.reliable
                        append(frameIndex + 1)
                        append(',')
                        append(region.row)
                        append(',')
                        append(region.column)
                        append(',')
                        append(String.format(Locale.US, "%.3f", alignment.xCorrectionPixels))
                        append(',')
                        append(String.format(Locale.US, "%.3f", alignment.yCorrectionPixels))
                        append(',')
                        append(String.format(Locale.US, "%.3f", alignment.confidence))
                        append(',')
                        append(String.format(Locale.US, "%.3f", localX))
                        append(',')
                        append(String.format(Locale.US, "%.3f", localY))
                        append(',')
                        append(String.format(Locale.US, "%.3f", localConfidence))
                        append(',')
                        append(reliable)
                        appendLine()
                    }
                }
            }
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the alignment report.")
            output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the alignment report.")
            }
            return uri
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val TAG = "FabulaRawFrameWriter"
        const val MAX_RAW_BURST_FRAMES = 15L
        const val MAX_SUPER_RESOLUTION_PIXELS = 60_000_000L
        const val MINIMUM_STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        val IDENTITY_COLOR_MATRIX = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
    }
}
