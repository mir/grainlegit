package dev.grainlegit.editor

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

internal data class Camera(val scale: Float, val center: Offset) {
    fun constrain(photo: IntSize, viewport: IntSize, fit: Float): Camera {
        val actual = scale.coerceIn(fit, 1f)
        fun bound(center: Float, view: Int, image: Int): Float {
            val half = view / (2f * actual)
            return if (2 * half >= image) image / 2f else center.coerceIn(half, image - half)
        }
        return Camera(actual, Offset(
            bound(center.x, viewport.width, photo.width),
            bound(center.y, viewport.height, photo.height),
        ))
    }

    fun pixelAligned(view: IntSize): Camera {
        if (scale != 1f) return this
        val half = view.center()
        return copy(center = Offset(round(center.x - half.x), round(center.y - half.y)) + half)
    }

    fun visibleRegion(photo: IntSize, viewport: IntSize): Rect {
        val half = viewport.center() / scale
        return Rect(
            floor(center.x - half.x).toInt().coerceIn(0, photo.width - 1),
            floor(center.y - half.y).toInt().coerceIn(0, photo.height - 1),
            ceil(center.x + half.x).toInt().coerceIn(1, photo.width),
            ceil(center.y + half.y).toInt().coerceIn(1, photo.height),
        )
    }
}

internal fun IntSize.center() = Offset(width / 2f, height / 2f)

internal fun fitScale(photo: IntSize, viewport: IntSize): Float =
    if (viewport.width <= 0 || viewport.height <= 0) 1f
    else min(viewport.width.toFloat() / photo.width, viewport.height.toFloat() / photo.height).coerceAtMost(1f)

internal fun Rect.copyExpanded(size: IntSize, padding: Int) = Rect(
    (left - padding).coerceAtLeast(0),
    (top - padding).coerceAtLeast(0),
    (right + padding).coerceAtMost(size.width),
    (bottom + padding).coerceAtMost(size.height),
)

internal fun Rect.interior(size: IntSize, padding: Int) = Rect(
    if (left == 0) left else left + padding,
    if (top == 0) top else top + padding,
    if (right == size.width) right else right - padding,
    if (bottom == size.height) bottom else bottom - padding,
)
