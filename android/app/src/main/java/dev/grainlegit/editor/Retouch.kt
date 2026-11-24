package dev.grainlegit.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One object removal, stored as a feathered RGBA patch over oriented source pixels. The patch is
 * saved rather than recomputed so exports never depend on the model, accelerator, or version.
 */
internal data class Retouch(
    val patch: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val model: String,
) {
    val bounds get() = Rect(left, top, left + width, top + height)

    fun encode(): JSONObject = JSONObject().put("patch", patch).put("left", left).put("top", top)
        .put("width", width).put("height", height).put("model", model)

    companion object {
        fun decode(o: JSONObject) = Retouch(
            o.getString("patch"), o.getInt("left"), o.getInt("top"), o.getInt("width"), o.getInt("height"),
            o.optString("model"),
        )

        fun decodeAll(array: JSONArray?): List<Retouch> =
            if (array == null) emptyList() else List(array.length()) { decode(array.getJSONObject(it)) }
    }
}

internal fun List<Retouch>.encode() = JSONArray().also { array -> forEach { array.put(it.encode()) } }

/** Content-addressed patch files in app storage, with a small decoded cache. */
internal object PatchStore {
    private lateinit var directory: File
    private val cache = object : LruCache<String, Bitmap>(160 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun init(context: Context) {
        if (!::directory.isInitialized) directory = File(context.filesDir, "retouch").apply { mkdirs() }
    }

    fun save(patch: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { out ->
            check(patch.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not encode the patch" }
            out.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val name = digest.joinToString("") { "%02x".format(it) }.take(32) + ".png"
        val file = File(directory, name)
        if (!file.exists()) {
            val temporary = File(directory, "$name.part")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(file)) { "Could not store the patch" }
        }
        cache.put(name, patch)
        return name
    }

    fun load(name: String): Bitmap = cache[name] ?: run {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(File(directory, name).absolutePath, options)) {
            "A removal patch is missing"
        }
        cache.put(name, bitmap)
        bitmap
    }

    /** Delete patches that no saved edit references. */
    fun retainOnly(names: Set<String>) {
        directory.listFiles()?.forEach { file -> if (file.name !in names) file.delete() }
    }
}

/**
 * Draw [retouches] over [target], which shows the oriented source rectangle [region] at any scale.
 * Returns a mutable bitmap (the same one when possible).
 */
internal fun applyRetouches(target: Bitmap, region: Rect, retouches: List<Retouch>): Bitmap {
    val touching = retouches.filter { Rect.intersects(it.bounds, region) }
    if (touching.isEmpty()) return target
    val result = if (target.isMutable) target else target.copy(Bitmap.Config.ARGB_8888, true).also { target.recycle() }
    val canvas = Canvas(result)
    val sx = result.width.toFloat() / region.width()
    val sy = result.height.toFloat() / region.height()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (retouch in touching) {
        val patch = PatchStore.load(retouch.patch)
        val destination = RectF(
            (retouch.left - region.left) * sx, (retouch.top - region.top) * sy,
            (retouch.left + retouch.width - region.left) * sx, (retouch.top + retouch.height - region.top) * sy,
        )
        canvas.drawBitmap(patch, null, destination, paint)
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// Selection: an 8-bit coverage mask over the output (post-geometry) image at a capped resolution.

/** The in-progress stroke stays a path so the committed bitmap can be reused by the GPU. */
internal class BrushStroke(private val start: Offset, private val radius: Float, add: Boolean) {
    private var last = start
    private var moved = false
    private val path = Path().apply { moveTo(start.x, start.y) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2 * radius
        if (!add) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    fun extend(point: Offset) {
        if (point == last) return
        path.lineTo(point.x, point.y)
        last = point
        moved = true
    }

    fun draw(canvas: Canvas, color: Int = android.graphics.Color.WHITE) {
        paint.color = color
        if (moved) {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(start.x, start.y, radius, paint)
        }
    }
}

internal class RemovalMask(val bitmap: Bitmap, val scale: Float, val bounds: Rect) : Closeable {
    override fun close() = bitmap.recycle()
}

internal class Selection(val output: androidx.compose.ui.unit.IntSize) {
    val scale = min(1f, MAX_EDGE.toFloat() / max(output.width, output.height))
    val width = max(1, (output.width * scale).roundToInt())
    val height = max(1, (output.height * scale).roundToInt())
    val mask: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(mask)
    private val history = ArrayDeque<Bitmap>()
    private val pixels by lazy { ByteBuffer.allocate(mask.byteCount) }

    fun checkpoint() {
        history.addLast(mask.copy(Bitmap.Config.ALPHA_8, true))
        while (history.size > 20) history.removeFirst().recycle()
    }

    val canUndo get() = history.isNotEmpty()

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        mask.eraseColor(0)
        canvas.drawBitmap(previous, 0f, 0f, null)
        previous.recycle()
    }

    fun clear() {
        mask.eraseColor(0)
    }

    /** Clear the selection and its history, after its contents have been removed. */
    fun reset() {
        clear()
        history.forEach { it.recycle() }
        history.clear()
    }

    fun commit(stroke: BrushStroke) {
        checkpoint()
        stroke.draw(canvas)
    }

    /** Union (or subtract) a segmentation (logits spanning the whole output image). */
    fun add(segmentation: Segmentation, subtract: Boolean = false) {
        val size = segmentation.size
        val alpha = ByteArray(size * size) { index ->
            (segmentation.probability(index) * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        val small = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        small.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        if (scaled !== small) small.recycle()
        // Threshold the smooth upscaled probability at 0.5, then merge with the existing selection.
        val incoming = ByteArray(scaled.byteCount)
        val incomingStride = scaled.rowBytes
        scaled.copyPixelsToBuffer(ByteBuffer.wrap(incoming))
        scaled.recycle()
        val current = readPixels()
        val stride = mask.rowBytes
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * stride + x
            val probability = incoming[y * incomingStride + x].toInt() and 0xFF
            // Keep a soft one-step edge around the 0.5 threshold for anti-aliased overlay drawing.
            val value = ((probability - 112) * 8).coerceIn(0, 255)
            val existing = current[index].toInt() and 0xFF
            current[index] = (if (subtract) minOf(existing, 255 - value) else maxOf(existing, value)).toByte()
        }
        pixels.rewind()
        mask.copyPixelsFromBuffer(pixels)
    }

    private fun readPixels(): ByteArray {
        pixels.rewind()
        mask.copyPixelsToBuffer(pixels)
        return pixels.array()
    }

    /** Bounds of selected pixels, in output pixels. */
    fun bounds(): Rect? {
        val pixels = readPixels()
        val stride = mask.rowBytes
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((pixels[y * stride + x].toInt() and 0xFF) >= 128) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return Rect(floor(left / scale).toInt(), floor(top / scale).toInt(),
            ceil((right + 1) / scale).toInt(), ceil((bottom + 1) / scale).toInt())
    }

    fun snapshot(bounds: Rect) = RemovalMask(mask.copy(Bitmap.Config.ALPHA_8, false), scale, bounds)

    fun recycle() {
        mask.recycle()
        history.forEach { it.recycle() }
        history.clear()
    }

    companion object {
        const val MAX_EDGE = 2048
    }
}

// ---------------------------------------------------------------------------------------------
// Removal: crop a context square around the selection, inpaint it, and build a feathered patch.

internal data class RemovalCandidate(val retouch: Retouch, val label: String, val ms: Double)

internal class Remover(
    private val photo: Photo,
    private val layout: GeometryLayout,
    private val resolver: android.content.ContentResolver,
) {
    /**
     * Inpaint the selected area with each model and return one patch per model. [existing] removals
     * are applied to the context first so successive removals build on each other.
     */
    fun remove(selection: RemovalMask, existing: List<Retouch>, models: List<Inpainter>): List<RemovalCandidate> {
        val sourceSelected = checkNotNull(layout.sourceRegion(selection.bounds)) { "The selection is outside the photo" }
        val target = models.maxOf { it.size }
        val context = contextSquare(sourceSelected, photo.size, target)

        // Source pixels for the context square, decoded at the nearest power-of-two reduction.
        var sample = 1
        while (context.width() / (sample * 2) >= target && context.height() / (sample * 2) >= target) sample *= 2
        val decoded = photo.openRegions(resolver).use { it.decode(context, sample) }
        val withRetouches = applyRetouches(decoded, context, existing)

        try {
            return models.map { model ->
                val size = model.size
                val crop = Bitmap.createScaledBitmap(withRetouches, size, size, true)
                val pixels = try {
                    IntArray(size * size).also { crop.getPixels(it, 0, size, 0, 0, size, size) }
                } finally {
                    if (crop !== withRetouches) crop.recycle()
                }
                val hole = rasterizeHole(selection, context, size)
                val inpainted = model.inpaint(pixels, hole)
                val retouch = buildPatch(inpainted, hole, context, size, model.id)
                RemovalCandidate(retouch, model.label, model.lastMs)
            }
        } finally {
            withRetouches.recycle()
        }
    }

    /** Selection → model-space hole mask, dilated so the fill covers the object's soft edge. */
    private fun rasterizeHole(selection: RemovalMask, context: Rect, size: Int): BooleanArray {
        val matrix = Matrix().apply {
            setScale(1f / selection.scale, 1f / selection.scale)          // mask px → output px
            postConcat(layout.outputToSource())                           // output px → source px
            postTranslate(-context.left.toFloat(), -context.top.toFloat())
            postScale(size.toFloat() / context.width(), size.toFloat() / context.height())
        }
        val coverage = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Canvas(coverage).drawBitmap(selection.bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val stride = coverage.rowBytes
        val alpha = ByteArray(coverage.byteCount)
        coverage.copyPixelsToBuffer(ByteBuffer.wrap(alpha))
        coverage.recycle()
        val hole = BooleanArray(size * size) { (alpha[it / size * stride + it % size].toInt() and 0xFF) >= 64 }
        return dilate(hole, size, max(3, (size * 0.012f).roundToInt()))
    }

    private fun buildPatch(inpainted: IntArray, hole: BooleanArray, context: Rect, size: Int, model: String): Retouch {
        val feather = max(2, (size * 0.01f).roundToInt())
        val alpha = featherMask(hole, size, feather)
        // Patch bounds in model pixels: every pixel the feathered mask touches.
        var left = size; var top = size; var right = -1; var bottom = -1
        for (y in 0 until size) for (x in 0 until size) if (alpha[y * size + x] > 0f) {
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }
        check(right >= 0) { "Nothing to remove" }
        val w = right - left + 1
        val h = bottom - top + 1
        val cropPixels = IntArray(w * h) { index ->
            val x = left + index % w
            val y = top + index / w
            val a = (alpha[y * size + x] * 255f).roundToInt().coerceIn(0, 255)
            (a shl 24) or (inpainted[y * size + x] and 0xFFFFFF)
        }
        val modelPatch = Bitmap.createBitmap(cropPixels, w, h, Bitmap.Config.ARGB_8888)

        // Map back to source pixels. Store at most twice the model resolution; drawing rescales.
        val sx = context.width().toFloat() / size
        val sy = context.height().toFloat() / size
        val sourceRect = Rect(
            context.left + floor(left * sx).toInt(), context.top + floor(top * sy).toInt(),
            context.left + ceil((right + 1) * sx).toInt(), context.top + ceil((bottom + 1) * sy).toInt(),
        )
        sourceRect.intersect(0, 0, photo.size.width, photo.size.height)
        val storeScale = min(1f, 2f * size / max(context.width(), context.height()))
        val patchWidth = max(1, (sourceRect.width() * storeScale).roundToInt())
        val patchHeight = max(1, (sourceRect.height() * storeScale).roundToInt())
        val patch = Bitmap.createScaledBitmap(modelPatch, patchWidth, patchHeight, true)
        if (patch !== modelPatch) modelPatch.recycle()
        val name = PatchStore.save(patch)
        return Retouch(name, sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height(), model)
    }
}

/** A square around [selected] with room for context, kept inside the photo where possible. */
internal fun contextSquare(selected: Rect, photo: androidx.compose.ui.unit.IntSize, modelSize: Int): Rect {
    val span = max(selected.width(), selected.height())
    var side = max((span * 2.2f).roundToInt(), min(modelSize, max(photo.width, photo.height)))
    side = min(side, max(photo.width, photo.height))
    fun place(center: Int, extent: Int): Pair<Int, Int> {
        val length = min(side, extent)
        val start = (center - length / 2).coerceIn(0, extent - length)
        return start to start + length
    }
    val (left, right) = place(selected.centerX(), photo.width)
    val (top, bottom) = place(selected.centerY(), photo.height)
    return Rect(left, top, right, bottom)
}

private fun dilate(mask: BooleanArray, size: Int, radius: Int): BooleanArray {
    // Separable square dilation with a circular-ish finish via two passes of half radius.
    fun pass(source: BooleanArray, r: Int, horizontal: Boolean): BooleanArray {
        val result = BooleanArray(source.size)
        for (line in 0 until size) {
            var last = -1_000_000
            // Forward and backward sweeps track the distance to the nearest set pixel on this line.
            val distance = IntArray(size) { Int.MAX_VALUE }
            for (i in 0 until size) {
                val index = if (horizontal) line * size + i else i * size + line
                if (source[index]) last = i
                distance[i] = i - last
            }
            last = 1_000_000
            for (i in size - 1 downTo 0) {
                val index = if (horizontal) line * size + i else i * size + line
                if (source[index]) last = i
                distance[i] = min(distance[i], last - i)
                result[index] = distance[i] <= r
            }
        }
        return result
    }
    return pass(pass(mask, radius, true), radius, false)
}

/** Distance-based feather: 1 inside the hole, falling to 0 over [radius] pixels outside it. */
private fun featherMask(hole: BooleanArray, size: Int, radius: Int): FloatArray {
    val far = (radius + 1).toFloat()
    val distance = FloatArray(size * size) { if (hole[it]) 0f else far }
    // Two-pass chamfer distance transform (3-4 weights scaled to pixels).
    val diagonal = sqrt(2f)
    for (y in 0 until size) for (x in 0 until size) {
        var d = distance[y * size + x]
        if (x > 0) d = min(d, distance[y * size + x - 1] + 1f)
        if (y > 0) {
            d = min(d, distance[(y - 1) * size + x] + 1f)
            if (x > 0) d = min(d, distance[(y - 1) * size + x - 1] + diagonal)
            if (x < size - 1) d = min(d, distance[(y - 1) * size + x + 1] + diagonal)
        }
        distance[y * size + x] = d
    }
    for (y in size - 1 downTo 0) for (x in size - 1 downTo 0) {
        var d = distance[y * size + x]
        if (x < size - 1) d = min(d, distance[y * size + x + 1] + 1f)
        if (y < size - 1) {
            d = min(d, distance[(y + 1) * size + x] + 1f)
            if (x < size - 1) d = min(d, distance[(y + 1) * size + x + 1] + diagonal)
            if (x > 0) d = min(d, distance[(y + 1) * size + x - 1] + diagonal)
        }
        distance[y * size + x] = d
    }
    return FloatArray(size * size) { index ->
        val t = 1f - distance[index] / far
        if (t <= 0f) 0f else t * t * (3f - 2f * t)
    }
}
