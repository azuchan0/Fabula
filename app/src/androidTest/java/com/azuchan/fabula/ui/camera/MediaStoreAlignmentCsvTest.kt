package com.azuchan.fabula.ui.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreAlignmentCsvTest {
    @Test
    fun writesAndPublishesAlignmentCsvInDownloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writer = MediaStoreRawFrameWriter(context)
        val burstId = "test-${UUID.randomUUID()}"
        val regions = (0 until 20).map { index ->
            LocalMotionRegion(
                row = index / 5,
                column = index % 5,
                xCorrectionPixels = 0.0,
                yCorrectionPixels = 0.0,
                confidence = 1.0,
                reliable = true,
            )
        }
        val alignment = RawFrameAlignment(
            xCorrectionPixels = 0.0,
            yCorrectionPixels = 0.0,
            meanAbsoluteError = 0.0,
            confidence = 1.0,
            isReferenceFrame = true,
            reachedSearchLimit = false,
            localMotionSpreadPixels = 0.0,
            meanLocalConfidence = 1.0,
            reliableLocalRegionCount = 20,
            evaluatedLocalRegionCount = 20,
            localRegions = regions,
        )

        val uri = writer.writeAlignmentCsv(burstId, mapOf(0 to alignment))
        try {
            val csv = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Could not read the exported alignment CSV.")

            assertTrue(csv.startsWith("frame,row,column,global_dx_px,global_dy_px"))
            assertTrue(csv.contains("1,0,0,0.000,0.000,1.000,0.000,0.000,1.000,true"))
            assertTrue(uri.toString().contains("downloads"))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
