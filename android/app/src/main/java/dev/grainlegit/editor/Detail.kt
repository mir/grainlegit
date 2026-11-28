package dev.grainlegit.editor

import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Restores what a 512 px fill loses when it is stretched over a large hole: fine texture, copied
// from the best-matching surroundings at full resolution, and grain, synthesised per grain size.
//
// Terms: the context square is decoded at "grain resolution", 512·n pixels square, where one grain
// pixel covers [PatchGrain.unit] source pixels. The model works on the 512 px box-average of it.

private const val PATCH = 8          // patch side at 512, in model pixels
private const val STRIDE = 4         // query spacing at 512; patches overlap by half
private const val SEARCH_ROUNDS = 6
private val BAND_SIGMAS = floatArrayOf(0f, 0.7f, 1.4f, 2.8f, 5.6f)
internal const val GRAIN_BANDS = 4
private const val R = 0.299f
private const val G = 0.587f
private const val B = 0.114f

/**
 * Grain to add over a removal: standard deviations per grain size (finest first) and channel, in
 * 8-bit code values, for a fill drawn without ([plain]) and with ([detailed]) its detail layer.
 * [mix] is the lower-triangular Cholesky factor of the grain's channel correlation. Grain strength
 * follows tone as exp([toneSlope] · (luma − [toneMean])), luma in 8-bit code values.
 */
internal data class PatchGrain(
    val unit: Float,
    val plain: List<Float>,
    val detailed: List<Float>,
    val mix: List<Float>,
    val toneMean: Float = 128f,
    val toneSlope: Float = 0f,
) {
    fun encode(): JSONArray = JSONArray().apply {
        put(unit.toDouble())
        (plain + detailed + mix + listOf(toneMean, toneSlope)).forEach { put(it.toDouble()) }
    }

    /** Relative grain strength at 8-bit [luma]. */
    fun toneGain(luma: Float): Float = if (toneSlope == 0f) 1f
        else exp(toneSlope * (luma - toneMean)).coerceIn(MIN_TONE_GAIN, MAX_TONE_GAIN)

    companion object {
        private const val SIZE = 1 + 2 * 3 * GRAIN_BANDS + 6

        fun decode(array: JSONArray?): PatchGrain? = array?.takeIf { it.length() == SIZE || it.length() == SIZE + 2 }?.let {
            val values = List(it.length()) { index -> it.getDouble(index).toFloat() }
            val bands = 3 * GRAIN_BANDS
            PatchGrain(values[0], values.subList(1, 1 + bands), values.subList(1 + bands, 1 + 2 * bands),
                values.subList(1 + 2 * bands, SIZE), values.getOrElse(SIZE) { 128f }, values.getOrElse(SIZE + 1) { 0f })
        }
    }
}

private const val MIN_TONE_GAIN = 0.5f
private const val MAX_TONE_GAIN = 2f
private const val TONE_BIN = 16       // 8-bit code values per tone bin
private const val TONE_SAMPLES = 300  // flat pixels a tone bin needs to count

/** The full-resolution context square, resampled to 512·[n] pixels, and its 512 px box average. */
internal class GrainResolution(val pixels: IntArray, val n: Int) {
    val side = 512 * n
    /** 512 × 512 box average, per channel. */
    val low = Array(3) { FloatArray(512 * 512) }

    init {
        val area = (n * n).toFloat()
        for (y in 0 until 512) for (x in 0 until 512) {
            var r = 0; var g = 0; var b = 0
            for (j in 0 until n) {
                val row = (y * n + j) * side + x * n
                for (i in 0 until n) {
                    val c = pixels[row + i]
                    r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
                }
            }
            val index = y * 512 + x
            low[0][index] = r / area; low[1][index] = g / area; low[2][index] = b / area
        }
    }

    /** The 512 px average sampled back at grain pixel (x, y), bilinearly. */
    fun lowAt(channel: Int, x: Int, y: Int) = bilinear(low[channel], 512, (x + 0.5f) / n - 0.5f, (y + 0.5f) / n - 0.5f)

    /** Detail at grain pixel (x, y): the full-resolution value minus the upsampled 512 px average. */
    fun detailAt(channel: Int, x: Int, y: Int): Float =
        (pixels[y * side + x] shr (16 - 8 * channel) and 0xFF) - lowAt(channel, x, y)
}

/** Result of [transferDetail]: an offset-128 RGB detail layer over the patch's 512 px bounds × n. */
internal class DetailLayer(val pixels: IntArray, val width: Int, val height: Int, val residual: Array<FloatArray>)

/**
 * For every patch of the fill inside [left]..[right] × [top]..[bottom] (512 px, inclusive), find the
 * most similar patch outside the hole at 512 px, and copy that patch's full-resolution detail.
 * [fill] is the model output at 512 (ARGB), [hole] the dilated hole, [alpha] the feathered patch
 * mask. Returns null when no surroundings are available to copy from.
 */
internal fun transferDetail(
    grain: GrainResolution, fill: IntArray, hole: BooleanArray, alpha: FloatArray,
    left: Int, top: Int, right: Int, bottom: Int, seed: Int,
): DetailLayer? {
    val size = 512
    // The image the search sees: the box average outside the hole, the fill inside it.
    val composite = Array(3) { c ->
        FloatArray(size * size) { i -> if (hole[i]) (fill[i] shr (16 - 8 * c) and 0xFF).toFloat() else grain.low[c][i] }
    }
    // 2 × 2 means, so a patch feature is 4 × 4 cells × 3 channels.
    val cells = Array(3) { c ->
        val plane = composite[c]
        FloatArray(size * size) { i ->
            val x = min(i % size, size - 2); val y = min(i / size, size - 2); val j = y * size + x
            (plane[j] + plane[j + 1] + plane[j + size] + plane[j + size + 1]) * 0.25f
        }
    }
    // Candidate patches may not touch the hole or its soft edge.
    val blocked = summedArea(size) { i -> hole[i] || alpha[i] > 0f }
    fun valid(u: Int, v: Int) = u in 0..size - PATCH && v in 0..size - PATCH &&
        areaSum(blocked, size, u - 2, v - 2, u + PATCH + 2, v + PATCH + 2) == 0
    val candidates = ArrayList<Int>()
    for (v in 0..size - PATCH step 2) for (u in 0..size - PATCH step 2) if (valid(u, v)) candidates += v * size + u
    if (candidates.isEmpty()) return null

    fun distance(qx: Int, qy: Int, u: Int, v: Int, limit: Float): Float {
        var sum = 0f
        for (j in 0 until 4) for (i in 0 until 4) {
            val a = (qy + 2 * j) * size + qx + 2 * i
            val b = (v + 2 * j) * size + u + 2 * i
            for (c in 0 until 3) {
                val d = cells[c][a] - cells[c][b]
                sum += d * d
            }
            if (sum >= limit) return sum
        }
        return sum
    }

    // Query patches cover the patch bounds on a STRIDE grid.
    val qx0 = max(0, min(left - PATCH / 2, size - PATCH)) / STRIDE * STRIDE
    val qy0 = max(0, min(top - PATCH / 2, size - PATCH)) / STRIDE * STRIDE
    val columns = (min(right, size - PATCH) - qx0) / STRIDE + 1
    val rows = (min(bottom, size - PATCH) - qy0) / STRIDE + 1
    if (columns <= 0 || rows <= 0) return null
    val random = java.util.Random(seed.toLong())
    val matchU = IntArray(columns * rows)
    val matchV = IntArray(columns * rows)
    val cost = FloatArray(columns * rows)
    for (q in 0 until columns * rows) {
        val c = candidates[random.nextInt(candidates.size)]
        matchU[q] = c % size; matchV[q] = c / size
        cost[q] = distance(qx0 + q % columns * STRIDE, qy0 + q / columns * STRIDE, matchU[q], matchV[q], Float.MAX_VALUE)
    }
    fun tryMatch(q: Int, u: Int, v: Int) {
        if (!valid(u, v)) return
        val d = distance(qx0 + q % columns * STRIDE, qy0 + q / columns * STRIDE, u, v, cost[q])
        if (d < cost[q]) { cost[q] = d; matchU[q] = u; matchV[q] = v }
    }
    // PatchMatch: propagate good offsets from neighbours, then search randomly around the best.
    for (round in 0 until SEARCH_ROUNDS) {
        val forward = round % 2 == 0
        val step = if (forward) 1 else -1
        var row = if (forward) 0 else rows - 1
        while (row in 0 until rows) {
            var column = if (forward) 0 else columns - 1
            while (column in 0 until columns) {
                val q = row * columns + column
                val previousColumn = column - step
                val previousRow = row - step
                if (previousColumn in 0 until columns) {
                    val p = row * columns + previousColumn
                    tryMatch(q, matchU[p] + step * STRIDE, matchV[p])
                }
                if (previousRow in 0 until rows) {
                    val p = previousRow * columns + column
                    tryMatch(q, matchU[p], matchV[p] + step * STRIDE)
                }
                var radius = size / 2
                while (radius >= 1) {
                    tryMatch(q, matchU[q] + random.nextInt(2 * radius + 1) - radius,
                        matchV[q] + random.nextInt(2 * radius + 1) - radius)
                    radius /= 2
                }
                column += step
            }
            row += step
        }
    }

    // Copy each match's full-resolution detail through a Hann window; half-overlapping windows sum to 1.
    val n = grain.n
    val outLeft = left * n
    val outTop = top * n
    val width = (right - left + 1) * n
    val height = (bottom - top + 1) * n
    val sum = Array(3) { FloatArray(width * height) }
    val squares = FloatArray(width * height)
    val weight = FloatArray(width * height)
    val window = PATCH * n
    val hann = FloatArray(window) { val s = sin(PI * (it + 0.5) / window).toFloat(); s * s }
    for (q in 0 until columns * rows) {
        val qx = (qx0 + q % columns * STRIDE) * n - outLeft
        val qy = (qy0 + q / columns * STRIDE) * n - outTop
        val sx = matchU[q] * n
        val sy = matchV[q] * n
        for (j in 0 until window) {
            val y = qy + j
            if (y !in 0 until height) continue
            for (i in 0 until window) {
                val x = qx + i
                if (x !in 0 until width) continue
                val w = hann[i] * hann[j]
                val index = y * width + x
                for (c in 0 until 3) {
                    val d = grain.detailAt(c, sx + i, sy + j)
                    sum[c][index] += w * d
                    squares[index] += w * d * d
                }
                weight[index] += w
            }
        }
    }
    val gain = blendGain(sum, squares, weight, width, height, n)
    val pixels = IntArray(width * height)
    for (index in 0 until width * height) {
        val w = weight[index]
        val g = gain(index % width, index / width)
        var packed = 0xFF shl 24
        for (c in 0 until 3) {
            val value = if (w > 1e-4f) g * sum[c][index] / w else 0f
            sum[c][index] = value
            packed = packed or ((value + 128.5f).toInt().coerceIn(0, 255) shl (16 - 8 * c))
        }
        pixels[index] = packed
    }
    return DetailLayer(pixels, width, height, sum)
}

/**
 * Overlapping windows from unrelated matches average their grain away: the blend of k equally
 * weighted independent patches has 1/√k of their deviation. Per model pixel (n × n grain pixels),
 * compare the mean energy of the contributions with the energy of their blend and return the gain
 * that restores it: 1 where the matches agree, up to 2 where they are independent.
 */
private fun blendGain(
    sum: Array<FloatArray>, squares: FloatArray, weight: FloatArray, width: Int, height: Int, n: Int,
): (Int, Int) -> Float {
    val cw = (width + n - 1) / n
    val ch = (height + n - 1) / n
    val blended = FloatArray(cw * ch)
    val contributed = FloatArray(cw * ch)
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        val w = weight[index]
        if (w <= 1e-4f) continue
        val cell = (y / n) * cw + x / n
        var mean2 = 0f
        for (c in 0 until 3) { val m = sum[c][index] / w; mean2 += m * m }
        blended[cell] += mean2
        contributed[cell] += squares[index] / w
    }
    val smoothBlended = gaussianBlur(blended, cw, ch, 1.5f)
    val smoothContributed = gaussianBlur(contributed, cw, ch, 1.5f)
    val gain = FloatArray(cw * ch) { i ->
        if (smoothBlended[i] <= 1e-3f) 1f else sqrt(smoothContributed[i] / smoothBlended[i]).coerceIn(1f, 2f)
    }
    return { x, y -> bilinear(gain, cw, (x + 0.5f) / n - 0.5f, (y + 0.5f) / n - 0.5f, ch) }
}

/**
 * Measure grain beside the hole and inside the upscaled fill, with and without [detail], and return
 * the top-up needed per grain size. Only flat areas count, so texture does not read as grain.
 */
internal fun measureGrain(
    grain: GrainResolution, fill: IntArray, hole: BooleanArray, alpha: FloatArray,
    detail: DetailLayer?, left: Int, top: Int, unit: Float,
): PatchGrain? {
    val n = grain.n
    val size = 512
    val near = summedArea(size) { hole[it] || alpha[it] > 0f }
    val inside = summedArea(size) { hole[it] }
    // Tiles of 128 grain pixels where possible, in 512 px cells.
    val tileCells = max(4, min(32, 128 / n))
    val tile = tileCells * n
    var cx = 0.0; var cy = 0.0; var count = 0
    for (i in 0 until size * size) if (hole[i]) { cx += i % size; cy += i / size; count++ }
    if (count == 0) return null
    cx /= count; cy /= count
    val outsideTiles = ArrayList<Pair<Int, Int>>()
    val insideTiles = ArrayList<Pair<Int, Int>>()
    val margin = 2
    for (v in 0..size - tileCells step tileCells / 2) for (u in 0..size - tileCells step tileCells / 2) {
        if (areaSum(near, size, u - margin, v - margin, u + tileCells + margin, v + tileCells + margin) == 0) outsideTiles += u to v
        if (areaSum(inside, size, u, v, u + tileCells, v + tileCells) == tileCells * tileCells) insideTiles += u to v
    }
    fun nearest(list: List<Pair<Int, Int>>) = list.sortedBy { (u, v) ->
        val dx = u + tileCells / 2 - cx; val dy = v + tileCells / 2 - cy; dx * dx + dy * dy
    }.take(8)
    val ring = nearest(outsideTiles)
    val filled = nearest(insideTiles)
    if (ring.isEmpty() || filled.isEmpty()) return null

    fun outsideTile(u: Int, v: Int) = Array(3) { c ->
        FloatArray(tile * tile) { i ->
            (grain.pixels[(v * n + i / tile) * grain.side + u * n + i % tile] shr (16 - 8 * c) and 0xFF).toFloat()
        }
    }
    fun filledTile(u: Int, v: Int, withDetail: Boolean) = Array(3) { c ->
        FloatArray(tile * tile) { i ->
            val x = u * n + i % tile
            val y = v * n + i / tile
            val base = bilinear(fill, size, c, (x + 0.5f) / n - 0.5f, (y + 0.5f) / n - 0.5f)
            val extra = if (withDetail && detail != null) {
                val dx = x - left * n; val dy = y - top * n
                if (dx in 0 until detail.width && dy in 0 until detail.height) detail.residual[c][dy * detail.width + dx] else 0f
            } else 0f
            base + extra
        }
    }
    val target = bandStatistics(ring.map { (u, v) -> outsideTile(u, v) }, tile, correlation = true) ?: return null
    val plainHave = bandStatistics(filled.map { (u, v) -> filledTile(u, v, false) }, tile, false) ?: return null
    val detailHave = if (detail == null) plainHave
        else bandStatistics(filled.map { (u, v) -> filledTile(u, v, true) }, tile, false) ?: return null

    // With detail, bands at the scale of a model pixel or coarser are the fill's own content: lattice
    // noise there reads as mottling, so the top-up only fills bands finer than a model pixel.
    fun topUp(have: FloatArray, finestOnly: Boolean): List<Float> {
        val amplitudes = FloatArray(3 * GRAIN_BANDS)
        for (c in 0 until 3) {
            val deficit = FloatArray(GRAIN_BANDS) { b ->
                val t = target.std[b * 3 + c]; val h = have[b * 3 + c]
                if (finestOnly && BAND_SIGMAS[b + 1] > n) 0f else max(0f, t * t - h * h)
            }
            val solved = GrainBasis.solve(deficit)
            for (k in 0 until GRAIN_BANDS) amplitudes[k * 3 + c] = solved[k]
        }
        return amplitudes.toList()
    }
    return PatchGrain(unit, topUp(plainHave.std, false), topUp(detailHave.std, detail != null), target.mix,
        target.toneMean, target.toneSlope)
}

private class BandStatistics(val std: FloatArray, val mix: List<Float>, val toneMean: Float = 128f, val toneSlope: Float = 0f)

/** Robust per-band, per-channel deviations over the flat half of [tiles] (square, side [tile]). */
private fun bandStatistics(tiles: List<Array<FloatArray>>, tile: Int, correlation: Boolean): BandStatistics? {
    val border = ceil(3 * BAND_SIGMAS.last()).toInt()
    if (tile <= 2 * border + 8) return null
    val structure = ArrayList<FloatArray>()
    val bands = ArrayList<Array<Array<FloatArray>>>()  // [tile][channel][band]
    val residuals = ArrayList<Array<FloatArray>>()
    val tones = ArrayList<FloatArray>()
    val fines = ArrayList<FloatArray>()
    for (planes in tiles) {
        val luma = FloatArray(tile * tile) { R * planes[0][it] + G * planes[1][it] + B * planes[2][it] }
        val smooth = gaussianBlur(luma, tile, tile, 2.8f)
        if (correlation) {
            tones += smooth
            val coarse = gaussianBlur(luma, tile, tile, BAND_SIGMAS[2])
            fines += FloatArray(tile * tile) { luma[it] - coarse[it] }
        }
        structure += FloatArray(tile * tile) { i ->
            val x = i % tile; val y = i / tile
            if (x == 0 || y == 0 || x == tile - 1 || y == tile - 1) 0f
            else abs(smooth[i + 1] - smooth[i - 1]) + abs(smooth[i + tile] - smooth[i - tile])
        }
        bands += Array(3) { c ->
            val blurred = BAND_SIGMAS.map { gaussianBlur(planes[c], tile, tile, it) }
            Array(GRAIN_BANDS) { b -> FloatArray(tile * tile) { i -> blurred[b][i] - blurred[b + 1][i] } }
        }
        if (correlation) residuals += Array(3) { c ->
            val blurred = gaussianBlur(planes[c], tile, tile, 2.8f)
            FloatArray(tile * tile) { planes[c][it] - blurred[it] }
        }
    }
    // Flat pixels: the less structured half of the tiles' interiors.
    val span = tile - 2 * border
    val interior = FloatArray(tiles.size * span * span)
    var count = 0
    for (t in tiles.indices) for (y in border until tile - border) for (x in border until tile - border) interior[count++] = structure[t][y * tile + x]
    if (count < 400) return null
    val limit = interior.copyOf().also { it.sort() }[count / 2]
    val std = FloatArray(3 * GRAIN_BANDS)
    val values = FloatArray(count)
    for (b in 0 until GRAIN_BANDS) for (c in 0 until 3) {
        var size = 0
        for (t in tiles.indices) for (y in border until tile - border) for (x in border until tile - border) {
            val i = y * tile + x
            if (structure[t][i] <= limit) values[size++] = abs(bands[t][c][b][i])
        }
        values.sort(0, size)
        std[b * 3 + c] = 1.4826f * values[size / 2]
    }
    val mix = if (!correlation) emptyList() else {
        val covariance = Array(3) { DoubleArray(3) }
        var samples = 0
        for (t in tiles.indices) for (y in border until tile - border) for (x in border until tile - border) {
            val i = y * tile + x
            if (structure[t][i] > limit) continue
            for (a in 0 until 3) for (b in 0 until 3) covariance[a][b] += residuals[t][a][i].toDouble() * residuals[t][b][i]
            samples++
        }
        val corr = Array(3) { a -> DoubleArray(3) { b ->
            covariance[a][b] / sqrt(max(1e-9, covariance[a][a] * covariance[b][b])) * if (a == b) 1.0 else 0.98
        } }
        cholesky(corr)
    }
    if (!correlation) return BandStatistics(std, mix)
    val (toneMean, toneSlope) = toneTrend(tiles.indices.flatMap { t ->
        (border until tile - border).flatMap { y -> (border until tile - border).map { x -> y * tile + x } }
            .filter { structure[t][it] <= limit }.map { tones[t][it] to fines[t][it] }
    })
    return BandStatistics(std, mix, toneMean, toneSlope)
}

/**
 * How fine grain strength changes with tone over flat (tone, grain) samples: a weighted line through
 * log robust deviation per [TONE_BIN] tone bin. Returns (mean tone, slope per code value); the slope
 * is 0 when fewer than two bins have [TONE_SAMPLES] samples.
 */
private fun toneTrend(samples: List<Pair<Float, Float>>): Pair<Float, Float> {
    if (samples.isEmpty()) return 128f to 0f
    val bins = samples.groupBy { (tone, _) -> (tone / TONE_BIN).toInt().coerceIn(0, 255 / TONE_BIN) }
        .filterValues { it.size >= TONE_SAMPLES }
    val mean = samples.sumOf { it.first.toDouble() }.toFloat() / samples.size
    if (bins.size < 2) return mean to 0f
    var sw = 0.0; var st = 0.0; var sl = 0.0; var stt = 0.0; var stl = 0.0
    for ((_, bin) in bins) {
        val deviations = FloatArray(bin.size) { abs(bin[it].second) }.also { it.sort() }
        val deviation = 1.4826f * deviations[bin.size / 2]
        if (deviation <= 0.05f) continue
        val t = bin.sumOf { it.first.toDouble() } / bin.size - mean
        val l = kotlin.math.ln(deviation.toDouble())
        val w = bin.size.toDouble()
        sw += w; st += w * t; sl += w * l; stt += w * t * t; stl += w * t * l
    }
    val variance = stt * sw - st * st
    if (sw == 0.0 || variance < 1e-6 * sw * sw) return mean to 0f
    val slope = ((stl * sw - st * sl) / variance).toFloat()
    // Two full gain doublings across the tone range is already beyond film grain.
    return mean to slope.coerceIn(-0.02f, 0.02f)
}

private fun cholesky(m: Array<DoubleArray>): List<Float> {
    val l = Array(3) { DoubleArray(3) }
    for (i in 0 until 3) for (j in 0..i) {
        var s = m[i][j]
        for (k in 0 until j) s -= l[i][k] * l[j][k]
        l[i][j] = if (i == j) sqrt(max(1e-6, s)) else s / l[j][j]
    }
    return listOf(l[0][0], l[1][0], l[1][1], l[2][0], l[2][1], l[2][2]).map { it.toFloat() }
}

/**
 * The noise used for grain top-up: per grain size, Gaussian values on a lattice with spacing 2^k
 * grain pixels (k = 0 is per pixel), smoothed on the lattice and interpolated. Values are keyed to
 * grain coordinates so any tile of any rendering reproduces them. [solve] maps the deficit per
 * measured band to lattice amplitudes, using each lattice's band response measured once.
 */
internal object GrainBasis {
    private val response: Array<FloatArray> by lazy { calibrate() }
    private var nativeStd = FloatArray(GRAIN_BANDS) { 1f }
    private const val OVERSHOOT = 4f

    /**
     * Non-negative amplitudes a with Σ_k (response[b][k]·a_k)² ≈ deficit[b] (squared deviations).
     * Overshoot costs [OVERSHOOT]× more than shortfall: grain lands on top of what the band already
     * has, so excess in a band that needs nothing reads as mottling.
     */
    fun solve(deficit: FloatArray): FloatArray {
        val a2 = Array(GRAIN_BANDS) { b -> FloatArray(GRAIN_BANDS) { k -> response[b][k] * response[b][k] } }
        val x = FloatArray(GRAIN_BANDS)
        var norm = 0f
        for (b in 0 until GRAIN_BANDS) for (k in 0 until GRAIN_BANDS) norm += a2[b][k] * a2[b][k]
        val step = 1f / (OVERSHOOT * max(norm, 1e-6f))
        repeat(1500) {
            val residual = FloatArray(GRAIN_BANDS) { b ->
                val r = (0 until GRAIN_BANDS).sumOf { k -> (a2[b][k] * x[k]).toDouble() }.toFloat() - deficit[b]
                if (r > 0f) OVERSHOOT * r else r
            }
            for (k in 0 until GRAIN_BANDS) {
                var gradient = 0f
                for (b in 0 until GRAIN_BANDS) gradient += a2[b][k] * residual[b]
                x[k] = max(0f, x[k] - step * gradient)
            }
        }
        return FloatArray(GRAIN_BANDS) { sqrt(x[it]) }
    }

    private fun calibrate(): Array<FloatArray> {
        val size = 192
        val raw = Array(GRAIN_BANDS) { k -> field(k, size, size, 0.0, 0.0, 1.0, 1.0, 12345, 0, normalise = false) }
        nativeStd = FloatArray(GRAIN_BANDS) { k -> sqrt(raw[k].fold(0.0) { s, v -> s + v * v } / raw[k].size).toFloat() }
        val border = ceil(3 * BAND_SIGMAS.last()).toInt()
        return Array(GRAIN_BANDS) { b ->
            FloatArray(GRAIN_BANDS) { k ->
                val unitField = FloatArray(size * size) { raw[k][it] / nativeStd[k] }
                val lower = gaussianBlur(unitField, size, size, BAND_SIGMAS[b])
                val upper = gaussianBlur(unitField, size, size, BAND_SIGMAS[b + 1])
                var sum = 0.0; var count = 0
                for (y in border until size - border) for (x in border until size - border) {
                    val d = lower[y * size + x] - upper[y * size + x]; sum += d * d; count++
                }
                sqrt(sum / count).toFloat()
            }
        }
    }

    /**
     * Unit-deviation noise of grain size [k] over a w × h area whose pixel (x, y) shows grain
     * coordinate (gx0 + (x + ½)·stepX, gy0 + (y + ½)·stepY). Below one pixel per lattice cell the
     * noise averages out, so it shrinks as it would in a downscaled image.
     */
    fun field(
        k: Int, w: Int, h: Int, gx0: Double, gy0: Double, stepX: Double, stepY: Double,
        seed: Int, salt: Int, normalise: Boolean = true,
    ): FloatArray {
        if (normalise) response // calibrates nativeStd
        val spacing = (1 shl k).toDouble()
        val out = FloatArray(w * h)
        val perPixel = spacing / stepX
        if (perPixel < 1.0 || k == 0) {
            val scale = min(1.0, perPixel).toFloat()
            for (y in 0 until h) {
                val ly = floor((gy0 + (y + 0.5) * stepY) / spacing).toInt()
                for (x in 0 until w) {
                    val lx = floor((gx0 + (x + 0.5) * stepX) / spacing).toInt()
                    out[y * w + x] = gaussian(lx, ly, seed, salt * 8 + k) * scale
                }
            }
            return out
        }
        // Lattice values around the area, smoothed with a small Gaussian on the lattice.
        val i0 = floor(gx0 / spacing).toInt() - 3
        val j0 = floor(gy0 / spacing).toInt() - 3
        val i1 = floor((gx0 + w * stepX) / spacing).toInt() + 3
        val j1 = floor((gy0 + h * stepY) / spacing).toInt() + 3
        val lw = i1 - i0 + 1
        val lh = j1 - j0 + 1
        val lattice = FloatArray(lw * lh) { gaussian(i0 + it % lw, j0 + it / lw, seed, salt * 8 + k) }
        val smooth = gaussianBlur(lattice, lw, lh, 0.8f)
        val scale = if (normalise) 1f / nativeStd[k] else 1f
        for (y in 0 until h) {
            val ly = (gy0 + (y + 0.5) * stepY) / spacing - 0.5 - j0
            for (x in 0 until w) {
                val lx = (gx0 + (x + 0.5) * stepX) / spacing - 0.5 - i0
                out[y * w + x] = bilinear(smooth, lw, lx.toFloat(), ly.toFloat(), lh) * scale
            }
        }
        return out
    }
}

/**
 * Add grain top-up and an optional detail layer to non-premultiplied ARGB [pixels] of a patch layer
 * whose pixel (x, y) shows source pixel ([originX] + (x + ½)·[stepX], …). Grain strength follows the
 * tone of [tones], the fill without detail.
 */
internal fun addGrain(
    pixels: IntArray, width: Int, height: Int, grain: PatchGrain, withDetail: Boolean, seed: Int,
    originX: Double, originY: Double, stepX: Double, stepY: Double, tones: IntArray = pixels,
) {
    val amplitudes = if (withDetail) grain.detailed else grain.plain
    if (amplitudes.all { it < 0.05f }) return
    val unit = grain.unit.toDouble()
    val total = Array(3) { FloatArray(width * height) }
    val m = grain.mix
    for (k in 0 until GRAIN_BANDS) {
        if ((0 until 3).all { amplitudes[k * 3 + it] < 0.05f }) continue
        val z = Array(3) { c ->
            GrainBasis.field(k, width, height, originX / unit, originY / unit, stepX / unit, stepY / unit, seed, c)
        }
        val a0 = amplitudes[k * 3]; val a1 = amplitudes[k * 3 + 1]; val a2 = amplitudes[k * 3 + 2]
        for (i in 0 until width * height) {
            val z0 = z[0][i]; val z1 = z[1][i]; val z2 = z[2][i]
            total[0][i] += a0 * (m[0] * z0)
            total[1][i] += a1 * (m[1] * z0 + m[2] * z1)
            total[2][i] += a2 * (m[3] * z0 + m[4] * z1 + m[5] * z2)
        }
    }
    for (i in 0 until width * height) {
        val color = pixels[i]
        if (color ushr 24 == 0) continue
        val tone = tones[i]
        val gain = grain.toneGain(R * (tone shr 16 and 0xFF) + G * (tone shr 8 and 0xFF) + B * (tone and 0xFF))
        var out = color and 0xFF000000.toInt()
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            out = out or (((color shr shift and 0xFF) + gain * total[c][i] + 0.5f).toInt().coerceIn(0, 255) shl shift)
        }
        pixels[i] = out
    }
}

/** Add an offset-128 detail layer ([detail], same size) to non-premultiplied ARGB [pixels]. */
internal fun addDetail(pixels: IntArray, detail: IntArray) {
    for (i in pixels.indices) {
        val color = pixels[i]
        if (color ushr 24 == 0) continue
        val d = detail[i]
        var out = color and 0xFF000000.toInt()
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            out = out or (((color shr shift and 0xFF) + (d shr shift and 0xFF) - 128).coerceIn(0, 255) shl shift)
        }
        pixels[i] = out
    }
}

internal fun gaussianBlur(source: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
    if (sigma <= 0f) return source.copyOf()
    val radius = ceil(3 * sigma).toInt()
    val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
    val total = kernel.sum()
    for (i in kernel.indices) kernel[i] /= total
    val horizontal = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0f
        for (t in kernel.indices) s += kernel[t] * source[y * w + (x + t - radius).coerceIn(0, w - 1)]
        horizontal[y * w + x] = s
    }
    val result = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0f
        for (t in kernel.indices) s += kernel[t] * horizontal[(y + t - radius).coerceIn(0, h - 1) * w + x]
        result[y * w + x] = s
    }
    return result
}

private fun bilinear(plane: FloatArray, w: Int, x: Float, y: Float, h: Int = w): Float {
    val cx = x.coerceIn(0f, (w - 1).toFloat()); val cy = y.coerceIn(0f, (h - 1).toFloat())
    val x0 = cx.toInt(); val y0 = cy.toInt()
    val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
    val fx = cx - x0; val fy = cy - y0
    val top = plane[y0 * w + x0] * (1 - fx) + plane[y0 * w + x1] * fx
    val bottom = plane[y1 * w + x0] * (1 - fx) + plane[y1 * w + x1] * fx
    return top * (1 - fy) + bottom * fy
}

private fun bilinear(argb: IntArray, size: Int, channel: Int, x: Float, y: Float): Float {
    val shift = 16 - 8 * channel
    val cx = x.coerceIn(0f, (size - 1).toFloat()); val cy = y.coerceIn(0f, (size - 1).toFloat())
    val x0 = cx.toInt(); val y0 = cy.toInt()
    val x1 = min(x0 + 1, size - 1); val y1 = min(y0 + 1, size - 1)
    val fx = cx - x0; val fy = cy - y0
    fun v(xx: Int, yy: Int) = (argb[yy * size + xx] shr shift and 0xFF).toFloat()
    return (v(x0, y0) * (1 - fx) + v(x1, y0) * fx) * (1 - fy) + (v(x0, y1) * (1 - fx) + v(x1, y1) * fx) * fy
}

/** Summed-area table (size + 1)² of a predicate over a size² grid. */
private fun summedArea(size: Int, set: (Int) -> Boolean): IntArray {
    val table = IntArray((size + 1) * (size + 1))
    for (y in 0 until size) {
        var row = 0
        for (x in 0 until size) {
            if (set(y * size + x)) row++
            table[(y + 1) * (size + 1) + x + 1] = table[y * (size + 1) + x + 1] + row
        }
    }
    return table
}

/** Count of set cells in [x0, x1) × [y0, y1), clipped to the grid. */
private fun areaSum(table: IntArray, size: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val a = x0.coerceIn(0, size); val b = y0.coerceIn(0, size)
    val c = x1.coerceIn(0, size); val d = y1.coerceIn(0, size)
    if (c <= a || d <= b) return 0
    val s = size + 1
    return table[d * s + c] - table[b * s + c] - table[d * s + a] + table[b * s + a]
}

/** Approximately unit Gaussian from the four bytes of one hash (Irwin–Hall, n = 4). */
private fun gaussian(x: Int, y: Int, seed: Int, salt: Int): Float {
    var h = x * 0x27d4eb2d xor (y * 0x165667b1) xor seed xor (salt * -0x61c88647)
    h = (h xor (h ushr 15)) * 0x2c1b3c6d
    h = (h xor (h ushr 12)) * 0x297a2d39
    h = h xor (h ushr 15)
    val sum = (h and 0xFF) + (h ushr 8 and 0xFF) + (h ushr 16 and 0xFF) + (h ushr 24 and 0xFF)
    return (sum / 255f - 2f) * 1.7320508f
}

/**
 * The 512 px [fill] upsampled bilinearly to grain resolution ×[n], as opaque ARGB over grain pixels
 * [x0] until [x0] + [w] and [y0] until [y0] + [h]; coordinates outside the square are clamped.
 */
internal fun fillAtGrainResolution(fill: IntArray, n: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
    val last = 512 * n - 1
    return IntArray(w * h) { i ->
        val gx = (x0 + i % w).coerceIn(0, last)
        val gy = (y0 + i / w).coerceIn(0, last)
        var argb = 0xFF000000.toInt()
        for (c in 0 until 3) {
            val v = bilinear(fill, 512, c, (gx + 0.5f) / n - 0.5f, (gy + 0.5f) / n - 0.5f)
            argb = argb or ((v + 0.5f).toInt().coerceIn(0, 255) shl (16 - 8 * c))
        }
        argb
    }
}
