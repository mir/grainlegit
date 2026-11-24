package dev.grainlegit.editor

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import kotlin.math.exp

private const val TAG = "Grainlegit"

internal enum class Backend(val accelerator: Accelerator) {
    NPU(Accelerator.NPU), GPU(Accelerator.GPU), CPU(Accelerator.CPU)
}

/** Shared LiteRT environment. The Qualcomm dispatch and compiler plugins ship as app native libraries. */
internal class Ml(private val context: Context) : Closeable {
    private val dispatchOptions by lazy {
        mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir)
    }
    private val environment: Environment by lazy {
        Environment.create(
            context,
            dispatchOptions + (Environment.Option.CompilerPluginLibraryDir to context.applicationInfo.nativeLibraryDir),
            enableCompilerCache = true,
        )
    }
    // Packaged bytecode needs only the dispatch library, so this path cannot trigger compilation.
    private val precompiledEnvironment by lazy { Environment.create(dispatchOptions) }
    private val precompiledModels by lazy {
        if (Build.SOC_MODEL.equals("SM8850", ignoreCase = true)) {
            context.assets.list("models/sm8850").orEmpty().toSet()
        } else {
            emptySet()
        }
    }
    private val gpuCacheDirectory by lazy {
        // Android clears the code cache when the APK or platform is updated.
        File(context.codeCacheDir, "litert-gpu").takeIf { it.mkdirs() || it.isDirectory }
    }

    fun load(asset: String, backends: List<Backend>): LoadedModel {
        var failure: Throwable? = null
        for (backend in backends) {
            val candidates = if (backend == Backend.NPU && asset in precompiledModels) {
                listOf("sm8850/$asset", asset)
            } else {
                listOf(asset)
            }
            for (candidate in candidates) {
                val start = SystemClock.elapsedRealtimeNanos()
                try {
                    val loaded = loadAsset(asset, candidate, backend, start)
                    Log.i(TAG, "Loaded $candidate on $backend in ${"%.0f".format(loaded.loadMs)} ms")
                    return loaded
                } catch (error: Throwable) {
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    Log.w(TAG, "Could not load $candidate on $backend after ${"%.0f".format(elapsed)} ms: ${error.message}")
                    failure = error
                }
            }
        }
        throw IllegalStateException("Could not load $asset", failure)
    }

    private fun loadAsset(asset: String, candidate: String, backend: Backend, start: Long): LoadedModel {
        val options = CompiledModel.Options(backend.accelerator)
        when (backend) {
            Backend.NPU -> options.qualcommOptions = CompiledModel.QualcommOptions(
                htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
            )
            Backend.GPU -> {
                val cache = gpuCacheDirectory
                options.gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP16,
                    serializationDir = cache?.absolutePath,
                    modelCacheKey = cache?.let { "$asset-fp16" },
                    serializeProgramCache = cache?.let { true },
                )
            }
            Backend.CPU -> Unit
        }
        if (backend != Backend.CPU) options.disableAutoFallbackToCpu()
        val modelEnvironment = if (candidate == asset) environment else precompiledEnvironment
        val model = CompiledModel.create(context.assets, "models/$candidate", options, modelEnvironment)
        try {
            return LoadedModel(asset, backend, model, start)
        } catch (error: Throwable) {
            model.close()
            throw error
        }
    }

    override fun close() {
        // The environment lives as long as the process; models own their native handles.
    }
}

internal class LoadedModel(
    val asset: String, val backend: Backend, private val model: CompiledModel, loadStartedAt: Long,
) : Closeable {
    val inputs: List<TensorBuffer> = model.createInputBuffers()
    val outputs: List<TensorBuffer> = try {
        model.createOutputBuffers()
    } catch (error: Throwable) {
        inputs.forEach { it.close() }
        throw error
    }
    val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStartedAt) / 1e6
    var lastMs = 0.0
        private set

    fun run(inputs: List<TensorBuffer> = this.inputs) {
        val start = SystemClock.elapsedRealtimeNanos()
        model.run(inputs, outputs)
        lastMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }
}

internal val preferredBackends = listOf(Backend.NPU, Backend.GPU, Backend.CPU)

// ---------------------------------------------------------------------------------------------
// Segmentation: EdgeTAM (SAM 2 family). The Qualcomm export folds the prompt encoder into the
// image encoder, so each prompt reruns the encoder (a few ms on the NPU).

internal data class Prompt(val x: Float, val y: Float, val positive: Boolean = true)

/** Mask logits on a [size]×[size] grid that spans the whole image (squashed to a square). */
internal class Segmentation(val size: Int, val logits: FloatArray, val score: Float, val objectScore: Float) {
    val found get() = objectScore > 0f
    fun probability(index: Int) = 1f / (1f + exp(-logits[index]))
}

internal interface ImageSegmenter : Closeable {
    fun setImage(bitmap: Bitmap)
    fun segment(prompts: List<Prompt>): Segmentation
}

internal class Segmenter(ml: Ml, backends: List<Backend> = preferredBackends) : ImageSegmenter {
    val encoder = ml.load("edgetam_encoder.tflite", backends)
    val decoder = try {
        ml.load("edgetam_decoder.tflite", backends)
    } catch (error: Throwable) {
        encoder.close()
        throw error
    }
    private val image = FloatArray(INPUT * INPUT * 3)
    private var imageReady = false

    /** Resize the whole image to the encoder's square input. Coordinates stay normalized. */
    override fun setImage(bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled !== bitmap) scaled.recycle()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            image[index * 3] = (pixel shr 16 and 0xFF) / 255f
            image[index * 3 + 1] = (pixel shr 8 and 0xFF) / 255f
            image[index * 3 + 2] = (pixel and 0xFF) / 255f
        }
        encoder.inputs[0].writeFloat(image)
        imageReady = true
    }

    /** Points are normalized to the image (0..1). Up to two prompts; a lone point is padded. */
    override fun segment(prompts: List<Prompt>): Segmentation {
        check(imageReady) { "Set an image before segmenting" }
        require(prompts.size in 1..2)
        val coords = FloatArray(4)
        val labels = floatArrayOf(-1f, -1f)
        prompts.forEachIndexed { index, prompt ->
            coords[index * 2] = prompt.x.coerceIn(0f, 1f)
            coords[index * 2 + 1] = prompt.y.coerceIn(0f, 1f)
            labels[index] = if (prompt.positive) 1f else 0f
        }
        encoder.inputs[1].writeFloat(coords)
        encoder.inputs[2].writeFloat(labels)
        encoder.run()
        // Encoder outputs: image_embeddings, high_res_features1, high_res_features2, sparse_embedding, pix_feat.
        // The first (conditioning) frame decodes from pix_feat.
        val outputs = encoder.outputs
        decoder.inputs[0].writeFloat(outputs[4].readFloat())
        decoder.inputs[1].writeFloat(outputs[1].readFloat())
        decoder.inputs[2].writeFloat(outputs[2].readFloat())
        decoder.inputs[3].writeFloat(outputs[3].readFloat())
        decoder.run()
        val masks = decoder.outputs[0].readFloat()
        val score = decoder.outputs[1].readFloat()[0]
        val objectScore = decoder.outputs[3].readFloat()[0]
        return Segmentation(MASK, masks, score, objectScore)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        const val INPUT = 1024
        const val MASK = 256
    }
}

// ---------------------------------------------------------------------------------------------
// Inpainting. Inputs are square ARGB crops and a hole mask (true = fill); output is ARGB.

internal interface Inpainter : Closeable {
    val id: String
    val label: String
    val size: Int
    val backend: Backend
    val lastMs: Double
    val loadMs: Double
    fun inpaint(pixels: IntArray, hole: BooleanArray): IntArray
}

/** MI-GAN (Picsart, MIT). Input NCHW [1,4,512,512] = concat(mask − 0.5, rgb·mask), rgb in [−1,1], mask 1 = keep. */
internal class MiGan(ml: Ml, backends: List<Backend> = preferredBackends) : Inpainter {
    private val model = ml.load("migan.tflite", backends)
    override val id = "migan"
    override val label = "MI-GAN"
    override val size = 512
    override val backend get() = model.backend
    override val lastMs get() = model.lastMs
    override val loadMs get() = model.loadMs
    private val input = FloatArray(4 * size * size)

    override fun inpaint(pixels: IntArray, hole: BooleanArray): IntArray {
        val plane = size * size
        for (index in 0 until plane) {
            val keep = if (hole[index]) 0f else 1f
            val pixel = pixels[index]
            input[index] = keep - 0.5f
            input[plane + index] = ((pixel shr 16 and 0xFF) / 127.5f - 1f) * keep
            input[2 * plane + index] = ((pixel shr 8 and 0xFF) / 127.5f - 1f) * keep
            input[3 * plane + index] = ((pixel and 0xFF) / 127.5f - 1f) * keep
        }
        model.inputs[0].writeFloat(input)
        model.run()
        val output = model.outputs[0].readFloat()
        return IntArray(plane) { index ->
            fun channel(c: Int) = ((output[c * plane + index] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
    }

    override fun close() = model.close()
}

/**
 * Big-LaMa (Apache-2.0, Places2) converted with matmul DFTs. Input NHWC image [1,S,S,3] in [0,1] with the
 * hole zeroed and mask [1,S,S,1] (1 = fill); output NHWC [1,S,S,3] in [0,1].
 */
internal class Lama(ml: Ml, asset: String, override val size: Int, backends: List<Backend> = preferredBackends) : Inpainter {
    private val model = ml.load(asset, backends)
    override val id = "lama"
    override val label = "LaMa"
    override val backend get() = model.backend
    override val lastMs get() = model.lastMs
    override val loadMs get() = model.loadMs
    private val image = FloatArray(size * size * 3)
    private val mask = FloatArray(size * size)

    override fun inpaint(pixels: IntArray, hole: BooleanArray): IntArray {
        for (index in 0 until size * size) {
            val fill = hole[index]
            val pixel = pixels[index]
            mask[index] = if (fill) 1f else 0f
            image[index * 3] = if (fill) 0f else (pixel shr 16 and 0xFF) / 255f
            image[index * 3 + 1] = if (fill) 0f else (pixel shr 8 and 0xFF) / 255f
            image[index * 3 + 2] = if (fill) 0f else (pixel and 0xFF) / 255f
        }
        model.inputs[0].writeFloat(image)
        model.inputs[1].writeFloat(mask)
        model.run()
        val output = model.outputs[0].readFloat()
        return IntArray(size * size) { index ->
            fun channel(c: Int) = (output[index * 3 + c] * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
    }

    override fun close() = model.close()
}
