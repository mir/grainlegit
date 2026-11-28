package dev.grainlegit.editor

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File

// Exports record the grain they were rendered with, as XMP. Erasing on a reopened export then draws
// the same grain model over the fill instead of matching noise to the surroundings.

private const val NAMESPACE = "https://grainlegit.dev/ns/xmp/1.0/"

/** Grain settings of an exported photo: the native film settings, the seed and the rendered long edge. */
internal data class BakedGrain(val film: JSONObject, val seed: Long, val edge: Int) {
    /** Native edit JSON for the grain and for the same film without grain, which keeps its tone mapping. */
    fun grainy(): String = JSONObject().put("film", film).toString()
    fun clean(): String = JSONObject().put("film", JSONObject(film.toString()).put("grain", 0.0)).toString()
}

/** Write the grain of [edit] (native settings JSON) into the JPEG at [jpeg]. */
internal fun writeBakedGrain(jpeg: File, edit: String, seed: Long, edge: Int) {
    val film = JSONObject(edit).optJSONObject("film") ?: return
    if (film.optDouble("grain", 1.0) <= 1e-6) return
    val xmp = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>""" +
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description rdf:about="" xmlns:grainlegit="$NAMESPACE" grainlegit:Seed="$seed" grainlegit:Edge="$edge" """ +
        """grainlegit:Film="${escape(film.toString())}"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
    ExifInterface(jpeg.absolutePath).apply {
        setAttribute(ExifInterface.TAG_XMP, xmp)
        saveAttributes()
    }
}

/** The grain recorded in a Grainlegit export at [uri], or null for any other photo. */
internal fun readBakedGrain(resolver: ContentResolver, uri: Uri): BakedGrain? = try {
    val xmp = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_XMP) }
    if (xmp == null || NAMESPACE !in xmp) null else {
        fun attribute(name: String) = Regex("""grainlegit:$name="([^"]*)"""").find(xmp)?.groupValues?.get(1)
        val film = attribute("Film")?.let { JSONObject(unescape(it)) }
        val seed = attribute("Seed")?.toLongOrNull()
        val edge = attribute("Edge")?.toIntOrNull()
        if (film == null || seed == null || edge == null || edge <= 0) null else BakedGrain(film, seed, edge)
    }
} catch (error: Exception) {
    android.util.Log.w("Grainlegit", "Could not read the photo's grain metadata", error)
    null
}

private fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
