package dev.grainlegit.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun FilmStockSheet(
    edit: Edit, generation: Long, renderer: Renderer, seed: Long, loadSource: suspend () -> Bitmap,
    dismiss: () -> Unit, choose: (FilmStock) -> Unit,
) {
    // One and a half thumbnails fit across, so the next stock peeks in to show the row scrolls.
    val visibleCount = 1.5f
    val selected = edit.stock.id
    val firstVisible = filmStocks.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val previews = remember(generation, edit) { mutableStateMapOf<String, ImageBitmap>() }
    var unavailable by remember(generation, edit) { mutableStateOf(false) }
    LaunchedEffect(generation, edit) {
        try {
            val source = loadSource()
            try {
                // Render the options on screen first. The renderer shows each stock at the slider's maximum.
                for (stock in filmStocks.drop(firstVisible) + filmStocks.take(firstVisible)) {
                    previews[stock.id] = renderer.stockPreview(
                        source.copy(Bitmap.Config.ARGB_8888, false), seed, edit.withStock(stock.id),
                    ).asImageBitmap()
                }
            } finally {
                source.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            Log.w("Grainlegit", "Could not render film stock previews", error)
            unavailable = true
        }
    }

    EditorSheet("Film stock", dismiss) {
        if (unavailable) Text("Previews unavailable", color = muted, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            val gap = 8.dp
            val width = (maxWidth - gap) / visibleCount
            LazyRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = firstVisible),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(filmStocks, key = { it.id }) { stock ->
                    val isSelected = stock.id == selected
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.width(width).selectable(isSelected, role = Role.RadioButton) { choose(stock) },
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(shape).background(glassWell)
                                .then(if (isSelected) Modifier.border(2.dp, accent, shape) else Modifier),
                        ) {
                            previews[stock.id]?.let { preview ->
                                Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit)
                            }
                        }
                        Text(stock.name, color = if (isSelected) accent else ink,
                            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp))
                    }
                }
            }
        }
    }
}
