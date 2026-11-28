package dev.grainlegit.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import android.content.Context
import android.content.Intent
import android.graphics.Color as PixelColor
import android.graphics.Rect
import android.util.Log
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
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
    val photo: Photo, val layout: GeometryLayout, val geometry: Geometry, val retouches: List<Retouch>,
    val generation: Long,
) {
    val selectionImageKey = Any()
}

/** Run [block] with the Fit-sized source, with [retouches] applied to a temporary copy. */
private inline fun <T> withRetouches(input: ImportedPhoto, retouches: List<Retouch>, block: (android.graphics.Bitmap) -> T): T {
    if (retouches.isEmpty()) return block(input.bitmap)
    val base = applyRetouches(input.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true),
        input.photo.size.bounds(), retouches)
    try {
        return block(base)
    } finally {
        base.recycle()
    }
}

private fun selectionImage(input: ImportedPhoto, shown: ActivePhoto): android.graphics.Bitmap {
    // The model sees geometry and saved removals without film processing.
    val output = shown.layout.output
    val scale = (Segmenter.INPUT.toFloat() / max(output.width, output.height)).coerceAtMost(1f)
    val size = IntSize((output.width * scale).roundToInt().coerceAtLeast(1),
        (output.height * scale).roundToInt().coerceAtLeast(1))
    return withRetouches(input, shown.retouches) { base ->
        shown.layout.render(base, input.photo.size.bounds(), output.bounds(), size)
    }
}

private fun fitImage(
    input: ImportedPhoto, layout: GeometryLayout, retouches: List<Retouch>, longEdge: Int = FIT_LONG_EDGE,
): android.graphics.Bitmap {
    val output = layout.output
    val scale = (longEdge.toFloat() / max(output.width, output.height)).coerceAtMost(1f)
    val size = IntSize((output.width * scale).roundToInt().coerceAtLeast(1),
        (output.height * scale).roundToInt().coerceAtLeast(1))
    return withRetouches(input, retouches) { base ->
        layout.render(base, input.photo.size.bounds(), output.bounds(), size)
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
    var tool by rememberSaveable { mutableStateOf(Tool.Exposure) }
    var cropTool by remember { mutableStateOf(Tool.Straighten) }
    var crop by remember { mutableStateOf<Geometry?>(null) }
    var curveDraft by remember { mutableStateOf<List<List<Float>>?>(null) }
    var curveChannel by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var samplePoint by remember { mutableStateOf<IntOffset?>(null) }
    var sample by remember { mutableStateOf<BalanceSample?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var erase by remember { mutableStateOf<EraseState?>(null) }
    val eraseEngine = remember(context) { EraseEngine.get(context) }
    val osorAssets = remember(context) { if (OsorAssets.supported) OsorAssets(context.applicationContext) else null }
    var osorInstalled by remember(osorAssets) { mutableStateOf(osorAssets?.installed == true) }
    var osorLicenseOpen by remember { mutableStateOf(false) }
    var osorDownload by remember { mutableStateOf<Job?>(null) }
    var osorProgress by remember { mutableStateOf(0) }
    var osorDownloadError by remember { mutableStateOf<String?>(null) }
    remember(context) { PatchStore.init(context) }

    var zoom by remember { mutableStateOf<Camera?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    // Height of the controls floating over the bottom of the preview, kept even so the photo stays pixel aligned.
    var controlsInset by remember { mutableIntStateOf(0) }
    val photoBackdrop = rememberLayerBackdrop()
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

    fun downloadOsor() {
        val assets = osorAssets ?: return
        if (osorDownload != null || osorInstalled) return
        osorLicenseOpen = false
        osorDownloadError = null
        osorProgress = 0
        osorDownload = scope.launch {
            try {
                assets.download { completed -> withContext(Dispatchers.Main) { osorProgress = completed } }
                osorInstalled = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Grainlegit", "OSOR download failed", error)
                osorDownloadError = error.message ?: "Could not download OSOR"
            } finally {
                osorDownload = null
            }
        }
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
        tool = Tool.Exposure
        exportMessage = null
    }

    fun dismissSheet() {
        sheet = null
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
            erase != null -> erase = null
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

    // Remove removal patches that no saved edit uses any more.
    LaunchedEffect(Unit) {
        val keep = (recents.flatMap { it.edit.retouches } + edit.retouches).flatMap { it.files }.toSet()
        withContext(Dispatchers.IO) { PatchStore.retainOnly(keep) }
    }

    // Closing Erase drops its selection and queued requests. The compiled models are shared.
    DisposableEffect(erase) {
        val closing = erase
        onDispose { closing?.dispose() }
    }
    // OSOR stays loaded while Erase is open, so only the first removal waits for its graphs.
    val eraseOpen = erase != null
    LaunchedEffect(eraseOpen, osorInstalled) {
        val directory = osorAssets?.directory
        if (!eraseOpen || !osorInstalled || directory == null) return@LaunchedEffect
        try {
            eraseEngine.warmOsor(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Grainlegit", "Could not preload OSOR", error)
        }
    }
    DisposableEffect(eraseOpen) {
        onDispose { if (eraseOpen) eraseEngine.releaseOsor() }
    }
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
        erase = null
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

    val cropGeometry = crop
    val cropping = cropGeometry != null
    val sourceGeometry = if (cropping) Geometry() else edit.geometry
    val sourceRetouches = erase?.draft ?: edit.retouches
    LaunchedEffect(original, sourceGeometry, sourceRetouches) {
        val input = original ?: return@LaunchedEffect
        try {
            val (layout, bitmap) = withContext(decodeDispatcher) {
                val layout = GeometryLayout(input.photo.size, sourceGeometry)
                layout to fitImage(input, layout, sourceRetouches)
            }
            val generation = renderer.beginSource(clear = false)
            renderer.setSource(generation, layout.output, bitmap, seed)
            current = ActivePhoto(input.photo, layout, sourceGeometry, sourceRetouches, generation)
            photoError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            photoError = error.message ?: "Could not apply the geometry"
        }
    }

    val active = current

    // The edit Auto grain would produce: grain size sized for visibility at Fit, amount restored if off.
    val autoGrain = remember(edit.with(Tool.GrainSize, Tool.GrainSize.initial), active?.layout?.output) {
        val output = active?.layout?.output
        val base = if (edit[Tool.Grain] < 0.05f) edit.with(Tool.Grain, Tool.Grain.initial) else edit
        if (output == null) null else runCatching {
            base.with(Tool.GrainSize, NativeRenderer.autoGrainSize(base.nativeSettings(), output.width, output.height))
        }.getOrNull()
    }

    // Let the preview settle first. Select and Remove share these models with the warm-up.
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        delay(500)
        try {
            eraseEngine.warmSelection()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Grainlegit", "Could not warm object selection", error)
        }
        try {
            eraseEngine.warmRemoval()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Grainlegit", "Could not warm object removal", error)
        }
    }

    val selectionDraft = erase
    val selectionMode = selectionDraft?.mode
    val selectionImageKey = active?.selectionImageKey
    var preparingSelection by remember(selectionDraft, selectionMode, selectionImageKey) { mutableStateOf(false) }
    LaunchedEffect(selectionDraft, selectionMode, selectionImageKey) {
        val state = selectionDraft ?: return@LaunchedEffect
        val shown = active ?: return@LaunchedEffect
        val input = original ?: return@LaunchedEffect
        if (selectionMode != EraseMode.Select || shown.retouches != state.draft ||
            shown.layout.output != state.output || shown.photo != input.photo) return@LaunchedEffect
        preparingSelection = true
        try {
            eraseEngine.prepareSelection(shown.selectionImageKey) { selectionImage(input, shown) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Grainlegit", "Could not prepare the photo for selection", error)
        } finally {
            preparingSelection = false
        }
    }
    val eraseWork = selectionDraft?.busy ?: EraseWork.Preparing.takeIf { preparingSelection }
    val eraseWorking = eraseWork != null
    var showEraseWork by remember(eraseWorking) { mutableStateOf(false) }
    LaunchedEffect(eraseWorking) {
        if (eraseWorking) {
            delay(140)
            showEraseWork = true
        }
    }
    val shownEraseWork = eraseWork.takeIf { showEraseWork }

    val cropSourceSize = active?.takeIf { cropping && it.geometry == sourceGeometry }?.photo?.size
    // Changing the window leaves the cached image, camera, and presentation matrix alone.
    val uncropped = cropGeometry?.withoutCrop()
    val cropPreview = remember(cropSourceSize, uncropped) {
        if (cropSourceSize != null && uncropped != null) GeometryLayout(cropSourceSize, uncropped) else null
    }
    val warp = remember(cropPreview) { cropPreview?.previewToSource() }
    val photoSize = cropPreview?.output ?: active?.layout?.output
    val fitViewport = if (cropping) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val system = WindowInsets.systemGestures
        val gap = with(density) { 12.dp.roundToPx() }
        val minimum = with(density) { 24.dp.roundToPx() }
        val side = maxOf(minimum, maxOf(system.getLeft(density, direction), system.getRight(density, direction)) + gap)
            .coerceAtMost(viewport.width / 3)
        val end = maxOf(minimum, maxOf(toolbarHeight, system.getTop(density), system.getBottom(density)) + gap)
            .coerceAtMost(viewport.height / 3)
        IntSize(viewport.width - 2 * side, viewport.height - 2 * end)
    } else viewport
    val fit = if (photoSize != null) fitScale(photoSize, fitViewport) else 1f
    val zoomEnabled = !cropping && !picking && curveDraft == null && active?.geometry == edit.geometry
    val view = photoSize?.let { ((if (zoomEnabled) zoom else null) ?: Camera(fit, it.center())).constrain(it, viewport, fit) }
    val fullResolution = zoomEnabled && view?.scale == 1f && zoom != null
    val previewEdit = edit.copy(curves = curveDraft ?: edit.curves, balance = sample?.gains ?: edit.balance)
    val nativeEdit = remember(previewEdit) { previewEdit.nativeSettings() }
    SideEffect {
        val previewCamera = if (warp == null) view?.pixelAligned(viewport) else view
        // The preview surface runs under the controls, so its centre sits half their height below the viewport's.
        val center = previewCamera?.let { it.center + Offset(0f, controlsInset / 2f / it.scale) } ?: Offset.Zero
        renderer.request(Frame(active?.generation ?: 0L, center,
            previewCamera?.scale ?: 1f, nativeEdit, comparing, fullResolution, warp))
    }

    val visible = if (fullResolution && viewport.width > 0 && viewport.height > 0) {
        view?.pixelAligned(viewport)?.visibleRegion(photoSize, viewport)
    } else null
    val padding = remember(nativeEdit, photoSize, visible != null) {
        if (visible != null && photoSize != null) runCatching {
            NativeRenderer.padding(nativeEdit, photoSize.width, photoSize.height)
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
            val bitmap = withContext(decodeDispatcher) { active.photo.renderOutput(resolver, active.layout, outer, active.retouches) }
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
    val selection = remember(cropSourceSize, cropGeometry) {
        if (cropSourceSize != null && cropGeometry != null) GeometryLayout(cropSourceSize, cropGeometry) else null
    }
    val cropWindow = if (selection != null && cropPreview != null) {
        val topLeft = viewport.center() + (selection.cropOrigin - cropPreview.cropOrigin - cropPreview.output.center()) * fit
        ScreenRect(topLeft, Size(selection.output.width * fit, selection.output.height * fit))
    } else null
    val currentCropWindow by rememberUpdatedState(cropWindow)
    val currentCropSize by rememberUpdatedState(cropPreview?.output)
    val cropTouchArea by rememberUpdatedState(ScreenRect(
        viewport.center() - fitViewport.center(), Size(fitViewport.width.toFloat(), fitViewport.height.toFloat()),
    ))
    val currentFit by rememberUpdatedState(fit)
    val currentPhotoSize by rememberUpdatedState(photoSize)
    val currentErase by rememberUpdatedState(erase)
    fun transformView(centroid: Offset, pan: Offset, factor: Float) {
        val size = currentPhotoSize ?: return
        val old = currentView ?: return
        val screenCenter = currentViewport.center()
        val scale = (old.scale * factor).coerceIn(currentFit, 1f)
        val anchor = old.center + (centroid - screenCenter) / old.scale
        val center = anchor - (centroid + pan - screenCenter) / scale
        zoom = if (scale <= currentFit) null else Camera(scale, center).constrain(size, currentViewport, currentFit)
    }

    fun eraseTap(point: Offset) {
        val state = erase ?: return
        val camera = view ?: return
        val pixel = camera.toOutput(point, viewport)
        val output = state.output
        if (pixel.x !in 0f..output.width.toFloat() || pixel.y !in 0f..output.height.toFloat()) return
        if (state.busy != null || state.mode != EraseMode.Select) return
        val shown = active ?: return
        val input = original ?: return
        if (shown.retouches != state.draft || shown.layout.output != output || shown.photo != input.photo) return
        state.message = null
        state.busy = EraseWork.Finding
        state.operation = scope.launch {
            try {
                val start = System.nanoTime()
                val prompt = Prompt(pixel.x / output.width, pixel.y / output.height)
                val found = eraseEngine.select(shown.selectionImageKey, prompt) {
                    selectionImage(input, shown)
                }
                if (erase !== state) return@launch
                Log.i("Grainlegit", "Segmented in ${(System.nanoTime() - start) / 1_000_000} ms " +
                    "(object ${"%.2f".format(found.objectScore)}, score ${"%.2f".format(found.score)})")
                if (!found.found) state.message = "No object found there. Try Brush."
                else {
                    state.selection.checkpoint()
                    state.selection.add(found)
                    state.selectionChanged()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e("Grainlegit", "Segmentation failed", error)
                state.message = error.message ?: "Could not select the object"
            } finally {
                state.busy = null
            }
        }
    }

    fun eraseRemove() {
        val state = erase ?: return
        val shown = active ?: return
        if (shown.retouches != state.draft || state.busy != null || state.liveStroke != null) return
        val osorDirectory = osorAssets?.directory?.takeIf { osorInstalled }
        state.busy = EraseWork.Removing
        state.message = null
        state.operation = scope.launch {
            try {
                val existing = state.draft
                val remover = Remover(shown.photo, shown.layout, resolver) { bitmap, outer, inner, edge, seed, edit ->
                    renderer.exportTile(bitmap, outer, inner, IntSize(edge, edge), seed, edit)
                }
                val start = System.nanoTime()
                val selection = state.snapshot() ?: return@launch
                var quick: RemovalCandidate? = null
                val precise = try {
                    selection.use {
                        eraseEngine.remove(remover, it, existing, osorDirectory) { candidate ->
                            if (erase !== state) return@remove
                            Log.i("Grainlegit", "Instant fill in ${(System.nanoTime() - start) / 1_000_000} ms " +
                                "(${candidate.label} ${"%.0f".format(candidate.ms)} ms)")
                            quick = candidate
                            state.draft = existing + candidate.retouch
                            state.busy = EraseWork.Refining
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (quick == null) throw error
                    Log.e("Grainlegit", "Precise removal failed, keeping the instant fill", error)
                    state.message = "Kept the quick fill: ${error.message ?: "precise removal failed"}"
                    null
                }
                if (erase !== state) return@launch
                if (precise != null) {
                    Log.i("Grainlegit", "Precise fill in ${(System.nanoTime() - start) / 1_000_000} ms " +
                        "(${precise.label} ${"%.0f".format(precise.ms)} ms)")
                    state.draft = existing + precise.retouch
                }
                state.selection.reset()
                state.selectionChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e("Grainlegit", "Removal failed", error)
                state.message = error.message ?: "Could not remove the selection"
            } finally {
                state.busy = null
            }
        }
    }
    val latestEraseTap by rememberUpdatedState<(Offset) -> Unit> { eraseTap(it) }

    val gestures = when {
        erase != null -> Modifier.pointerInput(Unit) {
            eraseGestures({ currentErase }, { currentView }, { currentViewport }, { latestEraseTap(it) }, ::transformView)
        }
        cropping -> Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val point = down.position
                if (!cropTouchArea.contains(point)) return@awaitEachGesture
                val photo = currentCropSize ?: return@awaitEachGesture
                val window = currentCropWindow ?: return@awaitEachGesture
                val draft = currentCrop ?: return@awaitEachGesture
                val scale = currentView?.scale ?: return@awaitEachGesture
                val bounds = Size(photo.width * scale, photo.height * scale)
                val handleRadius = 20.dp.toPx()
                val nearX = minOf(abs(point.x - window.left), abs(point.x - window.right)) <= handleRadius
                val nearY = minOf(abs(point.y - window.top), abs(point.y - window.bottom)) <= handleRadius
                val corner = if (nearX && nearY) Offset(if (point.x < window.center.x) -1f else 1f,
                    if (point.y < window.center.y) -1f else 1f) else null
                if (corner == null && !window.contains(point)) return@awaitEachGesture
                val cropDrag = CropDrag(draft, window, bounds, currentViewport.center(), corner)
                var movement = Offset.Zero
                val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                    movement = overSlop
                    change.consume()
                } ?: return@awaitEachGesture
                crop = cropDrag.move(movement)
                drag(start.id) { event ->
                    movement += event.position - event.previousPosition
                    crop = cropDrag.move(movement)
                    event.consume()
                }
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
                val bitmap = active.photo.renderOutput(resolver, active.layout, region, active.retouches)
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
        val input = original ?: return
        exportCancel.set(false)
        exportMessage = null
        progress = 0f
        val snapshot = edit
        val exportEdit = snapshot.nativeSettings()
        val prepareLens = snapshot[Tool.LensGlow] > 0f ||
            (snapshot[Tool.LensAperture] < 2.8f && snapshot[Tool.LensGhosts] > 0f)
        val exportSeed = seed
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val layout = GeometryLayout(photo.photo.size, snapshot.geometry)
                if (prepareLens) {
                    withContext(decodeDispatcher) {
                        renderer.prepareExportLens(fitImage(input, layout, snapshot.retouches), exportEdit)
                    }
                }
                savePhoto(context, renderer, photo.photo, layout, exportSeed, exportEdit, snapshot.retouches,
                    exportCancel) {
                    progress = it
                }
                exportMessage = "JPEG saved to your gallery"
            } catch (cancelled: CancellationException) {
                exportMessage = "Export cancelled"
                throw cancelled
            } catch (error: Exception) {
                exportMessage = error.message ?: "Could not export the photo"
            } finally {
                if (prepareLens) renderer.clearExportLens()
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
            val erasing = erase
            val editingTool = cropDraft != null || curves != null || picking || erasing != null
            val sampled = sample?.takeIf { it.point == samplePoint }
            Box(Modifier.fillMaxWidth().weight(1f).background(background)) {
                Box(Modifier.fillMaxSize().layerBackdrop(photoBackdrop)) { PreviewSurface(renderer) }
                CompositionLocalProvider(LocalGlassBackdrop provides photoBackdrop) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f)
                            .onSizeChanged { viewport = it }.then(gestures), contentAlignment = Alignment.Center) {
                            if (erasing != null) EraseOverlay(erasing, view, viewport, shownEraseWork)
                            if (cropWindow != null && cropPreview != null) {
                                val photoArea = ScreenRect(viewport.center() - cropPreview.output.center() * fit,
                                    Size(cropPreview.output.width * fit, cropPreview.output.height * fit))
                                CropGrid(cropWindow, photoArea)
                            }
                            if (picking && photoSize != null) {
                                samplePoint?.let { point ->
                                    val topLeft = viewport.center() - photoSize.center() * fit
                                    SampleTarget(topLeft + Offset(point.x.toFloat(), point.y.toFloat()) * fit)
                                }
                            }
                            val status = photoError ?: renderError ?: shownEraseWork?.title ?: erasing?.message
                                ?: "Opening photo…".takeIf { opening || (active == null && original != null) }
                            if (status != null) Surface(
                                modifier = if (shownEraseWork != null) {
                                    Modifier.align(Alignment.TopCenter).padding(top = with(LocalDensity.current) { toolbarHeight.toDp() } + 12.dp)
                                } else Modifier,
                                color = panelColor, shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { toolbarHeight = it.height }) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    EditorAction(if (editingTool) "Cancel" else "Recent edits",
                                        if (editingTool) EditorSymbol.Close else EditorSymbol.Back, enabled = !exporting, filled = true,
                                        action = ::goBack)
                                    if (editingTool) {
                                        if (erasing != null) {
                                            EditorAction("Undo", EditorSymbol.Undo,
                                                erasing.busy == null && erasing.liveStroke == null &&
                                                    (erasing.canUndoSelection || erasing.draft.isNotEmpty()), filled = true,
                                                action = erasing::undo)
                                        }
                                        val heading = when {
                                            cropDraft != null -> "Crop"
                                            erasing != null -> "Erase"
                                            curves != null -> "Curves"
                                            else -> "White balance"
                                        }
                                        Box(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                                            Text(heading, Modifier.background(controlColor, CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    } else {
                                        EditorAction("Undo", EditorSymbol.Undo, undo.isNotEmpty(), filled = true) {
                                            redo.add(edit)
                                            edit = undo.removeAt(undo.lastIndex)
                                        }
                                        EditorAction("Redo", EditorSymbol.Redo, redo.isNotEmpty(), filled = true) {
                                            undo.add(edit)
                                            edit = redo.removeAt(redo.lastIndex)
                                        }
                                        Spacer(Modifier.weight(1f))
                                    }
                                    if (active != null && (!editingTool || erasing != null)) {
                                        CompareControl(comparing) { comparing = it }
                                    }
                                    when {
                                        exporting -> Button(onClick = { exportCancel.set(true); exportJob?.cancel() }) {
                                            Text("Cancel export")
                                        }
                                        editingTool -> Button(
                                            enabled = (!picking || sampled != null) && erasing?.busy == null, onClick = {
                                                when {
                                                    erasing != null -> {
                                                        commit(edit.copy(retouches = erasing.draft))
                                                        erase = null
                                                    }
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
                                            },
                                        ) { Text("Apply") }
                                        else -> Button(enabled = active != null, onClick = { active?.let(::startExport) }) { Text("Save copy") }
                                    }
                                }

                                if (exporting) {
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                } else if (exportMessage != null) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                            .background(panelColor, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(exportMessage.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { exportMessage = null }) { Text("Dismiss") }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.fillMaxWidth().layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, controlsInset))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        })
                    }

                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { controlsInset = it.height + it.height % 2 }) {
                        when {
                            erasing != null -> {
                                EraseControls(erasing, modelControls = {
                                    if (osorAssets != null && !osorInstalled) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            when {
                                                osorDownload != null -> {
                                                    Text("OSOR $osorProgress%", color = muted, style = MaterialTheme.typography.labelMedium)
                                                    LinearProgressIndicator(progress = { osorProgress / 100f }, modifier = Modifier.weight(1f))
                                                    TextButton(onClick = { osorDownload?.cancel() }) { Text("Cancel") }
                                                }
                                                else -> TextButton(onClick = { osorLicenseOpen = true }) {
                                                    Text(if (osorDownloadError == null) "Download OSOR · 4.3 GiB" else "Retry OSOR download")
                                                }
                                            }
                                        }
                                        osorDownloadError?.let { error ->
                                            Text(error, color = muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }) { eraseRemove() }
                            }
                            cropDraft != null -> CropControls(cropDraft, cropTool, { cropTool = it }, { crop = it }) { sheet = Sheet.Ratio }
                            curves != null -> CurveControls(curves, curveChannel, { curveChannel = it }, { curveDraft = it }) {
                                curveDraft = curves.mapIndexed { index, curve -> if (index == curveChannel) neutralCurve else curve }
                            }
                            picking -> Row(
                                Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (sampled != null) "Tap another area to resample" else "Tap a neutral area in the photo",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (sampled != null) {
                                    Box(Modifier.size(36.dp, 24.dp).background(sampled.color, RoundedCornerShape(6.dp)))
                                    TextButton(onClick = ::resetSample) { Text("Reset") }
                                }
                            }
                            active != null -> {
                                val activeTool = when (tool) {
                                    Tool.Stock, Tool.Format, Tool.Medium -> panel.defaultTool
                                    Tool.StockStrength -> if (edit.stock.id == "generic") panel.defaultTool else tool
                                    else -> tool
                                }
                                EditControls(panel, {
                                    panel = it
                                    tool = if (it == Panel.Negative && edit.stock.id != "generic") Tool.StockStrength else it.defaultTool
                                }, activeTool, edit, ::selectTool,
                                    onCrop = { cropTool = Tool.Straighten; crop = edit.geometry },
                                    onErase = { erase = EraseState(active.layout.output, edit.retouches) },
                                    showSheet = { sheet = it },
                                    change = { edit = it }, start = { startEdit = edit }, finish = ::finishEdit, commit = ::commit,
                                    autoGrain = autoGrain)
                            }
                        }
                    }
                }
            }
        }
    }

    if (osorLicenseOpen && osorAssets != null) {
        val license = remember(osorAssets) { osorAssets.license() }
        AlertDialog(
            onDismissRequest = { osorLicenseOpen = false },
            title = { Text("Download OSOR") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("OSOR needs about 4.3 GiB of storage. By downloading it, you agree to the model license, including the use restrictions in paragraph 5 and Attachment A.",
                        modifier = Modifier.padding(bottom = 12.dp))
                    Text(license, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = ::downloadOsor) { Text("Agree & download") } },
            dismissButton = { TextButton(onClick = { osorLicenseOpen = false }) { Text("Cancel") } },
        )
    }

    when (sheet) {
        Sheet.Stock -> {
            val input = original
            if (input != null && active != null) {
                FilmStockSheet(edit, active.generation, renderer, seed, loadSource = {
                    withContext(decodeDispatcher) { fitImage(input, active.layout, active.retouches, longEdge = 768) }
                }, dismiss = ::dismissSheet) { stock ->
                    commit(edit.withStock(stock.id))
                    tool = Tool.StockStrength
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
        Sheet.Ratio -> EditorSheet("Aspect ratio", ::dismissSheet) {
            cropRatios.forEachIndexed { index, ratio ->
                SheetChoice(ratio, crop?.ratio == index) {
                    crop = crop?.copy(ratio = index)
                    dismissSheet()
                }
            }
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
    // A TextureView, unlike a SurfaceView, draws inside the view hierarchy, so the glass controls can refract it.
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    val created = Surface(texture)
                    surface = created
                    renderer.surfaceCreated(created)
                    renderer.surfaceChanged(width, height)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
                    renderer.surfaceChanged(width, height)
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    renderer.surfaceDestroyed()
                    surface?.release()
                    surface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    })
}

@Composable
private fun CropGrid(window: ScreenRect, photoArea: ScreenRect) {
    Canvas(Modifier.fillMaxSize()) {
        val mask = background.copy(alpha = 0.67f)
        fun shade(left: Float, top: Float, width: Float, height: Float) {
            if (width > 0 && height > 0) drawRect(mask, Offset(left, top), Size(width, height))
        }
        shade(photoArea.left, photoArea.top, photoArea.width, window.top - photoArea.top)
        shade(photoArea.left, window.bottom, photoArea.width, photoArea.bottom - window.bottom)
        shade(photoArea.left, window.top, window.left - photoArea.left, window.height)
        shade(window.right, window.top, photoArea.right - window.right, window.height)
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
