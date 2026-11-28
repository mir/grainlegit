package dev.grainlegit.editor

import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/**
 * White noise saved with removals made by Grainlegit 0.9: luma deviation for shadows, midtones and
 * highlights plus chroma, in 8-bit sRGB code values at source resolution. Newer removals store
 * [PatchGrain] instead; this stays so older edits keep rendering the same.
 */
internal data class PatchNoise(val shadows: Float, val midtones: Float, val highlights: Float, val chroma: Float) {
    val visible get() = max(max(shadows, midtones), max(highlights, chroma)) >= MIN_VISIBLE

    /** Luma deviation for a pixel of level [y] in 0..255, interpolated between the band centres. */
    fun luma(y: Float): Float {
        val t = (y - 42.5f) / 85f
        return when {
            t <= 0f -> shadows
            t <= 1f -> shadows + (midtones - shadows) * t
            t <= 2f -> midtones + (highlights - midtones) * (t - 1f)
            else -> highlights
        }
    }

    fun encode(): JSONArray = JSONArray().put(shadows.toDouble()).put(midtones.toDouble())
        .put(highlights.toDouble()).put(chroma.toDouble())

    companion object {
        const val MIN_VISIBLE = 0.4f

        fun decode(array: JSONArray?): PatchNoise? = array?.takeIf { it.length() == 4 }?.let {
            PatchNoise(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(),
                it.getDouble(3).toFloat())
        }
    }
}

private const val R = 0.299f
private const val G = 0.587f
private const val B = 0.114f

/**
 * Add [noise] to non-premultiplied ARGB [pixels] of a patch layer. Pixel (x, y) shows source pixel
 * ([originX] + (x + ½)·[stepX], [originY] + (y + ½)·[stepY]); the noise is keyed to that source pixel
 * and [seed], so export tiles and previews of the same area agree. [strength] scales the deviation
 * for layers drawn below source resolution, where each pixel averages several source pixels.
 */
internal fun addNoise(
    pixels: IntArray, width: Int, height: Int, noise: PatchNoise, seed: Int,
    originX: Double, originY: Double, stepX: Double, stepY: Double, strength: Float,
) {
    for (y in 0 until height) {
        val sourceY = kotlin.math.floor(originY + (y + 0.5) * stepY).toInt()
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            if (color ushr 24 == 0) continue
            val sourceX = kotlin.math.floor(originX + (x + 0.5) * stepX).toInt()
            val red = (color shr 16 and 0xFF).toFloat()
            val green = (color shr 8 and 0xFF).toFloat()
            val blue = (color and 0xFF).toFloat()
            val shared = noise.luma(R * red + G * green + B * blue) * gaussian(sourceX, sourceY, seed, 0) * strength
            val c = noise.chroma * strength
            val r = red + shared + c * gaussian(sourceX, sourceY, seed, 1)
            val g = green + shared + c * gaussian(sourceX, sourceY, seed, 2)
            val b = blue + shared + c * gaussian(sourceX, sourceY, seed, 3)
            pixels[index] = (color and 0xFF000000.toInt()) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
}

private fun channel(value: Float) = (value + 0.5f).toInt().coerceIn(0, 255)

/** Approximately unit Gaussian from the four bytes of one hash (Irwin–Hall, n = 4). */
private fun gaussian(x: Int, y: Int, seed: Int, salt: Int): Float {
    var h = x * 0x27d4eb2d xor (y * 0x165667b1) xor seed xor (salt * -0x61c88647)
    h = (h xor (h ushr 15)) * 0x2c1b3c6d
    h = (h xor (h ushr 12)) * 0x297a2d39
    h = h xor (h ushr 15)
    val sum = (h and 0xFF) + (h ushr 8 and 0xFF) + (h ushr 16 and 0xFF) + (h ushr 24 and 0xFF)
    return (sum / 255f - 2f) * 1.7320508f
}
