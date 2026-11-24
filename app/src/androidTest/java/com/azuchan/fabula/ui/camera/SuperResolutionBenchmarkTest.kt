package com.azuchan.fabula.ui.camera

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-size burst benchmark (4000x3000 RAW frames mapped from spool files, 8000x6000 TIFF + JPEG).
 * Skipped unless the runner gets `-e benchmark true`; results are logged under FabulaBenchmark.
 */
@RunWith(AndroidJUnit4::class)
class SuperResolutionBenchmarkTest {
    @Test
    fun rendersFullSizeBurst() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e benchmark true to run the benchmark.", arguments.getString("benchmark") == "true")
        val frameCount = arguments.getString("frames")?.toIntOrNull() ?: 15
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "benchmark").apply {
            deleteRecursively()
            mkdirs()
        }

        // A textured scene larger than the frame, so every frame can be a shifted crop of it.
        val baseWidth = WIDTH + 2 * MARGIN
        val baseHeight = HEIGHT + 2 * MARGIN
        val random = java.util.Random(1)
        val cells = IntArray((baseWidth / 3 + 1) * (baseHeight / 3 + 1)) { 64 + random.nextInt(900) }
        val scene = ShortArray(baseWidth * baseHeight) { index ->
            val x = index % baseWidth
            val y = index / baseWidth
            (cells[(y / 3) * (baseWidth / 3 + 1) + x / 3] + random.nextInt(16)).toShort()
        }

        val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 2).order(ByteOrder.LITTLE_ENDIAN)
        val samples = buffer.asShortBuffer()
        val aligner = NativeRawBurstAligner(superResolutionEnabled = true)
        val tiffFile = File(directory, "benchmark.tiff")
        val jpegFile = File(directory, "benchmark.jpg")
        try {
            var alignMs = 0L
            repeat(frameCount) { frame ->
                val shiftX = (frame * 7) % 9 - 4
                val shiftY = (frame * 5) % 7 - 3
                for (y in 0 until HEIGHT) {
                    samples.position(y * WIDTH)
                    samples.put(scene, (y + MARGIN + shiftY) * baseWidth + MARGIN + shiftX, WIDTH)
                }
                val spool = File(directory, "frame_$frame.raw16")
                FileOutputStream(spool).use { output ->
                    val data = buffer.duplicate().apply { clear() }
                    while (data.hasRemaining()) output.channel.write(data)
                }
                val start = SystemClock.elapsedRealtime()
                aligner.addBuffer(
                    buffer,
                    WIDTH,
                    HEIGHT,
                    WIDTH * 2,
                    2,
                    blackLevels = doubleArrayOf(64.0, 64.0, 64.0, 64.0),
                    whiteLevel = 1023.0,
                    spoolPath = spool.absolutePath,
                )
                alignMs += SystemClock.elapsedRealtime() - start
            }
            val renderStart = SystemClock.elapsedRealtime()
            val result = requireNotNull(aligner.writeSuperResolution(tiffFile, jpegFile, 90))
            val renderMs = SystemClock.elapsedRealtime() - renderStart
            Log.i(
                TAG,
                "$frameCount frames: align ${alignMs} ms total, render ${renderMs} ms, " +
                    "${result.contributingFrames} contributing, TIFF ${tiffFile.length()} B, JPEG ${jpegFile.length()} B",
            )
            assertEquals(WIDTH * 2, result.width)
            assertEquals(HEIGHT * 2, result.height)
        } finally {
            aligner.close()
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "FabulaBenchmark"
        const val WIDTH = 4000
        const val HEIGHT = 3000
        const val MARGIN = 16
    }
}
