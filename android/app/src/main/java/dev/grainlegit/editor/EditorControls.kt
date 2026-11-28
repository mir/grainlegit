package dev.grainlegit.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class Panel(val title: String, val defaultTool: Tool) {
    Lens("Lens", Tool.LensAperture), Negative("Film", Tool.CameraExposure), Adjust("Adjust", Tool.Exposure),
}

// Retained for activity state saved by Android 0.2.
internal enum class ToolGroup { Light, Colour, Detail }

internal enum class Sheet { Stock, Format, Ratio }

private val adjustmentTools = listOf(
    Tool.Exposure, Tool.Brightness, Tool.Highlights, Tool.Shadows, Tool.Contrast, Tool.Whites, Tool.Blacks, Tool.Curves,
    Tool.Vibrance, Tool.WhiteBalance, Tool.Temperature, Tool.Tint, Tool.Saturation, Tool.Sharpen,
)
private val grainTools = listOf(
    Tool.CameraExposure, Tool.Grain, Tool.GrainSize, Tool.GrainStrength, Tool.GrainTone, Tool.GrainColour, Tool.GrainLoad, Tool.CoarseFraction,
    Tool.RedCloudSize, Tool.GreenCloudSize, Tool.BlueCloudSize, Tool.Development,
)
private val lensTools = listOf(
    Tool.LensAperture, Tool.LensVignette, Tool.LensGlow, Tool.LensHaze, Tool.LensSoftness,
    Tool.LensWarmth, Tool.LensSaturation, Tool.LensGhosts,
)

@Composable
internal fun EditControls(
    panel: Panel, setPanel: (Panel) -> Unit, tool: Tool, edit: Edit,
    selectTool: (Tool) -> Unit, onCrop: () -> Unit, onErase: () -> Unit, showSheet: (Sheet) -> Unit,
    change: (Edit) -> Unit, start: () -> Unit, finish: () -> Unit, commit: (Edit) -> Unit,
    autoGrain: Edit? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().glassPanel().padding(vertical = 8.dp)) {
            if (tool != Tool.Curves && tool != Tool.WhiteBalance) Column(Modifier.padding(horizontal = 14.dp)) {
                val coarse = tool == Tool.CoarseFraction
                val usingStockMix = coarse && edit.useCoarseProfile
                if (coarse) {
                    Text(
                        "Use stock profile", color = if (usingStockMix) accent else muted,
                        modifier = Modifier.toggleable(usingStockMix, role = Role.Switch) {
                            commit(edit.copy(useCoarseProfile = it))
                        }.padding(horizontal = 8.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (!usingStockMix) {
                    Ruler(tool, edit[tool], start, { change(edit.with(tool, it)) }, finish, default = edit.defaultValue(tool),
                        top = edit.rangeMax(tool))
                }
            }
            when (panel) {
                Panel.Adjust -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorChip("Crop", onClick = onCrop)
                    adjustmentTools.forEach { entry ->
                        val value = if (entry == Tool.Curves || entry == Tool.WhiteBalance) null else entry.display(edit[entry])
                        EditorChip(entry.title, selected = entry == tool, value = value) { selectTool(entry) }
                    }
                }
                Panel.Lens -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lensTools.forEach { entry ->
                        EditorChip(entry.title, selected = entry == tool, value = entry.display(edit[entry])) { selectTool(entry) }
                    }
                }
                Panel.Negative -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (autoGrain != null) {
                        AutoGrainButton(applied = autoGrain == edit) {
                            commit(autoGrain)
                            selectTool(Tool.GrainSize)
                        }
                    }
                    val strength = Tool.StockStrength
                    val stockLabel = if (edit.stock.id == "generic") "Stock" else "Stock · ${strength.display(edit[strength])}"
                    EditorChip(stockLabel, selected = tool == strength, value = edit.stock.name, chevron = true) {
                        showSheet(Sheet.Stock)
                    }
                    EditorChip("Format", value = formatNames[edit.format], chevron = true, onClick = { showSheet(Sheet.Format) })
                    grainTools.forEach { entry ->
                        val value = if (entry == Tool.CoarseFraction && edit.useCoarseProfile) "Stock" else entry.display(edit[entry])
                        EditorChip(entry.chipTitle(), selected = entry == tool, value = value) { selectTool(entry) }
                    }
                }
            }
        }
        GlassTabBar(editorTabs, selected = panel.ordinal + 1, onSelect = { index ->
            if (index == 0) onErase() else Panel.entries[index - 1].let { if (it != panel) setPanel(it) }
        })
    }
}

private val editorTabs = listOf(
    GlassTab("Erase", EditorSymbol.Erase), GlassTab(Panel.Lens.title, EditorSymbol.Lens),
    GlassTab(Panel.Negative.title, EditorSymbol.Film), GlassTab(Panel.Adjust.title, EditorSymbol.Adjust),
)

// Sets grain size so the grain is clearly visible with the whole photo on screen.
@Composable
private fun AutoGrainButton(applied: Boolean, apply: () -> Unit) {
    Box(
        Modifier.size(48.dp).selectable(selected = applied, role = Role.Button, onClick = apply)
            .semantics { contentDescription = "Auto grain" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).background(if (applied) glassSelected else glassWell, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Glyph(EditorSymbol.Auto, if (applied) accent else ink, Modifier.size(20.dp))
        }
    }
}

private fun Tool.chipTitle() = when (this) {
    Tool.CameraExposure -> "Exposure"
    Tool.Grain -> "Amount"
    Tool.GrainSize -> "Size"
    Tool.GrainTone -> "Tone"
    Tool.RedCloudSize -> "Red size"
    Tool.GreenCloudSize -> "Green size"
    Tool.BlueCloudSize -> "Blue size"
    else -> title
}

@Composable
internal fun EditorChip(
    label: String, selected: Boolean = false, modifier: Modifier = Modifier, plain: Boolean = false,
    chevron: Boolean = false, role: Role = Role.Button, value: String? = null, onClick: () -> Unit,
) {
    val style = MaterialTheme.typography.labelLarge
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 48.dp).selectable(selected = selected, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.then(if (plain) Modifier.fillMaxWidth() else Modifier)
                .then(if (value == null) Modifier.heightIn(min = 34.dp) else Modifier.widthIn(min = 61.dp))
                .background(when {
                    selected -> glassSelected
                    plain -> Color.Transparent
                    else -> glassWell
                }, shape)
                .padding(horizontal = if (plain) 4.dp else 10.dp, vertical = if (value == null) 0.dp else 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (value != null) Text(value, color = ink, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(label, color = if (selected) accent else muted,
                    style = if (value == null) style else MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chevron) Glyph(EditorSymbol.Down, muted, Modifier.padding(start = 4.dp).size(13.dp))
        }
    }
}

@Composable
internal fun CropControls(
    geometry: Geometry, tool: Tool, setTool: (Tool) -> Unit, change: (Geometry) -> Unit, chooseRatio: () -> Unit,
) {
    val value = when (tool) {
        Tool.Horizontal -> geometry.horizontal
        Tool.Vertical -> geometry.vertical
        else -> geometry.straighten
    }
    GlassDock(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Ruler(tool, value, {}, {
            change(when (tool) {
                Tool.Horizontal -> geometry.copy(horizontal = it)
                Tool.Vertical -> geometry.copy(vertical = it)
                else -> geometry.copy(straighten = it)
            })
        }, {}, label = when (tool) {
            Tool.Horizontal -> "Horizontal perspective"
            Tool.Vertical -> "Vertical perspective"
            else -> tool.title
        })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorChip("Aspect ratio", value = cropRatios[geometry.ratio], chevron = true, onClick = chooseRatio)
            EditorChip(Tool.Straighten.title, selected = tool == Tool.Straighten,
                value = Tool.Straighten.display(geometry.straighten)) { setTool(Tool.Straighten) }
            EditorChip(Tool.Horizontal.title, selected = tool == Tool.Horizontal,
                value = Tool.Horizontal.display(geometry.horizontal)) { setTool(Tool.Horizontal) }
            EditorChip(Tool.Vertical.title, selected = tool == Tool.Vertical,
                value = Tool.Vertical.display(geometry.vertical)) { setTool(Tool.Vertical) }
        }
        HorizontalDivider(color = glassDivider)
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction("Rotate 90°", EditorSymbol.Rotate) { change(geometry.copy(turns = (geometry.turns + 1) % 4)) }
            EditorAction("Flip horizontally", EditorSymbol.FlipX, selected = geometry.flipH) {
                change(geometry.copy(flipH = !geometry.flipH))
            }
            EditorAction("Flip vertically", EditorSymbol.FlipY, selected = geometry.flipV) {
                change(geometry.copy(flipV = !geometry.flipV))
            }
            EditorChip("Constrain", selected = geometry.constrain, onClick = {
                change(geometry.copy(constrain = !geometry.constrain))
            })
            TextButton(onClick = { change(Geometry()) }) { Text("Reset") }
        }
    }
}

internal enum class EditorSymbol { Down, Close, Back, Undo, Redo, Compare, Check, Rotate, FlipX, FlipY, Auto, Erase, Lens, Film, Adjust }

@Composable
internal fun Glyph(symbol: EditorSymbol, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val path = Path()
        fun line(vararg point: Float) {
            path.moveTo(point[0], point[1])
            for (index in 2 until point.size step 2) path.lineTo(point[index], point[index + 1])
        }
        when (symbol) {
            EditorSymbol.Down -> line(5f, 9f, 12f, 16f, 19f, 9f)
            EditorSymbol.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            EditorSymbol.Back -> { line(10f, 5f, 3f, 12f, 10f, 19f); line(3f, 12f, 21f, 12f) }
            EditorSymbol.Undo -> {
                line(8f, 5f, 3f, 10f, 8f, 15f)
                line(3f, 10f, 13f, 10f)
                path.cubicTo(18f, 10f, 21f, 13f, 21f, 17f)
            }
            EditorSymbol.Redo -> {
                line(16f, 5f, 21f, 10f, 16f, 15f)
                line(21f, 10f, 11f, 10f)
                path.cubicTo(6f, 10f, 3f, 13f, 3f, 17f)
            }
            EditorSymbol.Compare -> {
                line(9f, 5f, 4f, 5f, 4f, 19f, 9f, 19f)
                line(15f, 5f, 20f, 5f, 20f, 19f, 15f, 19f)
                line(12f, 3f, 12f, 21f)
            }
            EditorSymbol.Check -> line(4f, 12f, 9f, 17f, 20f, 6f)
            EditorSymbol.Rotate -> {
                line(4f, 3f, 4f, 10f, 11f, 10f)
                path.moveTo(4f, 10f)
                path.cubicTo(6f, 3f, 16f, 3f, 20f, 10f)
                path.cubicTo(24f, 19f, 11f, 24f, 6f, 17f)
            }
            EditorSymbol.FlipX -> {
                line(12f, 3f, 12f, 21f); line(9f, 6f, 3f, 18f, 9f, 18f, 9f, 6f)
                line(15f, 6f, 21f, 18f, 15f, 18f, 15f, 6f)
            }
            EditorSymbol.Auto -> {
                line(11f, 4f, 12.8f, 10.2f, 19f, 12f, 12.8f, 13.8f, 11f, 20f, 9.2f, 13.8f, 3f, 12f, 9.2f, 10.2f, 11f, 4f)
                line(19f, 2.5f, 19f, 7.5f); line(16.5f, 5f, 21.5f, 5f)
            }
            EditorSymbol.Erase -> {
                line(9f, 20f, 3.5f, 14.5f, 13f, 5f, 20.5f, 12.5f, 13f, 20f, 9f, 20f)
                line(8f, 10f, 15.5f, 17.5f)
                line(13f, 20f, 21f, 20f)
            }
            EditorSymbol.Lens -> {
                path.addOval(Rect(Offset(12f, 12f), 8.5f))
                // Aperture blades.
                line(12f, 3.5f, 15f, 11f); line(19.4f, 7.8f, 12.8f, 13.6f); line(19.4f, 16.2f, 10.2f, 15f)
                line(12f, 20.5f, 9f, 13f); line(4.6f, 16.2f, 11.2f, 10.4f); line(4.6f, 7.8f, 13.8f, 9f)
            }
            EditorSymbol.Film -> {
                line(5f, 4f, 19f, 4f, 19f, 20f, 5f, 20f, 5f, 4f)
                line(8.5f, 4f, 8.5f, 20f); line(15.5f, 4f, 15.5f, 20f)
                line(5f, 8f, 8.5f, 8f); line(5f, 12f, 8.5f, 12f); line(5f, 16f, 8.5f, 16f)
                line(15.5f, 8f, 19f, 8f); line(15.5f, 12f, 19f, 12f); line(15.5f, 16f, 19f, 16f)
            }
            EditorSymbol.Adjust -> {
                line(4f, 7f, 12.5f, 7f); line(17.5f, 7f, 20f, 7f); path.addOval(Rect(Offset(15f, 7f), 2.5f))
                line(4f, 12f, 6.5f, 12f); line(11.5f, 12f, 20f, 12f); path.addOval(Rect(Offset(9f, 12f), 2.5f))
                line(4f, 17f, 10.5f, 17f); line(15.5f, 17f, 20f, 17f); path.addOval(Rect(Offset(13f, 17f), 2.5f))
            }
            EditorSymbol.FlipY -> {
                line(3f, 12f, 21f, 12f); line(6f, 9f, 18f, 3f, 18f, 9f, 6f, 9f)
                line(6f, 15f, 18f, 21f, 18f, 15f, 6f, 15f)
            }
        }
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun EditorAction(
    label: String, symbol: EditorSymbol, enabled: Boolean = true, selected: Boolean = false, action: () -> Unit,
) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Glyph(symbol, if (!enabled) muted.copy(alpha = 0.55f) else if (selected) accent else ink)
    }
}

@Composable
internal fun CompareControl(comparing: Boolean, change: (Boolean) -> Unit) {
    val latestChange by rememberUpdatedState(change)
    DisposableEffect(Unit) {
        onDispose { latestChange(false) }
    }
    Box(
        Modifier.size(48.dp).semantics {
            contentDescription = "Hold to compare"
            role = Role.Button
            onClick(if (comparing) "Show edit" else "Show original") { latestChange(!comparing); true }
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                try {
                    latestChange(true)
                    waitForUpOrCancellation()?.consume()
                } finally {
                    latestChange(false)
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).glassCapsule(if (comparing) Color.White.copy(alpha = 0.3f) else glassTint),
            contentAlignment = Alignment.Center) {
            Glyph(EditorSymbol.Compare, ink)
        }
    }
}

@Composable
internal fun Ruler(
    tool: Tool, amount: Float, start: () -> Unit, change: (Float) -> Unit, finish: () -> Unit,
    label: String = tool.title, default: Float = tool.initial, top: Float = tool.max,
) {
    val latestChange by rememberUpdatedState(change)
    val latestStart by rememberUpdatedState(start)
    val latestFinish by rememberUpdatedState(finish)
    val latestDefault by rememberUpdatedState(default)
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f, 0.001f) }
    fun pressThumb(down: Boolean) {
        scope.launch { press.animateTo(if (down) 1f else 0f, spring(1f, 1000f, 0.001f)) }
    }
    fun commitValue(value: Float) {
        latestStart()
        latestChange(value)
        latestFinish()
    }
    val track = rememberLayerBackdrop()
    // While dragging, the thumb follows the finger; values snap to steps and the photo catches up.
    var dragPosition by remember(tool) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.CenterStart) {
        val trackWidth = constraints.maxWidth.toFloat()
        val inset = with(LocalDensity.current) { 2.dp.toPx() }
        Canvas(Modifier.matchParentSize().layerBackdrop(track).semantics {
            contentDescription = if (label.endsWith(" amount") || tool == Tool.StockStrength) label else "$label amount"
            if (tool == Tool.StockStrength) stateDescription = tool.display(amount)
            val aperture = tool == Tool.LensAperture
            progressBarRangeInfo = if (aperture) {
                stateDescription = tool.display(amount)
                ProgressBarRangeInfo(tool.rulerPosition(amount), 0f..1f, tool.steps - 1)
            } else ProgressBarRangeInfo(amount, tool.min..top, tool.rulerSteps(top) - 1)
            setProgress { value ->
                commitValue(if (aperture) tool.rulerValue(value) else value.coerceIn(tool.min, top))
                true
            }
            customActions = listOf(CustomAccessibilityAction("Reset to default") {
                commitValue(latestDefault)
                true
            })
        }.pointerInput(tool, top) {
            fun fractionAt(x: Float): Float {
                val inset = 2.dp.toPx()
                return ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
            }
            fun valueAt(x: Float) = tool.rulerValue(fractionAt(x), top)
            suspend fun AwaitPointerEventScope.trackSliderGesture(down: PointerInputChange): Float? {
                var editing = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
                        if (change.isConsumed) return null
                        if (!editing) {
                            val delta = change.position - down.position
                            val slop = viewConfiguration.touchSlop
                            if (abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) return null
                            if (abs(delta.x) < slop) {
                                if (change.pressed) continue
                                change.consume()
                                return change.position.x
                            }
                            latestStart()
                            pressThumb(true)
                            editing = true
                        }
                        dragPosition = fractionAt(change.position.x)
                        latestChange(valueAt(change.position.x))
                        change.consume()
                        if (!change.pressed) return null
                    }
                } finally {
                    if (editing) {
                        dragPosition = null
                        latestFinish()
                        pressThumb(false)
                    }
                }
            }
            awaitEachGesture {
                val firstTap = trackSliderGesture(awaitFirstDown()) ?: return@awaitEachGesture
                // Wait before applying a tap so a double tap creates only one undo step.
                val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                val tapped = if (secondDown == null) valueAt(firstTap)
                    else if (trackSliderGesture(secondDown) != null) latestDefault else null
                if (tapped != null) {
                    commitValue(tapped)
                    // A brief swell so the thumb visibly answers the tap.
                    scope.launch {
                        press.animateTo(1f, spring(1f, 1000f, 0.001f))
                        press.animateTo(0f, spring(1f, 300f, 0.001f))
                    }
                }
            }
        }) {
            val inset = 2.dp.toPx()
            val width = size.width - 2 * inset
            val ticks = if (tool == Tool.LensAperture) tool.steps else 14
            val majorTick = if (tool == Tool.LensAperture) 3 else 7
            val tickColor = muted.copy(alpha = 0.55f)
            drawLine(dividerColor, Offset(inset, center.y), Offset(inset + width, center.y), 1.dp.toPx())
            for (tick in 0..ticks) {
                val x = inset + width * tick / ticks
                val half = if (tick % majorTick == 0) 5.dp.toPx() else 2.dp.toPx()
                drawLine(tickColor, Offset(x, center.y - half), Offset(x, center.y + half), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        val position = dragPosition ?: tool.rulerPosition(amount, top)
        GlassThumb(track, (inset + (trackWidth - 2 * inset) * position) / trackWidth, { press.value }, trackWidth)
    }
}

// Match the slope limiter in adjust.wgsl. A local extremum has a horizontal tangent.
private fun curveTangents(values: List<Float>): List<Float> {
    val steps = values.zipWithNext { a, b -> b - a }
    return listOf(steps.first()) + steps.zipWithNext { a, b ->
        maxOf(0f, minOf(a, b)) + minOf(0f, maxOf(a, b))
    } + steps.last()
}

@Composable
internal fun CurveControls(
    curves: List<List<Float>>, channel: Int, select: (Int) -> Unit, change: (List<List<Float>>) -> Unit,
    reset: () -> Unit,
) {
    val channels = listOf("RGB", "Red", "Green", "Blue")
    val colors = listOf(accent, Color(0xFFF09A99), Color(0xFFA3DAA1), Color(0xFFA2BEF5))
    val active by rememberUpdatedState(curves)
    val updateCurves by rememberUpdatedState(change)
    GlassDock {
        // A darker well keeps the curve readable over bright photos.
        Canvas(Modifier.fillMaxWidth().height(148.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .semantics { contentDescription = "${channels[channel]} curve" }
            .pointerInput(channel) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val inset = 8.dp.toPx()
                    val point = ((down.position.x - inset) / (size.width - 2 * inset) * 4).roundToInt().coerceIn(0, 4)
                    val start = active[channel][point]
                    val height = size.height - 2 * inset
                    down.consume()
                    drag(down.id) { event ->
                        val delta = (down.position.y - event.position.y) / (3f * height)
                        val output = (start + delta).coerceIn(0f, 1f)
                        updateCurves(active.mapIndexed { index, curve ->
                            if (index != channel) curve else curve.mapIndexed { i, value ->
                                if (i == point) output else value
                            }
                        })
                        event.consume()
                    }
                }
            }) {
            val inset = 8.dp.toPx()
            val width = size.width - 2 * inset
            val height = size.height - 2 * inset
            for (line in 0..4) {
                val x = inset + width * line / 4
                val y = inset + height * line / 4
                drawLine(glassDivider, Offset(x, inset), Offset(x, inset + height))
                drawLine(glassDivider, Offset(inset, y), Offset(inset + width, y))
            }
            drawLine(muted.copy(alpha = 0.55f), Offset(inset, inset + height), Offset(inset + width, inset))
            val values = curves[channel]
            val points = values.mapIndexed { index, value ->
                Offset(inset + index * width / 4, inset + (1f - value) * height)
            }
            val tangents = curveTangents(values)
            val curve = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (index in 0 until points.lastIndex) {
                    val a = points[index]
                    val b = points[index + 1]
                    val handle = (b.x - a.x) / 3
                    cubicTo(a.x + handle, a.y - tangents[index] * height / 3,
                        b.x - handle, b.y + tangents[index + 1] * height / 3, b.x, b.y)
                }
            }
            val color = colors[channel]
            drawPath(curve, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            points.forEach {
                drawCircle(background, 4.dp.toPx(), it)
                drawCircle(color, 4.dp.toPx(), it, style = Stroke(1.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            channels.forEachIndexed { index, title ->
                EditorChip(title, selected = channel == index, onClick = { select(index) })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = reset) { Text("Reset") }
        }
    }
}

@Composable
internal fun EditorSheet(title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    GlassSheet(title, dismiss) { content() }
}

@Composable
internal fun SheetChoice(title: String, selected: Boolean, subtitle: String? = null, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) glassSelected else glassWell,
            RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = choose).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) accent else ink, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Glyph(EditorSymbol.Check, accent)
    }
}

