package com.example.island

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.sin
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.asAndroidBitmap
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
    CamRow(
        Modifier.fillMaxSize(), pad = 10.dp,
        left = { Art(Modifier.size(24.dp), 7.dp) },
        right = { Equalizer(IslandBus.mediaPlaying) },
    )
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

/**
 * The iPhone's music waveform: five thin rounded bars that rise and fall around the middle line, each on its own
 * slow-and-quick rhythm so it looks like a real signal, not a metronome. Paused, the bars settle into a row of dots.
 */
@Composable
internal fun Equalizer(active: Boolean, color: Color = IslandBus.mediaAccent) {
    var time by remember { mutableFloatStateOf(0f) }
    val level by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "wave")
    LaunchedEffect(active) {
        if (active) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    Canvas(Modifier.size(25.dp, 20.dp)) {
        val bars = 5
        val barW = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val startX = (size.width - (bars * barW + (bars - 1) * gap)) / 2f
        for (i in 0 until bars) {
            // Two sine waves per bar, at different speeds, added together: smooth and never quite repeating.
            val a = sin(time * (4.6f + i * 1.15f) + i * 1.9f)
            val b = sin(time * (7.9f - i * 0.8f) + i * 3.1f)
            val v = 0.5f + 0.5f * (0.62f * a + 0.38f * b)
            val h = barW + (size.height - barW) * (0.18f + 0.82f * v) * level
            drawRoundRect(
                color,
                Offset(startX + i * (barW + gap), (size.height - h) / 2f),
                Size(barW, h),
                CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * The lock screen player, styled like the iPhone's: cover and song on top with the waveform, a thin progress bar
 * with the times underneath, and big white transport buttons.
 */
@Composable
internal fun LockMusicCard(onMinimize: () -> Unit = {}) {
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
    val fraction = dragFraction ?: if (duration > 0) (posMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = if (duration > 0) (fraction * duration).toLong() else 0L
    val soft = Color.White.copy(alpha = 0.6f)

    fun seekTo(f: Float) {
        if (duration <= 0) return
        val ms = (f.coerceIn(0f, 1f) * duration).toLong()
        IslandBus.mediaSeek(ms)
        IslandBus.mediaPosBase = ms
        IslandBus.mediaPosTime = SystemClock.elapsedRealtime()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(
                    Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { IslandBus.openMusicApp() },
                    11.dp,
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        IslandBus.mediaTitle.ifEmpty { "Not playing" },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(IslandBus.mediaArtist, color = soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Equalizer(IslandBus.mediaPlaying, Color.White)
            }
            Spacer(Modifier.height(4.dp))
            // The times sit either side of the bar (the touch area is taller than the drawn bar).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(shownMs), color = soft, fontSize = 12.sp, modifier = Modifier.widthIn(min = 34.dp))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.weight(1f).height(26.dp)
                        .pointerInput(duration) { detectTapGestures { offset -> seekTo(offset.x / size.width) } }
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
                    // Like the iPhone's lock screen: the bar rests greyed out, and lights up white while you touch it.
                    val touched = dragFraction != null
                    val barHeight by animateDpAsState(if (touched) 9.dp else 5.dp, label = "lock bar")
                    val lit by animateFloatAsState(if (touched) 1f else 0f, tween(200), label = "lock bar lit")
                    Box(
                        Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.16f + 0.14f * lit)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color.White.copy(alpha = 0.5f + 0.45f * lit)))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("-" + clock(duration - shownMs), color = soft, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 38.dp))
            }
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Control(Kind.Prev, 30.dp) { IslandBus.mediaPrev() }
                Control(if (IslandBus.mediaPlaying) Kind.Pause else Kind.Play, 38.dp) { IslandBus.mediaPlayPause() }
                Control(Kind.Next, 30.dp) { IslandBus.mediaNext() }
            }
        }
        // A small handle at the top: tap it, or swipe the player down, to shrink it.
        Box(
            Modifier.align(Alignment.TopCenter).size(84.dp, 22.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onMinimize,
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.32f)))
        }
    }
}

/** The shrunk lock screen player: a small pill with the song, a progress ring round the art, and play/pause. */
@Composable
internal fun LockMusicMini(onExpand: () -> Unit) {
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(IslandBus.mediaPosBase, IslandBus.mediaPosTime, IslandBus.mediaPlaying, IslandBus.mediaDuration) {
        while (true) {
            val d = IslandBus.mediaDuration
            val moved = if (IslandBus.mediaPlaying)
                ((SystemClock.elapsedRealtime() - IslandBus.mediaPosTime) * IslandBus.mediaSpeed).toLong()
            else 0L
            fraction = if (d > 0) ((IslandBus.mediaPosBase + moved).toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }
    val soft = Color.White.copy(alpha = 0.6f)
    // Tap the song to open the full player again.
    Row(
        Modifier.fillMaxSize().padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExpand,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The album art with the song's progress drawn as a ring around it.
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Art(
                    Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { IslandBus.openMusicApp() },
                    18.dp,
                )
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.6.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        Color.White.copy(alpha = 0.2f), 0f, 360f, false,
                        Offset(inset, inset), arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                    )
                    drawArc(
                        Color.White.copy(alpha = 0.9f), -90f, 360f * fraction, false,
                        Offset(inset, inset), arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    IslandBus.mediaTitle.ifEmpty { "Not playing" },
                    color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(IslandBus.mediaArtist, color = soft, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Control(if (IslandBus.mediaPlaying) Kind.Pause else Kind.Play, 22.dp) { IslandBus.mediaPlayPause() }
    }
}

/** The main colour of the album art (colourful, bright parts count most): used for the lock screen player's glow. */
internal fun artGlowColor(art: androidx.compose.ui.graphics.ImageBitmap?): Color {
    val fallback = Color(0xFF3A3A44)
    if (art == null) return fallback
    return runCatching {
        val src = art.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val small = android.graphics.Bitmap.createScaledBitmap(src, 16, 16, true)
        val hsv = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val w = (hsv[1] * hsv[2]).toDouble() + 0.02
            r += android.graphics.Color.red(p) * w
            g += android.graphics.Color.green(p) * w
            b += android.graphics.Color.blue(p) * w
            total += w
        }
        Color((r / total).toInt().coerceIn(0, 255), (g / total).toInt().coerceIn(0, 255), (b / total).toInt().coerceIn(0, 255))
    }.getOrDefault(fallback)
}

private enum class Kind { Play, Pause, Next, Prev }

@Composable
private fun Control(kind: Kind, glyph: Dp = 28.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(glyph + 20.dp).pressScale(source).clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(glyph)) {
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