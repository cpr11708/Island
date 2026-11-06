package com.example.island

import android.media.AudioManager
import androidx.compose.foundation.background
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
        Equalizer(true, CallGreen)
    }
}

/** Expanded: who is calling, the time, and the buttons the phone app offers (Answer, Decline, Hang up...). */
@Composable
internal fun CallOpenContent() {
    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 0.dp, bottom = 14.dp)) {
        // The top row is as tall as the compact island, so the camera sits in its middle: who is calling on the left,
        // the time and a waveform on the right.
        Row(Modifier.fillMaxWidth().height(COMPACT), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Phone",
                color = CallGray, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(IslandBus.camGap))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (IslandBus.callIncoming) "Incoming" else callText(),
                    color = CallGray, fontSize = 14.sp, maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Equalizer(true, CallGreen)
            }
        }
        // The name gets the whole width under the camera, at a comfortable size.
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
            // Mute only makes sense once the call is connected.
            if (!IslandBus.callIncoming) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(CircleShape)
                        .background(if (muted) Color.White else Color(0xFF3A3A3C))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            runCatching { audio.isMicrophoneMute = !muted }
                            muted = runCatching { audio.isMicrophoneMute }.getOrDefault(!muted)
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
                Box(
                    Modifier.weight(1f).height(44.dp).clip(CircleShape)
                        .background(if (action.accept) CallGreen else CallRed)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val intent = action.intent
                            if (intent != null) IslandBus.sendPI(intent) else IslandBus.demoPress(action)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}