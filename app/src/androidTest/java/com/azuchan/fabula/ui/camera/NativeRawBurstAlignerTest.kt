package com.azuchan.fabula.ui.camera

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRawBurstAlignerTest {
    @Test
    fun estimatesTranslationForTexturedFrames() {
        val outputFile = File.createTempFile("fabula-aligner-test-", ".tiff")
        val directOutputFile = File.createTempFile("fabula-aligner-stream-test-", ".tiff")
        val aligner = NativeRawBurstAligner(superResolutionEnabled = true)
        try {
            val reference = aligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0), WIDTH, HEIGHT, ROW_STRIDE, 2)
            val shifted = aligner.addBuffer(rawFrame(shiftX = 8, shiftY = -12), WIDTH, HEIGHT, ROW_STRIDE, 2)

            assertTrue(reference.isReferenceFrame)
            assertFalse(shifted.isReferenceFrame)
            assertEquals(-8.0, shifted.xCorrectionPixels, 1.5)
            assertEquals(12.0, shifted.yCorrectionPixels, 1.5)
            assertTrue("Expected a confident match, got ${shifted.confidence}", shifted.confidence > 0.05)
            assertFalse(shifted.reachedSearchLimit)
            assertTrue("Alignment result: $shifted", shifted.reliableLocalRegionCount > 0)
            assertEquals(20, shifted.evaluatedLocalRegionCount)
            assertTrue(
                "Unexpected local motion spread: ${shifted.localMotionSpreadPixels}",
                shifted.localMotionSpreadPixels < 1.5,
            )
            assertEquals(20, shifted.localRegions.size)
            assertTrue(shifted.localRegions.any { it.reliable })
            assertEquals(
                shifted.reliableLocalRegionCount,
                shifted.localRegions.count { it.reliable },
            )
            val preview = requireNotNull(aligner.finishFusionPreview())
            assertEquals(WIDTH / 2, preview.width)
            assertEquals(HEIGHT / 2, preview.height)
            assertEquals(2, preview.contributingFrames)
            assertTrue(preview.argbData.drop(preview.pixelOffset).any { (it and 0x00ffffff) != 0 })

            val tiff = requireNotNull(aligner.writeSuperResolution(outputFile, null, 90))
            assertEquals(WIDTH * 2, tiff.width)
            assertEquals(HEIGHT * 2, tiff.height)
            assertEquals(2, tiff.contributingFrames)
            val tiffFile = requireNotNull(tiff.file)
            val header = tiffFile.inputStream().use { it.readNBytes(512) }
            assertEquals(512, header.size)
            val tiffHeader = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals('I'.code.toByte(), header[0])
            assertEquals('I'.code.toByte(), header[1])
            assertEquals(42, tiffHeader.getShort(2).toInt())
            val entryCount = tiffHeader.getShort(8).toInt() and 0xffff
            var orientation: Int? = null
            var imageDescription: String? = null
            var stripOffset = 0L
            var encodedWidth = 0
            var encodedHeight = 0
            repeat(entryCount) { index ->
                val entry = 10 + index * 12
                when (tiffHeader.getShort(entry).toInt() and 0xffff) {
                    270 -> {
                        val descriptionLength = tiffHeader.getInt(entry + 4)
                        val descriptionOffset = tiffHeader.getInt(entry + 8)
                        imageDescription = String(
                            header,
                            descriptionOffset,
                            descriptionLength - 1,
                            Charsets.US_ASCII,
                        )
                    }
                    256 -> encodedWidth = tiffHeader.getInt(entry + 8)
                    257 -> encodedHeight = tiffHeader.getInt(entry + 8)
                    273 -> stripOffset = tiffHeader.getInt(entry + 8).toLong() and 0xffff_ffffL
                    274 -> orientation = tiffHeader.getShort(entry + 8).toInt() and 0xffff
                }
            }
            assertEquals(6, orientation)
            assertEquals(tiff.width, encodedWidth)
            assertEquals(tiff.height, encodedHeight)
            assertTrue(requireNotNull(imageDescription).contains("reconstruction prototype"))
            assertEquals(stripOffset + tiff.width.toLong() * tiff.height * 3, tiffFile.length())
            val firstRow = ByteArray(tiff.width * 3)
            RandomAccessFile(tiffFile, "r").use { file ->
                file.seek(stripOffset)
                file.readFully(firstRow)
            }
            assertTrue(firstRow.any { (it.toInt() and 0xff) > 0 })
            assertTrue(firstRow.map { it.toInt() and 0xff }.toSet().size > 1)

            val descriptor = ParcelFileDescriptor.open(
                directOutputFile,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            val streamedTiff = requireNotNull(
                descriptor.use { aligner.writeSuperResolution(it, null, 180) },
            )
            assertEquals(tiff.width, streamedTiff.width)
            assertEquals(tiff.height, streamedTiff.height)
            assertEquals(tiff.contributingFrames, streamedTiff.contributingFrames)
            assertEquals(tiffFile.length(), directOutputFile.length())
            val streamedHeader = ByteBuffer.wrap(directOutputFile.inputStream().use { it.readNBytes(512) })
                .order(ByteOrder.LITTLE_ENDIAN)
            var streamedOrientation: Int? = null
            repeat(streamedHeader.getShort(8).toInt() and 0xffff) { index ->
                val entry = 10 + index * 12
                if ((streamedHeader.getShort(entry).toInt() and 0xffff) == 274) {
                    streamedOrientation = streamedHeader.getShort(entry + 8).toInt() and 0xffff
                }
            }
            assertEquals(3, streamedOrientation)
        } finally {
            aligner.close()
            outputFile.delete()
            directOutputFile.delete()
        }
    }

    @Test
    fun marksUniformFramesAsLowConfidence() {
        val aligner = NativeRawBurstAligner(superResolutionEnabled = true)
        try {
            aligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0, uniform = true), WIDTH, HEIGHT, ROW_STRIDE, 2)
            val repeated = aligner.addBuffer(
                rawFrame(shiftX = 0, shiftY = 0, uniform = true),
                WIDTH,
                HEIGHT,
                ROW_STRIDE,
                2,
            )

            assertTrue(repeated.confidence < 0.05)
            assertTrue(repeated.reachedSearchLimit)
            assertEquals(0.0, repeated.xCorrectionPixels, 0.0)
            assertEquals(0.0, repeated.yCorrectionPixels, 0.0)
            assertEquals(0, repeated.evaluatedLocalRegionCount)
            assertTrue(repeated.localRegions.none { it.reliable })
        } finally {
            aligner.close()
        }
    }

    @Test
    fun keepsReducedPreviewWhenDiskBackedReconstructionIsUnavailable() {
        val outputFile = File.createTempFile("fabula-no-space-", ".tiff")
        val aligner = NativeRawBurstAligner(superResolutionEnabled = false)
        try {
            aligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0), WIDTH, HEIGHT, ROW_STRIDE, 2)

            assertEquals(1, requireNotNull(aligner.finishFusionPreview()).contributingFrames)
            assertEquals(null, aligner.writeSuperResolution(outputFile, null, 0))
        } finally {
            aligner.close()
            outputFile.delete()
        }
    }

    @Test
    fun reconstructsFlatFieldWithoutBanding() {
        val outputFile = File.createTempFile("fabula-aligner-flat-", ".tiff")
        val aligner = NativeRawBurstAligner(superResolutionEnabled = true)
        try {
            aligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0, uniform = true), WIDTH, HEIGHT, ROW_STRIDE, 2)
            aligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0, uniform = true), WIDTH, HEIGHT, ROW_STRIDE, 2)
            val tiff = requireNotNull(aligner.writeSuperResolution(outputFile, null, 0))
            val pixels = readTiffRegion(outputFile, tiff.width, x = 8, y = 8, size = tiff.width - 16)
            val values = pixels.map { it.toInt() and 0xff }
            val spread = values.max() - values.min()
            assertTrue("Flat field should stay flat, spread was $spread", spread <= 1)
            assertTrue(values.first() > 0)
        } finally {
            aligner.close()
            outputFile.delete()
        }
    }

    @Test
    fun rejectsMovingObjectAgainstReference() {
        val staticOutput = File.createTempFile("fabula-aligner-static-", ".tiff")
        val movingOutput = File.createTempFile("fabula-aligner-moving-", ".tiff")
        val staticAligner = NativeRawBurstAligner(superResolutionEnabled = true)
        val movingAligner = NativeRawBurstAligner(superResolutionEnabled = true)
        try {
            val reference = rawFrame(shiftX = 0, shiftY = 0)
            staticAligner.addBuffer(reference, WIDTH, HEIGHT, ROW_STRIDE, 2)
            staticAligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0), WIDTH, HEIGHT, ROW_STRIDE, 2)
            movingAligner.addBuffer(rawFrame(shiftX = 0, shiftY = 0), WIDTH, HEIGHT, ROW_STRIDE, 2)
            val moving = rawFrame(shiftX = 0, shiftY = 0)
            for (y in 400 until 600) {
                for (x in 400 until 600) moving.putShort(y * ROW_STRIDE + x * 2, 60_000.toShort())
            }
            movingAligner.addBuffer(moving, WIDTH, HEIGHT, ROW_STRIDE, 2)

            val staticTiff = requireNotNull(staticAligner.writeSuperResolution(staticOutput, null, 0))
            val movingTiff = requireNotNull(movingAligner.writeSuperResolution(movingOutput, null, 0))
            val expected = readTiffRegion(staticOutput, staticTiff.width, x = 900, y = 900, size = 200)
            val actual = readTiffRegion(movingOutput, movingTiff.width, x = 900, y = 900, size = 200)
            val meanDifference = expected.indices.sumOf {
                kotlin.math.abs((expected[it].toInt() and 0xff) - (actual[it].toInt() and 0xff))
            }.toDouble() / expected.size
            assertTrue("Moving object leaked into the fusion: $meanDifference", meanDifference < 2.0)
        } finally {
            staticAligner.close()
            movingAligner.close()
            staticOutput.delete()
            movingOutput.delete()
        }
    }

    @Test
    fun alignsNoisyLowLightBurst() {
        val aligner = NativeRawBurstAligner(superResolutionEnabled = false)
        val shifts = listOf(0 to 0, 3 to -2, -5 to 4, 7 to 1, -2 to -6, 1 to 5, -4 to -1, 6 to -3)
        val report = StringBuilder()
        try {
            var contributing = 0
            shifts.forEachIndexed { index, (shiftX, shiftY) ->
                val alignment = aligner.addBuffer(
                    darkFrame(shiftX, shiftY, seed = index + 1L),
                    WIDTH,
                    HEIGHT,
                    ROW_STRIDE,
                    2,
                    blackLevels = doubleArrayOf(64.0, 64.0, 64.0, 64.0),
                    whiteLevel = 1023.0,
                )
                if (index == 0) return@forEachIndexed
                report.append(
                    "frame $index: x=${"%.2f".format(alignment.xCorrectionPixels)} " +
                        "y=${"%.2f".format(alignment.yCorrectionPixels)} " +
                        "conf=${"%.3f".format(alignment.confidence)} " +
                        "local=${alignment.reliableLocalRegionCount}\n",
                )
                val accurate = kotlin.math.abs(alignment.xCorrectionPixels + shiftX) <= 1.0 &&
                    kotlin.math.abs(alignment.yCorrectionPixels + shiftY) <= 1.0
                if (accurate && alignment.confidence >= 0.05 && !alignment.reachedSearchLimit &&
                    alignment.reliableLocalRegionCount >= 10
                ) {
                    contributing++
                }
            }
            assertEquals("Low-light alignment:\n$report", shifts.size - 1, contributing)
            assertEquals(shifts.size, requireNotNull(aligner.finishFusionPreview()).contributingFrames)
        } finally {
            aligner.close()
        }
    }

    @Test
    fun writesDecodableJpegMatchingTheTiff() {
        val tiffFile = File.createTempFile("fabula-aligner-jpeg-", ".tiff")
        val jpegFile = File.createTempFile("fabula-aligner-jpeg-", ".jpg")
        val aligner = NativeRawBurstAligner(superResolutionEnabled = true)
        try {
            aligner.addBuffer(smoothFrame(0.0, 0.0), WIDTH, HEIGHT, ROW_STRIDE, 2)
            aligner.addBuffer(smoothFrame(1.5, -0.5), WIDTH, HEIGHT, ROW_STRIDE, 2)
            val result = requireNotNull(aligner.writeSuperResolution(tiffFile, jpegFile, 90))
            assertEquals(2, result.contributingFrames)

            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(jpegFile.absolutePath)) {
                "Android could not decode the 2x JPEG (${jpegFile.length()} bytes)."
            }
            assertEquals(result.width, bitmap.width)
            assertEquals(result.height, bitmap.height)
            assertEquals(
                android.media.ExifInterface.ORIENTATION_ROTATE_90,
                android.media.ExifInterface(jpegFile.absolutePath).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_UNDEFINED,
                ),
            )
            // The JPEG must be a faithful lossy copy of the TIFF pixels.
            val size = 256
            val x0 = 900
            val y0 = 700
            val tiffPixels = readTiffRegion(tiffFile, result.width, x = x0, y = y0, size = size)
            var totalError = 0L
            for (row in 0 until size) {
                for (column in 0 until size) {
                    val argb = bitmap.getPixel(x0 + column, y0 + row)
                    val offset = (row * size + column) * 3
                    totalError += kotlin.math.abs(((argb shr 16) and 0xff) - (tiffPixels[offset].toInt() and 0xff))
                    totalError += kotlin.math.abs(((argb shr 8) and 0xff) - (tiffPixels[offset + 1].toInt() and 0xff))
                    totalError += kotlin.math.abs((argb and 0xff) - (tiffPixels[offset + 2].toInt() and 0xff))
                }
            }
            val meanError = totalError.toDouble() / (size * size * 3)
            assertTrue("JPEG differs from the TIFF by $meanError on average", meanError < 3.0)
            bitmap.recycle()
        } finally {
            aligner.close()
            tiffFile.delete()
            jpegFile.delete()
        }
    }

    @Test
    fun recoversNegativeSubpixelShifts() {
        val aligner = NativeRawBurstAligner(superResolutionEnabled = false)
        // Content shifted by +s needs a correction of -s; negative corrections used to lose their
        // sub-pixel part in the fine refinement.
        val shifts = listOf(1.5 to 2.5, -2.5 to 1.5, 3.25 to -0.75)
        val report = StringBuilder()
        try {
            aligner.addBuffer(smoothFrame(0.0, 0.0), WIDTH, HEIGHT, ROW_STRIDE, 2)
            var worstError = 0.0
            shifts.forEach { (shiftX, shiftY) ->
                val alignment = aligner.addBuffer(smoothFrame(shiftX, shiftY), WIDTH, HEIGHT, ROW_STRIDE, 2)
                val error = maxOf(
                    kotlin.math.abs(alignment.xCorrectionPixels + shiftX),
                    kotlin.math.abs(alignment.yCorrectionPixels + shiftY),
                )
                worstError = maxOf(worstError, error)
                report.append(
                    "shift ($shiftX, $shiftY): correction " +
                        "(${"%.2f".format(alignment.xCorrectionPixels)}, " +
                        "${"%.2f".format(alignment.yCorrectionPixels)}) conf=${"%.3f".format(alignment.confidence)}\n",
                )
            }
            assertTrue("Sub-pixel alignment too coarse:\n$report", worstError <= 0.3)
        } finally {
            aligner.close()
        }
    }

    // Band-limited texture sampled at a continuous offset, so sub-pixel shifts are exact.
    private fun smoothFrame(shiftX: Double, shiftY: Double): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(ROW_STRIDE * HEIGHT).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val sourceX = x - shiftX
                val sourceY = y - shiftY
                var value = 4000.0
                for (wave in smoothWaves) {
                    value += wave[0] * kotlin.math.sin(wave[1] * sourceX + wave[2] * sourceY + wave[3])
                }
                buffer.putShort((y * ROW_STRIDE) + x * 2, value.roundToInt().coerceIn(0, 16383).toShort())
            }
        }
        return buffer
    }

    // Very dark scene: a few DN of large-scale texture above black, buried in shot and read noise.
    private fun darkFrame(shiftX: Int, shiftY: Int, seed: Long): ByteBuffer {
        val random = java.util.Random(seed)
        val buffer = ByteBuffer.allocateDirect(ROW_STRIDE * HEIGHT).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val cellX = Math.floorMod((x - shiftX).floorDiv(8), DARK_CELLS)
                val cellY = Math.floorMod((y - shiftY).floorDiv(8), DARK_CELLS)
                val texture = darkTexture[cellY * DARK_CELLS + cellX]
                val signal = 2.0 + texture
                val noise = random.nextGaussian() * kotlin.math.sqrt(signal + 4.0)
                val sample = (64.0 + signal + noise).roundToInt().coerceIn(0, 1023)
                buffer.putShort((y * ROW_STRIDE) + x * 2, sample.toShort())
            }
        }
        return buffer
    }

    private fun readTiffRegion(file: File, width: Int, x: Int, y: Int, size: Int): ByteArray {
        val header = ByteBuffer.wrap(file.inputStream().use { it.readNBytes(512) })
            .order(ByteOrder.LITTLE_ENDIAN)
        var stripOffset = 0L
        repeat(header.getShort(8).toInt() and 0xffff) { index ->
            val entry = 10 + index * 12
            if ((header.getShort(entry).toInt() and 0xffff) == 273) {
                stripOffset = header.getInt(entry + 8).toLong() and 0xffff_ffffL
            }
        }
        val region = ByteArray(size * size * 3)
        RandomAccessFile(file, "r").use { input ->
            for (row in 0 until size) {
                input.seek(stripOffset + ((y + row).toLong() * width + x) * 3)
                input.readFully(region, row * size * 3, size * 3)
            }
        }
        return region
    }

    private fun rawFrame(
        shiftX: Int,
        shiftY: Int,
        uniform: Boolean = false,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(ROW_STRIDE * HEIGHT).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val sourceX = x - shiftX
                val sourceY = y - shiftY
                val sample = if (uniform) {
                    4096
                } else if (sourceX < 0 || sourceY < 0 || sourceX >= WIDTH || sourceY >= HEIGHT) {
                    512
                } else {
                    val cellX = sourceX / 2
                    val cellY = sourceY / 2
                    1024 + (((cellX * 73_856_093) xor (cellY * 19_349_663)) and 0x3fff)
                }
                buffer.putShort((y * ROW_STRIDE) + x * 2, sample.toShort())
            }
        }
        return buffer
    }

    private companion object {
        const val DARK_CELLS = 160
        val darkTexture: IntArray = java.util.Random(42).let { random ->
            IntArray(DARK_CELLS * DARK_CELLS) { random.nextInt(4) }
        }
        // Amplitude, x and y angular frequencies (periods of roughly 12-60 px), and phase.
        val smoothWaves: List<DoubleArray> = java.util.Random(7).let { random ->
            List(12) {
                val period = 12.0 + random.nextDouble() * 48.0
                val angle = random.nextDouble() * Math.PI
                val frequency = 2.0 * Math.PI / period
                doubleArrayOf(
                    150.0 + random.nextDouble() * 250.0,
                    frequency * kotlin.math.cos(angle),
                    frequency * kotlin.math.sin(angle),
                    random.nextDouble() * 2.0 * Math.PI,
                )
            }
        }
        const val WIDTH = 1024
        const val HEIGHT = 1024
        const val ROW_STRIDE = WIDTH * 2
    }
}
