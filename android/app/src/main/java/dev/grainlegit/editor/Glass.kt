package dev.grainlegit.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// The photo preview, recorded so floating controls can refract it.
internal val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

// Dark enough that white labels stay readable over bright photos.
private val glassTint = Color(0xFF121212).copy(alpha = 0.42f)

// A frosted panel with refracting edges. Content is clipped to the panel shape.
@Composable
internal fun Modifier.glassPanel(cornerRadius: Dp = 26.dp): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val shape = remember(cornerRadius) { RoundedRectangle(cornerRadius) }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(12.dp.toPx())
            lens(16.dp.toPx(), 28.dp.toPx())
        },
        onDrawSurface = { drawRect(glassTint) },
    ).clip(shape)
}

internal data class GlassTab(val title: String, val symbol: EditorSymbol)

private val tabBarHeight = 64.dp
private val tabPadding = 4.dp
private val pressSpec = spring(1f, 1000f, 0.001f)
private val moveSpec = spring(0.8f, 500f, 0.0005f)

// A capsule tab bar whose selection is a glass droplet: it swells and refracts the tabs while
// moving, and can be dragged between tabs.
@Composable
internal fun GlassTabBar(
    tabs: List<GlassTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val backdrop = LocalGlassBackdrop.current
    val tabsBackdrop = rememberLayerBackdrop()
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    BoxWithConstraints(modifier.fillMaxWidth().height(tabBarHeight), contentAlignment = Alignment.CenterStart) {
        val padding = with(LocalDensity.current) { tabPadding.toPx() }
        val tabWidth = (constraints.maxWidth - 2 * padding) / tabs.size
        val last = tabs.lastIndex.toFloat()
        val scope = rememberCoroutineScope()
        val position = remember { Animatable(selected.toFloat()) }
        val press = remember { Animatable(0f, 0.001f) }
        fun moveTo(index: Int) {
            scope.launch {
                launch { press.animateTo(1f, pressSpec) }
                position.animateTo(index.toFloat(), moveSpec)
                press.animateTo(0f, pressSpec)
            }
        }
        LaunchedEffect(selected) { if (position.targetValue != selected.toFloat()) moveTo(selected) }

        val capsule = remember { Capsule() }
        val content: @Composable RowScope.(Color, Boolean) -> Unit = { color, magnify ->
            tabs.forEachIndexed { index, tab ->
                val tabModifier = if (magnify) Modifier.graphicsLayer {
                    val scale = lerp(1f, 1.15f, press.value)
                    scaleX = scale
                    scaleY = scale
                } else Modifier.selectable(selected = index == selected, role = Role.Tab, onClick = { latestSelect(index) })
                Column(
                    Modifier.weight(1f).fillMaxHeight().then(tabModifier),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph(tab.symbol, color, Modifier.size(22.dp))
                    Text(tab.title, color = color, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (magnify) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }

        // The resting bar.
        Row(
            Modifier.fillMaxWidth().fillMaxHeight()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { capsule },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    layerBlock = {
                        val scale = lerp(1f, 1f + 12.dp.toPx() / size.width, press.value)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(glassTint) },
                )
                .pointerInput(tabWidth) {
                    // Drag the droplet between tabs; taps fall through to the tabs.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!dragging && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                dragging = true
                                scope.launch { press.animateTo(1f, pressSpec) }
                            }
                            if (dragging) {
                                val delta = change.positionChange().x / tabWidth
                                scope.launch { position.snapTo((position.value + delta).coerceIn(0f, last)) }
                                change.consume()
                            }
                        }
                        if (dragging) {
                            val target = position.value.roundToInt()
                            if (latestSelected != target) latestSelect(target)
                            scope.launch {
                                position.animateTo(target.toFloat(), moveSpec)
                                press.animateTo(0f, pressSpec)
                            }
                        }
                    }
                }
                .padding(horizontal = tabPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(muted, false) }

        // A brighter copy of the bar that only shows through the droplet.
        Row(
            Modifier.clearAndSetSemantics {}.graphicsLayer { alpha = 0f }
                .layerBackdrop(tabsBackdrop)
                .fillMaxWidth().fillMaxHeight()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { capsule },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx() * press.value, 24.dp.toPx() * press.value)
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(glassTint) },
                )
                .padding(horizontal = tabPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(accent, true) }

        Box(
            Modifier
                .graphicsLayer { translationX = padding + position.value * tabWidth }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { capsule },
                    effects = {
                        lens(10.dp.toPx() * press.value, 14.dp.toPx() * press.value, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.35f + 0.65f * press.value) },
                    shadow = { Shadow(alpha = press.value) },
                    innerShadow = { InnerShadow(radius = 8.dp * press.value, alpha = press.value) },
                    layerBlock = {
                        val scale = lerp(1f, 78f / 56f, press.value)
                        val velocity = (position.velocity / 10f).coerceIn(-1f, 1f)
                        scaleX = scale / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                        scaleY = scale * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.12f), alpha = 1f - press.value)
                    },
                )
                .size(with(LocalDensity.current) { tabWidth.toDp() }, tabBarHeight - tabPadding * 2),
        )
    }
}

private val thumbMoveSpec = spring(1f, 1000f, 0.0005f)
private val thumbSize = androidx.compose.ui.unit.DpSize(40.dp, 24.dp)

// The slider thumb: a white pill at rest that turns into a magnifying glass lens while held.
// [track] is the ruler drawn beneath it; [position] is the thumb's place on the track, 0..1.
@Composable
internal fun GlassThumb(track: com.kyant.backdrop.backdrops.LayerBackdrop, position: Float, press: () -> Float, trackWidth: Float) {
    val backdrop = LocalGlassBackdrop.current
    val capsule = remember { Capsule() }
    val moving = remember { Animatable(position) }
    val target by rememberUpdatedState(position)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { target }.collect { moving.animateTo(it, thumbMoveSpec) }
    }
    val magnifiedTrack = com.kyant.backdrop.backdrops.rememberBackdrop(track) { drawTrack ->
        // At rest the ruler is hidden behind the white pill; pressed, it shows through at full size.
        val progress = press()
        scale(lerp(2f / 3f, 1f, progress), progress) { drawTrack() }
    }
    Box(
        Modifier
            .graphicsLayer {
                translationX = (-size.width / 2f + trackWidth * moving.value)
                    .coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f)
            }
            .drawBackdrop(
                backdrop = rememberCombinedBackdrop(backdrop, magnifiedTrack),
                shape = { capsule },
                effects = {
                    val latestPress = press()
                    blur(8.dp.toPx() * (1f - latestPress))
                    lens(10.dp.toPx() * latestPress, 14.dp.toPx() * latestPress, chromaticAberration = true)
                },
                highlight = {
                    Highlight.Ambient.copy(
                        width = Highlight.Ambient.width / 1.5f,
                        blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                        alpha = press(),
                    )
                },
                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.3f)) },
                innerShadow = { press().let { InnerShadow(radius = 4.dp * it, alpha = it) } },
                layerBlock = {
                    val scale = lerp(1f, 1.5f, press())
                    val velocity = moving.velocity / 10f
                    scaleX = scale / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                    scaleY = scale * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - press())) },
            )
            .size(thumbSize),
    )
}
