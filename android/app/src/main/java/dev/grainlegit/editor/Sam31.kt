package dev.grainlegit.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** SM8850 precompiled vision encoder and the CPU decoder from the SAM 3.1 click export. */
internal class Sam31 private constructor(
    private val runtime: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : ImageSegmenter {
    private var features: OrtSession.Result? = null

    override fun setImage(bitmap: Bitmap) {
        features?.close()
        features = null
        val start = SystemClock.elapsedRealtimeNanos()
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val area = INPUT * INPUT
        val pixels = IntArray(area)
        try {
            scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val planar = ByteBuffer.allocateDirect(3 * area * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (shift in intArrayOf(16, 8, 0)) {
            for (pixel in pixels) planar.put((pixel shr shift and 255) * (2f / 255f) - 1f)
        }
        planar.rewind()
        OnnxTensor.createTensor(runtime, planar, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { image ->
            features = encoder.run(mapOf("image" to image))
        }
        Log.i(TAG, "SAM 3.1 encoded photo on NPU in ${elapsed(start)} ms")
    }

    override fun segment(prompts: List<Prompt>): Segmentation {
        val encoded = checkNotNull(features) { "Set an image before segmenting" }
        require(prompts.isNotEmpty())
        val coords = FloatArray(prompts.size * 2)
        val labels = FloatArray(prompts.size)
        prompts.forEachIndexed { index, prompt ->
            coords[2 * index] = prompt.x.coerceIn(0f, 1f) * INPUT
            coords[2 * index + 1] = prompt.y.coerceIn(0f, 1f) * INPUT
            labels[index] = if (prompt.positive) 1f else 0f
        }
        val count = prompts.size.toLong()
        OnnxTensor.createTensor(runtime, FloatBuffer.wrap(coords), longArrayOf(1, count, 2)).use { points ->
            OnnxTensor.createTensor(runtime, FloatBuffer.wrap(labels), longArrayOf(1, count)).use { pointLabels ->
                val inputs = mutableMapOf<String, OnnxTensorLike>("points" to points, "labels" to pointLabels)
                for ((name, feature) in encoded) inputs[name] = feature as OnnxTensor
                decoder.run(inputs).use { result ->
                    val scores = (result.get("ious").orElseThrow() as OnnxTensor).floatBuffer
                    // A single click uses the three multimask outputs. Multiple prompts use mask zero.
                    val best = if (prompts.size == 1) (1..3).maxBy { scores.get(it) } else 0
                    val masks = (result.get("masks").orElseThrow() as OnnxTensor).floatBuffer
                    val objectScore = (result.get("object").orElseThrow() as OnnxTensor).floatBuffer.get(0)
                    val logits = FloatArray(MASK * MASK)
                    masks.position(best * logits.size)
                    masks.get(logits)
                    return Segmentation(MASK, logits, scores.get(best), objectScore)
                }
            }
        }
    }

    override fun close() {
        features?.close()
        decoder.close()
        encoder.close()
    }

    companion object {
        private const val TAG = "Grainlegit"
        private const val INPUT = 1008
        private const val MASK = 288
        private const val ASSETS = "models/sm8850/sam31"
        private const val ENCODER = "encoder-sm8850.onnx"
        private const val CONTEXT = "encoder-sm8850_qnn.bin"
        private const val DECODER = "click-decoder.onnx"

        fun available(context: Context): Boolean =
            Build.SOC_MODEL.equals("SM8850", ignoreCase = true) &&
                context.assets.list(ASSETS).orEmpty().toSet().containsAll(listOf(ENCODER, CONTEXT, DECODER))

        fun open(context: Context): Sam31 {
            val start = SystemClock.elapsedRealtimeNanos()
            val modelDir = stageAssets(context)
            val runtime = OrtEnvironment.getEnvironment()
            val npu = Qnn.npu(File(context.applicationInfo.nativeLibraryDir))
            val encoder = OrtSession.SessionOptions().use { options ->
                options.addExecutionProvider(listOf(npu), mapOf(
                    "backend_type" to "htp",
                    "htp_performance_mode" to "burst",
                ))
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                runtime.createSession(File(modelDir, ENCODER).absolutePath, options)
            }
            try {
                val decoder = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(4)
                    runtime.createSession(File(modelDir, DECODER).absolutePath, options)
                }
                Log.i(TAG, "Loaded precompiled SAM 3.1 on NPU in ${elapsed(start)} ms")
                return Sam31(runtime, encoder, decoder)
            } catch (error: Throwable) {
                encoder.close()
                throw error
            }
        }

        // QNN opens the context next to the ONNX wrapper. Stage each signed APK asset once.
        private fun stageAssets(context: Context): File {
            val root = File(context.noBackupFilesDir, "sam31-sm8850")
            val revision = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val models = File(root, revision)
            root.listFiles()?.filter { it != models }?.forEach { it.deleteRecursively() }
            check(models.isDirectory || models.mkdirs()) { "Could not create the selection model directory" }
            for (name in listOf(CONTEXT, DECODER, ENCODER)) {
                val asset = "$ASSETS/$name"
                val file = File(models, name)
                val size = context.assets.openFd(asset).use { it.length }
                if (file.isFile && file.length() == size) continue
                val temporary = File(models, "$name.part")
                try {
                    context.assets.open(asset).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                    }
                    check(temporary.length() == size && temporary.renameTo(file)) { "Could not stage $name" }
                } finally {
                    temporary.delete()
                }
            }
            return models
        }

        private fun elapsed(start: Long) = SystemClock.elapsedRealtime() - start / 1_000_000
    }
}
