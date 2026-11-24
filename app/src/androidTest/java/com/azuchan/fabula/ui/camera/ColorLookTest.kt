package com.azuchan.fabula.ui.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorLookTest {
    @Test
    fun standardLookIsIdentity() {
        val tables = ColorLook.STANDARD.tables()
        for (i in 0 until 256) {
            assertEquals(i, tables.curves[i].toInt() and 0xff)
        }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.rgb(200, 40, 90)) }
        applyLook(bitmap, ColorLook.STANDARD)
        assertEquals(Color.rgb(200, 40, 90), bitmap.getPixel(0, 0))
    }

    @Test
    fun monochromeLookRemovesColor() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(220, 30, 30))
            setPixel(1, 0, Color.rgb(30, 60, 220))
        }
        applyLook(bitmap, ColorLook.LEICA_MONO)
        for (x in 0 until 2) {
            val pixel = bitmap.getPixel(x, 0)
            assertEquals(Color.red(pixel), Color.green(pixel))
            assertEquals(Color.green(pixel), Color.blue(pixel))
        }
    }

    @Test
    fun everyLookKeepsCurvesMonotonic() {
        ColorLook.entries.forEach { look ->
            val curves = look.tables().curves
            for (channel in 0 until 3) {
                for (i in 1 until 256) {
                    val previous = curves[channel * 256 + i - 1].toInt() and 0xff
                    val current = curves[channel * 256 + i].toInt() and 0xff
                    assertTrue("$look channel $channel inverts at $i", current >= previous)
                }
            }
        }
    }
}
