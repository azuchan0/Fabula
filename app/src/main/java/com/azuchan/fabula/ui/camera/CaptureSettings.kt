package com.azuchan.fabula.ui.camera

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which RAW frames of a burst are kept as DNG files next to the 2x JPEG. */
enum class KeepRawFrames {
    NONE,
    FIRST,
    ALL,
}

data class CaptureSettings(
    val keepRawFrames: KeepRawFrames = KeepRawFrames.NONE,
    val saveTiff: Boolean = false,
    val gridLines: Boolean = true,
    val shutterSound: Boolean = true,
    val burstFrames: Int = 15,
    val captureMode: String = "BURST",
    val histogram: Boolean = false,
    val colorLook: String = "STANDARD",
    val drive: String = "SINGLE",
)

/** Capture preferences, persisted in SharedPreferences and shared by the UI and the RAW writer. */
class CaptureSettingsStore private constructor(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(load())

    val settings: StateFlow<CaptureSettings> = mutableSettings.asStateFlow()

    fun update(transform: (CaptureSettings) -> CaptureSettings) {
        val updated = transform(mutableSettings.value)
        mutableSettings.value = updated
        preferences.edit()
            .putString(KEY_KEEP_RAW, updated.keepRawFrames.name)
            .putBoolean(KEY_SAVE_TIFF, updated.saveTiff)
            .putBoolean(KEY_GRID, updated.gridLines)
            .putBoolean(KEY_SHUTTER_SOUND, updated.shutterSound)
            .putInt(KEY_BURST_FRAMES, updated.burstFrames)
            .putString(KEY_CAPTURE_MODE, updated.captureMode)
            .putBoolean(KEY_HISTOGRAM, updated.histogram)
            .putString(KEY_COLOR_LOOK, updated.colorLook)
            .putString(KEY_DRIVE, updated.drive)
            .apply()
    }

    private fun load(): CaptureSettings {
        val defaults = CaptureSettings()
        return CaptureSettings(
            keepRawFrames = preferences.getString(KEY_KEEP_RAW, null)
                ?.let { name -> KeepRawFrames.entries.firstOrNull { it.name == name } }
                ?: defaults.keepRawFrames,
            saveTiff = preferences.getBoolean(KEY_SAVE_TIFF, defaults.saveTiff),
            gridLines = preferences.getBoolean(KEY_GRID, defaults.gridLines),
            shutterSound = preferences.getBoolean(KEY_SHUTTER_SOUND, defaults.shutterSound),
            burstFrames = preferences.getInt(KEY_BURST_FRAMES, defaults.burstFrames)
                .takeIf { it in BURST_FRAME_CHOICES } ?: defaults.burstFrames,
            captureMode = preferences.getString(KEY_CAPTURE_MODE, null) ?: defaults.captureMode,
            histogram = preferences.getBoolean(KEY_HISTOGRAM, defaults.histogram),
            colorLook = preferences.getString(KEY_COLOR_LOOK, null) ?: defaults.colorLook,
            drive = preferences.getString(KEY_DRIVE, null) ?: defaults.drive,
        )
    }

    companion object {
        val BURST_FRAME_CHOICES = listOf(8, 12, 15)

        private const val PREFERENCES_NAME = "fabula_capture_settings"
        private const val KEY_KEEP_RAW = "keep_raw_frames"
        private const val KEY_SAVE_TIFF = "save_tiff"
        private const val KEY_GRID = "grid_lines"
        private const val KEY_SHUTTER_SOUND = "shutter_sound"
        private const val KEY_BURST_FRAMES = "burst_frames"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_HISTOGRAM = "histogram"
        private const val KEY_COLOR_LOOK = "color_look"
        private const val KEY_DRIVE = "drive"

        @Volatile
        private var instance: CaptureSettingsStore? = null

        fun get(context: Context): CaptureSettingsStore =
            instance ?: synchronized(this) {
                instance ?: CaptureSettingsStore(context).also { instance = it }
            }
    }
}
