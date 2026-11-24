package dev.grainlegit.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

internal val cropRatios = listOf("Free", "Original", "1:1", "4:5", "3:2", "4:3", "16:9")
private val ratios = listOf(0f, 0f, 1f, 0.8f, 1.5f, 4f / 3f, 16f / 9f)

internal data class Geometry(
    val turns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
    val ratio: Int = 0,
    val constrain: Boolean = true,
    val width: Float = 1f,
    val height: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    fun withoutCrop() = copy(ratio = 0, constrain = false, width = 1f, height = 1f, centerX = 0.5f, centerY = 0.5f)

    fun encode(): JSONObject = JSONObject().put("turns", turns).put("flipH", flipH).put("flipV", flipV)
        .put("straighten", straighten.toDouble()).put("horizontal", horizontal.toDouble())
        .put("vertical", vertical.toDouble()).put("ratio", ratio).put("constrain", constrain)
        .put("width", width.toDouble()).put("height", height.toDouble())
        .put("x", centerX.toDouble()).put("y", centerY.toDouble())

    companion object {
        fun decode(o: JSONObject) = Geometry(
            o.getInt("turns"), o.getBoolean("flipH"), o.getBoolean("flipV"),
            o.getDouble("straighten").toFloat(), o.getDouble("horizontal").toFloat(),
            o.getDouble("vertical").toFloat(), o.getInt("ratio"), o.getBoolean("constrain"),
            o.getDouble("width").toFloat(), o.getDouble("height").toFloat(),
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
        )
    }
}

internal class GeometryLayout(val sourceSize: IntSize, geometry: Geometry) {
    private val source = RectF(0f, 0f, sourceSize.width.toFloat(), sourceSize.height.toFloat())
    private val transform = Matrix()
    private val inverse = Matrix()
    val output: IntSize
    val cropOrigin: Offset

    init {
        val oriented = Matrix().apply {
            setRotate((geometry.turns * 90).toFloat())
            postScale(if (geometry.flipH) -1f else 1f, if (geometry.flipV) -1f else 1f)
        }
        val orientedBounds = RectF(source)
        oriented.mapRect(orientedBounds)
        oriented.postTranslate(-orientedBounds.left, -orientedBounds.top)
        val w = orientedBounds.width()
        val h = orientedBounds.height()
        val original = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val target = FloatArray(8)
        for (index in 0..3) {
            val x = original[index * 2] - w / 2f
            val y = original[index * 2 + 1] - h / 2f
            val scale = 1f + (x / w * geometry.horizontal + y / h * geometry.vertical) / 90f
            target[index * 2] = x / scale + w / 2f
            target[index * 2 + 1] = y / scale + h / 2f
        }
        val perspective = Matrix()
        check(perspective.setPolyToPoly(original, 0, target, 0, 4))
        transform.set(oriented)
        transform.postConcat(perspective)
        transform.postRotate(geometry.straighten, w / 2f, h / 2f)
        check(transform.invert(inverse))
        val bounds = RectF(source)
        transform.mapRect(bounds)
        val ratio = if (geometry.ratio == 1) w / h else ratios[geometry.ratio]
        var spanW = bounds.width() * geometry.width
        var spanH = bounds.height() * geometry.height
        if (ratio > 0f) {
            spanW = min(spanW, spanH * ratio)
            spanH = spanW / ratio
        }
        var cx = (bounds.left + bounds.width() * geometry.centerX)
            .coerceIn(bounds.left + spanW / 2, bounds.right - spanW / 2 + 0.001f)
        var cy = (bounds.top + bounds.height() * geometry.centerY)
            .coerceIn(bounds.top + spanH / 2, bounds.bottom - spanH / 2 + 0.001f)
        var shrunk = false
        if (geometry.constrain) {
            if (!contains(cx, cy, 0f, 0f)) {
                val center = floatArrayOf(source.centerX(), source.centerY())
                transform.mapPoints(center)
                cx = center[0]
                cy = center[1]
            }
            if (!contains(cx, cy, spanW, spanH)) {
                shrunk = true
                var lower = 0f
                var upper = 1f
                repeat(18) {
                    val trial = (lower + upper) / 2f
                    if (contains(cx, cy, spanW * trial, spanH * trial)) lower = trial else upper = trial
                }
                spanW *= lower
                spanH *= lower
            }
        }
        val width = (if (shrunk) floor(spanW).toInt() else spanW.roundToInt()).coerceAtLeast(1)
        val height = (if (shrunk) floor(spanH).toInt() else spanH.roundToInt()).coerceAtLeast(1)
        output = IntSize(width, height)
        val left = cx - width / 2f
        val top = cy - height / 2f
        cropOrigin = Offset(left, top)
        transform.postTranslate(-left, -top)
        check(transform.invert(inverse))
    }

    fun previewToSource() = FloatArray(9).also(inverse::getValues)

    fun outputToSource() = Matrix(inverse)

    fun sourceToOutput() = Matrix(transform)

    private fun contains(x: Float, y: Float, width: Float, height: Float): Boolean {
        val points = floatArrayOf(
            x - width / 2, y - height / 2, x + width / 2, y - height / 2,
            x + width / 2, y + height / 2, x - width / 2, y + height / 2,
        )
        inverse.mapPoints(points)
        return points.indices.step(2).all { index ->
            points[index] in -0.001f..(source.right + 0.001f) &&
                points[index + 1] in -0.001f..(source.bottom + 0.001f)
        }
    }

    fun sourceRegion(out: Rect): Rect? {
        val bounds = RectF(out)
        inverse.mapRect(bounds)
        val region = Rect(
            floor(bounds.left).toInt() - 2, floor(bounds.top).toInt() - 2,
            ceil(bounds.right).toInt() + 2, ceil(bounds.bottom).toInt() + 2,
        )
        return region.takeIf { it.intersect(0, 0, sourceSize.width, sourceSize.height) }
    }

    fun render(bitmap: Bitmap?, bitmapRegion: Rect, outputRegion: Rect, size: IntSize): Bitmap {
        val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        if (bitmap != null) {
            val drawing = Matrix(transform)
            drawing.preTranslate(bitmapRegion.left.toFloat(), bitmapRegion.top.toFloat())
            drawing.preScale(bitmapRegion.width().toFloat() / bitmap.width, bitmapRegion.height().toFloat() / bitmap.height)
            drawing.postTranslate(-outputRegion.left.toFloat(), -outputRegion.top.toFloat())
            drawing.postScale(size.width.toFloat() / outputRegion.width(), size.height.toFloat() / outputRegion.height())
            canvas.drawBitmap(bitmap, drawing, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }
}

internal fun IntSize.bounds(): Rect = Rect(0, 0, width, height)
