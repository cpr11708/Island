package com.example.island

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val MiniOrange = Color(0xFFFF9F0A)
private val MiniGreen = Color(0xFF30D158)

private fun miniClock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    // Hours and minutes ("1:19") when there are hours, otherwise minutes and seconds ("19:48"): it never ends in a colon.
    return if (t >= 3600) "%d:%02d".format(t / 3600, t % 3600 / 60) else "%d:%02d".format(t / 60, t % 60)
}

/** A one-second ticking clock for the little pills. */
@Composable
private fun tick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    return now
}

/** What one of the two small pills shows when two things are going at once. */
@Composable
internal fun MiniActivity(kind: IslandState) {
    val now = tick()
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            IslandState.Music, IslandState.MusicOpen -> Equalizer(IslandBus.mediaPlaying, IslandBus.mediaAccent)
            IslandState.Call, IslandState.CallOpen -> {
                Icon(Icons.Rounded.Phone, null, tint = MiniGreen, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (IslandBus.callIncoming) "Ring" else if (IslandBus.callStartMs > 0) miniClock(now - IslandBus.callStartMs) else "Call",
                    color = MiniGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            IslandState.Timer, IslandState.TimerOpen -> {
                Canvas(Modifier.size(13.dp)) {
                    val sw = size.width * 0.14f
                    drawCircle(MiniOrange, size.width / 2 - sw / 2, style = Stroke(sw))
                    drawLine(MiniOrange, center, Offset(center.x, size.height * 0.24f), sw, StrokeCap.Round)
                    drawLine(MiniOrange, center, Offset(size.width * 0.68f, center.y), sw, StrokeCap.Round)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    if (IslandBus.timerKnown) miniClock(IslandBus.timerRemainingMs(now) + 999) else "Timer",
                    color = MiniOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            else -> Unit
        }
    }
}

/** A short message: an icon on the left, a few words on the right (silent mode, a Bluetooth device...). */
@Composable
internal fun AlertCompact() {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(IslandBus.alertIcon, fontSize = 15.sp, color = IslandBus.alertColor)
        Text(
            IslandBus.alertText,
            color = IslandBus.alertColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}