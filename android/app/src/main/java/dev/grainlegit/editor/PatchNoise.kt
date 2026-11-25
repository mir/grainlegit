package dev.grainlegit.editor

import android.graphics.Bitmap
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Noise measured in the untouched photo beside a removal: luma noise for shadows, midtones and
 * highlights, plus the extra per-channel (chroma) noise. Values are standard deviations in 8-bit
 * sRGB code values at source resolution.
 *
 * Inpainting models return clean fills at about 512 px. Drawn into a noisy photo, a clean fill reads
 * as a smooth blob even when its content is right. Adding matching noise when the patch is drawn
 * hides that.
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
/** Std of (x − 3×3 box mean of x) for white noise of unit std: sqrt(1 − 1/9). */
private const val BOX_RESIDUAL = 0.9428f
/**
 * Std of (channel − luma) for unit independent channel noise, pooled over R, G and B:
 * sqrt(mean of (1−R)²+G²+B², R²+(1−G)²+B², R²+G²+(1−B)²).
 */
private const val CHROMA_RESIDUAL = 0.883f
/** Share of channel noise variance that lands in luma: R² + G² + B². */
private const val CHROMA_IN_LUMA = R * R + G * G + B * B
private const val MIN_SAMPLES = 400

/**
 * Estimate noise from full-resolution [tiles] of the photo around a selection. Only flat pixels
 * count: the half with the least local structure, so edges and texture do not read as noise.
 * Returns null when there are too few flat pixels.
 */
internal fun measureNoise(tiles: List<Bitmap>): PatchNoise? {
    val residual = Floats()
    val structure = Floats()
    val level = Floats()
    val chroma = Floats()
    for (tile in tiles) {
        val w = tile.width
        val h = tile.height
        if (w < 8 || h < 8) continue
        val argb = IntArray(w * h).also { tile.getPixels(it, 0, w, 0, 0, w, h) }
        val red = FloatArray(w * h) { (argb[it] shr 16 and 0xFF).toFloat() }
        val green = FloatArray(w * h) { (argb[it] shr 8 and 0xFF).toFloat() }
        val blue = FloatArray(w * h) { (argb[it] and 0xFF).toFloat() }
        val luma = FloatArray(w * h) { R * red[it] + G * green[it] + B * blue[it] }
        val blurred = boxBlur(luma, w, h)
        val blurredRed = boxBlur(red, w, h)
        val blurredGreen = boxBlur(green, w, h)
        val blurredBlue = boxBlur(blue, w, h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val rY = luma[i] - blurred[i]
            residual.add(rY)
            structure.add(abs(blurred[i + 1] - blurred[i - 1]) + abs(blurred[i + w] - blurred[i - w]))
            level.add(blurred[i])
            chroma.add(abs(red[i] - blurredRed[i] - rY))
            chroma.add(abs(green[i] - blurredGreen[i] - rY))
            chroma.add(abs(blue[i] - blurredBlue[i] - rY))
        }
    }
    if (residual.size < MIN_SAMPLES) return null

    val flatLimit = structure.copy().median()
    val bands = Array(3) { Floats() }
    val flatChroma = Floats()
    for (i in 0 until residual.size) {
        if (structure[i] > flatLimit) continue
        bands[min(2, (level[i] / 85.34f).toInt().coerceAtLeast(0))].add(abs(residual[i]))
        for (c in 0 until 3) flatChroma.add(chroma[i * 3 + c])
    }
    // Median absolute deviation → standard deviation for Gaussian noise.
    fun sigma(values: Floats, factor: Float) =
        if (values.size < MIN_SAMPLES) Float.NaN else 1.4826f * values.median() / factor
    val measured = bands.map { sigma(it, BOX_RESIDUAL) }
    if (measured.all { it.isNaN() }) return null
    val filled = measured.indices.map { band ->
        if (!measured[band].isNaN()) measured[band]
        else measured.indices.filter { !measured[it].isNaN() }.minBy { abs(it - band) }.let { measured[it] }
    }
    val chromaSigma = sigma(flatChroma, BOX_RESIDUAL * CHROMA_RESIDUAL).takeUnless { it.isNaN() } ?: 0f
    // Channel noise also shows up in luma, so remove its share to avoid adding it twice.
    val lumaOnly = filled.map { sqrt(max(0f, it * it - CHROMA_IN_LUMA * chromaSigma * chromaSigma)) }
    return PatchNoise(lumaOnly[0], lumaOnly[1], lumaOnly[2], chromaSigma)
}

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

private fun boxBlur(values: FloatArray, w: Int, h: Int): FloatArray {
    val horizontal = FloatArray(values.size)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        horizontal[i] = (values[i - if (x > 0) 1 else 0] + values[i] + values[i + if (x < w - 1) 1 else 0]) / 3f
    }
    val result = FloatArray(values.size)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        result[i] = (horizontal[i - if (y > 0) w else 0] + horizontal[i] + horizontal[i + if (y < h - 1) w else 0]) / 3f
    }
    return result
}

/** A growable float list without boxing. */
private class Floats {
    private var values = FloatArray(4096)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int) = values[index]

    fun copy() = Floats().also { it.values = values.copyOf(size); it.size = size }

    /** Median, sorting this list in place. */
    fun median(): Float {
        java.util.Arrays.sort(values, 0, size)
        return if (size == 0) 0f else values[size / 2]
    }
}
