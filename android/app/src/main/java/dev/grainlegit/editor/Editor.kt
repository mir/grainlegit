package dev.grainlegit.editor

import android.content.Context
import android.content.Intent
import android.graphics.Color as PixelColor
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

private val previewBackground = Color(0xFF0B0A0C)
private data class ActivePhoto(val photo: Photo, val layout: GeometryLayout, val generation: Long)

private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

@Composable
internal fun Editor() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("last-edit", Context.MODE_PRIVATE) }
    var uri by rememberSaveable { mutableStateOf(preferences.getString("photo", null)) }
    var seed by rememberSaveable { mutableStateOf(preferences.getLong("seed", Random.nextLong())) }
    var edit by rememberSaveable(stateSaver = Edit.saver) {
        val restored = preferences.getString("recipe", null)?.let { runCatching { Edit.restore(it) }.getOrNull() }
        mutableStateOf(restored ?: Edit())
    }
    val undo = remember { mutableStateListOf<Edit>() }
    val redo = remember { mutableStateListOf<Edit>() }
    var startEdit by remember { mutableStateOf<Edit?>(null) }
    var panel by rememberSaveable { mutableStateOf(Panel.Negative) }
    var group by rememberSaveable { mutableStateOf(ToolGroup.Light) }
    var tool by rememberSaveable { mutableStateOf(Tool.Grain) }
    var cropTool by remember { mutableStateOf(Tool.Straighten) }
    var crop by remember { mutableStateOf<Geometry?>(null) }
    var showingCurves by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    var zoom by remember { mutableStateOf<Camera?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var original by remember { mutableStateOf<ImportedPhoto?>(null) }
    var current by remember { mutableStateOf<ActivePhoto?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val exportCancel = remember { AtomicBoolean() }
    val exporting = exportJob != null

    fun rememberUndo(previous: Edit) {
        undo.add(previous)
        if (undo.size > 60) undo.removeAt(0)
        redo.clear()
    }
    fun commit(updated: Edit) {
        if (updated == edit) return
        rememberUndo(edit)
        edit = updated
    }
    fun finishEdit() {
        startEdit?.takeIf { it != edit }?.let(::rememberUndo)
        startEdit = null
    }

    val renderer = remember { Renderer { renderError = it } }
    DisposableEffect(renderer) {
        onDispose {
            exportCancel.set(true)
            renderer.close()
        }
    }
    BackHandler(enabled = crop != null || picking) {
        crop = null
        picking = false
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { chosen ->
        if (chosen != null) {
            runCatching { resolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val previous = uri
            uri = chosen.toString()
            if (previous != null && previous != uri) {
                runCatching { resolver.releasePersistableUriPermission(previous.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            seed = Random.nextLong()
            edit = Edit()
            undo.clear()
            redo.clear()
            exportMessage = null
        }
    }

    LaunchedEffect(uri, seed) {
        val input = uri ?: return@LaunchedEffect
        renderer.beginSource()
        current = null
        original = null
        zoom = null
        crop = null
        comparing = false
        picking = false
        photoError = null
        opening = true
        try {
            original = withContext(decodeDispatcher) { importPhoto(resolver, input.toUri()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not open the photo"
        } finally {
            opening = false
        }
    }

    LaunchedEffect(uri, seed, edit, original?.photo) {
        val opened = original?.photo ?: return@LaunchedEffect
        if (opened.uri.toString() != uri) return@LaunchedEffect
        delay(300)
        preferences.edit {
            putString("photo", uri)
            putLong("seed", seed)
            putString("recipe", edit.savedSettings())
        }
    }

    val displayGeometry = crop ?: edit.geometry
    LaunchedEffect(original, displayGeometry) {
        val input = original ?: return@LaunchedEffect
        if (current != null) delay(35)
        try {
            val (layout, bitmap) = withContext(decodeDispatcher) {
                val layout = GeometryLayout(input.photo.size, displayGeometry)
                val output = layout.output
                val scale = (FIT_LONG_EDGE.toFloat() / max(output.width, output.height)).coerceAtMost(1f)
                val dimensions = IntSize((output.width * scale).roundToInt().coerceAtLeast(1),
                    (output.height * scale).roundToInt().coerceAtLeast(1))
                layout to layout.render(input.bitmap, input.photo.size.bounds(), output.bounds(), dimensions)
            }
            val generation = renderer.beginSource(clear = false)
            renderer.setSource(generation, layout.output, bitmap, seed)
            current = ActivePhoto(input.photo, layout, generation)
            photoError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not apply the geometry"
        }
    }

    val active = current
    val photoSize = active?.layout?.output
    val fit = if (photoSize != null) fitScale(photoSize, viewport) else 1f
    val view = photoSize?.let { ((if (crop == null) zoom else null) ?: Camera(fit, it.center())).constrain(it, viewport, fit) }
    val fullResolution = view?.scale == 1f && zoom != null && crop == null
    val nativeEdit = remember(edit) { edit.nativeSettings() }
    SideEffect {
        renderer.request(Frame(active?.generation ?: 0L, view?.pixelAligned(viewport)?.center ?: Offset.Zero,
            view?.scale ?: 1f, nativeEdit, comparing, fullResolution))
    }

    val visible = if (fullResolution && viewport.width > 0 && viewport.height > 0) {
        view?.pixelAligned(viewport)?.visibleRegion(photoSize, viewport)
    } else null
    val padding = remember(nativeEdit, photoSize, visible != null) {
        if (visible != null) runCatching {
            NativeRenderer.padding(nativeEdit, max(photoSize!!.width, photoSize.height))
        }.getOrNull() else null
    }
    var covered by remember(active) { mutableStateOf<Rect?>(null) }
    val needed = if (visible != null && padding != null) visible.copyExpanded(photoSize!!, padding) else null
    LaunchedEffect(active, needed) {
        if (active == null || needed == null || covered?.contains(needed) == true) return@LaunchedEffect
        val detail = renderer.requestDetail()
        delay(55)
        val outer = needed.copyExpanded(active.layout.output, 48)
        try {
            val bitmap = withContext(decodeDispatcher) { active.photo.renderOutput(resolver, active.layout, outer) }
            renderer.setDetail(active.generation, detail, bitmap, outer.left, outer.top)
            covered = outer
            photoError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not load the 100% view"
        }
    }

    val currentView by rememberUpdatedState(view)
    val currentViewport by rememberUpdatedState(viewport)
    val currentCrop by rememberUpdatedState(crop)
    val currentPhoto by rememberUpdatedState(active)
    val gestures = when {
        crop != null -> Modifier.pointerInput(photoSize, fit) {
            var resizing = false
            detectDragGestures(onDragStart = { point ->
                val photo = photoSize ?: return@detectDragGestures
                val half = Offset(photo.width * fit / 2f, photo.height * fit / 2f)
                val fromCenter = point - currentViewport.center()
                resizing = abs(fromCenter.x) > half.x * 0.65f && abs(fromCenter.y) > half.y * 0.65f
            }) { event, delta ->
                val draft = currentCrop ?: return@detectDragGestures
                val camera = currentView ?: return@detectDragGestures
                val dimension = photoSize ?: return@detectDragGestures
                val movement = Offset(delta.x / (dimension.width * camera.scale), delta.y / (dimension.height * camera.scale))
                crop = if (resizing) {
                    val side = event.position - currentViewport.center()
                    val dx = if (side.x >= 0) movement.x else -movement.x
                    val dy = if (side.y >= 0) movement.y else -movement.y
                    draft.copy(width = (draft.width + dx * 2).coerceIn(0.1f, 1f),
                        height = (draft.height + dy * 2).coerceIn(0.1f, 1f))
                } else draft.copy(centerX = (draft.centerX + movement.x).coerceIn(0f, 1f),
                    centerY = (draft.centerY + movement.y).coerceIn(0f, 1f))
                event.consume()
            }
        }
        picking -> Modifier.pointerInput(Unit) {
            detectTapGestures { point ->
                val photo = currentPhoto ?: return@detectTapGestures
                val camera = currentView ?: return@detectTapGestures
                val pixel = camera.center + (point - currentViewport.center()) / camera.scale
                if (pixel.x !in 0f..<photo.layout.output.width.toFloat() || pixel.y !in 0f..<photo.layout.output.height.toFloat()) {
                    return@detectTapGestures
                }
                picking = false
                scope.launch {
                    try {
                        val region = Rect(pixel.x.toInt(), pixel.y.toInt(), pixel.x.toInt() + 1, pixel.y.toInt() + 1)
                            .copyExpanded(photo.layout.output, 4)
                        val balance = withContext(decodeDispatcher) {
                            val bitmap = photo.photo.renderOutput(resolver, photo.layout, region)
                            try { sampleBalance(bitmap) } finally { bitmap.recycle() }
                        }
                        if (photo == currentPhoto) commit(edit.copy(balance = balance))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        photoError = error.message ?: "Could not sample white balance"
                    }
                }
            }
        }
        else -> Modifier.pointerInput(photoSize, fit) {
            val size = photoSize ?: return@pointerInput
            detectTransformGestures { centroid, pan, factor, _ ->
                val old = currentView ?: return@detectTransformGestures
                val screenCenter = currentViewport.center()
                val scale = (old.scale * factor).coerceIn(fit, 1f)
                val anchor = old.center + (centroid - screenCenter) / old.scale
                val center = anchor - (centroid + pan - screenCenter) / scale
                zoom = if (scale <= fit) null else Camera(scale, center).constrain(size, currentViewport, fit)
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Grainlegit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (active != null && crop == null) {
                ActionIcon("Undo", "↶", undo.isNotEmpty()) {
                    redo.add(edit)
                    edit = undo.removeAt(undo.lastIndex)
                }
                ActionIcon("Redo", "↷", redo.isNotEmpty()) {
                    undo.add(edit)
                    edit = redo.removeAt(redo.lastIndex)
                }
                ActionIcon("Export JPEG", "↑", !exporting) {
                    exportCancel.set(false)
                    exportMessage = null
                    progress = 0f
                    val snapshot = edit
                    exportJob = scope.launch {
                        try {
                            val layout = GeometryLayout(active.photo.size, snapshot.geometry)
                            savePhoto(context, renderer, active.photo, layout, seed, snapshot.nativeSettings(), exportCancel) { progress = it }
                            exportMessage = "Saved to Pictures/Grainlegit"
                        } catch (cancelled: CancellationException) {
                            exportMessage = "Export cancelled"
                            throw cancelled
                        } catch (error: Exception) {
                            exportMessage = error.message ?: "Could not export the photo"
                        } finally {
                            exportJob = null
                        }
                    }
                }
            }
            Button(enabled = !exporting, onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
                Text("Open photo")
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(previewBackground)
            .onSizeChanged { viewport = it }.then(gestures), contentAlignment = Alignment.Center) {
            PreviewSurface(renderer)
            if (crop != null && photoSize != null) CropGrid(photoSize, fit)
            val status = photoError ?: renderError ?: when {
                opening || (active == null && original != null) -> "Opening photo…"
                active == null -> "Open a JPEG or HEIF photo"
                picking -> "Tap a neutral area"
                else -> null
            }
            if (status != null) {
                Surface(color = background, shape = RoundedCornerShape(12.dp)) {
                    Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        if (exporting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                TextButton(onClick = { exportCancel.set(true); exportJob?.cancel() }) { Text("Cancel export") }
            }
        } else if (exportMessage != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(exportMessage.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { exportMessage = null }) { Text("Dismiss") }
            }
        }

        val draft = crop
        if (draft == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(zoom == null, { zoom = null }, { Text("Fit") }, enabled = active != null)
                FilterChip(fullResolution, {
                    if (photoSize != null) zoom = Camera(1f, view?.center ?: photoSize.center())
                }, { Text("100%") }, enabled = active != null)
                if (active != null) CompareControl(comparing) { comparing = it }
            }
            EditControls(panel, { panel = it; tool = if (it == Panel.Adjust) group.tools.first() else it.defaultTool; picking = false },
                group, { group = it; tool = it.tools.first(); picking = false },
                tool, { tool = it; picking = false }, edit, active != null, picking,
                onCrop = { zoom = null; cropTool = Tool.Straighten; crop = edit.geometry; picking = false },
                change = { edit = it }, start = { startEdit = edit }, finish = ::finishEdit, commit = ::commit,
                openCurves = { showingCurves = true }, pickBalance = { picking = !picking })
        } else {
            CropControls(draft, cropTool, { cropTool = it }, { crop = it },
                apply = { commit(edit.copy(geometry = draft)); crop = null }, cancel = { crop = null })
        }
    }
    if (showingCurves) {
        CurvesDialog(edit.curves, { commit(edit.copy(curves = it)); showingCurves = false }, { showingCurves = false })
    }
}

private fun sampleBalance(bitmap: android.graphics.Bitmap): List<Float> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val rgb = listOf(pixels.map { linear(PixelColor.red(it)) }.average(),
        pixels.map { linear(PixelColor.green(it)) }.average(), pixels.map { linear(PixelColor.blue(it)) }.average())
    val neutral = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
    require(neutral > 0.003) { "Choose a brighter neutral area" }
    return rgb.map { (neutral / it.coerceAtLeast(0.001)).toFloat().coerceIn(0.35f, 3f) }
}

@Composable
private fun PreviewSurface(renderer: Renderer) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = renderer.surfaceCreated(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                    renderer.surfaceChanged(width, height)
                override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.surfaceDestroyed()
            })
        }
    })
}

@Composable
private fun CropGrid(photo: IntSize, scale: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = photo.width * scale
        val h = photo.height * scale
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val line = Color.White.copy(alpha = 0.66f)
        for (i in 0..3) {
            val x = left + w * i / 3
            val y = top + h * i / 3
            drawLine(line, Offset(x, top), Offset(x, top + h), 1.dp.toPx())
            drawLine(line, Offset(left, y), Offset(left + w, y), 1.dp.toPx())
        }
        for (x in listOf(left, left + w)) {
            for (y in listOf(top, top + h)) drawCircle(Color.White, 3.dp.toPx(), Offset(x, y))
        }
    }
}
