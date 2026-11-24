package dev.grainlegit.editor

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object NativeRenderer {
    init {
        System.loadLibrary("grainlegit_renderer")
    }

    @JvmStatic external fun mount(handle: Long, surface: Surface): Long
    @JvmStatic external fun resize(handle: Long, width: Int, height: Int)
    @JvmStatic external fun unmount(handle: Long)
    @JvmStatic external fun clear(handle: Long)
    @JvmStatic external fun setSource(handle: Long, bitmap: Bitmap, width: Int, height: Int, seed: Long)
    @JvmStatic external fun setDetail(handle: Long, bitmap: Bitmap, x: Int, y: Int)
    @JvmStatic external fun draw(
        handle: Long, edit: String, compare: Boolean, x: Float, y: Float, scale: Float, useDetail: Boolean,
        previewToSource: FloatArray?,
    )
    @JvmStatic external fun padding(edit: String, edge: Int): Int
    @JvmStatic external fun autoGrainSize(edit: String, width: Int, height: Int): Float
    @JvmStatic external fun exportTile(
        handle: Long, bitmap: Bitmap, x: Int, y: Int, left: Int, top: Int,
        width: Int, height: Int, edge: Int, seed: Long, edit: String,
    ): ByteArray
    @JvmStatic external fun encodeJpeg(
        raw: String, output: String, width: Int, height: Int, cancelled: AtomicBoolean,
    )
    @JvmStatic external fun close(handle: Long)
}

internal data class Frame(
    val generation: Long,
    val center: Offset,
    val scale: Float,
    val edit: String,
    val compare: Boolean,
    val useDetail: Boolean,
    val previewToSource: FloatArray?,
)

internal class Renderer(private val reportError: (String?) -> Unit) : AutoCloseable {
    private data class Source(val generation: Long, val size: IntSize, val seed: Long, val bitmap: Bitmap)

    private val thread = HandlerThread("Grainlegit renderer").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val detailGeneration = AtomicLong()
    private val mounted = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val scheduled = AtomicBoolean()
    private val latest = AtomicReference<Frame>()
    private var handle = 0L
    private var surfaceActive = false
    private var source: Source? = null
    private var uploadedGeneration = 0L
    private var lastFrame: Frame? = null
    private var lastError: String? = null

    fun surfaceCreated(surface: Surface) {
        mounted.set(true)
        worker.post {
            if (mounted.get() && !closed.get()) guarded {
                handle = NativeRenderer.mount(handle, surface)
                surfaceActive = true
                uploadSource()
                report(null)
            }
        }
    }

    fun surfaceChanged(width: Int, height: Int) {
        worker.post {
            if (surfaceActive && mounted.get() && !closed.get()) guarded {
                NativeRenderer.resize(handle, width, height)
                drawLatest()
            }
        }
    }

    fun surfaceDestroyed() {
        mounted.set(false)
        if (closed.get()) return
        // SurfaceHolder requires that native drawing has stopped before this callback returns.
        finishOnWorker {
            if (surfaceActive) NativeRenderer.unmount(handle)
            surfaceActive = false
            lastFrame = null
        }
    }

    fun beginSource(clear: Boolean = true): Long {
        val requested = generation.incrementAndGet()
        worker.post {
            if (requested == generation.get()) guarded {
                source?.bitmap?.recycle()
                source = null
                uploadedGeneration = 0
                if (clear && handle != 0L) {
                    NativeRenderer.clear(handle)
                    if (surfaceActive && mounted.get() && !closed.get()) {
                        NativeRenderer.draw(handle, "{}", false, 0f, 0f, 1f, false, null)
                    }
                }
            }
        }
        return requested
    }

    fun setSource(requested: Long, size: IntSize, bitmap: Bitmap, seed: Long) {
        worker.post {
            if (requested == generation.get() && !closed.get()) guarded {
                source?.bitmap?.recycle()
                source = Source(requested, size, seed, bitmap)
                uploadSource()
                drawLatest()
            } else {
                bitmap.recycle()
            }
        }
    }

    fun requestDetail(): Long = detailGeneration.incrementAndGet()

    fun setDetail(requested: Long, version: Long, bitmap: Bitmap, x: Int, y: Int) {
        worker.post {
            try {
                if (requested == uploadedGeneration && requested == generation.get() &&
                    version == detailGeneration.get() && handle != 0L && !closed.get()
                ) guarded {
                    NativeRenderer.setDetail(handle, bitmap, x, y)
                    Log.d("Grainlegit", "100% crop uploaded ${bitmap.width}×${bitmap.height} at ($x, $y)")
                    drawLatest()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun exportTile(
        bitmap: Bitmap, outer: Rect, inner: Rect, edge: Int, seed: Long, edit: String,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        if (!worker.post {
            try {
                if (continuation.isActive) {
                    check(handle != 0L && !closed.get()) { "The Vulkan renderer is unavailable" }
                    val pixels = NativeRenderer.exportTile(
                        handle, bitmap, outer.left, outer.top, inner.left - outer.left, inner.top - outer.top,
                        inner.width(), inner.height(), edge, seed, edit,
                    )
                    continuation.resume(pixels)
                }
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                bitmap.recycle()
            }
        }) {
            bitmap.recycle()
            continuation.resumeWithException(IllegalStateException("The renderer has stopped"))
        }
    }

    fun request(frame: Frame) {
        if (closed.get() || latest.getAndSet(frame) == frame) return
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        worker.post {
            val frame = latest.get()
            try {
                guarded { draw(frame) }
            } finally {
                scheduled.set(false)
                if (latest.get() != frame && !closed.get()) schedule()
            }
        }
    }

    private fun uploadSource() {
        val pending = source ?: return
        if (handle != 0L && pending.generation == generation.get()) {
            source = null
            try {
                NativeRenderer.setSource(handle, pending.bitmap, pending.size.width, pending.size.height, pending.seed)
                uploadedGeneration = pending.generation
            } finally {
                pending.bitmap.recycle()
            }
        }
    }

    private fun drawLatest() = draw(latest.get())

    private fun draw(frame: Frame?) {
        if (frame == null || frame.generation != generation.get() || !surfaceActive ||
            !mounted.get() || closed.get() || (frame.generation != 0L && uploadedGeneration != frame.generation)
        ) return

        val start = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("Grainlegit preview")
        try {
            NativeRenderer.draw(
                handle, frame.edit, frame.compare, frame.center.x, frame.center.y, frame.scale, frame.useDetail,
                frame.previewToSource,
            )
        } finally {
            Trace.endSection()
        }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        if (lastFrame == null || lastFrame != frame) {
            Log.d("Grainlegit", "Preview GPU completion ${elapsedMs.toInt()} ms zoomed=${frame.useDetail} " +
                "original=${frame.compare} crop=${frame.previewToSource != null}")
        }
        lastFrame = frame
        report(null)
    }

    private fun report(message: String?) {
        if (message == lastError) return
        lastError = message
        main.post { if (!closed.get()) reportError(message) }
    }

    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalStateException) {
            Log.e("Grainlegit", "Renderer failed", error)
            report(error.message ?: "Could not render the preview")
        } catch (error: LinkageError) {
            Log.e("Grainlegit", "Native renderer unavailable", error)
            report("Could not load the native renderer")
        }
    }

    private fun finishOnWorker(action: () -> Unit) {
        val done = CountDownLatch(1)
        worker.post {
            try {
                guarded(action)
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mounted.set(false)
        finishOnWorker {
            source?.bitmap?.recycle()
            source = null
            NativeRenderer.close(handle)
            handle = 0
        }
        thread.quitSafely()
    }
}
