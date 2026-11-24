package dev.grainlegit.editor

import ai.onnxruntime.OrtException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal enum class EraseMode(val title: String) { Select("Select"), Brush("Brush"), Unbrush("Erase brush") }

/** One compiled copy per app process. Each GPU delegate stays on its own worker thread. */
internal class EraseEngine private constructor(context: Context) {
    private val selectionWorker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "Grainlegit selection ML") }.asCoroutineDispatcher()
    private val removalWorker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "Grainlegit removal ML") }.asCoroutineDispatcher()
    private val selectionMl = Ml(context)
    private val application = context
    private var segmenter: ImageSegmenter? = null
    private var imageKey: Any? = null
    private val removalMl = Ml(context)
    private val miGan by lazy { MiGan(removalMl) }
    private val inpainters by lazy {
        val primary = miGan
        buildList {
            if ("lama_512.tflite" in context.assets.list("models").orEmpty()) {
                try {
                    add(Lama(removalMl, "lama_512.tflite", 512))
                } catch (error: Exception) {
                    Log.w("Grainlegit", "LaMa unavailable", error)
                }
            }
            add(primary)
        }
    }

    suspend fun warmSelection() {
        withContext(selectionWorker) { selectionModel() }
    }

    suspend fun warmRemoval() {
        withContext(removalWorker) { miGan }
    }

    suspend fun prepareSelection(key: Any, image: () -> Bitmap) {
        withContext(selectionWorker) { prepareImage(key, image) }
    }

    suspend fun select(key: Any, prompt: Prompt, image: () -> Bitmap): Segmentation = withContext(selectionWorker) {
        prepareImage(key, image).segment(listOf(prompt))
    }

    private fun selectionModel(): ImageSegmenter = segmenter ?: run {
        val sam = if (Sam31.available(application)) {
            try {
                Sam31.open(application)
            } catch (error: Exception) {
                Log.w("Grainlegit", "SAM 3.1 unavailable, using EdgeTAM", error)
                null
            } catch (error: LinkageError) {
                Log.w("Grainlegit", "SAM 3.1 runtime unavailable, using EdgeTAM", error)
                null
            }
        } else null
        (sam ?: Segmenter(selectionMl)).also { segmenter = it }
    }

    private fun prepareImage(key: Any, image: () -> Bitmap): ImageSegmenter {
        var model = selectionModel()
        if (imageKey !== key) {
            val bitmap = image()
            try {
                imageKey = null
                try {
                    model.setImage(bitmap)
                } catch (error: OrtException) {
                    if (model !is Sam31) throw error
                    Log.w("Grainlegit", "SAM 3.1 encoding failed, using EdgeTAM", error)
                    segmenter = null
                    model.close()
                    model = Segmenter(selectionMl).also { segmenter = it }
                    model.setImage(bitmap)
                }
                imageKey = key
            } finally {
                bitmap.recycle()
            }
        }
        return model
    }

    suspend fun remove(remover: Remover, selection: RemovalMask, existing: List<Retouch>): List<RemovalCandidate> =
        withContext(removalWorker) { remover.remove(selection, existing, inpainters) }

    companion object {
        private var instance: EraseEngine? = null

        @Synchronized
        fun get(context: Context): EraseEngine =
            instance ?: EraseEngine(context.applicationContext).also { instance = it }
    }
}

@Stable
internal class EraseState(val output: IntSize, initial: List<Retouch>) {
    val selection = Selection(output)
    var draft by mutableStateOf(initial)
    var mode by mutableStateOf(EraseMode.Select)
    var brush by mutableStateOf(Tool.BrushSize.initial)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var candidates by mutableStateOf<List<RemovalCandidate>>(emptyList())
    var chosen by mutableIntStateOf(0)
    var operation: Job? = null
    private var selectionBounds: Rect? = null
    var hasSelection by mutableStateOf(false)
        private set
    var canUndoSelection by mutableStateOf(false)
        private set
    var liveStroke by mutableStateOf<BrushStroke?>(null)
        private set
    /** Read only in the draw phase. */
    var version by mutableIntStateOf(0)
        private set

    fun selectionChanged() {
        selectionBounds = selection.bounds()
        hasSelection = selectionBounds != null
        canUndoSelection = selection.canUndo
        version++
    }

    fun beginStroke(point: Offset, radius: Float) {
        message = null
        liveStroke = BrushStroke(point * selection.scale, radius * selection.scale, mode == EraseMode.Brush)
    }

    fun extendStroke(point: Offset) {
        liveStroke?.extend(point * selection.scale)
        version++
    }

    fun endStroke() {
        val stroke = liveStroke ?: return
        liveStroke = null
        selection.commit(stroke)
        selectionChanged()
    }

    fun cancelStroke() { liveStroke = null }

    fun choose(index: Int) {
        if (busy != null || liveStroke != null) return
        val candidate = candidates.getOrNull(index) ?: return
        draft = draft.dropLast(1) + candidate.retouch
        chosen = index
    }

    fun undo() {
        when {
            selection.canUndo -> { selection.undo(); selectionChanged() }
            draft.isNotEmpty() -> { draft = draft.dropLast(1); candidates = emptyList() }
        }
    }

    fun snapshot() = selectionBounds?.let(selection::snapshot)

    fun dispose() {
        operation?.cancel()
        selection.recycle()
    }
}

/** Map a screen point to output pixels for the current camera. */
internal fun Camera.toOutput(point: Offset, viewport: IntSize) =
    center + (point - Offset(viewport.width / 2f, viewport.height / 2f)) / scale

/**
 * Erase gestures: a tap selects an object, one finger paints in the
 * brush modes, and two fingers zoom and pan in every mode.
 */
internal suspend fun PointerInputScope.eraseGestures(
    state: () -> EraseState?,
    camera: () -> Camera?,
    viewport: () -> IntSize,
    tap: (Offset) -> Unit,
    transform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val erase = state() ?: return@awaitEachGesture
        if (erase.busy != null) return@awaitEachGesture
        val brushing = erase.mode != EraseMode.Select
        if (brushing) {
            val view = camera() ?: return@awaitEachGesture
            val radius = erase.brush.dp.toPx() / view.scale
            erase.beginStroke(view.toOutput(down.position, viewport()), radius)
        }
        var moved = false
        var transforming = false
        var last = down.position
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    if (!transforming) {
                        if (moved) erase.endStroke() else erase.cancelStroke()
                        transforming = true
                    }
                    transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (transforming || !change.positionChanged()) continue
                if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (brushing) {
                    val view = camera() ?: break
                    erase.extendStroke(view.toOutput(change.position, viewport()))
                    change.consume()
                } else if (moved) {
                    transform(change.position, change.position - last, 1f)
                    change.consume()
                }
                last = change.position
            }
            erase.endStroke()
            if (!brushing && !moved && !transforming) tap(down.position)
        } finally {
            erase.cancelStroke()
        }
    }
}

@Composable
internal fun EraseOverlay(state: EraseState, camera: Camera?, viewport: IntSize) {
    val view = camera ?: return
    val matrix = remember(state, view, viewport) {
        Matrix().apply {
            setScale(1f / state.selection.scale, 1f / state.selection.scale)
            postTranslate(-view.center.x, -view.center.y)
            postScale(view.scale, view.scale)
            postTranslate(viewport.width / 2f, viewport.height / 2f)
        }
    }
    val tinted = remember {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = accent.toArgb()
            alpha = 140
        }
    }
    val opaque = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { color = accent.toArgb() } }
    val layer = remember { Paint().apply { alpha = 140 } }
    val maskBounds = remember(state) {
        RectF(0f, 0f, state.selection.width.toFloat(), state.selection.height.toFloat())
    }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val version = state.version
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            val mask = state.selection.mask
            val stroke = state.liveStroke
            if (stroke == null) {
                canvas.drawBitmap(mask, matrix, tinted)
            } else {
                val saved = canvas.save()
                canvas.clipRect(0f, 0f, size.width, size.height)
                canvas.concat(matrix)
                canvas.clipRect(maskBounds)
                canvas.saveLayer(maskBounds, layer)
                canvas.drawBitmap(mask, null, maskBounds, opaque)
                stroke.draw(canvas, opaque.color)
                canvas.restoreToCount(saved)
            }
        }
    }
}

@Composable
internal fun EraseControls(state: EraseState, remove: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 16.dp)) {
        HorizontalDivider(color = dividerColor)
        if (state.mode == EraseMode.Select) {
            Column(Modifier.fillMaxWidth().height(77.dp).padding(top = 5.dp), verticalArrangement = Arrangement.Center) {
                Text(if (state.hasSelection) "Tap more objects to add them" else "Tap an object to select it",
                    color = ink, style = MaterialTheme.typography.bodyMedium)
                Text("Use Brush for shadows and anything the selection misses", color = muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            Ruler(Tool.BrushSize, state.brush, true, {}, { state.brush = it }, {})
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            EraseMode.entries.forEach { mode ->
                EditorChip(mode.title, selected = state.mode == mode) { state.mode = mode }
            }
            if (state.candidates.size > 1) {
                Spacer(Modifier.width(6.dp))
                state.candidates.forEachIndexed { index, candidate ->
                    EditorChip(candidate.label, selected = state.chosen == index) { state.choose(index) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = dividerColor)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val idle = state.busy == null && state.liveStroke == null
            EditorAction("Undo", EditorSymbol.Undo, idle &&
                (state.canUndoSelection || state.draft.isNotEmpty())) { state.undo() }
            TextButton(enabled = idle && state.hasSelection, onClick = {
                state.selection.checkpoint()
                state.selection.clear()
                state.selectionChanged()
            }) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = remove, enabled = idle && state.hasSelection,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
            ) { Text("Remove") }
        }
    }
}
