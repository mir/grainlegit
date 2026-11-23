package dev.grainlegit.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.round
import kotlin.math.roundToInt

internal enum class Panel(val title: String, val defaultTool: Tool) {
    Adjust("Adjust", Tool.Exposure), Negative("Negative", Tool.Grain),
    Print("Optical Print", Tool.Medium),
}

internal enum class ToolGroup(val tools: List<Tool>) {
    Light(listOf(Tool.Exposure, Tool.Brightness, Tool.Highlights, Tool.Shadows, Tool.Contrast, Tool.Whites, Tool.Blacks, Tool.Curves)),
    Colour(listOf(Tool.Temperature, Tool.Tint, Tool.Saturation, Tool.Vibrance, Tool.WhiteBalance)),
    Detail(listOf(Tool.Sharpen)),
}

private val negativeTools = listOf(Tool.Stock, Tool.Format, Tool.CameraExposure, Tool.Grain)
private val printTools = listOf(Tool.Medium, Tool.PrintExposure, Tool.Enlargement, Tool.EnlargerBlur,
    Tool.PrintGrain, Tool.Cyan, Tool.Magenta, Tool.Yellow, Tool.ScanBlur, Tool.Aperture)
private val tickColor = Color(0xFF777278)

@Composable
internal fun EditControls(
    panel: Panel, setPanel: (Panel) -> Unit, group: ToolGroup, setGroup: (ToolGroup) -> Unit,
    tool: Tool, selectTool: (Tool) -> Unit, edit: Edit, enabled: Boolean, picking: Boolean,
    onCrop: () -> Unit, change: (Edit) -> Unit, start: () -> Unit, finish: () -> Unit,
    commit: (Edit) -> Unit, openCurves: () -> Unit, pickBalance: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        when (tool) {
            Tool.Stock -> OptionsRuler(tool.title, stockNames, stockIds.indexOf(edit.stockId)) {
                commit(edit.copy(stockId = stockIds[it]))
            }
            Tool.Format -> OptionsRuler(tool.title, formatNames, edit.format) { commit(edit.copy(format = it)) }
            Tool.Medium -> OptionsRuler(tool.title, printMedia, edit.medium) { commit(edit.copy(medium = it)) }
            Tool.Curves -> TextButton(onClick = openCurves) { Text("Edit curves") }
            Tool.WhiteBalance -> Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = pickBalance) { Text(if (picking) "Cancel picker" else "Sample a neutral area") }
                TextButton(onClick = { commit(edit.copy(balance = listOf(1f, 1f, 1f))) }) { Text("Reset") }
            }
            else -> Ruler(tool, edit[tool], enabled && (edit.medium != 0 || !tool.requiresPrint()),
                start, { change(edit.with(tool, it)) }, finish)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (panel == Panel.Adjust) {
                TextButton(onClick = onCrop, enabled = enabled) { Text("Crop") }
            }
            val tools = when (panel) {
                Panel.Adjust -> group.tools
                Panel.Negative -> negativeTools
                Panel.Print -> printTools
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (entry in tools) {
                    FilterChip(selected = entry == tool, onClick = { selectTool(entry) },
                        enabled = enabled && (edit.medium != 0 || !entry.requiresPrint()), label = { Text(entry.title) })
                }
            }
            if (panel == Panel.Adjust) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    TextButton(onClick = { menu = true }) { Text(group.name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ToolGroup.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = { menu = false; setGroup(item) })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Panel.entries.forEach {
                FilterChip(selected = panel == it, onClick = { setPanel(it) },
                    label = { Text(it.title) }, enabled = enabled)
            }
        }
    }
}

private fun Tool.requiresPrint() = this in listOf(
    Tool.PrintExposure, Tool.Enlargement, Tool.EnlargerBlur, Tool.PrintGrain, Tool.Cyan, Tool.Magenta, Tool.Yellow,
)

@Composable
internal fun CropControls(
    geometry: Geometry, tool: Tool, setTool: (Tool) -> Unit,
    change: (Geometry) -> Unit, apply: () -> Unit, cancel: () -> Unit,
) {
    val value = when (tool) {
        Tool.Horizontal -> geometry.horizontal
        Tool.Vertical -> geometry.vertical
        else -> geometry.straighten
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Ruler(tool, value, true, {}, {
            change(when (tool) {
                Tool.Horizontal -> geometry.copy(horizontal = it)
                Tool.Vertical -> geometry.copy(vertical = it)
                else -> geometry.copy(straighten = it)
            })
        }, {})
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in listOf(Tool.Straighten, Tool.Horizontal, Tool.Vertical)) {
                FilterChip(tool == mode, { setTool(mode) }, { Text(mode.title) })
            }
            FilterChip(geometry.constrain, { change(geometry.copy(constrain = !geometry.constrain)) },
                { Text("Constrain Crop") })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            var ratioMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { ratioMenu = true }) { Text(cropRatios[geometry.ratio]) }
                DropdownMenu(expanded = ratioMenu, onDismissRequest = { ratioMenu = false }) {
                    cropRatios.forEachIndexed { index, title ->
                        DropdownMenuItem(text = { Text(title) }, onClick = {
                            ratioMenu = false
                            change(geometry.copy(ratio = index))
                        })
                    }
                }
            }
            ActionIcon("Rotate 90°", "↻") { change(geometry.copy(turns = (geometry.turns + 1) % 4)) }
            ActionIcon("Flip horizontally", "↔") { change(geometry.copy(flipH = !geometry.flipH)) }
            ActionIcon("Flip vertically", "↕") { change(geometry.copy(flipV = !geometry.flipV)) }
            TextButton(onClick = { change(Geometry()) }) { Text("Reset") }
            TextButton(onClick = cancel) { Text("Cancel") }
            TextButton(onClick = apply) { Text("Apply") }
        }
    }
}

@Composable
internal fun ActionIcon(name: String, glyph: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = name }) {
        Text(glyph, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
internal fun CompareControl(comparing: Boolean, change: (Boolean) -> Unit) {
    val latestChange by rememberUpdatedState(change)
    Surface(
        modifier = Modifier.semantics {
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
        color = if (comparing) MaterialTheme.colorScheme.secondaryContainer else background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text("◐", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun OptionsRuler(title: String, options: List<String>, active: Int, choose: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RulerLabel(title, options.getOrElse(active) { options.first() })
        Row(Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                FilterChip(selected = index == active, onClick = { choose(index) }, label = { Text(option) })
            }
        }
    }
}

@Composable
private fun RulerLabel(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(value, color = accent, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun Ruler(tool: Tool, amount: Float, enabled: Boolean, start: () -> Unit, change: (Float) -> Unit, finish: () -> Unit) {
    val latestChange by rememberUpdatedState(change)
    val latestStart by rememberUpdatedState(start)
    val latestFinish by rememberUpdatedState(finish)
    val range = tool.max - tool.min
    Column(Modifier.fillMaxWidth()) {
        RulerLabel(tool.title, tool.display(amount))
        Canvas(Modifier.fillMaxWidth().height(42.dp).semantics {
            contentDescription = "${tool.title} amount"
            progressBarRangeInfo = ProgressBarRangeInfo(amount, tool.min..tool.max, tool.steps - 1)
            if (enabled) setProgress { value ->
                latestStart()
                latestChange(value.coerceIn(tool.min, tool.max))
                latestFinish()
                true
            }
        }.pointerInput(tool, enabled) {
            if (!enabled) return@pointerInput
            fun update(x: Float) {
                val fraction = (x / size.width).coerceIn(0f, 1f)
                latestChange(tool.min + range * round(fraction * tool.steps) / tool.steps)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                latestStart()
                try {
                    update(down.position.x)
                    down.consume()
                    drag(down.id) { event -> update(event.position.x); event.consume() }
                } finally {
                    latestFinish()
                }
            }
        }) {
            val inset = 2.dp.toPx()
            val width = size.width - 2 * inset
            for (tick in 0..14) {
                val x = inset + width * tick / 14
                val half = if (tick % 7 == 0) 11.dp.toPx() else 6.dp.toPx()
                drawLine(tickColor, Offset(x, center.y - half), Offset(x, center.y + half), 1.dp.toPx())
            }
            val x = inset + width * (amount - tool.min) / range
            drawLine(if (enabled) accent else tickColor, Offset(x, center.y - 15.dp.toPx()),
                Offset(x, center.y + 15.dp.toPx()), 2.dp.toPx())
        }
    }
}

@Composable
internal fun CurvesDialog(curves: List<List<Float>>, apply: (List<List<Float>>) -> Unit, close: () -> Unit) {
    var draft by remember { mutableStateOf(curves) }
    var channel by remember { mutableIntStateOf(0) }
    val channels = listOf("RGB", "Red", "Green", "Blue")
    val colors = listOf(accent, Color(0xFFF16967), Color(0xFF83C787), Color(0xFF7BA6F0))
    val active by rememberUpdatedState(draft)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Curves") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    channels.forEachIndexed { index, title ->
                        FilterChip(index == channel, { channel = index }, { Text(title) })
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(210.dp).semantics { contentDescription = "${channels[channel]} curve" }
                    .pointerInput(channel) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val point = (down.position.x / size.width * 4).roundToInt().coerceIn(0, 4)
                            fun update(y: Float) {
                                draft = active.mapIndexed { index, curve ->
                                    if (index != channel) curve else curve.mapIndexed { i, current ->
                                        if (i == point) (1f - y / size.height).coerceIn(0f, 1f) else current
                                    }
                                }
                            }
                            update(down.position.y)
                            down.consume()
                            drag(down.id) { event -> update(event.position.y); event.consume() }
                        }
                    }) {
                    for (line in 0..4) {
                        val x = size.width * line / 4
                        val y = size.height * line / 4
                        drawLine(tickColor, Offset(x, 0f), Offset(x, size.height))
                        drawLine(tickColor, Offset(0f, y), Offset(size.width, y))
                    }
                    val points = draft[channel].mapIndexed { i, value -> Offset(i * size.width / 4f, (1f - value) * size.height) }
                    points.zipWithNext { a, b -> drawLine(colors[channel], a, b, 2.dp.toPx()) }
                    points.forEach { drawCircle(colors[channel], 4.dp.toPx(), it) }
                }
                TextButton(onClick = { draft = draft.mapIndexed { i, curve -> if (i == channel) neutralCurve else curve } }) {
                    Text("Reset channel")
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(draft) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
