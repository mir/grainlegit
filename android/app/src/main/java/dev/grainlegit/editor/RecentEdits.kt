package dev.grainlegit.editor

import android.content.ContentResolver
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

internal data class RecentEdit(val uri: String, val title: String, val seed: Long, val edit: Edit)

internal fun loadRecentEdits(preferences: SharedPreferences): List<RecentEdit> {
    val stored = preferences.getString("recent-edits", null)
    if (stored != null) {
        val entries = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return List(entries.length()) { entries.optJSONObject(it) }.mapNotNull { entry ->
            runCatching {
                checkNotNull(entry)
                RecentEdit(entry.getString("uri"), entry.getString("title"), entry.getLong("seed"),
                    Edit.restore(entry.getString("edit")))
            }.getOrNull()
        }
    }
    val uri = preferences.getString("photo", null) ?: return emptyList()
    val recipe = preferences.getString("recipe", null) ?: return emptyList()
    return runCatching {
        listOf(RecentEdit(uri, "Photo", preferences.getLong("seed", 0L), Edit.restore(recipe)))
    }.getOrDefault(emptyList())
}

internal fun saveRecentEdits(preferences: SharedPreferences, entries: List<RecentEdit>) {
    val encoded = JSONArray()
    entries.forEach {
        encoded.put(JSONObject().put("uri", it.uri).put("title", it.title)
            .put("seed", it.seed).put("edit", it.edit.savedSettings()))
    }
    preferences.edit { putString("recent-edits", encoded.toString()) }
}

internal fun ContentResolver.photoTitle(uri: Uri): String = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }?.let { name ->
    if (name.all(Char::isDigit)) "Photo $name" else name
} ?: "Photo"

@Composable
internal fun RecentEdits(entries: List<RecentEdit>, openPhoto: () -> Unit, resume: (RecentEdit) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Grainlegit", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = openPhoto) { Text("Open photo") }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Recent edits", style = MaterialTheme.typography.titleMedium)
            }
            if (entries.isEmpty()) {
                item { Text("Open a JPEG or HEIF photo to start editing.", color = muted) }
            }
            itemsIndexed(entries, key = { _, entry -> entry.uri }) { index, entry ->
                val card = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(panelColor)
                    .clickable { resume(entry) }
                if (index == 0) {
                    Column(card) {
                        RecentThumbnail(entry.uri, Modifier.fillMaxWidth().aspectRatio(0.9f), 720)
                        RecentCaption(entry, Modifier.padding(14.dp))
                    }
                } else {
                    Row(card.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RecentThumbnail(entry.uri, Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)), 240)
                        RecentCaption(entry, Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCaption(entry: RecentEdit, modifier: Modifier) {
    Column(modifier) {
        Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text("${entry.edit.stock.name}  ·  ${formatNames[entry.edit.format]}", color = muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecentThumbnail(uri: String, modifier: Modifier, edge: Int) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(null, uri, resolver, edge) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri.toUri())) { decoder, info, _ ->
                    val size = info.size
                    val scale = (edge.toFloat() / max(size.width, size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1),
                        (size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(controlColor)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
