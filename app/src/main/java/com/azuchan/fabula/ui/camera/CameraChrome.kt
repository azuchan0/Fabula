package com.azuchan.fabula.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.net.Uri
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azuchan.fabula.ui.theme.Barlow
import com.azuchan.fabula.ui.theme.FabulaColors
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext

enum class CaptureMode(val description: String) {
    VIDEO("Video"),
    PHOTO("Photo"),
    BURST("48MP"),
}

/** The parameters shown in the top readout strip, each set with the dial. */
enum class ProParam(val label: String) {
    MODE("MODE"),
    ISO("ISO"),
    SHUTTER("SHUTTER"),
    EV("EV"),
    WB("WB"),
    FOCUS("FOCUS"),
    DRIVE("DRIVE"),
    LOOK("LOOK"),
}

/** One readout cell: [manual] values are drawn in the accent color, automatic ones in white. */
data class Readout(
    val param: ProParam,
    val value: String,
    val manual: Boolean = false,
    /** Why this setting cannot be changed (unsupported on this phone, or not in this mode). */
    val unavailable: String? = null,
)

/** A dial: discrete [stops], the [index] currently set (or -1 when automatic). */
data class DialState(
    val param: ProParam,
    val stops: List<String>,
    val index: Int,
    val auto: Boolean,
    val autoAvailable: Boolean,
    val value: String,
)

private const val HOLD_DELAY_MS = 250L

private val ModeItemWidth = 64.dp
private val ColorTween = tween<Color>(180)
private val SlideSpring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
private val DialSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
private val PressSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh)

private val Tabular = TextStyle(fontFamily = Barlow, fontFeatureSettings = "tnum")

// ---- Top readout strip --------------------------------------------------------------------

@Composable
fun ReadoutStrip(
    readouts: List<Readout>,
    active: ProParam?,
    onSelect: (ProParam) -> Unit,
    onUnavailable: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        readouts.forEach { readout ->
            val selected = readout.param == active
            val reason = readout.unavailable
            val background by animateColorAsState(
                if (selected) FabulaColors.AccentTint else Color.Transparent, ColorTween, label = "readoutBackground",
            )
            val valueColor by animateColorAsState(
                when {
                    reason != null -> FabulaColors.Line
                    selected || readout.manual -> FabulaColors.Accent
                    else -> FabulaColors.Text
                },
                ColorTween,
                label = "readoutValue",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable(role = Role.Button) { if (reason != null) onUnavailable(reason) else onSelect(readout.param) }
                    .semantics { contentDescription = "${readout.param.label} ${readout.value}" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = readout.value,
                    style = Tabular,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = valueColor,
                )
                Text(
                    text = readout.param.label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = FabulaColors.TextMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// ---- Pro dial -----------------------------------------------------------------------------

/**
 * A ruler dial over the bottom of the viewfinder: drag left or right to step through [DialState.stops].
 * One step per 14 dp of travel, like the detents of a command dial.
 */
@Composable
fun ProDial(state: DialState, onIndexChange: (Int) -> Unit, onAutoChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val stepPx = with(LocalDensity.current) { 14.dp.toPx() }
    val currentState by rememberUpdatedState(state)
    val currentOnIndexChange by rememberUpdatedState(onIndexChange)
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var dragIndex by remember { mutableFloatStateOf(0f) }
    val restingIndex = (if (state.index >= 0) state.index else state.stops.size / 2).toFloat()
    val animatedIndex by animateFloatAsState(restingIndex, DialSpring, label = "dialIndex")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(FabulaColors.Background.copy(alpha = 0.78f))
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.param.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = FabulaColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                state.value,
                style = Tabular,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = FabulaColors.Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (state.autoAvailable) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, if (state.auto) FabulaColors.Accent else FabulaColors.Line, RoundedCornerShape(8.dp))
                            .background(if (state.auto) FabulaColors.AccentTint else Color.Transparent)
                            .toggleable(value = state.auto, role = Role.Switch, onValueChange = onAutoChange)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "AUTO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = if (state.auto) FabulaColors.Accent else FabulaColors.TextSoft,
                        )
                    }
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics {
                    contentDescription = "${state.param.label} dial"
                    stateDescription = state.value
                }
                .pointerInput(state.param) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccumulator = 0f
                            dragIndex = currentState.index.coerceAtLeast(0).toFloat()
                        },
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator -= dragAmount
                            val steps = (dragAccumulator / stepPx).toInt()
                            if (steps != 0) {
                                dragAccumulator -= steps * stepPx
                                val last = currentState.stops.lastIndex
                                val next = (dragIndex + steps).coerceIn(0f, last.toFloat())
                                if (next != dragIndex) {
                                    dragIndex = next
                                    currentOnIndexChange(next.toInt())
                                }
                            }
                        },
                    )
                },
        ) {
            val center = size.width / 2f
            val spacing = stepPx
            // Between detents the ruler moves with the finger; at a detent it springs into place.
            val position = animatedIndex + dragAccumulator / stepPx
            for (stop in state.stops.indices) {
                val x = center + (stop - position) * spacing
                if (x < 0f || x > size.width) continue
                val major = stop % 3 == 0
                drawLine(
                    color = if (major) Color(0xFFD8D8D4) else Color(0xFF5C5C58),
                    start = Offset(x, 6.dp.toPx()),
                    end = Offset(x, (if (major) 22 else 14).dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawLine(
                color = FabulaColors.Accent,
                start = Offset(center, 0f),
                end = Offset(center, 30.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

// ---- Viewfinder overlays ------------------------------------------------------------------

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Color.White.copy(alpha = 0.16f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
fun OverlayChip(text: String, modifier: Modifier = Modifier, color: Color = FabulaColors.Text) {
    Text(
        text = text,
        color = color,
        style = Tabular,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(CircleShape)
            .background(FabulaColors.Background.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun FrameCountPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val choices = CaptureSettingsStore.BURST_FRAME_CHOICES
    val indicator by animateDpAsState(
        (50 * choices.indexOf(selected).coerceAtLeast(0)).dp, SlideSpring, label = "frameIndicator",
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(FabulaColors.Background.copy(alpha = 0.55f))
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(indicator.roundToPx(), 0) }
                .size(width = 48.dp, height = 40.dp)
                .clip(CircleShape)
                .background(FabulaColors.AccentTint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEach { count ->
                val isSelected = count == selected
                val textColor by animateColorAsState(
                    if (isSelected) FabulaColors.Accent else FabulaColors.Text, ColorTween, label = "frameText",
                )
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 40.dp)
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(count) })
                        .semantics { contentDescription = "$count frames" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        count.toString(),
                        style = Tabular,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun BurstProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(total.coerceAtLeast(1)) { index ->
                val segment by animateColorAsState(
                    if (index < done) FabulaColors.Accent else Color.White.copy(alpha = 0.25f), ColorTween, label = "segment",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(segment),
                )
            }
        }
        Text(
            "$done/$total",
            style = Tabular,
            color = FabulaColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---- Bottom controls ----------------------------------------------------------------------

@Composable
fun ShutterButton(
    mode: CaptureMode,
    enabled: Boolean,
    capturing: Boolean,
    processing: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: () -> Unit = {},
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnHoldStart by rememberUpdatedState(onHoldStart)
    val currentOnHoldEnd by rememberUpdatedState(onHoldEnd)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(if (pressed) 0.9f else 1f, PressSpring, label = "shutterPress")
    val description = when {
        processing -> "Building the 48MP image"
        capturing -> "Capturing RAW burst"
        mode == CaptureMode.BURST -> "Capture RAW burst"
        else -> "Take photo"
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // Tap: one shot. Hold (continuous drive): fire until the finger lifts.
                detectTapGestures(onPress = {
                    pressed = true
                    val hold = currentOnHoldStart
                    if (hold == null) {
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) currentOnClick()
                        return@detectTapGestures
                    }
                    var holding = false
                    val holdJob = scope.launch {
                        delay(HOLD_DELAY_MS)
                        holding = true
                        hold()
                    }
                    val released = tryAwaitRelease()
                    pressed = false
                    holdJob.cancel()
                    if (holding) currentOnHoldEnd() else if (released) currentOnClick()
                })
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                if (enabled) onClick { currentOnClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            processing -> CircularProgressIndicator(
                modifier = Modifier.size(84.dp),
                color = FabulaColors.Accent,
                trackColor = FabulaColors.Line,
                strokeWidth = 3.dp,
            )
            capturing -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(84.dp),
                color = FabulaColors.Accent,
                trackColor = FabulaColors.Line,
                strokeWidth = 3.dp,
            )
            else -> Box(
                Modifier
                    .size(84.dp)
                    .border(3.dp, if (enabled) FabulaColors.Text else FabulaColors.Line, CircleShape),
            )
        }
        val fill by animateColorAsState(
            if (mode == CaptureMode.BURST) FabulaColors.Accent else FabulaColors.Text, ColorTween, label = "shutterFill",
        )
        if (capturing || processing) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (processing) FabulaColors.Line else FabulaColors.Accent),
            )
        } else {
            Box(
                Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(if (enabled) fill else fill.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
fun LastCaptureThumbnail(uri: Uri?, highlighted: Boolean, onOpen: () -> Unit) {
    val thumbnail = rememberThumbnail(uri)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(FabulaColors.Raised)
            .border(2.dp, if (highlighted) FabulaColors.Accent else FabulaColors.Hairline, shape)
            .clickable(enabled = uri != null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Open last photo" },
    ) {
        Crossfade(thumbnail, animationSpec = tween(220), label = "thumbnail") { image ->
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** A round 56 dp control; [active] fills it with the accent. */
@Composable
fun RoundControl(
    description: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    outlined: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val tint = when {
        !enabled -> FabulaColors.Line
        active -> FabulaColors.OnAccent
        outlined -> FabulaColors.Accent
        else -> FabulaColors.Text
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (active) FabulaColors.Accent else if (outlined) Color.Transparent else FabulaColors.Raised)
            .then(if (outlined && !active) Modifier.border(1.5.dp, tint, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(tint) }
}

@Composable
fun ModeSwitch(
    mode: CaptureMode,
    unsupported: Map<CaptureMode, String>,
    enabled: Boolean,
    onModeChange: (CaptureMode) -> Unit,
    onUnavailable: (String) -> Unit = {},
) {
    val indicator by animateDpAsState(
        (ModeItemWidth.value * CaptureMode.entries.indexOf(mode)).dp, SlideSpring, label = "modeIndicator",
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(FabulaColors.Card)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(indicator.roundToPx(), 0) }
                .size(width = ModeItemWidth, height = 44.dp)
                .clip(CircleShape)
                .background(FabulaColors.Selected),
        )
        Row {
            CaptureMode.entries.forEach { entry ->
                val reason = unsupported[entry]
                val available = enabled && reason == null
                val selected = entry == mode
                val tint by animateColorAsState(
                    when {
                        selected -> FabulaColors.Accent
                        available -> FabulaColors.TextSoft
                        else -> FabulaColors.Line
                    },
                    ColorTween,
                    label = "modeTint",
                )
                Box(
                    modifier = Modifier
                        .size(width = ModeItemWidth, height = 44.dp)
                        .clip(CircleShape)
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab) {
                            if (reason != null) onUnavailable(reason) else onModeChange(entry)
                        }
                        .semantics { contentDescription = entry.description },
                    contentAlignment = Alignment.Center,
                ) {
                    ModeIcon(entry, tint)
                }
            }
        }
    }
}

// ---- Settings screen ----------------------------------------------------------------------

@Composable
fun SettingsScreen(
    settings: CaptureSettings,
    onChange: ((CaptureSettings) -> CaptureSettings) -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
    rawSupported: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FabulaColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTapTarget(description = "Back to camera", onClick = onBack) { BackIcon(FabulaColors.Text) }
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (rawSupported) "KEEP RAW" else "KEEP RAW · NOT AVAILABLE ON THIS PHONE",
                    color = FabulaColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(FabulaColors.Card)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KeepRawFrames.entries.forEach { option ->
                        val selected = option == settings.keepRawFrames && rawSupported
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) FabulaColors.Selected else Color.Transparent)
                                .selectable(selected = selected, enabled = rawSupported, role = Role.RadioButton) {
                                    onChange { it.copy(keepRawFrames = option) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when (option) {
                                    KeepRawFrames.NONE -> "None"
                                    KeepRawFrames.FIRST -> "First"
                                    KeepRawFrames.ALL -> "All"
                                },
                                color = when {
                                    !rawSupported -> FabulaColors.Line
                                    selected -> FabulaColors.Accent
                                    else -> FabulaColors.TextSoft
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(FabulaColors.Card),
            ) {
                SettingSwitch("TIFF", settings.saveTiff && rawSupported, enabled = rawSupported) { value ->
                    onChange { it.copy(saveTiff = value) }
                }
                Hairline()
                SettingSwitch("Grid", settings.gridLines) { value -> onChange { it.copy(gridLines = value) } }
                Hairline()
                SettingSwitch("Histogram", settings.histogram) { value -> onChange { it.copy(histogram = value) } }
                Hairline()
                SettingSwitch("Shutter sound", settings.shutterSound) { value ->
                    onChange { it.copy(shutterSound = value) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(FabulaColors.Card)
                    .clickable(role = Role.Button, onClick = onDiagnostics)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Diagnostics", color = FabulaColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ChevronIcon(FabulaColors.TextMuted)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Fabula", color = FabulaColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Abdel-Aziz El Kihal (azuchan0)", color = FabulaColors.TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FabulaColors.Hairline),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (enabled) FabulaColors.Text else FabulaColors.Line,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FabulaColors.OnAccent,
                checkedTrackColor = FabulaColors.Accent,
                uncheckedThumbColor = FabulaColors.TextMuted,
                uncheckedTrackColor = FabulaColors.Raised,
                uncheckedBorderColor = FabulaColors.Line,
            ),
        )
    }
}

// ---- Icons (stroke drawings, no icon font dependency) ---------------------------------------

@Composable
fun IconTapTarget(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun StrokeIcon(color: Color, draw: Path.(unit: Float) -> Unit) {
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 24f
        val path = Path().apply { draw(unit) }
        drawPath(path, color, style = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun DialIcon(color: Color) = Canvas(Modifier.size(24.dp)) {
    val u = size.width / 24f
    val stroke = 1.7f * u
    drawCircle(color, 8 * u, Offset(12 * u, 12 * u), style = Stroke(stroke))
    drawCircle(color, 1.5f * u, Offset(12 * u, 12 * u))
    drawLine(color, Offset(12 * u, 4 * u), Offset(12 * u, 7 * u), stroke, StrokeCap.Round)
    drawLine(color, Offset(12 * u, 17 * u), Offset(12 * u, 20 * u), stroke, StrokeCap.Round)
    drawLine(color, Offset(4 * u, 12 * u), Offset(7 * u, 12 * u), stroke, StrokeCap.Round)
    drawLine(color, Offset(17 * u, 12 * u), Offset(20 * u, 12 * u), stroke, StrokeCap.Round)
}

@Composable
fun DotsIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val u = size.width / 24f
    for (x in listOf(6f, 12f, 18f)) {
        for (y in listOf(9f, 15f)) drawCircle(color, 1.6f * u, Offset(x * u, y * u))
    }
}

@Composable
fun BackIcon(color: Color) = StrokeIcon(color) { u ->
    moveTo(15 * u, 5 * u); lineTo(8 * u, 12 * u); lineTo(15 * u, 19 * u)
}

@Composable
fun ChevronIcon(color: Color) = StrokeIcon(color) { u ->
    moveTo(9 * u, 5 * u); lineTo(16 * u, 12 * u); lineTo(9 * u, 19 * u)
}

// ---- Thumbnails ---------------------------------------------------------------------------

@Composable
internal fun rememberThumbnail(uri: Uri?): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { loadThumbnail(context, it) }
    }
    return bitmap
}

private suspend fun loadThumbnail(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
    }.getOrNull()
}

// ---- Histogram ----------------------------------------------------------------------------

private const val HistogramBins = 64
private const val HistogramSampleMs = 100L
private const val HistogramEaseSeconds = 0.09f

/**
 * Live luminance histogram of the viewfinder. The preview is a SurfaceView, so a small copy of
 * its current frame is taken with PixelCopy a few times per second.
 */
@Composable
fun HistogramOverlay(view: SurfaceView, modifier: Modifier = Modifier) {
    var bins by remember { mutableStateOf<Array<FloatArray>?>(null) }
    // Displayed bars ease towards the latest sample every display frame (120 Hz when available).
    val shown = remember { Array(3) { FloatArray(HistogramBins) } }
    var frameTick by remember { mutableLongStateOf(0L) }
    var hasSample by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val target = bins
                if (target != null) {
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    val blend = 1f - kotlin.math.exp(-dt / HistogramEaseSeconds)
                    var moving = false
                    for (channel in 0 until 3) {
                        for (bin in 0 until HistogramBins) {
                            val delta = target[channel][bin] - shown[channel][bin]
                            if (kotlin.math.abs(delta) > 0.002f) moving = true
                            shown[channel][bin] += delta * blend
                        }
                    }
                    hasSample = true
                    if (moving) frameTick = now
                }
                last = now
            }
        }
    }
    LaunchedEffect(view) {
        val bitmap = Bitmap.createBitmap(54, 72, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        try {
            while (isActive) {
                if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                    val copied = suspendCancellableCoroutine { continuation ->
                        runCatching {
                            PixelCopy.request(view, bitmap, { result ->
                                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                            }, handler)
                        }.onFailure { if (continuation.isActive) continuation.resume(false) }
                    }
                    if (copied) bins = withContext(Dispatchers.Default) { luminanceHistogram(bitmap) }
                }
                delay(HistogramSampleMs)
            }
        } finally {
            bitmap.recycle()
        }
    }
    if (!hasSample) return
    Canvas(
        modifier = modifier
            .size(width = 92.dp, height = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FabulaColors.Background.copy(alpha = 0.55f))
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .semantics { contentDescription = "Histogram" },
    ) {
        frameTick // redraw on every eased frame
        // Red, green and blue add up where they overlap, so tones shared by all three read as white.
        val colors = listOf(Color(0xFFFF4A4A), Color(0xFF4ADE6A), Color(0xFF4A8CFF))
        shown.forEachIndexed { channel, values ->
            val path = Path().apply {
                moveTo(0f, size.height)
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.size - 1)
                    lineTo(x, size.height - value * (size.height - 4.dp.toPx()))
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, colors[channel].copy(alpha = 0.8f), blendMode = BlendMode.Plus)
        }
    }
}

private fun luminanceHistogram(bitmap: Bitmap): Array<FloatArray> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val counts = Array(3) { FloatArray(HistogramBins) }
    for (pixel in pixels) {
        counts[0][((pixel shr 16) and 0xff) * HistogramBins / 256] += 1f
        counts[1][((pixel shr 8) and 0xff) * HistogramBins / 256] += 1f
        counts[2][(pixel and 0xff) * HistogramBins / 256] += 1f
    }
    val peak = counts.maxOf { it.max() }.coerceAtLeast(1f)
    // Square-root scale keeps small populations (highlights, shadows) visible next to a big peak.
    return Array(3) { channel -> FloatArray(HistogramBins) { kotlin.math.sqrt(counts[channel][it] / peak) } }
}

// ---- Video --------------------------------------------------------------------------------

val RecordRed = Color(0xFFE5322D)

@Composable
fun RecordButton(enabled: Boolean, recording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .border(3.dp, if (enabled) FabulaColors.Text else FabulaColors.Line, CircleShape),
        )
        if (recording) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RecordRed),
            )
        } else {
            Box(
                Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(if (enabled) RecordRed else RecordRed.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
fun RecordingTimer(startedAtElapsedMs: Long, modifier: Modifier = Modifier) {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtElapsedMs) {
        while (isActive) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L) / 1000L
            delay(250)
        }
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(FabulaColors.Background.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(RecordRed),
        )
        Text(
            String.format(java.util.Locale.US, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
            style = Tabular,
            color = FabulaColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Darkens what falls outside the recorded frame ([frameAspect] = width / height on screen). */
@Composable
fun FrameMask(frameAspect: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val shade = Color.Black.copy(alpha = 0.55f)
        val viewAspect = size.width / size.height
        if (frameAspect < viewAspect) {
            val frameWidth = size.height * frameAspect
            val side = (size.width - frameWidth) / 2f
            drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(side, size.height))
            drawRect(shade, Offset(size.width - side, 0f), androidx.compose.ui.geometry.Size(side, size.height))
        } else {
            val frameHeight = size.width / frameAspect
            val band = (size.height - frameHeight) / 2f
            drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, band))
            drawRect(shade, Offset(0f, size.height - band), androidx.compose.ui.geometry.Size(size.width, band))
        }
    }
}

// ---- Zoom ---------------------------------------------------------------------------------

/**
 * Lens chips (0.5x switches to the ultra-wide, 1x and up use the main sensor) plus a drag ruler:
 * sliding along the chips zooms continuously, with a ruler shown while the finger is down.
 */
@Composable
fun ZoomControl(
    ratio: Float,
    minRatio: Float,
    maxRatio: Float,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onPresetSelected: (Float) -> Unit = onRatioChange,
    onUnavailable: (String) -> Unit = {},
) {
    val currentOnPreset by rememberUpdatedState(onPresetSelected)
    // Every lens chip is shown; those outside this camera's zoom range are greyed out.
    val presets = listOf(0.5f, 1f, 2f, 4f)
        .map { if (it < 1f && minRatio < 1f) minRatio else it }
    val supported = presets.map { it >= minRatio - 0.001f && it <= maxRatio + 0.001f }
    val currentRatio by rememberUpdatedState(ratio)
    val currentOnRatioChange by rememberUpdatedState(onRatioChange)
    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(1f) }
    // One doubling of zoom per 120 dp of travel.
    val pxPerDoubling = with(LocalDensity.current) { 120.dp.toPx() }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
        ) {
            ZoomRuler(ratio = ratio, minRatio = minRatio, maxRatio = maxRatio)
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(FabulaColors.Background.copy(alpha = 0.55f))
                .pointerInput(minRatio, maxRatio) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragRatio = currentRatio
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragRatio = (dragRatio * 2f.pow(-dragAmount / pxPerDoubling)).coerceIn(minRatio, maxRatio)
                            currentOnRatioChange(dragRatio)
                        },
                    )
                }
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val activeIndex = presets.indices.lastOrNull { supported[it] && ratio >= presets[it] - 0.01f } ?: 1
            presets.forEachIndexed { index, preset ->
                val selected = index == activeIndex
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (selected) FabulaColors.AccentTint else Color.Transparent)
                        .selectable(selected = selected, role = Role.Button) {
                            if (supported[index]) {
                                currentOnPreset(preset)
                            } else {
                                onUnavailable(
                                    if (preset < 1f) "Ultra-wide isn't available on this phone" else "Zoom isn't available on this phone",
                                )
                            }
                        }
                        .semantics { contentDescription = "Zoom ${formatZoom(preset)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selected) formatZoom(ratio) else formatZoom(preset).removeSuffix("×"),
                        style = Tabular,
                        color = when {
                            selected -> FabulaColors.Accent
                            supported[index] -> FabulaColors.Text
                            else -> FabulaColors.Line
                        },
                        fontSize = if (selected) 13.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomRuler(ratio: Float, minRatio: Float, maxRatio: Float) {
    Column(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FabulaColors.Background.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatZoom(ratio), style = Tabular, color = FabulaColors.Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .padding(top = 4.dp)
                .size(width = 240.dp, height = 22.dp),
        ) {
            // Log scale: a tick every tenth of a doubling, the current ratio under the centre line.
            val pxPerDoubling = size.width / 2f
            val center = log2(ratio)
            val low = log2(minRatio)
            val high = log2(maxRatio)
            for (tick in floor(low * 10f).toInt()..ceil(high * 10f).toInt()) {
                val position = tick / 10f
                if (position < low - 1e-3f || position > high + 1e-3f) continue
                val x = size.width / 2f + (position - center) * pxPerDoubling
                if (x < 0f || x > size.width) continue
                val major = tick % 10 == 0
                drawLine(
                    color = if (major) Color(0xFFD8D8D4) else Color(0xFF5C5C58),
                    start = Offset(x, 0f),
                    end = Offset(x, (if (major) 14 else 8).dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawLine(FabulaColors.Accent, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2.dp.toPx())
        }
    }
}

fun formatZoom(ratio: Float): String {
    // The ultra-wide's native 0.55x reads as the familiar 0.5x lens.
    if (ratio < 0.6f) return "0.5×"
    val rounded = round(ratio * 10f) / 10f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else String.format(java.util.Locale.US, "%.1f×", rounded)
}

// ---- Mode icons ---------------------------------------------------------------------------

@Composable
fun ModeIcon(mode: CaptureMode, color: Color) = Canvas(Modifier.size(24.dp)) {
    val u = size.width / 24f
    val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (mode) {
        CaptureMode.VIDEO -> {
            drawRoundRect(color, Offset(2.5f * u, 6.5f * u), GeometrySize(13f * u, 11f * u), CornerRadius(2.5f * u), style = stroke)
            val lens = Path().apply {
                moveTo(15.5f * u, 10.5f * u)
                lineTo(21.5f * u, 7.5f * u)
                lineTo(21.5f * u, 16.5f * u)
                lineTo(15.5f * u, 13.5f * u)
            }
            drawPath(lens, color, style = stroke)
        }
        CaptureMode.PHOTO -> {
            val body = Path().apply {
                moveTo(3f * u, 8.5f * u)
                lineTo(7.5f * u, 8.5f * u)
                lineTo(9f * u, 6f * u)
                lineTo(15f * u, 6f * u)
                lineTo(16.5f * u, 8.5f * u)
                lineTo(21f * u, 8.5f * u)
                lineTo(21f * u, 19f * u)
                lineTo(3f * u, 19f * u)
                close()
            }
            drawPath(body, color, style = stroke)
            drawCircle(color, 3.6f * u, Offset(12f * u, 13.2f * u), style = stroke)
        }
        CaptureMode.BURST -> {
            // Stacked frames merged into one: the RAW burst.
            drawRoundRect(color, Offset(7.5f * u, 3f * u), GeometrySize(13.5f * u, 11f * u), CornerRadius(1.5f * u), style = stroke)
            drawRoundRect(color, Offset(3f * u, 9.5f * u), GeometrySize(13.5f * u, 11f * u), CornerRadius(1.5f * u))
        }
    }
}

// ---- Switch veil --------------------------------------------------------------------------

/** A quarter-resolution copy of what the viewfinder shows right now, or null if it fails. */
suspend fun snapshotViewfinder(view: SurfaceView): ImageBitmap? {
    if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return null
    val bitmap = Bitmap.createBitmap(
        (view.width / 4).coerceAtLeast(1),
        (view.height / 4).coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val copied = suspendCancellableCoroutine { continuation ->
        runCatching {
            PixelCopy.request(view, bitmap, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { if (continuation.isActive) continuation.resume(false) }
    }
    return if (copied) bitmap.asImageBitmap() else null.also { bitmap.recycle() }
}

/**
 * While the camera reconfigures (mode switch, recording start), the last frame stays on screen
 * blurred, then dissolves into the live preview once the new session streams.
 */
@Composable
fun SwitchVeil(image: ImageBitmap?, visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        if (visible && image != null) 1f else 0f,
        tween(if (visible) 90 else 320),
        label = "switchVeil",
    )
    if (image == null || alpha <= 0f) return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .blur(24.dp),
    )
}
