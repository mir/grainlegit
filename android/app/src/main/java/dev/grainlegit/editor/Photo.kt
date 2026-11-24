package dev.grainlegit.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

internal const val FIT_LONG_EDGE = 1536
private val srgb = ColorSpace.get(ColorSpace.Named.SRGB)

internal data class Photo(
    val uri: Uri,
    val size: IntSize,
    private val rawToOriented: Matrix,
    private val heif: Boolean,
    private val embeddedColour: ColorSpace?,
) {
    fun openRegions(resolver: ContentResolver): PhotoRegions {
        val fallback: ((Rect) -> Bitmap)? = if (heif) ({ region ->
            decodeSrgb(resolver, uri, embeddedColour) { decoder -> decoder.setCrop(region) }
        }) else null
        val fd = resolver.openFileDescriptor(uri, "r")
        if (fd != null) {
            try {
                return PhotoRegions(BitmapRegionDecoder.newInstance(fd), fd, rawToOriented, embeddedColour, fallback)
            } catch (_: IOException) {
                fd.close()
            }
        }
        val stream = checkNotNull(resolver.openInputStream(uri)) { "Android could not open the photo" }
        try {
            val decoder = checkNotNull(BitmapRegionDecoder.newInstance(stream)) {
                "Android could not open the photo region decoder"
            }
            return PhotoRegions(decoder, stream, rawToOriented, embeddedColour, fallback)
        } catch (error: Exception) {
            stream.close()
            throw error
        }
    }

    fun decodeDetail(resolver: ContentResolver, oriented: Rect): Bitmap =
        openRegions(resolver).use { it.decode(oriented) }
}

internal class PhotoRegions(
    private val decoder: BitmapRegionDecoder,
    private val source: Closeable,
    private val rawToOriented: Matrix,
    private val embeddedColour: ColorSpace?,
    private val fallback: ((Rect) -> Bitmap)?,
) : Closeable {
    private val inverse = Matrix().also {
        check(rawToOriented.invert(it)) { "The photo orientation is invalid" }
    }

    fun decode(oriented: Rect, sampleSize: Int = 1): Bitmap {
        val bounds = RectF(oriented)
        inverse.mapRect(bounds)
        val raw = Rect(
            bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.right.roundToInt(), bounds.bottom.roundToInt(),
        )
        val decoded = decoder.decodeRegion(raw, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
            inMutable = true
            if (embeddedColour == null) inPreferredColorSpace = srgb
        })
        // Some HEIF codecs accept a whole image but decline arbitrary regions.
        if (decoded == null && fallback != null) return fallback.invoke(oriented)
        val bitmap = checkNotNull(decoded) { "Android could not decode the visible photo region" }
        if (rawToOriented.isIdentity) return bitmap.toSrgb(embeddedColour)
        val result = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, rawToOriented, false,
        )
        if (result !== bitmap) bitmap.recycle()
        return result.toSrgb(embeddedColour)
    }

    override fun close() {
        try { decoder.recycle() } finally { source.close() }
    }
}

internal data class ImportedPhoto(val photo: Photo, val bitmap: Bitmap)

internal fun importPhoto(resolver: ContentResolver, uri: Uri): ImportedPhoto {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outMimeType in setOf("image/jpeg", "image/heic", "image/heif") && bounds.outWidth > 0 && bounds.outHeight > 0) {
        "Choose a JPEG or HEIF photo"
    }

    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = jpegOrientation(orientation, bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
    val oriented = RectF(0f, 0f, bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
    matrix.mapRect(oriented)
    val heif = bounds.outMimeType != "image/jpeg"
    val embeddedColour = if (heif) readHeifColour(resolver, uri) else null
    val photo = Photo(uri, IntSize(oriented.width().roundToInt(), oriented.height().roundToInt()),
        matrix, heif, embeddedColour)

    val bitmap = decodeSrgb(resolver, uri, embeddedColour) { decoder ->
        val ratio = (FIT_LONG_EDGE.toFloat() / max(photo.size.width, photo.size.height)).coerceAtMost(1f)
        decoder.setTargetSize(
            (photo.size.width * ratio).roundToInt().coerceAtLeast(1),
            (photo.size.height * ratio).roundToInt().coerceAtLeast(1),
        )
    }
    return ImportedPhoto(photo, bitmap)
}

private fun decodeSrgb(
    resolver: ContentResolver, uri: Uri, embeddedColour: ColorSpace?, configure: (ImageDecoder) -> Unit,
): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    if (embeddedColour == null) decoder.setTargetColorSpace(srgb)
    configure(decoder)
}.toSrgb(embeddedColour)

private fun Bitmap.toSrgb(embeddedColour: ColorSpace?): Bitmap {
    if (embeddedColour != null && !embeddedColour.isSrgb && colorSpace?.isSrgb != false) {
        setColorSpace(embeddedColour)
    }
    if (config == Bitmap.Config.ARGB_8888 && colorSpace?.isSrgb == true) return this
    val converted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(converted).drawBitmap(this, 0f, 0f, null)
    recycle()
    return converted
}

private fun jpegOrientation(orientation: Int, width: Float, height: Float): Matrix {
    val values = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f)
        ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height)
        ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
        ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f)
        ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, height, -1f, 0f, width)
        ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width)
        else -> return Matrix()
    }
    return Matrix().apply { setValues(values + floatArrayOf(0f, 0f, 1f)) }
}
