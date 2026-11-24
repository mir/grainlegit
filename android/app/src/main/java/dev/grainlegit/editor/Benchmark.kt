package dev.grainlegit.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

private const val TAG = "GrainlegitBench"

/**
 * Developer benchmark for the object-removal models, started with
 * `adb shell am start -n dev.grainlegit.editor/.MainActivity --ez benchmark true`.
 * Put a test photo at files/bench.jpg in the app's external storage to use real content.
 */
internal fun runModelBenchmark(context: Context) {
    val ml = Ml(context)
    val directory = checkNotNull(context.getExternalFilesDir(null))
    val photo = File(directory, "bench.jpg").takeIf { it.exists() }
        ?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: syntheticPhoto()
    Log.i(TAG, "Photo ${photo.width}×${photo.height}")

    val crop = Bitmap.createScaledBitmap(photo, 512, 512, true)
    val pixels = IntArray(512 * 512).also { crop.getPixels(it, 0, 512, 0, 0, 512, 512) }
    val hole = BooleanArray(512 * 512) { index ->
        hypot((index % 512 - 256).toDouble(), (index / 512 - 256).toDouble()) < 90
    }

    var reference: IntArray? = null
    var referenceMask: FloatArray? = null
    for (backend in listOf(Backend.CPU, Backend.GPU, Backend.NPU)) {
        runCatching {
            MiGan(ml, listOf(backend)).use { model ->
                var result = model.inpaint(pixels, hole)
                val times = timed(10) { result = model.inpaint(pixels, hole) }
                val diff = reference?.let { meanDifference(it, result) }
                if (backend == Backend.CPU) reference = result
                save(directory, "migan-${backend.name.lowercase()}.png", result, 512)
                Log.i(TAG, "MI-GAN $backend load ${ms(model.loadMs)} run median ${ms(times.median())} " +
                    "min ${ms(times.min())} model ${ms(model.lastMs)} diffVsCpu ${diff?.let { "%.2f".format(it) }}")
            }
        }.onFailure { Log.e(TAG, "MI-GAN $backend failed", it) }

        runCatching {
            Segmenter(ml, listOf(backend)).use { segmenter ->
                val start = SystemClock.elapsedRealtimeNanos()
                segmenter.setImage(photo)
                val prep = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val prompt = listOf(Prompt(0.5f, 0.5f))
                var mask = segmenter.segment(prompt)
                val times = timed(10) { mask = segmenter.segment(prompt) }
                val positive = mask.logits.count { it > 0f }.toFloat() / mask.logits.size
                val iou = referenceMask?.let { iou(it, mask.logits) }
                if (backend == Backend.CPU) referenceMask = mask.logits
                save(directory, "edgetam-${backend.name.lowercase()}.png",
                    IntArray(mask.logits.size) { if (mask.logits[it] > 0f) -1 else 0xFF000000.toInt() }, mask.size)
                Log.i(TAG, "EdgeTAM $backend load ${ms(segmenter.encoder.loadMs + segmenter.decoder.loadMs)} " +
                    "prep ${ms(prep)} segment median ${ms(times.median())} (encoder ${ms(segmenter.encoder.lastMs)}, " +
                    "decoder ${ms(segmenter.decoder.lastMs)}) score ${"%.3f".format(mask.score)} " +
                    "object ${"%.2f".format(mask.objectScore)} area ${"%.3f".format(positive)} iouVsCpu ${iou?.let { "%.3f".format(it) }}")
            }
        }.onFailure { Log.e(TAG, "EdgeTAM $backend failed", it) }
    }
    Log.i(TAG, "Benchmark finished")
}

private fun timed(count: Int, action: () -> Unit): List<Double> = List(count) {
    val start = SystemClock.elapsedRealtimeNanos()
    action()
    (SystemClock.elapsedRealtimeNanos() - start) / 1e6
}

private fun List<Double>.median() = sorted()[size / 2]
private fun ms(value: Double) = "%.1f ms".format(value)

private fun meanDifference(a: IntArray, b: IntArray): Double {
    var total = 0L
    for (index in a.indices) {
        for (shift in intArrayOf(0, 8, 16)) total += abs((a[index] shr shift and 0xFF) - (b[index] shr shift and 0xFF))
    }
    return total.toDouble() / (a.size * 3)
}

private fun iou(a: FloatArray, b: FloatArray): Double {
    var both = 0
    var either = 0
    for (index in a.indices) {
        val x = a[index] > 0f
        val y = b[index] > 0f
        if (x && y) both++
        if (x || y) either++
    }
    return if (either == 0) 1.0 else both.toDouble() / either
}

private fun save(directory: File, name: String, pixels: IntArray, size: Int) {
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

private fun syntheticPhoto(): Bitmap {
    val size = 1024
    val pixels = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        val inside = hypot((x - size / 2).toDouble(), (y - size / 2).toDouble()) < 200
        if (inside) 0xFFD04030.toInt() else (0xFF000000.toInt() or ((x / 4) shl 8) or (y / 4))
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
