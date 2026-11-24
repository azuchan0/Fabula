package com.azuchan.fabula.ui.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.azuchan.fabula.camera.RawBurstFinishResult
import com.azuchan.fabula.camera.RawFrameData
import com.azuchan.fabula.camera.RawFrameWriter
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class MediaStoreRawFrameWriter(context: Context) : RawFrameWriter {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private var activeBurstId: String? = null
    private var burstAligner: NativeRawBurstAligner? = null
    private var activeBurstOrientationDegrees = 0
    private val burstAlignments = mutableMapOf<String, MutableMap<Int, RawFrameAlignment>>()
    private val settingsStore = CaptureSettingsStore.get(appContext)
    // Settings are read once per burst, so changing them mid-burst cannot split its outputs.
    private var burstSettings = settingsStore.settings.value
    private val dngExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FabulaDngWriter").apply { isDaemon = true }
    }

    init {
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith("Fabula_") && it.name.endsWith("_superres.accum") }
            ?.forEach(File::delete)
    }

    override fun writeDngFrame(
        frame: RawFrameData,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        burstId: String,
        frameIndex: Int,
        orientationDegrees: Int,
    ): String {
        if (activeBurstId != burstId || frameIndex == 0) {
            activeBurstOrientationDegrees = orientationDegrees
            burstSettings = settingsStore.settings.value
        }
        val frameStart = SystemClock.elapsedRealtime()
        val keepDng = when (burstSettings.keepRawFrames) {
            KeepRawFrames.NONE -> false
            KeepRawFrames.FIRST -> frameIndex == 0
            KeepRawFrames.ALL -> true
        }
        // A kept DNG is written on its own thread while this one aligns the same frame; both only
        // read the frame buffer, and this call returns once both are done.
        val dngTask = if (keepDng) {
            dngExecutor.submit<String> {
                writeDng(frame, characteristics, captureResult, burstId, frameIndex, orientationDegrees)
            }
        } else {
            null
        }
        runCatching {
            alignerForBurst(burstId, frameIndex, frame).addFrame(
                frame = frame,
                cfaChannels = cfaChannels(characteristics),
                blackLevels = blackLevels(characteristics),
                whiteLevel = characteristics
                    .get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                    ?.toDouble()
                    ?: 65535.0,
                colorGains = colorGains(captureResult),
                colorMatrix = colorCorrectionMatrix(captureResult),
                noiseModel = noiseModel(captureResult),
            )
        }.onSuccess { alignment ->
            burstAlignments.getOrPut(burstId) { mutableMapOf() }[frameIndex] = alignment
        }.onFailure {
            Log.e(TAG, "Could not align RAW frame $frameIndex of $burstId.", it)
        }
        val alignDone = SystemClock.elapsedRealtime()
        val uri = try {
            dngTask?.get() ?: ""
        } catch (exception: ExecutionException) {
            throw exception.cause as? Exception ?: exception
        }
        Log.i(
            TIMING_TAG,
            "$burstId frame $frameIndex: align ${alignDone - frameStart} ms, " +
                "DNG + align ${SystemClock.elapsedRealtime() - frameStart} ms",
        )
        return uri
    }

    private fun writeDng(
        frame: RawFrameData,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        burstId: String,
        frameIndex: Int,
        orientationDegrees: Int,
    ): String {
        val rawStats = runCatching { NativeRawFrameAnalyzer.analyze(frame) }
            .onFailure { Log.e(TAG, "Could not analyze RAW frame $frameIndex of $burstId.", it) }
            .getOrNull()

        val frameNumber = String.format(Locale.US, "%02d", frameIndex + 1)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Fabula_${burstId}_$frameNumber.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, burstPicturesPath(burstId))
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
                        .writeByteBuffer(stream, Size(frame.width, frame.height), frame.buffer.duplicate(), 0)
                }
            }
            publish(uri, "DNG")
            return uri.toString()
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    override fun finishBurst(burstId: String): RawBurstFinishResult {
        var fusionPreview: RawFusionPreview? = null
        var fusionPreviewUri: Uri? = null
        var superResolution: RawFusionTiff? = null
        val orientationDegrees = activeBurstOrientationDegrees
        val settings = burstSettings
        val finishStart = SystemClock.elapsedRealtime()
        var superResolutionDone = finishStart
        var previewDone = finishStart
        if (activeBurstId == burstId) {
            try {
                val aligner = burstAligner
                    ?: throw IOException("The RAW super-resolution aligner is unavailable.")
                superResolution = writeSuperResolution(burstId, aligner, orientationDegrees, settings.saveTiff)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not build the RAW 2x reconstruction for $burstId.", exception)
            }
            superResolutionDone = SystemClock.elapsedRealtime()
            try {
                fusionPreview = burstAligner?.finishFusionPreview()
            } catch (exception: Exception) {
                Log.e(TAG, "Could not build the RAW fusion preview for $burstId.", exception)
            }
            previewDone = SystemClock.elapsedRealtime()
            burstAligner?.close()
            burstAligner = null
            activeBurstId = null
            activeBurstOrientationDegrees = 0
        }

        // The reduced fusion preview is only a fallback for a burst whose 2x render failed.
        fusionPreview?.takeIf { superResolution?.jpegMediaUri == null }?.let { preview ->
            try {
                fusionPreviewUri = writeFusionPreviewJpeg(burstId, preview, orientationDegrees)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save the RAW fusion preview for $burstId.", exception)
            }
        }

        val frames = burstAlignments.remove(burstId)
        // The alignment report only accompanies a burst kept in full, as diagnostic data.
        if (frames != null && settings.keepRawFrames == KeepRawFrames.ALL) {
            try {
                writeAlignmentCsv(burstId, frames)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save the RAW alignment report for $burstId.", exception)
            }
        }
        val finishDone = SystemClock.elapsedRealtime()
        Log.i(
            TIMING_TAG,
            "$burstId finish: 2x render ${superResolutionDone - finishStart} ms, " +
                "preview build ${previewDone - superResolutionDone} ms, " +
                "preview JPEG + CSV ${finishDone - previewDone} ms",
        )
        return RawBurstFinishResult(
            fusionPreviewSaved = fusionPreviewUri != null,
            superResolutionWidth = superResolution?.width ?: 0,
            superResolutionHeight = superResolution?.height ?: 0,
            superResolutionFrames = superResolution?.contributingFrames ?: 0,
            superResolutionUri = superResolution?.mediaUri,
            superResolutionJpegUri = superResolution?.jpegMediaUri,
            fusionPreviewUri = fusionPreviewUri?.toString(),
        )
    }

    /** Writes the 2x JPEG, plus the 8-bit TIFF master when [saveTiff] is set. */
    private fun writeSuperResolution(
        burstId: String,
        aligner: NativeRawBurstAligner,
        orientationDegrees: Int,
        saveTiff: Boolean,
    ): RawFusionTiff {
        val folder = picturesPath()
        val jpegUri = insertPendingImage("Fabula_${burstId}_2x.jpg", "image/jpeg", folder)
        val tiffUri = if (saveTiff) {
            runCatching { insertPendingImage("Fabula_${burstId}_2x.tiff", "image/tiff", folder) }
                .onFailure { Log.e(TAG, "Could not create the 2x TIFF entry for $burstId.", it) }
                .getOrNull()
        } else {
            null
        }
        try {
            val jpegDescriptor = resolver.openFileDescriptor(jpegUri, "w")
                ?: throw IOException("Could not open the 2x JPEG.")
            val tiffDescriptor = tiffUri?.let { resolver.openFileDescriptor(it, "w") }
            val result = jpegDescriptor.use { jpeg ->
                tiffDescriptor.use { tiff -> aligner.writeSuperResolution(tiff, jpeg, orientationDegrees) }
            } ?: throw IOException("Could not encode the RAW 2x reconstruction.")
            publish(jpegUri, "2x JPEG")
            val publishedTiff = tiffUri?.takeIf { tiffDescriptor != null }?.let { uri ->
                publish(uri, "2x TIFF")
                uri.toString()
            }
            if (publishedTiff == null) tiffUri?.let { resolver.delete(it, null, null) }
            return result.copy(mediaUri = publishedTiff, jpegMediaUri = jpegUri.toString())
        } catch (exception: Exception) {
            resolver.delete(jpegUri, null, null)
            tiffUri?.let { resolver.delete(it, null, null) }
            throw exception
        }
    }

    private fun insertPendingImage(displayName: String, mimeType: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create $displayName.")
    }

    private fun publish(uri: Uri, label: String) {
        val publishValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(uri, publishValues, null, null) <= 0) {
            throw IOException("Could not publish the $label in MediaStore.")
        }
    }

    private fun picturesPath() = "${Environment.DIRECTORY_PICTURES}/Fabula"

    private fun burstPicturesPath(burstId: String) =
        "${Environment.DIRECTORY_PICTURES}/Fabula/RAW_Burst_$burstId"

    private fun alignerForBurst(
        burstId: String,
        frameIndex: Int,
        frame: RawFrameData,
    ): NativeRawBurstAligner {
        if (activeBurstId != burstId || frameIndex == 0) {
            burstAligner?.close()
            val superResolutionEnabled = hasRoomForSuperResolution(frame)
            if (!superResolutionEnabled) {
                Log.w(TAG, "Not enough free storage for the 2x RAW reconstruction.")
            }
            burstAligner = NativeRawBurstAligner(superResolutionEnabled).apply {
                val look = ColorLook.entries.firstOrNull { it.name == burstSettings.colorLook } ?: ColorLook.STANDARD
                if (look != ColorLook.STANDARD) setLook(look.tables())
            }
            activeBurstId = burstId
        }
        return requireNotNull(burstAligner)
    }

    private fun hasRoomForSuperResolution(frame: RawFrameData): Boolean {
        val rawPixels = frame.width.toLong() * frame.height
        val outputPixels = rawPixels * 4L
        if (outputPixels > MAX_SUPER_RESOLUTION_PIXELS) return false
        val rawFrameBytes = rawPixels * frame.pixelStride
        // The 2x TIFF (3 bytes/px) and JPEG, plus the DNGs and spooled RAW frames of the burst.
        val requiredBytes = outputPixels * 4L + rawFrameBytes * MAX_RAW_BURST_FRAMES * 2L +
            MINIMUM_STORAGE_HEADROOM_BYTES
        return appContext.cacheDir.usableSpace >= requiredBytes
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

    // SENSOR_NOISE_PROFILE gives, per CFA channel, variance = scale * x + offset for a normalized
    // signal x in [0, 1]. The aligner compares channel-mixed proxies, so the channels are averaged.
    private fun noiseModel(result: TotalCaptureResult): DoubleArray? {
        val profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE) ?: return null
        val usable = profile.filter { pair ->
            pair.first.isFinite() && pair.second.isFinite() && pair.first >= 0.0 && pair.second >= 0.0
        }
        if (usable.isEmpty()) return null
        return doubleArrayOf(usable.map { it.first }.average(), usable.map { it.second }.average())
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
                "Fabula_${burstId}_preview.jpg",
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, picturesPath())
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
        const val TIMING_TAG = "FabulaTiming"
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
