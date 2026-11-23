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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Rect as ScreenRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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

private data class ActivePhoto(
    val photo: Photo, val layout: GeometryLayout, val previewRegion: Rect,
    val includesSurroundings: Boolean, val generation: Long,
) {
    val previewSize get() = IntSize(previewRegion.width(), previewRegion.height())

    fun cropOnScreen(viewport: IntSize, scale: Float): ScreenRect {
        val offset = viewport.center() - previewSize.center() * scale
        val topLeft = offset - Offset(previewRegion.left.toFloat(), previewRegion.top.toFloat()) * scale
        return ScreenRect(topLeft, Size(layout.output.width * scale, layout.output.height * scale))
    }
}

private data class BalanceSample(val point: IntOffset, val color: Color, val gains: List<Float>)

private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

@Composable
internal fun Editor() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("last-edit", Context.MODE_PRIVATE) }
    var recents by remember(preferences) { mutableStateOf(loadRecentEdits(preferences)) }
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var seed by rememberSaveable { mutableStateOf(0L) }
    var edit by rememberSaveable(stateSaver = Edit.saver) { mutableStateOf(Edit()) }
    var title by remember { mutableStateOf("Photo") }
    val undo = remember { mutableStateListOf<Edit>() }
    val redo = remember { mutableStateListOf<Edit>() }
    var startEdit by remember { mutableStateOf<Edit?>(null) }
    var panel by rememberSaveable { mutableStateOf(Panel.Adjust) }
    var group by rememberSaveable { mutableStateOf(ToolGroup.Light) }
    var tool by rememberSaveable { mutableStateOf(Tool.Exposure) }
    var cropTool by remember { mutableStateOf(Tool.Straighten) }
    var crop by remember { mutableStateOf<Geometry?>(null) }
    var curveDraft by remember { mutableStateOf<List<List<Float>>?>(null) }
    var curveChannel by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var samplePoint by remember { mutableStateOf<IntOffset?>(null) }
    var sample by remember { mutableStateOf<BalanceSample?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var printDraft by remember { mutableStateOf<Edit?>(null) }

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

    fun saveCurrent() {
        val input = uri ?: return
        if (original?.photo?.uri?.toString() != input) return
        val latest = RecentEdit(input, title, seed, edit)
        val updated = (listOf(latest) + recents.filterNot { it.uri == input }).take(30)
        if (updated == recents) return
        val retained = updated.map { it.uri }.toSet()
        val expired = recents.filterNot { it.uri in retained }
        recents = updated
        saveRecentEdits(preferences, updated)
        expired.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun resetSample() {
        samplePoint = null
        sample = null
        photoError = null
    }

    fun openPhoto(entry: RecentEdit) {
        saveCurrent()
        uri = entry.uri
        seed = entry.seed
        edit = entry.edit
        title = entry.title
        undo.clear()
        redo.clear()
        panel = Panel.Adjust
        group = ToolGroup.Light
        tool = Tool.Exposure
        exportMessage = null
    }

    fun dismissSheet() {
        sheet = null
        printDraft = null
    }

    fun selectTool(selected: Tool) {
        tool = selected
        when (selected) {
            Tool.Curves -> {
                curveChannel = 0
                curveDraft = edit.curves
            }
            Tool.WhiteBalance -> {
                resetSample()
                picking = true
            }
            else -> Unit
        }
    }

    fun goBack() {
        when {
            sheet != null -> dismissSheet()
            crop != null -> crop = null
            curveDraft != null -> curveDraft = null
            picking -> {
                picking = false
                resetSample()
            }
            !exporting -> {
                saveCurrent()
                uri = null
            }
        }
    }

    val renderer = remember { Renderer { renderError = it } }
    val saveLatest by rememberUpdatedState(::saveCurrent)
    DisposableEffect(renderer) {
        onDispose {
            saveLatest()
            exportCancel.set(true)
            renderer.close()
        }
    }
    BackHandler(enabled = uri != null) { goBack() }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { chosen ->
        if (chosen != null) {
            runCatching { resolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val selected = chosen.toString()
            openPhoto(recents.firstOrNull { it.uri == selected } ?: RecentEdit(selected, "Photo", Random.nextLong(), Edit()))
        }
    }

    LaunchedEffect(uri, seed) {
        renderer.beginSource()
        current = null
        original = null
        zoom = null
        crop = null
        comparing = false
        picking = false
        curveDraft = null
        dismissSheet()
        resetSample()
        val input = uri ?: return@LaunchedEffect
        opening = true
        try {
            val (imported, name) = withContext(decodeDispatcher) {
                val photoUri = input.toUri()
                importPhoto(resolver, photoUri) to resolver.photoTitle(photoUri)
            }
            title = name
            original = imported
            saveCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not open the photo"
        } finally {
            opening = false
        }
    }

    LaunchedEffect(uri, seed, edit, original?.photo) {
        delay(300)
        saveCurrent()
    }

    val displayGeometry = crop ?: edit.geometry
    val cropping = crop != null
    LaunchedEffect(original, displayGeometry, cropping) {
        val input = original ?: return@LaunchedEffect
        if (current != null) delay(35)
        try {
            val (layout, region, bitmap) = withContext(decodeDispatcher) {
                val layout = GeometryLayout(input.photo.size, displayGeometry)
                val region = if (cropping) layout.fullRegion else layout.output.bounds()
                val output = IntSize(region.width(), region.height())
                val scale = (FIT_LONG_EDGE.toFloat() / max(output.width, output.height)).coerceAtMost(1f)
                val dimensions = IntSize((output.width * scale).roundToInt().coerceAtLeast(1),
                    (output.height * scale).roundToInt().coerceAtLeast(1))
                Triple(layout, region, layout.render(input.bitmap, input.photo.size.bounds(), region, dimensions))
            }
            val generation = renderer.beginSource(clear = false)
            renderer.setSource(generation, IntSize(region.width(), region.height()), bitmap, seed)
            current = ActivePhoto(input.photo, layout, region, cropping, generation)
            photoError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not apply the geometry"
        }
    }

    val active = current
    val photoSize = active?.previewSize
    val cropInset = with(LocalDensity.current) { 18.dp.roundToPx() }
    val fitViewport = if (cropping) {
        // Keep the crop handles clear of Android's edge gestures.
        IntSize((viewport.width - 2 * cropInset).coerceAtLeast(0), (viewport.height - 2 * cropInset).coerceAtLeast(0))
    } else viewport
    val fit = if (photoSize != null) fitScale(photoSize, fitViewport) else 1f
    val zoomEnabled = !cropping && !picking && curveDraft == null && active?.includesSurroundings == false
    val view = photoSize?.let { ((if (zoomEnabled) zoom else null) ?: Camera(fit, it.center())).constrain(it, viewport, fit) }
    val fullResolution = zoomEnabled && view?.scale == 1f && zoom != null
    val previewEdit = printDraft ?: edit.copy(curves = curveDraft ?: edit.curves, balance = sample?.gains ?: edit.balance)
    val nativeEdit = remember(previewEdit) { previewEdit.nativeSettings() }
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
    val cropWindow = active?.takeIf { it.includesSurroundings && cropping }?.cropOnScreen(viewport, fit)
    val currentCropWindow by rememberUpdatedState(cropWindow)
    val gestures = when {
        cropping -> Modifier.pointerInput(Unit) {
            var gesture: CropDrag? = null
            var movement = Offset.Zero
            detectDragGestures(onDragStart = { point ->
                val photo = currentPhoto ?: return@detectDragGestures
                val window = currentCropWindow ?: return@detectDragGestures
                val draft = currentCrop ?: return@detectDragGestures
                val scale = currentView?.scale ?: return@detectDragGestures
                val bounds = Size(photo.previewSize.width * scale, photo.previewSize.height * scale)
                val slop = 24.dp.toPx()
                val nearX = minOf(abs(point.x - window.left), abs(point.x - window.right)) <= slop
                val nearY = minOf(abs(point.y - window.top), abs(point.y - window.bottom)) <= slop
                val corner = if (nearX && nearY) Offset(if (point.x < window.center.x) -1f else 1f,
                    if (point.y < window.center.y) -1f else 1f) else null
                movement = Offset.Zero
                gesture = CropDrag(draft, window, bounds, currentViewport.center(), corner)
            }, onDragEnd = { gesture = null }, onDragCancel = { gesture = null }) { event, delta ->
                val drag = gesture ?: return@detectDragGestures
                movement += delta
                crop = drag.move(movement)
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
                samplePoint = IntOffset(pixel.x.toInt(), pixel.y.toInt())
            }
        }
        zoomEnabled -> Modifier.pointerInput(photoSize, fit) {
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
        else -> Modifier
    }

    LaunchedEffect(picking, samplePoint, active) {
        val point = samplePoint ?: return@LaunchedEffect
        if (!picking || active == null) return@LaunchedEffect
        try {
            val region = Rect(point.x, point.y, point.x + 1, point.y + 1).copyExpanded(active.layout.output, 4)
            sample = withContext(decodeDispatcher) {
                val bitmap = active.photo.renderOutput(resolver, active.layout, region)
                try { sampleBalance(bitmap, point) } finally { bitmap.recycle() }
            }
            photoError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not sample white balance"
        }
    }

    fun startExport(photo: ActivePhoto) {
        exportCancel.set(false)
        exportMessage = null
        progress = 0f
        val snapshot = edit
        val exportSeed = seed
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val layout = GeometryLayout(photo.photo.size, snapshot.geometry)
                savePhoto(context, renderer, photo.photo, layout, exportSeed, snapshot.nativeSettings(), exportCancel) {
                    progress = it
                }
                sheet = Sheet.Saved
            } catch (cancelled: CancellationException) {
                exportMessage = "Export cancelled"
                throw cancelled
            } catch (error: Exception) {
                exportMessage = error.message ?: "Could not export the photo"
            } finally {
                exportJob = null
            }
        }
        exportJob?.start()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (uri == null) {
            RecentEdits(recents, { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, ::openPhoto)
        } else {
            val cropDraft = crop
            val curves = curveDraft
            val editingTool = cropDraft != null || curves != null || picking
            val sampled = sample?.takeIf { it.point == samplePoint }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorAction(if (editingTool) "Cancel" else "Recent edits",
                    if (editingTool) EditorSymbol.Close else EditorSymbol.Back, enabled = !exporting, action = ::goBack)
                if (editingTool) {
                    val heading = when {
                        cropDraft != null -> "Crop"
                        curves != null -> "Curves"
                        else -> "White balance"
                    }
                    Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (curves != null || picking) {
                        TextButton(onClick = {
                            if (curves == null) resetSample()
                            else curveDraft = curves.mapIndexed { index, curve ->
                                if (index == curveChannel) neutralCurve else curve
                            }
                        }) { Text("Reset") }
                    }
                    TextButton(enabled = !picking || sampled != null, onClick = {
                        when {
                            cropDraft != null -> {
                                if (cropDraft != edit.geometry) zoom = null
                                commit(edit.copy(geometry = cropDraft))
                                crop = null
                            }
                            curves != null -> {
                                commit(edit.copy(curves = curves))
                                curveDraft = null
                            }
                            sampled != null -> {
                                commit(edit.copy(balance = sampled.gains))
                                picking = false
                                resetSample()
                            }
                        }
                    }) { Text("Apply") }
                } else {
                    EditorAction("Undo", EditorSymbol.Undo, undo.isNotEmpty()) {
                        redo.add(edit)
                        edit = undo.removeAt(undo.lastIndex)
                    }
                    EditorAction("Redo", EditorSymbol.Redo, redo.isNotEmpty()) {
                        undo.add(edit)
                        edit = redo.removeAt(redo.lastIndex)
                    }
                    Spacer(Modifier.weight(1f))
                    EditorAction("Export JPEG", EditorSymbol.Export, active != null && !exporting) { sheet = Sheet.Export }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f).background(background)
                .onSizeChanged { viewport = it }.then(gestures), contentAlignment = Alignment.Center) {
                PreviewSurface(renderer)
                cropWindow?.let { CropGrid(it) }
                if (picking && photoSize != null) {
                    samplePoint?.let { point ->
                        val topLeft = viewport.center() - photoSize.center() * fit
                        SampleTarget(topLeft + Offset(point.x.toFloat(), point.y.toFloat()) * fit)
                    }
                }
                val status = photoError ?: renderError ?: if (opening || (active == null && original != null)) {
                    "Opening photo…"
                } else null
                if (status != null) Surface(color = panelColor, shape = RoundedCornerShape(12.dp)) {
                    Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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

            when {
                cropDraft != null -> CropControls(cropDraft, cropTool, { cropTool = it }, { crop = it }) { sheet = Sheet.Ratio }
                curves != null -> CurveControls(curves, curveChannel, { curveChannel = it }, { curveDraft = it })
                picking -> Column(Modifier.fillMaxWidth().background(panelColor).padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sample != null) "Sampled colour" else "Tap a neutral area in the photo",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        sample?.let {
                            Box(Modifier.size(52.dp, 30.dp).background(it.color, RoundedCornerShape(7.dp)))
                        }
                    }
                    if (sample != null) Text("Tap another area to resample", color = muted,
                        modifier = Modifier.padding(top = 12.dp))
                }
                active != null -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditorChip("Fit", zoom == null, Modifier.width(48.dp), plain = true, highlightOnly = true) { zoom = null }
                        EditorChip("100%", fullResolution, Modifier.width(55.dp), plain = true, highlightOnly = true) {
                            if (photoSize != null) zoom = Camera(1f, view?.center ?: photoSize.center())
                        }
                        Spacer(Modifier.weight(1f))
                        CompareControl(comparing) { comparing = it }
                    }
                    val activeTool = when (tool) {
                        Tool.Stock, Tool.Format, Tool.Medium -> panel.defaultTool
                        else -> tool
                    }
                    EditControls(panel, {
                        panel = it
                        tool = if (it == Panel.Adjust) group.tools.first() else it.defaultTool
                    }, group, activeTool, edit, ::selectTool,
                        onCrop = { cropTool = Tool.Straighten; crop = edit.geometry },
                        showSheet = {
                            if (it == Sheet.PrintSettings) printDraft = edit
                            sheet = it
                        },
                        change = { edit = it }, start = { startEdit = edit }, finish = ::finishEdit, commit = ::commit)
                }
            }
        }
    }

    when (sheet) {
        Sheet.Stock -> EditorSheet("Film stock", ::dismissSheet) {
            val descriptions = listOf("Colour negative", "Colour negative", "Colour negative", "Colour negative", "Tungsten negative")
            stockNames.forEachIndexed { index, name ->
                SheetChoice(name, edit.stockId == stockIds[index], descriptions[index]) {
                    commit(edit.copy(stockId = stockIds[index]))
                    dismissSheet()
                }
            }
        }
        Sheet.Format -> EditorSheet("Negative format", ::dismissSheet) {
            Text("Smaller negatives make grain more visible.", color = muted, modifier = Modifier.padding(bottom = 8.dp))
            formatNames.forEachIndexed { index, name ->
                val width = formatWidths[index].toString().removeSuffix(".0")
                SheetChoice(name, edit.format == index, "$width mm wide") {
                    commit(edit.copy(format = index))
                    dismissSheet()
                }
            }
        }
        Sheet.Groups -> EditorSheet("Adjust", ::dismissSheet) {
            ToolGroup.entries.forEach { choice ->
                SheetChoice(choice.name, group == choice) {
                    if (group != choice) {
                        group = choice
                        tool = choice.tools.first()
                    }
                    dismissSheet()
                }
            }
        }
        Sheet.Ratio -> EditorSheet("Aspect ratio", ::dismissSheet) {
            cropRatios.forEachIndexed { index, ratio ->
                SheetChoice(ratio, crop?.ratio == index) {
                    crop = crop?.copy(ratio = index)
                    dismissSheet()
                }
            }
        }
        Sheet.PrintSettings -> printDraft?.let { draft ->
            PrintSettingsSheet(draft, { printDraft = it }, ::dismissSheet) {
                commit(draft)
                dismissSheet()
            }
        }
        Sheet.Export -> EditorSheet("Export photo", ::dismissSheet, "Save copy", {
            dismissSheet()
            if (active != null) startExport(active)
        }) {
            Text("Save a JPEG copy to your device.", color = muted)
            Text("JPEG · Full resolution · sRGB", modifier = Modifier.padding(top = 12.dp))
        }
        Sheet.Saved -> EditorSheet("Photo saved", ::dismissSheet, "Done", ::dismissSheet) {
            Text("You can find the JPEG in your gallery.", color = muted)
        }
        null -> Unit
    }
}

private fun sampleBalance(bitmap: android.graphics.Bitmap, point: IntOffset): BalanceSample {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    fun srgb(linear: Double): Float = (if (linear <= 0.0031308) linear * 12.92 else
        1.055 * linear.pow(1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    val rgb = DoubleArray(3)
    for (pixel in pixels) {
        rgb[0] += linear(PixelColor.red(pixel)) / pixels.size
        rgb[1] += linear(PixelColor.green(pixel)) / pixels.size
        rgb[2] += linear(PixelColor.blue(pixel)) / pixels.size
    }
    val neutral = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
    require(neutral > 0.003) { "Choose a brighter neutral area" }
    val gains = rgb.map { (neutral / it.coerceAtLeast(0.001)).toFloat().coerceIn(0.35f, 3f) }
    return BalanceSample(point, Color(srgb(rgb[0]), srgb(rgb[1]), srgb(rgb[2])), gains)
}

private data class CropDrag(
    val draft: Geometry, val window: ScreenRect, val photo: Size, val viewportCenter: Offset, val corner: Offset?,
) {
    fun move(delta: Offset): Geometry {
        val origin = viewportCenter - Offset(photo.width, photo.height) / 2f
        if (corner == null) {
            val center = window.center + delta - origin
            return draft.copy(centerX = (center.x / photo.width).coerceIn(0f, 1f),
                centerY = (center.y / photo.height).coerceIn(0f, 1f))
        }

        val dx = delta.x * corner.x
        val dy = delta.y * corner.y
        val width: Float
        val height: Float
        if (draft.ratio == 0) {
            width = (window.width + dx).coerceIn(photo.width * 0.05f, photo.width)
            height = (window.height + dy).coerceIn(photo.height * 0.05f, photo.height)
        } else {
            val resize = (dx * window.width + dy * window.height) / (window.width * window.width + window.height * window.height)
            val maxScale = minOf(photo.width / window.width, photo.height / window.height)
            val scale = (1f + resize).coerceIn(0.1f, maxScale.coerceAtLeast(0.1f))
            width = window.width * scale
            height = window.height * scale
        }
        val center = window.center + Offset((width - window.width) * corner.x, (height - window.height) * corner.y) / 2f - origin
        return draft.copy(width = (width / photo.width).coerceIn(0.001f, 1f),
            height = (height / photo.height).coerceIn(0.001f, 1f),
            centerX = (center.x / photo.width).coerceIn(0f, 1f), centerY = (center.y / photo.height).coerceIn(0f, 1f))
    }
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
private fun CropGrid(window: ScreenRect) {
    Canvas(Modifier.fillMaxSize()) {
        val mask = background.copy(alpha = 0.67f)
        fun shade(left: Float, top: Float, width: Float, height: Float) {
            if (width > 0 && height > 0) drawRect(mask, Offset(left, top), Size(width, height))
        }
        shade(0f, 0f, size.width, window.top)
        shade(0f, window.bottom, size.width, size.height - window.bottom)
        shade(0f, window.top, window.left, window.height)
        shade(window.right, window.top, size.width - window.right, window.height)
        drawRect(ink, window.topLeft, window.size, style = Stroke(1.dp.toPx()))
        val line = ink.copy(alpha = 0.50f)
        for (index in 1..2) {
            val x = window.left + window.width * index / 3
            val y = window.top + window.height * index / 3
            drawLine(line, Offset(x, window.top), Offset(x, window.bottom), 1.dp.toPx())
            drawLine(line, Offset(window.left, y), Offset(window.right, y), 1.dp.toPx())
        }
        val handle = minOf(13.dp.toPx(), window.width / 3, window.height / 3)
        fun corner(point: Offset, horizontal: Float, vertical: Float) {
            drawLine(ink, point, point + Offset(horizontal * handle, 0f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(ink, point, point + Offset(0f, vertical * handle), 2.dp.toPx(), StrokeCap.Round)
        }
        corner(window.topLeft, 1f, 1f)
        corner(window.topRight, -1f, 1f)
        corner(window.bottomLeft, 1f, -1f)
        corner(window.bottomRight, -1f, -1f)
    }
}

@Composable
private fun SampleTarget(point: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(background.copy(alpha = 0.5f), 14.dp.toPx(), point)
        drawCircle(ink, 14.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
        val arm = 6.dp.toPx()
        drawLine(ink, point - Offset(arm, 0f), point + Offset(arm, 0f), 1.dp.toPx())
        drawLine(ink, point - Offset(0f, arm), point + Offset(0f, arm), 1.dp.toPx())
    }
}
