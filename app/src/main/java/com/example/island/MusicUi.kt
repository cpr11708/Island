package com.example.island

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Pink = Color(0xFFD96C9B)
private val Dim = Color(0xFF8E8E93)
private val Track = Color(0xFF3A3A3C)

/** Compact: album art on the left, a little equaliser on the right. */
@Composable
internal fun MusicCompact() {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(Modifier.size(24.dp), 7.dp)
        Equalizer(IslandBus.mediaPlaying)
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Open, laid out like the iPhone: art, title and artist, a waveform; times either side of the bar; transport buttons. */
@Composable
internal fun MusicOpen() {
    var posMs by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(IslandBus.mediaPosBase, IslandBus.mediaPosTime, IslandBus.mediaPlaying, IslandBus.mediaDuration) {
        while (true) {
            val moved = if (IslandBus.mediaPlaying)
                ((SystemClock.elapsedRealtime() - IslandBus.mediaPosTime) * IslandBus.mediaSpeed).toLong()
            else 0L
            posMs = (IslandBus.mediaPosBase + moved).coerceIn(0L, IslandBus.mediaDuration.coerceAtLeast(0L))
            delay(250)
        }
    }
    val duration = IslandBus.mediaDuration
    val shownFraction = dragFraction ?: if (duration > 0) (posMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = if (duration > 0) (shownFraction * duration).toLong() else 0L

    fun seekTo(fraction: Float) {
        if (duration <= 0) return
        val ms = (fraction.coerceIn(0f, 1f) * duration).toLong()
        IslandBus.mediaSeek(ms)
        // Move the bar straight away instead of waiting for the player to report the new position.
        IslandBus.mediaPosBase = ms
        IslandBus.mediaPosTime = SystemClock.elapsedRealtime()
    }

    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(Modifier.size(58.dp), 13.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    IslandBus.mediaTitle.ifEmpty { "Nothing playing" },
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    IslandBus.mediaArtist,
                    color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Equalizer(IslandBus.mediaPlaying)
        }
        Spacer(Modifier.height(4.dp))
        // Elapsed time, the bar, time left. The bar's touch area is taller than what is drawn.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(shownMs), color = Dim, fontSize = 11.sp, modifier = Modifier.width(34.dp))
            Box(
                Modifier.weight(1f).height(24.dp)
                    .pointerInput(duration) {
                        detectTapGestures { offset -> seekTo(offset.x / size.width) }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                dragFraction?.let { seekTo(it) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        ) { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val barHeight by animateDpAsState(if (dragFraction != null) 8.dp else 5.dp, label = "bar height")
                Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(4.dp)).background(Track)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shownFraction).background(Color.White))
                }
            }
            Text(
                "-" + clock(duration - shownMs), color = Dim, fontSize = 11.sp,
                modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
            )
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Control(Kind.Prev) { IslandBus.mediaPrev() }
            Control(if (IslandBus.mediaPlaying) Kind.Pause else Kind.Play) { IslandBus.mediaPlayPause() }
            Control(Kind.Next) { IslandBus.mediaNext() }
        }
    }
}

@Composable
private fun Art(modifier: Modifier, radius: Dp) {
    val art = IslandBus.mediaArt
    Box(modifier.clip(RoundedCornerShape(radius)).background(Track)) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun Equalizer(active: Boolean, color: Color = IslandBus.mediaAccent) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(
        Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(420, 560, 360, 500).forEach { duration ->
            val h by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar",
            )
            Box(
                Modifier.width(3.dp).fillMaxHeight(if (active) h else 0.25f)
                    .clip(RoundedCornerShape(2.dp)).background(color),
            )
        }
    }
}

private enum class Kind { Play, Pause, Next, Prev }

@Composable
private fun Control(kind: Kind, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val w = size.width
            val h = size.height
            fun tri(x0: Float, x1: Float) = Path().apply {
                // A triangle between x0 (flat side) and x1 (point).
                moveTo(x0, h * .12f); lineTo(x0, h * .88f); lineTo(x1, h / 2); close()
            }
            when (kind) {
                Kind.Play -> drawPath(tri(w * .2f, w * .95f), Color.White)
                Kind.Pause -> {
                    drawRoundRect(Color.White, Offset(w * .18f, 0f), Size(w * .24f, h), CornerRadius(w * .06f))
                    drawRoundRect(Color.White, Offset(w * .58f, 0f), Size(w * .24f, h), CornerRadius(w * .06f))
                }
                // Two triangles in a row, like the iPhone's rewind and fast-forward.
                Kind.Next -> {
                    drawPath(tri(0f, w * .5f), Color.White)
                    drawPath(tri(w * .5f, w), Color.White)
                }
                Kind.Prev -> {
                    drawPath(tri(w * .5f, 0f), Color.White)
                    drawPath(tri(w, w * .5f), Color.White)
                }
            }
        }
    }
}