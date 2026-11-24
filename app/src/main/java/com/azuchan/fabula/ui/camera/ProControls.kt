package com.azuchan.fabula.ui.camera

import android.hardware.camera2.CameraMetadata
import com.azuchan.fabula.camera.Camera2PreviewController
import com.azuchan.fabula.camera.CameraExposureControls
import com.azuchan.fabula.camera.CameraExposureMode
import com.azuchan.fabula.camera.CameraFocusControls
import com.azuchan.fabula.camera.CameraFocusMode
import com.azuchan.fabula.camera.CameraLiveReadout
import com.azuchan.fabula.camera.CameraWhiteBalanceControls
import com.azuchan.fabula.camera.CameraWhiteBalanceMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Drive modes, as on a camera's drive dial. Continuous modes fire while the shutter is held. */
enum class DriveMode(val short: String, val label: String, val intervalMs: Long = 0L, val timerSeconds: Int = 0) {
    SINGLE("S", "Single"),
    CL("CL", "Continuous low", intervalMs = 333L),
    CH("CH", "Continuous high", intervalMs = 125L),
    TIMER_3("3s", "Self-timer 3 s", timerSeconds = 3),
    TIMER_10("10s", "Self-timer 10 s", timerSeconds = 10),
}

fun CaptureSettings.driveMode(): DriveMode = DriveMode.entries.firstOrNull { it.name == drive } ?: DriveMode.SINGLE

fun CaptureSettings.look(): ColorLook = ColorLook.entries.firstOrNull { it.name == colorLook } ?: ColorLook.STANDARD

/** Third-stop ISO values, as on a camera's ISO dial. */
private val IsoStops = listOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
    1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
)

/** Third-stop shutter speeds in seconds, from 1/8000 to 30". */
private val ShutterStopsSeconds: List<Double> = listOf(
    8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
    200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4,
).map { 1.0 / it } + listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)

/** Focus distances in metres, near to far; infinity is the last stop. */
private val FocusStopsMeters = listOf(0.1, 0.12, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.7, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)

/** Builds the readout strip and dials from the controller state, and applies dial changes. */
class ProControls(
    private val exposure: CameraExposureControls,
    private val whiteBalance: CameraWhiteBalanceControls,
    private val focus: CameraFocusControls,
    private val live: CameraLiveReadout,
    private val settings: CaptureSettings,
    private val captureMode: CaptureMode,
    // Before the camera reports its capabilities nothing is greyed out, to avoid a grey flash.
    private val capabilitiesKnown: Boolean = true,
) {
    private fun unsupported(supported: Boolean, what: String): String? =
        if (!supported && capabilitiesKnown) "$what isn't available on this phone" else null

    // Video has no drive modes, and looks are applied to stills only.
    private val drives = when (captureMode) {
        CaptureMode.PHOTO -> DriveMode.entries
        CaptureMode.BURST -> listOf(DriveMode.SINGLE, DriveMode.TIMER_3, DriveMode.TIMER_10)
        CaptureMode.VIDEO -> emptyList()
    }
    private val drive = settings.driveMode().takeIf { it in drives } ?: DriveMode.SINGLE

    private val manualExposure = exposure.mode == CameraExposureMode.MANUAL

    private val isoStops = exposure.isoRange?.let { range -> IsoStops.filter { it in range.lower..range.upper } }.orEmpty()
    private val shutterStops = exposure.exposureTimeRangeNs?.let { range ->
        ShutterStopsSeconds.filter { (it * 1e9).roundToLong() in range.lower..range.upper }
    }.orEmpty()
    private val evIndices = exposure.exposureCompensationRange?.let { (it.lower..it.upper).toList() }.orEmpty()
    private val evStep = exposure.exposureCompensationStep?.toFloat() ?: 0f
    private val kelvinStops = whiteBalance.colorTemperatureRange
        ?.takeIf { whiteBalance.cctSupported }
        ?.let { range -> (range.lower..range.upper step 100).toList() }
        .orEmpty()
    private val presetStops = whiteBalance.availableAwbModes
        .filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF && it != CameraMetadata.CONTROL_AWB_MODE_AUTO }
    private val focusStops: List<Double> = run {
        val nearest = if (focus.minimumFocusDistanceDiopters > 0f) 1.0 / focus.minimumFocusDistanceDiopters else 0.1
        FocusStopsMeters.filter { it >= nearest - 1e-6 } + Double.POSITIVE_INFINITY
    }

    private val currentIso: Int = if (manualExposure) exposure.iso else live.iso ?: exposure.iso
    private val currentExposureNs: Long =
        if (manualExposure) exposure.exposureTimeNs else live.exposureTimeNs ?: exposure.exposureTimeNs
    private val currentFocusDiopters: Float =
        if (focus.mode == CameraFocusMode.MANUAL) focus.focusDistanceDiopters else live.focusDistanceDiopters ?: 0f

    fun readouts(): List<Readout> = listOf(
        Readout(
            ProParam.MODE,
            if (manualExposure) "M" else "P",
            manual = manualExposure,
            unavailable = unsupported(exposure.manualSensorSupported, "Manual exposure"),
        ),
        Readout(
            ProParam.ISO,
            currentIso.toString(),
            manual = manualExposure,
            unavailable = unsupported(isoStops.isNotEmpty(), "Manual ISO"),
        ),
        Readout(
            ProParam.SHUTTER,
            formatShutter(currentExposureNs),
            manual = manualExposure,
            unavailable = unsupported(shutterStops.isNotEmpty(), "Manual shutter speed"),
        ),
        Readout(
            ProParam.EV,
            formatEv(exposure.exposureCompensationIndex * evStep),
            manual = exposure.exposureCompensationIndex != 0,
            unavailable = unsupported(evIndices.isNotEmpty(), "Exposure compensation")
                ?: if (manualExposure) "EV applies in P mode" else null,
        ),
        Readout(
            ProParam.WB,
            whiteBalanceLabel(),
            manual = !(whiteBalance.mode == CameraWhiteBalanceMode.PRESET &&
                whiteBalance.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO),
            unavailable = unsupported(kelvinStops.isNotEmpty() || presetStops.isNotEmpty(), "White balance control"),
        ),
        Readout(
            ProParam.FOCUS,
            focusLabel(),
            manual = focus.mode == CameraFocusMode.MANUAL,
            unavailable = unsupported(focus.manualFocusSupported, "Manual focus"),
        ),
        Readout(
            ProParam.DRIVE,
            if (drives.isEmpty()) "—" else drive.short,
            manual = drive != DriveMode.SINGLE,
            unavailable = if (drives.isEmpty()) "No drive modes in video" else null,
        ),
        Readout(
            ProParam.LOOK,
            settings.look().short,
            manual = settings.look() != ColorLook.STANDARD,
            unavailable = if (captureMode == CaptureMode.VIDEO) "Looks apply to photos" else null,
        ),
    )

    /** The drive mode in effect for the current capture mode. */
    fun drive(): DriveMode = drive

    fun dial(param: ProParam): DialState? = when (param) {
        ProParam.MODE -> DialState(
            param, listOf("P", "M"), if (manualExposure) 1 else 0,
            auto = false, autoAvailable = false, value = if (manualExposure) "M" else "P",
        )
        ProParam.ISO -> isoStops.takeIf { it.isNotEmpty() }?.let { stops ->
            DialState(
                param, stops.map(Int::toString), nearestIndex(stops.map { ln(it.toDouble()) }, ln(currentIso.toDouble())),
                auto = !manualExposure, autoAvailable = true,
                value = if (manualExposure) currentIso.toString() else "AUTO",
            )
        }
        ProParam.SHUTTER -> shutterStops.takeIf { it.isNotEmpty() }?.let { stops ->
            DialState(
                param, stops.map { formatShutter((it * 1e9).roundToLong()) },
                nearestIndex(stops.map { ln(it) }, ln(currentExposureNs / 1e9)),
                auto = !manualExposure, autoAvailable = true,
                value = if (manualExposure) formatShutter(currentExposureNs) else "AUTO",
            )
        }
        ProParam.EV -> evIndices.takeIf { it.isNotEmpty() && !manualExposure }?.let { indices ->
            DialState(
                param, indices.map { formatEv(it * evStep) }, indices.indexOf(exposure.exposureCompensationIndex),
                auto = false, autoAvailable = false, value = formatEv(exposure.exposureCompensationIndex * evStep),
            )
        }
        ProParam.WB -> {
            val auto = whiteBalance.mode == CameraWhiteBalanceMode.PRESET &&
                whiteBalance.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO
            when {
                kelvinStops.isNotEmpty() -> DialState(
                    param, kelvinStops.map { "${it}K" },
                    nearestIndex(kelvinStops.map(Int::toDouble), whiteBalance.colorTemperatureKelvin.toDouble()),
                    auto = auto, autoAvailable = true, value = if (auto) "AUTO" else whiteBalanceLabel(),
                )
                presetStops.isNotEmpty() -> DialState(
                    param, presetStops.map(::presetLabel), presetStops.indexOf(whiteBalance.awbMode),
                    auto = auto, autoAvailable = true, value = if (auto) "AUTO" else whiteBalanceLabel(),
                )
                else -> null
            }
        }
        ProParam.FOCUS -> focusStops.takeIf { focus.manualFocusSupported }?.let { stops ->
            val manual = focus.mode == CameraFocusMode.MANUAL
            DialState(
                param, stops.map(::formatDistance),
                nearestIndex(stops.map { 1.0 / it }, currentFocusDiopters.toDouble()),
                auto = !manual, autoAvailable = true,
                value = if (manual) formatDistance(diopterToMeters(currentFocusDiopters)) else focusLabel(),
            )
        }
        ProParam.DRIVE -> drives.takeIf { it.isNotEmpty() }?.let { modes ->
            DialState(param, modes.map { it.short }, modes.indexOf(drive), auto = false, autoAvailable = false, value = drive.label)
        }
        ProParam.LOOK -> ColorLook.entries.let { looks ->
            DialState(
                param, looks.map { it.short }, looks.indexOf(settings.look()),
                auto = false, autoAvailable = false, value = settings.look().label,
            )
        }
    }

    fun select(
        param: ProParam,
        index: Int,
        controller: Camera2PreviewController,
        updateSettings: ((CaptureSettings) -> CaptureSettings) -> Unit,
    ) {
        when (param) {
            ProParam.MODE -> controller.setManualExposureEnabled(index == 1)
            ProParam.ISO -> isoStops.getOrNull(index)?.let { iso ->
                if (!manualExposure) controller.setManualExposureEnabled(true)
                controller.setManualExposure(iso, currentExposureNs)
            }
            ProParam.SHUTTER -> shutterStops.getOrNull(index)?.let { seconds ->
                if (!manualExposure) controller.setManualExposureEnabled(true)
                controller.setManualExposure(currentIso, (seconds * 1e9).roundToLong())
            }
            ProParam.EV -> evIndices.getOrNull(index)?.let(controller::setExposureCompensation)
            ProParam.WB -> if (kelvinStops.isNotEmpty()) {
                kelvinStops.getOrNull(index)?.let { controller.setWhiteBalanceCct(it, whiteBalance.colorTint) }
            } else {
                presetStops.getOrNull(index)?.let(controller::setWhiteBalancePreset)
            }
            ProParam.FOCUS -> focusStops.getOrNull(index)?.let { meters ->
                if (focus.mode != CameraFocusMode.MANUAL) controller.setFocusMode(CameraFocusMode.MANUAL)
                controller.setManualFocusDistance((1.0 / meters).toFloat())
            }
            ProParam.DRIVE -> drives.getOrNull(index)?.let { mode -> updateSettings { it.copy(drive = mode.name) } }
            ProParam.LOOK -> ColorLook.entries.getOrNull(index)?.let { look -> updateSettings { it.copy(colorLook = look.name) } }
        }
    }

    fun setAuto(param: ProParam, auto: Boolean, controller: Camera2PreviewController) {
        when (param) {
            ProParam.ISO, ProParam.SHUTTER -> controller.setManualExposureEnabled(!auto)
            ProParam.WB -> if (auto) {
                controller.setWhiteBalancePreset(CameraMetadata.CONTROL_AWB_MODE_AUTO)
            } else if (kelvinStops.isNotEmpty()) {
                controller.setWhiteBalanceCct(whiteBalance.colorTemperatureKelvin, whiteBalance.colorTint)
            } else {
                presetStops.firstOrNull()?.let(controller::setWhiteBalancePreset)
            }
            ProParam.FOCUS -> controller.setFocusMode(if (auto) CameraFocusMode.CONTINUOUS else CameraFocusMode.MANUAL)
            ProParam.MODE, ProParam.EV, ProParam.DRIVE, ProParam.LOOK -> Unit
        }
    }

    private fun whiteBalanceLabel(): String = when {
        whiteBalance.mode == CameraWhiteBalanceMode.CCT -> "${whiteBalance.colorTemperatureKelvin}K"
        whiteBalance.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AWB"
        else -> presetLabel(whiteBalance.awbMode)
    }

    private fun focusLabel(): String = when (focus.mode) {
        CameraFocusMode.CONTINUOUS -> "AF-C"
        CameraFocusMode.AUTO -> "AF-S"
        CameraFocusMode.MANUAL -> formatDistance(diopterToMeters(focus.focusDistanceDiopters))
    }
}

private fun nearestIndex(values: List<Double>, target: Double): Int =
    values.indices.minByOrNull { abs(values[it] - target) } ?: 0

private fun diopterToMeters(diopters: Float): Double =
    if (diopters <= 0f) Double.POSITIVE_INFINITY else 1.0 / diopters

private fun formatDistance(meters: Double): String = when {
    meters.isInfinite() -> "∞"
    meters < 1.0 -> "${(meters * 100).roundToInt()}cm"
    else -> oneDecimal(meters).removeSuffix(".0") + "m"
}

/** Shutter speeds snap to the nearest standard third stop, as a camera displays them. */
fun formatShutter(exposureNs: Long): String {
    val seconds = exposureNs / 1e9
    val stop = ShutterStopsSeconds.minByOrNull { abs(ln(it) - ln(seconds.coerceAtLeast(1e-6))) } ?: seconds
    return if (stop >= 0.3) {
        oneDecimal(stop).removeSuffix(".0") + "\""
    } else {
        "1/${(1.0 / stop).roundToInt()}"
    }
}

private fun formatEv(value: Float): String = when {
    abs(value) < 0.05f -> "±0.0"
    value > 0f -> "+" + oneDecimal(value.toDouble())
    else -> oneDecimal(value.toDouble())
}

private fun presetLabel(mode: Int): String = when (mode) {
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "TUNG"
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUO"
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "W.FL"
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAY"
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLDY"
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWIL"
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
    else -> "AWB"
}

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
