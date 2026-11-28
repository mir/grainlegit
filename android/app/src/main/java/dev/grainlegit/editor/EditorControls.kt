package dev.grainlegit.editor

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class Panel(val title: String, val defaultTool: Tool) {
    Lens("Lens", Tool.LensAperture), Negative("Grain", Tool.CameraExposure), Adjust("Adjust", Tool.Exposure),
}

// Retained for activity state saved by Android 0.2.
internal enum class ToolGroup { Light, Colour, Detail }

internal enum class Sheet { Stock, Format, Ratio, Export, Saved }

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

// Translucent black behind controls that float over the photo.
internal val overlayColor = Color.Black.copy(alpha = 0.6f)

@Composable
internal fun EditControls(
    panel: Panel, setPanel: (Panel) -> Unit, tool: Tool, edit: Edit,
    selectTool: (Tool) -> Unit, onCrop: () -> Unit, onErase: () -> Unit, showSheet: (Sheet) -> Unit,
    change: (Edit) -> Unit, start: () -> Unit, finish: () -> Unit, commit: (Edit) -> Unit,
    autoGrain: Edit? = null,
) {
    Column(Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 16.dp)) {
        HorizontalDivider(color = dividerColor)
        if (tool == Tool.Curves || tool == Tool.WhiteBalance) {
            Box(
                Modifier.fillMaxWidth().height(50.dp).clickable(role = Role.Button) { selectTool(tool) },
                contentAlignment = Alignment.CenterStart,
            ) { Text(tool.title) }
        } else {
            val coarse = tool == Tool.CoarseFraction
            val usingStockMix = coarse && edit.useCoarseProfile
            val disabledHint = if (usingStockMix) "Using the stock’s fine and coarse mix." else null
            Ruler(tool, edit[tool], disabledHint == null,
                start, { change(edit.with(tool, it)) }, finish,
                disabledHint = disabledHint,
                headerOption = when {
                    tool == Tool.LensAperture -> {
                        { Text("35/1.4 Double-Asph", color = muted, style = MaterialTheme.typography.labelMedium) }
                    }
                    coarse -> {
                        {
                            Text(
                                "Use stock profile", color = if (usingStockMix) accent else muted,
                                modifier = Modifier.background(if (usingStockMix) selectedColor else controlColor, RoundedCornerShape(6.dp))
                                    .toggleable(usingStockMix, role = Role.Switch) { commit(edit.copy(useCoarseProfile = it)) }
                                    .padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    else -> null
                })
        }
        when (panel) {
            Panel.Adjust -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorChip("Crop", onClick = onCrop)
                adjustmentTools.forEach { entry ->
                    EditorChip(entry.title, selected = entry == tool, onClick = { selectTool(entry) })
                }
            }
            Panel.Lens -> ToolStrip(lensTools, tool, selectTool, Modifier.fillMaxWidth())
            Panel.Negative -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (autoGrain != null) {
                    AutoGrainButton(applied = autoGrain == edit) {
                        commit(autoGrain)
                        selectTool(Tool.GrainSize)
                    }
                }
                val name = stockNames[stockIds.indexOf(edit.stockId).coerceAtLeast(0)]
                EditorChip(name, chevron = true, onClick = { showSheet(Sheet.Stock) })
                EditorChip(formatNames[edit.format], chevron = true, onClick = { showSheet(Sheet.Format) })
                grainTools.forEach { entry ->
                    EditorChip(entry.chipTitle(), selected = entry == tool, onClick = { selectTool(entry) })
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = dividerColor)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorChip("Erase", modifier = Modifier.weight(1f), plain = true, onClick = onErase)
            Panel.entries.forEach {
                EditorChip(it.title, selected = panel == it, modifier = Modifier.weight(1f), plain = true,
                    role = Role.Tab, onClick = { if (panel != it) setPanel(it) })
            }
        }
    }
}

// Sets grain size so the grain is clearly visible with the whole photo on screen.
@Composable
private fun AutoGrainButton(applied: Boolean, apply: () -> Unit) {
    Box(
        Modifier.size(48.dp).selectable(selected = applied, role = Role.Button, onClick = apply)
            .semantics { contentDescription = "Auto grain" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).background(if (applied) selectedColor else controlColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Glyph(EditorSymbol.Auto, if (applied) accent else ink, Modifier.size(20.dp))
        }
    }
}

private fun Tool.chipTitle() = when (this) {
    Tool.CameraExposure -> "Exposure"
    Tool.Grain -> "Amount"
    Tool.GrainSize -> "Size"
    Tool.GrainStrength -> "Strength"
    Tool.GrainTone -> "Tone"
    Tool.RedCloudSize -> "Red size"
    Tool.GreenCloudSize -> "Green size"
    Tool.BlueCloudSize -> "Blue size"
    else -> title
}

@Composable
private fun ToolStrip(tools: List<Tool>, selected: Tool, select: (Tool) -> Unit, modifier: Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tool in tools) EditorChip(tool.title, selected = tool == selected, onClick = { select(tool) })
    }
}

@Composable
internal fun EditorChip(
    label: String, selected: Boolean = false, modifier: Modifier = Modifier, plain: Boolean = false,
    chevron: Boolean = false, role: Role = Role.Button, highlightOnly: Boolean = false, overlay: Boolean = false,
    onClick: () -> Unit,
) {
    val style = MaterialTheme.typography.labelLarge
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(48.dp).selectable(selected = selected, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.then(if (plain) Modifier.fillMaxWidth() else Modifier)
                .height(34.dp).background(when {
                    selected && !highlightOnly -> selectedColor
                    overlay -> overlayColor
                    plain -> Color.Transparent
                    else -> controlColor
                }, shape)
                .padding(horizontal = if (plain) 4.dp else 11.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (selected) accent else muted, style = style, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
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
    Column(Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Ruler(tool, value, true, {}, {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolStrip(listOf(Tool.Straighten, Tool.Vertical, Tool.Horizontal), tool, setTool, Modifier.weight(1f))
            EditorChip(cropRatios[geometry.ratio], chevron = true, onClick = chooseRatio)
        }
        HorizontalDivider(color = dividerColor)
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

internal enum class EditorSymbol { Down, Close, Back, Undo, Redo, Export, Compare, Check, Rotate, FlipX, FlipY, Auto }

@Composable
private fun Glyph(symbol: EditorSymbol, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
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
            EditorSymbol.Export -> {
                line(12f, 16f, 12f, 3f); line(7f, 8f, 12f, 3f, 17f, 8f)
                line(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f)
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
    label: String, symbol: EditorSymbol, enabled: Boolean = true, selected: Boolean = false, overlay: Boolean = false,
    action: () -> Unit,
) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Box(if (overlay) Modifier.size(38.dp).background(overlayColor, CircleShape) else Modifier,
            contentAlignment = Alignment.Center) {
            Glyph(symbol, if (!enabled) muted.copy(alpha = 0.55f) else if (selected) accent else ink)
        }
    }
}

@Composable
internal fun CompareControl(comparing: Boolean, change: (Boolean) -> Unit) {
    val latestChange by rememberUpdatedState(change)
    Box(
        Modifier.size(48.dp).semantics {
            contentDescription = "Hold to compare"
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
        Box(Modifier.size(38.dp).background(overlayColor, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(EditorSymbol.Compare, if (comparing) accent else ink)
        }
    }
}

@Composable
internal fun Ruler(
    tool: Tool, amount: Float, enabled: Boolean, start: () -> Unit, change: (Float) -> Unit, finish: () -> Unit,
    label: String = tool.title, disabledHint: String? = null,
    headerOption: (@Composable () -> Unit)? = null,
) {
    val latestChange by rememberUpdatedState(change)
    val latestStart by rememberUpdatedState(start)
    val latestFinish by rememberUpdatedState(finish)
    Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = ink, style = MaterialTheme.typography.bodyMedium)
            headerOption?.invoke()
            Text(if (enabled) tool.display(amount) else "—", color = if (enabled) accent else muted,
                modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (disabledHint != null) {
            Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) {
                Text(disabledHint, color = muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics {
            contentDescription = if (label.endsWith(" amount")) label else "$label amount"
            val aperture = tool == Tool.LensAperture
            progressBarRangeInfo = if (aperture) {
                stateDescription = tool.display(amount)
                ProgressBarRangeInfo(tool.rulerPosition(amount), 0f..1f, tool.steps - 1)
            } else ProgressBarRangeInfo(amount, tool.min..tool.max, tool.steps - 1)
            if (enabled) setProgress { value ->
                latestStart()
                latestChange(if (aperture) tool.rulerValue(value) else value.coerceIn(tool.min, tool.max))
                latestFinish()
                true
            }
        }.pointerInput(tool, enabled) {
            if (!enabled) return@pointerInput
            fun update(x: Float) {
                val inset = 2.dp.toPx()
                val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                latestChange(tool.rulerValue(fraction))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                var editing = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (!editing) {
                            val delta = change.position - down.position
                            val slop = viewConfiguration.touchSlop
                            if (abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) break
                            if (change.pressed && abs(delta.x) < slop) continue
                            latestStart()
                            editing = true
                        }
                        update(change.position.x)
                        change.consume()
                        if (!change.pressed) break
                    }
                } finally {
                    if (editing) latestFinish()
                }
            }
        }) {
            val inset = 2.dp.toPx()
            val width = size.width - 2 * inset
            val ticks = if (tool == Tool.LensAperture) tool.steps else 14
            val majorTick = if (tool == Tool.LensAperture) 3 else 7
            drawLine(dividerColor, Offset(inset, center.y), Offset(inset + width, center.y), 1.dp.toPx())
            for (tick in 0..ticks) {
                val x = inset + width * tick / ticks
                val half = if (tick % majorTick == 0) 5.dp.toPx() else 2.dp.toPx()
                drawLine(dividerColor, Offset(x, center.y - half), Offset(x, center.y + half), 2.dp.toPx(), StrokeCap.Round)
            }
            val x = inset + width * tool.rulerPosition(amount)
            drawLine(accent, Offset(x, center.y - 8.dp.toPx()), Offset(x, center.y + 8.dp.toPx()),
                4.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun CurveControls(curves: List<List<Float>>, channel: Int, select: (Int) -> Unit, change: (List<List<Float>>) -> Unit) {
    val channels = listOf("RGB", "Red", "Green", "Blue")
    val colors = listOf(accent, Color(0xFFF09A99), Color(0xFFA3DAA1), Color(0xFFA2BEF5))
    val active by rememberUpdatedState(curves)
    val updateCurves by rememberUpdatedState(change)
    Row(
        Modifier.fillMaxWidth().background(panelColor).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.weight(1f).height(168.dp).background(background, RoundedCornerShape(5.dp))
            .semantics { contentDescription = "${channels[channel]} curve" }
            .pointerInput(channel) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val point = (down.position.x / size.width * 4).roundToInt().coerceIn(0, 4)
                    fun update(y: Float) {
                        updateCurves(active.mapIndexed { index, curve ->
                            if (index != channel) curve else curve.mapIndexed { i, value ->
                                if (i == point) (1f - y / size.height).coerceIn(0f, 1f) else value
                            }
                        })
                    }
                    update(down.position.y)
                    down.consume()
                    drag(down.id) { event -> update(event.position.y); event.consume() }
                }
            }) {
            for (line in 0..4) {
                val x = size.width * line / 4
                val y = size.height * line / 4
                drawLine(dividerColor, Offset(x, 0f), Offset(x, size.height))
                drawLine(dividerColor, Offset(0f, y), Offset(size.width, y))
            }
            drawLine(dividerColor, Offset(0f, size.height), Offset(size.width, 0f))
            val points = curves[channel].mapIndexed { index, value ->
                Offset(index * (size.width - 8.dp.toPx()) / 4 + 4.dp.toPx(), (1f - value) * size.height)
            }
            points.zipWithNext { a, b -> drawLine(colors[channel], a, b, 2.dp.toPx(), StrokeCap.Round) }
            points.drop(1).dropLast(1).forEach {
                drawCircle(background, 4.dp.toPx(), it)
                drawCircle(colors[channel], 4.dp.toPx(), it, style = Stroke(1.5.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            channels.forEachIndexed { index, title ->
                EditorChip(title, selected = channel == index, onClick = { select(index) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSheet(
    title: String, dismiss: () -> Unit, confirmLabel: String? = null, confirm: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = panelColor, contentColor = ink,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.headlineSmall)
            EditorAction("Close", EditorSymbol.Close, action = dismiss)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            content()
        }
        if (confirmLabel != null) {
            Button(onClick = confirm, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(confirmLabel)
            }
        }
    }
}

@Composable
internal fun SheetChoice(title: String, selected: Boolean, subtitle: String? = null, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) selectedColor else controlColor,
            RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = choose).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) accent else ink, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Glyph(EditorSymbol.Check, accent)
    }
}

