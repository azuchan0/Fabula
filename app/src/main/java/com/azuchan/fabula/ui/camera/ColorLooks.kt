package com.azuchan.fabula.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour renderings inspired by well-known camera, Fujifilm film-simulation and Kodak film looks.
 * They are Fabula's own approximations (saturation, contrast, highlight shoulder, hue cross-talk
 * and split-toning), not the manufacturers' profiles.
 */
enum class ColorLook(val label: String, val short: String, val family: String) {
    STANDARD("Standard", "STD", "Fabula"),
    LEICA("Leica Classic", "LEICA", "Leica"),
    LEICA_MONO("Leica Monochrom", "MONO", "Leica"),
    HASSELBLAD("Hasselblad Natural", "HBLAD", "Hasselblad"),
    PROVIA("Provia / Standard", "PROVIA", "Fujifilm"),
    VELVIA("Velvia / Vivid", "VELVIA", "Fujifilm"),
    ASTIA("Astia / Soft", "ASTIA", "Fujifilm"),
    CHROME("Classic Chrome", "CC", "Fujifilm"),
    REALA_ACE("Reala Ace", "REALA", "Fujifilm"),
    PRO_NEG_HI("Pro Neg. Hi", "NEG.HI", "Fujifilm"),
    PRO_NEG_STD("Pro Neg. Std", "NEG.ST", "Fujifilm"),
    CLASSIC_NEG("Classic Neg.", "C.NEG", "Fujifilm"),
    NOSTALGIC_NEG("Nostalgic Neg.", "N.NEG", "Fujifilm"),
    ETERNA("Eterna / Cinema", "ETERNA", "Fujifilm"),
    ETERNA_BLEACH("Eterna Bleach Bypass", "BLEACH", "Fujifilm"),
    ACROS("Acros", "ACROS", "Fujifilm"),
    SEPIA("Sepia", "SEPIA", "Fujifilm"),
    PORTRA_160("Portra 160", "P160", "Kodak"),
    PORTRA_400("Portra 400", "P400", "Kodak"),
    EKTAR_100("Ektar 100", "EKTAR", "Kodak"),
    GOLD_200("Gold 200", "GOLD", "Kodak"),
    EKTACHROME("Ektachrome E100", "E100", "Kodak"),
    KODACHROME("Kodachrome 64", "K64", "Kodak"),
    TRI_X("Tri-X 400", "TRI-X", "Kodak"),
    FILM("Warm Film", "FILM", "Fabula"),
    CINEMA("Cinema", "CINE", "Fabula"),
}

/** A look as a 3x3 matrix on display RGB followed by one 256-entry curve per channel. */
class LookTables(val matrix: FloatArray, val curves: ByteArray)

private class LookRecipe(
    val saturation: Float = 1f,
    val monoWeights: FloatArray? = null,
    val contrast: Float = 0f,
    val crush: Float = 0f,
    val fade: Float = 0f,
    val shoulder: Float = 0f,
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val channelGain: FloatArray = floatArrayOf(1f, 1f, 1f),
    val blueToGreen: Float = 0f,
    val redToGreen: Float = 0f,
)

private fun tint(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

private fun ColorLook.recipe(): LookRecipe = when (this) {
    ColorLook.STANDARD -> LookRecipe()
    // Dense contrast, deep blacks, a touch of warmth in the highlights and cool shadows.
    ColorLook.LEICA -> LookRecipe(
        saturation = 1.10f, contrast = 0.35f, crush = 0.02f,
        shadowTint = tint(-0.006f, 0f, 0.012f), highlightTint = tint(0.02f, 0.006f, -0.018f),
    )
    // Panchromatic black and white with a slight red-filter response and firm contrast.
    ColorLook.LEICA_MONO -> LookRecipe(monoWeights = floatArrayOf(0.36f, 0.54f, 0.10f), contrast = 0.50f, crush = 0.03f)
    // Neutral, true-to-life colour with gentle contrast and soft highlights.
    ColorLook.HASSELBLAD -> LookRecipe(
        saturation = 1.03f, contrast = 0.12f, fade = 0.01f, shoulder = 0.15f, highlightTint = tint(0.008f, 0.004f, 0f),
    )
    // Fujifilm-style simulations.
    ColorLook.PROVIA -> LookRecipe(saturation = 1.06f, contrast = 0.18f)
    ColorLook.VELVIA -> LookRecipe(
        saturation = 1.38f, contrast = 0.36f, crush = 0.02f,
        highlightTint = tint(0.012f, -0.004f, 0.01f), channelGain = floatArrayOf(1.01f, 0.99f, 1.02f),
    )
    ColorLook.ASTIA -> LookRecipe(
        saturation = 1.06f, contrast = 0.04f, shoulder = 0.25f, highlightTint = tint(0.012f, 0.006f, -0.004f),
    )
    ColorLook.CHROME -> LookRecipe(
        saturation = 0.78f, contrast = 0.40f, blueToGreen = 0.05f,
        shadowTint = tint(0f, 0.008f, 0.02f), highlightTint = tint(0.01f, 0.004f, -0.012f),
    )
    ColorLook.REALA_ACE -> LookRecipe(saturation = 1.02f, contrast = 0.22f, shadowTint = tint(-0.004f, 0f, 0.006f))
    ColorLook.PRO_NEG_HI -> LookRecipe(saturation = 0.96f, contrast = 0.26f, highlightTint = tint(0.008f, 0.004f, -0.004f))
    ColorLook.PRO_NEG_STD -> LookRecipe(saturation = 0.88f, contrast = 0.06f, fade = 0.012f, shoulder = 0.2f)
    ColorLook.CLASSIC_NEG -> LookRecipe(
        saturation = 0.86f, contrast = 0.38f, crush = 0.015f, blueToGreen = 0.04f, redToGreen = 0.03f,
        shadowTint = tint(-0.01f, 0.012f, 0.018f), highlightTint = tint(0.03f, 0.012f, -0.022f),
    )
    ColorLook.NOSTALGIC_NEG -> LookRecipe(
        saturation = 0.92f, contrast = 0.16f, fade = 0.02f,
        shadowTint = tint(0.008f, 0f, -0.004f), highlightTint = tint(0.045f, 0.022f, -0.035f),
    )
    ColorLook.ETERNA -> LookRecipe(
        saturation = 0.66f, contrast = -0.10f, fade = 0.04f, shoulder = 0.2f,
        shadowTint = tint(-0.008f, 0.006f, 0.014f), highlightTint = tint(0.008f, 0.006f, -0.006f),
    )
    ColorLook.ETERNA_BLEACH -> LookRecipe(saturation = 0.42f, contrast = 0.48f, crush = 0.02f)
    ColorLook.ACROS -> LookRecipe(monoWeights = floatArrayOf(0.30f, 0.60f, 0.10f), contrast = 0.36f, shoulder = 0.15f)
    ColorLook.SEPIA -> LookRecipe(
        monoWeights = floatArrayOf(0.30f, 0.59f, 0.11f), contrast = 0.12f, fade = 0.02f,
        shadowTint = tint(0.03f, 0.008f, -0.03f), highlightTint = tint(0.06f, 0.025f, -0.06f),
    )
    // Kodak-style film stocks.
    ColorLook.PORTRA_160 -> LookRecipe(
        saturation = 0.84f, contrast = 0.04f, fade = 0.03f, shoulder = 0.25f, highlightTint = tint(0.024f, 0.01f, -0.02f),
    )
    ColorLook.PORTRA_400 -> LookRecipe(
        saturation = 0.92f, contrast = 0.10f, fade = 0.04f, shoulder = 0.2f,
        shadowTint = tint(0f, 0.006f, 0.002f), highlightTint = tint(0.03f, 0.012f, -0.022f),
    )
    ColorLook.EKTAR_100 -> LookRecipe(
        saturation = 1.32f, contrast = 0.26f, channelGain = floatArrayOf(1.03f, 1f, 1.01f),
        shadowTint = tint(0f, 0f, 0.01f),
    )
    ColorLook.GOLD_200 -> LookRecipe(
        saturation = 1.08f, contrast = 0.15f, fade = 0.03f,
        shadowTint = tint(0.01f, 0.006f, -0.01f), highlightTint = tint(0.04f, 0.03f, -0.04f),
    )
    ColorLook.EKTACHROME -> LookRecipe(
        saturation = 1.16f, contrast = 0.30f, shadowTint = tint(-0.004f, 0f, 0.012f), highlightTint = tint(-0.006f, 0f, 0.014f),
    )
    ColorLook.KODACHROME -> LookRecipe(
        saturation = 1.22f, contrast = 0.42f, crush = 0.03f, channelGain = floatArrayOf(1.03f, 0.99f, 0.98f),
        shadowTint = tint(0f, 0f, 0.01f), highlightTint = tint(0.02f, 0.006f, -0.014f),
    )
    ColorLook.TRI_X -> LookRecipe(monoWeights = floatArrayOf(0.30f, 0.59f, 0.11f), contrast = 0.56f, crush = 0.03f)
    // Lifted blacks and warm, creamy highlights, like colour negative film.
    ColorLook.FILM -> LookRecipe(
        saturation = 0.92f, contrast = 0.10f, fade = 0.05f,
        shadowTint = tint(0.01f, 0f, -0.01f), highlightTint = tint(0.035f, 0.015f, -0.025f),
    )
    // Teal shadows and warm skin tones.
    ColorLook.CINEMA -> LookRecipe(
        contrast = 0.25f, shadowTint = tint(-0.02f, 0.01f, 0.03f), highlightTint = tint(0.03f, 0.01f, -0.03f),
    )
}

fun ColorLook.tables(): LookTables {
    val recipe = recipe()
    val luma = recipe.monoWeights ?: floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    val s = if (recipe.monoWeights != null) 0f else recipe.saturation
    val matrix = FloatArray(9) { index ->
        val row = index / 3
        val column = index % 3
        ((1f - s) * luma[column] + if (row == column) s else 0f) * recipe.channelGain[row]
    }
    // Small hue cross-talk: blues towards cyan, reds towards orange.
    if (recipe.blueToGreen != 0f) matrix[1 * 3 + 2] += recipe.blueToGreen
    if (recipe.redToGreen != 0f) matrix[1 * 3 + 0] += recipe.redToGreen
    val curves = ByteArray(768)
    for (channel in 0 until 3) {
        for (i in 0 until 256) {
            var y = i / 255f
            // S-curve: blend towards smoothstep for more mid-tone contrast (negative flattens).
            val smooth = y * y * (3f - 2f * y)
            y += (smooth - y) * recipe.contrast
            // Shoulder: compress the top end so highlights roll off instead of clipping.
            if (recipe.shoulder > 0f) y = y * (1f + recipe.shoulder) / (1f + recipe.shoulder * y)
            if (recipe.crush > 0f) y = max(0f, (y - recipe.crush) / (1f - recipe.crush))
            y = recipe.fade + (1f - recipe.fade) * y
            y += recipe.shadowTint[channel] * (1f - y) * (1f - y) + recipe.highlightTint[channel] * y * y
            curves[channel * 256 + i] = (min(1f, max(0f, y)) * 255f).roundToInt().toByte()
        }
    }
    return LookTables(matrix, curves)
}

/** Native, multi-threaded look application on a bitmap's pixels. */
internal object NativeLook {
    init {
        System.loadLibrary("fabula")
    }

    @JvmStatic
    external fun applyLook(bitmap: Bitmap, matrix: FloatArray, curves: ByteArray): Boolean
}

/** Applies [look] in place to every pixel of [bitmap] (ARGB_8888, mutable). */
fun applyLook(bitmap: Bitmap, look: ColorLook) {
    if (look == ColorLook.STANDARD) return
    val tables = look.tables()
    if (runCatching { NativeLook.applyLook(bitmap, tables.matrix, tables.curves) }.getOrDefault(false)) return
    val m = tables.matrix
    val curves = tables.curves
    val width = bitmap.width
    val row = IntArray(width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val pixel = row[x]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            val nr = (m[0] * r + m[1] * g + m[2] * b).roundToInt().coerceIn(0, 255)
            val ng = (m[3] * r + m[4] * g + m[5] * b).roundToInt().coerceIn(0, 255)
            val nb = (m[6] * r + m[7] * g + m[8] * b).roundToInt().coerceIn(0, 255)
            row[x] = (pixel and -0x1000000) or
                ((curves[nr].toInt() and 0xff) shl 16) or
                ((curves[256 + ng].toInt() and 0xff) shl 8) or
                (curves[512 + nb].toInt() and 0xff)
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
}

private val CopiedExifTags = listOf(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
)

/**
 * Re-renders a camera JPEG with [look], keeping its EXIF metadata. Returns the original bytes for
 * the standard look or when the image cannot be decoded.
 */
fun renderJpegWithLook(jpeg: ByteArray, look: ColorLook, scratchDirectory: File): ByteArray {
    if (look == ColorLook.STANDARD) return jpeg
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return jpeg
    val scratch = File.createTempFile("fabula_look_", ".jpg", scratchDirectory)
    return try {
        applyLook(bitmap, look)
        scratch.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val source = ExifInterface(ByteArrayInputStream(jpeg))
        val target = ExifInterface(scratch.absolutePath)
        CopiedExifTags.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        target.setAttribute(ExifInterface.TAG_SOFTWARE, "Fabula · ${look.label}")
        target.saveAttributes()
        scratch.readBytes()
    } catch (_: Exception) {
        jpeg
    } finally {
        bitmap.recycle()
        scratch.delete()
    }
}
