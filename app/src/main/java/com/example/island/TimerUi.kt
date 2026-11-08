package com.example.island

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val TOrange = Color(0xFFFF9F0A)
private val TGray = Color(0xFF8E8E93)
private val TDark = Color(0xFF3A3A3C)

private fun timerClock(ms: Long): String {
    val t = (if (IslandBus.timerStopwatch) ms else ms + 999) / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** Time left, redrawn four times a second. */
@Composable
private fun remaining(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return IslandBus.timerRemainingMs(now)
}

/** Collapsed: an orange timer icon on the left, the time left on the right. */
@Composable
internal fun TimerCompact() {
    val left = remaining()
    val finished = IslandBus.timerKnown && !IslandBus.timerPaused && !IslandBus.timerStopwatch && left <= 0
    // When the time runs out, open up so the Stop button is right there.
    LaunchedEffect(finished) {
        if (finished && IslandBus.state == IslandState.Timer) IslandBus.state = IslandState.TimerOpen
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimerIcon(Modifier.size(16.dp))
        Text(
            if (IslandBus.timerKnown) timerClock(left) else "Timer",
            color = TOrange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

/** Expanded: a big orange countdown, and the buttons the Clock app offers (Pause, Stop...). */
@Composable
internal fun TimerOpenContent() {
    val left = remaining()
    val known = IslandBus.timerKnown
    val finished = known && !IslandBus.timerPaused && !IslandBus.timerStopwatch && left <= 0
    Row(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(IslandBus.timerLabel.ifEmpty { "Timer" }, color = TGray, fontSize = 13.sp, maxLines = 1)
            Text(
                if (!known) "Running" else if (finished) "Time's up" else timerClock(left),
                color = TOrange, fontSize = if (finished || !known) 28.sp else 36.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (IslandBus.timerReal && IslandBus.timerActions.none { it.label != "Open" }) {
                // Samsung's timer has no buttons of its own, so these drive the Clock app through the accessibility service.
                val open = IslandBus.timerActions.firstOrNull { it.intent != null }?.intent
                val press = { cmd: String ->
                    // First try the notification's own button; only if there is none, drive the Clock app.
                    if (IslandBus.timerRemotePress?.invoke(cmd) == true) {
                        IslandBus.timerCmdDebug = "pressed $cmd on the notification"
                    } else {
                        IslandBus.timerCmdDebug += " -> sent $cmd via the Clock app"
                        IslandBus.timerCmd = cmd
                        if (open != null) IslandBus.sendPI(open) else IslandBus.openClock()
                    }
                    Unit
                }
                if (known) {
                    TimerButton(
                        CallAction(if (IslandBus.timerPaused) "Resume" else "Pause", false, null),
                        onPress = { press("pause") },
                    )
                }
                TimerButton(CallAction("Cancel", false, null), onPress = { press("cancel") })
            } else {
                IslandBus.timerActions.take(3).forEach { action -> TimerButton(action) }
            }
        }
    }
}

@Composable
private fun TimerIcon(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.width * 0.14f
        drawCircle(TOrange, radius = size.width / 2 - stroke / 2, style = Stroke(stroke))
        // The hand.
        drawLine(TOrange, Offset(size.width / 2, size.height / 2), Offset(size.width / 2, size.height * 0.24f), stroke, StrokeCap.Round)
        drawLine(TOrange, Offset(size.width / 2, size.height / 2), Offset(size.width * 0.68f, size.height / 2), stroke, StrokeCap.Round)
    }
}

private enum class Glyph { Pause, Play, Cross, Text }

private fun glyphFor(label: String): Glyph {
    val l = label.lowercase()
    return when {
        "pause" in l -> Glyph.Pause
        "resume" in l || "start" in l || "play" in l -> Glyph.Play
        "stop" in l || "cancel" in l || "delete" in l || "dismiss" in l || "reset" in l -> Glyph.Cross
        else -> Glyph.Text
    }
}

@Composable
private fun TimerButton(action: CallAction, onPress: (() -> Unit)? = null) {
    val glyph = glyphFor(action.label)
    val click = remember { MutableInteractionSource() }
    val onClick = {
        if (onPress != null) onPress() else {
            val intent = action.intent
            if (intent != null) IslandBus.sendPI(intent) else IslandBus.demoTimerPress(action)
        }
        Unit
    }
    if (glyph == Glyph.Text) {
        Box(
            Modifier.height(48.dp).defaultMinSize(minWidth = 48.dp).pressScale(click).clip(CircleShape).background(TDark)
                .clickable(interactionSource = click, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(action.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        return
    }
    Box(
        Modifier.size(48.dp).pressScale(click).clip(CircleShape).background(TDark)
            .clickable(interactionSource = click, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            when (glyph) {
                Glyph.Pause -> {
                    drawRoundRect(TOrange, Offset(w * .15f, 0f), Size(w * .28f, h), CornerRadius(w * .06f))
                    drawRoundRect(TOrange, Offset(w * .57f, 0f), Size(w * .28f, h), CornerRadius(w * .06f))
                }
                Glyph.Play -> drawPath(
                    Path().apply { moveTo(w * .2f, 0f); lineTo(w * .2f, h); lineTo(w * .95f, h / 2); close() },
                    TOrange,
                )
                Glyph.Cross -> {
                    val sw = w * .16f
                    drawLine(Color.White, Offset(w * .1f, h * .1f), Offset(w * .9f, h * .9f), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * .9f, h * .1f), Offset(w * .1f, h * .9f), sw, StrokeCap.Round)
                }
                Glyph.Text -> Unit
            }
        }
    }
}