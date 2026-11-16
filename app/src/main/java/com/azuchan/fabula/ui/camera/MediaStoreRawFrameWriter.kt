package com.azuchan.fabula.ui.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import com.azuchan.fabula.camera.RawFrameWriter
import java.io.IOException
import java.util.Locale

class MediaStoreRawFrameWriter(context: Context) : RawFrameWriter {
    private val resolver = context.applicationContext.contentResolver

    override fun writeDngFrame(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        burstId: String,
        frameIndex: Int,
    ): String {
        require(image.format == ImageFormat.RAW_SENSOR) {
            "The burst expects RAW_SENSOR buffers."
        }

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
                        .setDescription("Fabula RAW burst $burstId, frame ${frameIndex + 1}")
                        .writeImage(stream, image)
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the DNG in MediaStore.")
            }
            return uri.toString()
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }
}
