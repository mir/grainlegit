package dev.grainlegit.editor

import ai.onnxruntime.OrtException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

internal enum class EraseMode(val title: String) { Select("Tap to select"), Brush("Brush"), Unbrush("Erase brush") }

internal enum class EraseWork(val title: String) {
    Preparing("Preparing selection…"), Finding("Finding object…"), Removing("Removing…"), Refining("Refining…"),
}

/** One compiled copy per app process. Each GPU delegate stays on its own worker thread. */
internal class EraseEngine private constructor(context: Context) {
    private val selectionWorker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "Grainlegit selection ML") }.asCoroutineDispatcher()
    private val removalWorker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "Grainlegit removal ML") }.asCoroutineDispatcher()
    // OSOR has its own thread so loading its graphs never holds up the instant MI-GAN fill.
    private val osorWorker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "Grainlegit OSOR") }.asCoroutineDispatcher()
    /** Kept loaded while Erase is open: reopening its graphs costs about 4 s per removal. */
    private var osor: Osor? = null
    private val selectionMl = Ml(context)
    private val application = context
    private var segmenter: ImageSegmenter? = null
    private var imageKey: Any? = null
    private val removalMl = Ml(context)
    private val miGan by lazy { MiGan(removalMl) }
    private val lama: Inpainter? by lazy {
        if ("lama_512.tflite" !in context.assets.list("models").orEmpty()) null
        else try {
            // LaMa's DFT matmuls were verified on the GPU; NPU builds of it return noise.
            Lama(removalMl, "lama_512.tflite", MODEL_SIDE, listOf(Backend.GPU, Backend.CPU))
        } catch (error: Exception) {
            Log.w("Grainlegit", "LaMa unavailable", error)
            null
        }
    }

    suspend fun warmSelection() {
        withContext(selectionWorker) { selectionModel() }
    }

    /**
     * Load MI-GAN for the instant fill. The precise model loads on first use, after the instant
     * fill is on screen: preparing LaMa for the NPU can take a minute on a fresh install.
     */
    suspend fun warmRemoval() {
        withContext(removalWorker) { miGan }
    }

    /** Load OSOR's graphs ahead of the first removal. Runs on the OSOR thread. */
    suspend fun warmOsor(directory: File) = withContext(osorWorker) {
        val model = osorFor(directory)
        model.checkActive = { coroutineContext.ensureActive() }
        try {
            model.preload()
        } finally {
            model.checkActive = {}
        }
    }

    /** Free OSOR's graphs (about 4.3 GiB) when Erase closes or the system is short of memory. */
    fun releaseOsor() {
        CoroutineScope(osorWorker).launch {
            osor?.close()
            osor = null
        }
    }

    private fun osorFor(directory: File): Osor =
        osor?.takeIf { it.directory == directory } ?: run {
            osor?.close()
            Osor(application, directory).also { osor = it }
        }

    suspend fun prepareSelection(key: Any, image: () -> Bitmap) {
        withContext(selectionWorker) { prepareImage(key, image) }
    }

    suspend fun select(key: Any, prompt: Prompt, image: () -> Bitmap): Segmentation = withContext(selectionWorker) {
        prepareImage(key, image).segment(listOf(prompt))
    }

    private fun selectionModel(): ImageSegmenter = segmenter ?: run {
        val unavailable = Sam31.unavailableReason(application)
        val sam = if (unavailable == null) {
            try {
                Sam31.open(application)
            } catch (error: Exception) {
                Log.w("Grainlegit", "SAM 3.1 unavailable, using EdgeTAM", error)
                null
            } catch (error: LinkageError) {
                Log.w("Grainlegit", "SAM 3.1 runtime unavailable, using EdgeTAM", error)
                null
            }
        } else {
            Log.i("Grainlegit", "SAM 3.1 unavailable ($unavailable), using EdgeTAM")
            null
        }
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

    /**
     * Fill the selection with MI-GAN and hand that to [instant] at once, then return the precise fill:
     * OSOR when it is installed, otherwise LaMa. Returns null when no precise model is available.
     */
    suspend fun remove(
        remover: Remover, selection: RemovalMask, existing: List<Retouch>, osorDirectory: File?,
        instant: (RemovalCandidate) -> Unit,
    ): RemovalCandidate? = withContext(removalWorker) {
        remover.prepare(selection, existing).use { prepared ->
            val quick = prepared.inpaint(miGan)
            withContext(Dispatchers.Main.immediate) { instant(quick) }
            ensureActive()
            if (osorDirectory != null) {
                withContext(osorWorker) {
                    val model = osorFor(osorDirectory)
                    model.checkActive = { coroutineContext.ensureActive() }
                    try {
                        prepared.inpaint(model, refine = true)
                    } finally {
                        model.checkActive = {}
                    }
                }
            } else {
                lama?.let { prepared.inpaint(it, refine = true) }
            }
        }
    }

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
    var busy by mutableStateOf<EraseWork?>(null)
    var message by mutableStateOf<String?>(null)
    var operation: Job? = null
    var selectionBounds: Rect? = null
        private set
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

    /** The last removal when it can show detail or grain separately, for comparing the stages. */
    val stages get() = draft.lastOrNull()?.takeIf { it.detail != null || it.grain != null }

    fun toggleStage(detail: Boolean) {
        val last = stages ?: return
        if (busy != null || liveStroke != null) return
        draft = draft.dropLast(1) + if (detail) last.copy(useDetail = !last.useDetail) else last.copy(useGrain = !last.useGrain)
    }

    fun undo() {
        when {
            selection.canUndo -> { selection.undo(); selectionChanged() }
            draft.isNotEmpty() -> draft = draft.dropLast(1)
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
internal fun EraseOverlay(state: EraseState, camera: Camera?, viewport: IntSize, work: EraseWork?) {
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
    val removing = work == EraseWork.Removing || work == EraseWork.Refining
    val refining = work == EraseWork.Refining
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val version = state.version
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            val mask = state.selection.mask
            val stroke = state.liveStroke
            if (refining) {
                // The quick fill is already drawn; leave it visible under the waves.
            } else if (stroke == null) {
                tinted.alpha = if (removing) 56 else 140
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
    if (work != null) EraseWaves(state, view, viewport, matrix, removing)
}

private const val ERASE_WAVE_SHADER = """
    uniform float phase;
    uniform float spacing;

    half4 main(float2 pixel) {
        float2 p = pixel / spacing;
        float wave = p.y + 0.25 * p.x + 0.19 * sin(2.4 * p.x);
        float crest = 0.5 + 0.5 * cos(6.283185 * (wave - phase));
        half alpha = half(0.13 * crest * crest * crest);
        return half4(alpha, alpha, alpha, alpha);
    }
"""

@Composable
private fun EraseWaves(state: EraseState, view: Camera, viewport: IntSize, matrix: Matrix, masked: Boolean) {
    val phase by rememberInfiniteTransition(label = "Erase waves").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1550, easing = LinearEasing)),
        label = "Wave phase",
    )
    val shader = remember { RuntimeShader(ERASE_WAVE_SHADER) }
    val wavePaint = remember(shader) { Paint().apply { this.shader = shader } }
    val maskPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN } }
    val bounds = remember { RectF() }
    val left = viewport.width / 2f - view.center.x * view.scale
    val top = viewport.height / 2f - view.center.y * view.scale

    Canvas(Modifier.fillMaxSize()) {
        if (masked) {
            // Read the committed mask and its bounds in the draw phase, as in the brush overlay.
            @Suppress("UNUSED_VARIABLE") val version = state.version
            val selection = state.selectionBounds ?: return@Canvas
            bounds.set(left + selection.left * view.scale, top + selection.top * view.scale,
                left + selection.right * view.scale, top + selection.bottom * view.scale)
        } else {
            bounds.set(left, top, left + state.output.width * view.scale, top + state.output.height * view.scale)
        }
        if (!bounds.intersect(0f, 0f, size.width, size.height)) return@Canvas
        shader.setFloatUniform("spacing", 116.dp.toPx())
        shader.setFloatUniform("phase", phase)
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            if (masked) {
                val layer = canvas.saveLayer(bounds, null)
                canvas.drawRect(bounds, wavePaint)
                canvas.drawBitmap(state.selection.mask, matrix, maskPaint)
                canvas.restoreToCount(layer)
            } else {
                canvas.drawRect(bounds, wavePaint)
            }
        }
    }
}

@Composable
internal fun EraseControls(state: EraseState, modelControls: @Composable () -> Unit, remove: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 16.dp)) {
        HorizontalDivider(color = dividerColor)
        if (state.mode != EraseMode.Select) Ruler(Tool.BrushSize, state.brush, true, {}, { state.brush = it }, {})
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            EraseMode.entries.forEach { mode ->
                EditorChip(mode.title, selected = state.mode == mode) { state.mode = mode }
            }
            // Temporary controls for comparing the refinement stages on the last removal.
            state.stages?.let { last ->
                Spacer(Modifier.width(6.dp))
                if (last.detail != null) EditorChip("Detail", selected = last.useDetail) { state.toggleStage(detail = true) }
                if (last.grain != null) EditorChip("Grain", selected = last.useGrain) { state.toggleStage(detail = false) }
            }
        }
        modelControls()
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
