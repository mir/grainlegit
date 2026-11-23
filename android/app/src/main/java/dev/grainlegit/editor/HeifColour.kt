package dev.grainlegit.editor

import android.content.ContentResolver
import android.graphics.ColorSpace
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

// Android's HEIF codec can expose sRGB even when the primary item's colour
// property carries an ICC profile. Recover SDR ICC and common SDR nclx profiles.
internal fun readHeifColour(resolver: ContentResolver, uri: Uri): ColorSpace? = runCatching {
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        val channel = stream.channel
        val limit = channel.size()
        var start = 0L
        while (start + 8 <= limit) {
            val header = ByteBuffer.allocate(16)
            channel.position(start)
            while (header.hasRemaining() && channel.read(header) > 0) { }
            header.flip()
            if (header.remaining() < 8) return null
            val shortSize = header.int.toLong() and 0xffff_ffff
            val type = header.int
            val size = when (shortSize) {
                0L -> limit - start
                1L -> header.long
                else -> shortSize
            }
            val headerSize = header.position()
            if (size < headerSize || size > limit - start) return null
            if (type == fourcc("meta") && size <= 4 * 1024 * 1024) {
                val meta = ByteBuffer.allocate(size.toInt() - headerSize)
                channel.position(start + headerSize)
                while (meta.hasRemaining() && channel.read(meta) > 0) { }
                meta.flip()
                return primaryColour(meta)
            }
            start += size
        }
        null
    }
}.getOrNull()

private data class Box(val type: Int, val data: ByteBuffer)

private fun boxes(data: ByteBuffer): List<Box> {
    val result = ArrayList<Box>()
    while (data.remaining() >= 8) {
        val start = data.position()
        val shortSize = data.int.toLong() and 0xffff_ffff
        val type = data.int
        val size = when (shortSize) {
            0L -> (data.limit() - start).toLong()
            1L -> if (data.remaining() >= 8) data.long else break
            else -> shortSize
        }
        if (size < data.position() - start || size > data.limit() - start) break
        val end = start + size.toInt()
        result.add(Box(type, data.slice(data.position(), end - data.position())))
        data.position(end)
    }
    return result
}

private fun List<Box>.box(name: String) = firstOrNull { it.type == fourcc(name) }?.data

private fun primaryColour(meta: ByteBuffer): ColorSpace? {
    if (meta.remaining() < 4) return null
    meta.int // FullBox header
    val children = boxes(meta)
    val primary = children.box("pitm")?.let { data ->
        if ((data.int ushr 24) == 0) data.short.toInt() and 0xffff else data.int
    }
    val properties = children.box("iprp")?.let(::boxes) ?: return null
    val container = properties.box("ipco")?.let(::boxes) ?: return null
    val associated = properties.filter { it.type == fourcc("ipma") }
        .flatMap { indices(it.data, primary) }
        .mapNotNull { container.getOrNull(it - 1) }
    val colours = if (associated.isEmpty()) container.filter { it.type == fourcc("colr") }.takeIf { it.size == 1 }
        else associated.filter { it.type == fourcc("colr") }
    return colours.orEmpty().mapNotNull { colour(it.data) }.sortedByDescending { it.first }.firstOrNull()?.second
}

private fun indices(data: ByteBuffer, primary: Int?): List<Int> {
    if (primary == null) return emptyList()
    val flags = data.int
    val version = flags ushr 24
    val large = flags and 1 == 1
    val entries = data.int
    if (entries < 0 || entries > data.remaining() / 3) return emptyList()
    repeat(entries) {
        val id = if (version == 0) data.short.toInt() and 0xffff else data.int
        val count = data.get().toInt() and 0xff
        val index = List(count) {
            if (large) data.short.toInt() and 0x7fff else data.get().toInt() and 0x7f
        }
        if (id == primary) return index
    }
    return emptyList()
}

private fun colour(data: ByteBuffer): Pair<Boolean, ColorSpace>? {
    return when (data.int) {
        fourcc("prof"), fourcc("rICC") -> icc(data.slice())?.let { true to it }
        fourcc("nclx") -> {
            val primaries = data.short.toInt() and 0xffff
            val transfer = data.short.toInt() and 0xffff
            val named = when {
                primaries == 12 && transfer == 13 -> ColorSpace.Named.DISPLAY_P3
                primaries == 9 && transfer in setOf(1, 14, 15) -> ColorSpace.Named.BT2020
                primaries == 1 && transfer == 13 -> ColorSpace.Named.SRGB
                else -> return null
            }
            false to ColorSpace.get(named)
        }
        else -> null
    }
}

private fun icc(profile: ByteBuffer): ColorSpace? {
    val actualLength = profile.remaining()
    if (actualLength < 132 || profile.getInt(16) != fourcc("RGB ") || profile.getInt(20) != fourcc("XYZ ")) return null
    val length = profile.getInt(0)
    val count = profile.getInt(128)
    if (length !in 132..actualLength || count !in 0..((length - 132) / 12)) return null
    val tags = HashMap<Int, ByteBuffer>()
    for (i in 0 until count) {
        val base = 132 + 12 * i
        val key = profile.getInt(base)
        val offset = profile.getInt(base + 4)
        val size = profile.getInt(base + 8)
        if (offset < 0 || size < 0 || offset > length || size > length - offset) return null
        tags[key] = profile.slice(offset, size)
    }
    val matrix = FloatArray(9)
    for ((index, channel) in listOf("r", "g", "b").withIndex()) {
        val tag = tags[fourcc("${channel}XYZ")] ?: return null
        if (tag.remaining() < 20 || tag.getInt(0) != fourcc("XYZ ")) return null
        for (row in 0..2) matrix[index * 3 + row] = (tag.getInt(8 + row * 4) / 65536.0).toFloat()
    }
    val transfer = tags[fourcc("rTRC")]?.let(::curve) ?: return null
    if (listOf("gTRC", "bTRC").any { tags[fourcc(it)]?.let(::curve) != transfer }) return null
    return ColorSpace.match(matrix, transfer) ?: ColorSpace.Rgb("Embedded HEIF ICC", matrix, transfer)
}

private fun curve(tag: ByteBuffer): ColorSpace.Rgb.TransferParameters? {
    if (tag.remaining() < 12) return null
    fun param(i: Int) = tag.getInt(12 + i * 4) / 65536.0
    return when (tag.getInt(0)) {
        fourcc("para") -> when (tag.getShort(8).toInt()) {
            0 -> ColorSpace.Rgb.TransferParameters(1.0, 0.0, 0.0, 0.0, param(0))
            3 -> ColorSpace.Rgb.TransferParameters(param(1), param(2), param(3), param(4), param(0))
            4 -> ColorSpace.Rgb.TransferParameters(param(1), param(2), param(3), param(4), param(5), param(6), param(0))
            else -> null
        }
        fourcc("curv") -> when (tag.getInt(8)) {
            0 -> ColorSpace.Rgb.TransferParameters(1.0, 0.0, 0.0, 0.0, 1.0)
            1 -> ColorSpace.Rgb.TransferParameters(1.0, 0.0, 0.0, 0.0,
                (tag.getShort(12).toInt() and 0xffff) / 256.0)
            else -> null
        }
        else -> null
    }
}

private fun fourcc(name: String): Int = name.fold(0) { value, char -> (value shl 8) or char.code }
