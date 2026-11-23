package dev.grainlegit.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal fun Photo.renderOutput(
    resolver: ContentResolver, layout: GeometryLayout, output: Rect, reader: PhotoRegions? = null,
): Bitmap {
    val region = layout.sourceRegion(output)
    val source = region?.let { reader?.decode(it) ?: decodeDetail(resolver, it) }
    try {
        return layout.render(source, region ?: Rect(), output, IntSize(output.width(), output.height()))
    } finally {
        source?.recycle()
    }
}

internal suspend fun savePhoto(
    context: Context,
    renderer: Renderer,
    photo: Photo,
    layout: GeometryLayout,
    seed: Long,
    edit: String,
    cancelled: AtomicBoolean,
    progress: (Float) -> Unit,
) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val size = layout.output
    require(size.width <= 65535 && size.height <= 65535) { "JPEG dimensions exceed 65535 pixels" }
    val edge = max(size.width, size.height)
    val padding = NativeRenderer.padding(edit, edge)
    val rows = File.createTempFile("grainlegit-", ".rgb", context.cacheDir)
    try {
        val jpeg = File.createTempFile("grainlegit-", ".jpg", context.cacheDir)
        try {
            rows.outputStream().buffered().use { out ->
                photo.openRegions(resolver).use { reader ->
                    // Store one stripe of final RGB rows in RAM and discard each GPU tile after use.
                    val stripeHeight = (12_000_000 / (size.width * 3)).coerceIn(1, 384)
                    for (y in 0 until size.height step stripeHeight) {
                        val bottom = (y + stripeHeight).coerceAtMost(size.height)
                        val stripe = ByteArray(size.width * (bottom - y) * 3)
                        for (x in 0 until size.width step 1024) {
                            ensureActive()
                            val inside = Rect(x, y, (x + 1024).coerceAtMost(size.width), bottom)
                            val outer = inside.copyExpanded(size, padding)
                            val bitmap = photo.renderOutput(resolver, layout, outer, reader)
                            val rgb = renderer.exportTile(bitmap, outer, inside, edge, seed, edit)
                            val rowBytes = inside.width() * 3
                            for (row in 0 until inside.height()) {
                                rgb.copyInto(stripe, (row * size.width + x) * 3, row * rowBytes, (row + 1) * rowBytes)
                            }
                        }
                        out.write(stripe)
                        progress(0.93f * bottom / size.height)
                    }
                }
            }

            ensureActive()
            NativeRenderer.encodeJpeg(rows.absolutePath, jpeg.absolutePath, size.width, size.height, cancelled)
            ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Grainlegit_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Grainlegit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Android could not create the exported photo"
            }
            var published = false
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { out ->
                    jpeg.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
                ensureActive()
                val updated = resolver.update(
                    uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null,
                )
                check(updated == 1) { "Android could not publish the exported photo" }
                published = true
                progress(1f)
            } finally {
                if (!published) resolver.delete(uri, null, null)
            }
        } finally {
            jpeg.delete()
        }
    } finally {
        rows.delete()
    }
}
