package com.example.island

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val CallGreen = Color(0xFF30D158)
private val CallRed = Color(0xFFFF453A)
private val CallGray = Color(0xFF8E8E93)

private fun callClock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** "Incoming", or the running call time, ticking every second. */
@Composable
private fun callText(): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val start = IslandBus.callStartMs
    return when {
        IslandBus.callIncoming -> "Incoming call"
        start > 0 -> callClock(now - start)
        else -> "On call"
    }
}

/** Collapsed, like the iPhone: a green phone and the call time on the left, a waveform on the right. */
@Composable
internal fun CallCompact() {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Phone, contentDescription = null, tint = CallGreen, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                if (IslandBus.callIncoming) "Incoming" else callText(),
                color = CallGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        CallWave()
    }
}

/** Expanded: who is calling, the time, and the buttons the phone app offers (Answer, Decline, Hang up...). */
@Composable
internal fun CallOpenContent() {
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 0.dp, bottom = 14.dp)) {
        // The top row is as tall as the compact island, so the camera sits in its middle: who is calling on the left,
        // the time and a waveform on the right.
        // A little taller than the camera row, so the text sits between the top edge and the name.
        Row(Modifier.fillMaxWidth().height(COMPACT + 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Phone",
                color = CallGray, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(IslandBus.camGap))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (IslandBus.callIncoming) "Incoming" else callText(),
                    color = CallGray, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                CallWave()
            }
        }
        // The name gets the whole width under the camera, at a comfortable size.
        // Shifted so the space from the "Phone" line to the photo equals the space from the photo to the buttons.
        Row(Modifier.fillMaxWidth().weight(1f).offset(y = -(COMPACT / 4 - 2.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF636366)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    IslandBus.callName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                    color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                IslandBus.callName.ifEmpty { "Unknown" },
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val context = LocalContext.current
        val audio = remember { context.getSystemService(AudioManager::class.java) }
        var muted by remember { mutableStateOf(runCatching { audio.isMicrophoneMute }.getOrDefault(false)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Mute only makes sense once the call is connected, and only where it can work: the phone app (the system
            // microphone switch) or an app that has its own Mute button in its notification.
            val canMute = IslandBus.callMuteIntent != null || IslandBus.isPhoneApp(IslandBus.callPackage) || !IslandBus.callReal
            if (!IslandBus.callIncoming && canMute) {
                val muteSource = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(44.dp).pressScale(muteSource).clip(CircleShape)
                        .background(if (muted) Color.White else Color(0xFF3A3A3C))
                        .clickable(
                            interactionSource = muteSource,
                            indication = null,
                        ) {
                            val own = IslandBus.callMuteIntent
                            if (own != null) {
                                IslandBus.sendPI(own)
                                muted = !muted
                            } else {
                                runCatching { audio.isMicrophoneMute = !muted }
                                muted = runCatching { audio.isMicrophoneMute }.getOrDefault(!muted)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (muted) "Unmute" else "Mute",
                        color = if (muted) Color.Black else Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
            IslandBus.callActions.forEach { action ->
                val actionSource = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(44.dp).pressScale(actionSource).clip(CircleShape)
                        .background(if (action.accept) CallGreen else CallRed)
                        .clickable(
                            interactionSource = actionSource,
                            indication = null,
                        ) {
                            if (action.intent != null) IslandBus.pressCallAction(action) else IslandBus.demoPress(action)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}


/**
 * The iPhone's call waveform: many thin bars that fade from green on the left to orange on the right, bouncing
 * like a voice. Android doesn't let an app hear the call, so the movement is simulated.
 */
@Composable
internal fun CallWave() {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time += (now - last) / 1_000_000_000f
            last = now
        }
    }
    val green = Color(0xFF32D74B)
    val orange = Color(0xFFFF9F0A)
    Canvas(Modifier.size(56.dp, 20.dp)) {
        val bars = 20
        val barW = 1.8.dp.toPx()
        val gap = (size.width - bars * barW) / (bars - 1)
        val brush = Brush.horizontalGradient(listOf(green, Color(0xFFD7E02C), orange), 0f, size.width)
        // Like speech, the whole wave swells and settles in bursts.
        val burst = 0.55f + 0.45f * abs(sin(time * 2.1f) * sin(time * 0.83f + 1f))
        for (i in 0 until bars) {
            val a = sin(time * (5.2f + (i % 5) * 1.3f) + i * 1.7f)
            val b = sin(time * (9.1f - (i % 4) * 1.1f) + i * 2.9f)
            val v = 0.5f + 0.5f * (0.6f * a + 0.4f * b)
            // Smaller at both ends, bigger in the middle.
            val edge = 0.45f + 0.55f * sin(Math.PI.toFloat() * (i + 0.5f) / bars)
            val h = barW + (size.height - barW) * (0.12f + 0.88f * v) * edge * burst
            drawRoundRect(
                brush,
                Offset(i * (barW + gap), (size.height - h) / 2f),
                Size(barW, h),
                CornerRadius(barW / 2f),
            )
        }
    }
}