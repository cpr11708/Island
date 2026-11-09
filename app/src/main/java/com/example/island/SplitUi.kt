package com.example.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val MiniOrange = Color(0xFFFF9F0A)
private val MiniGreen = Color(0xFF30D158)

internal fun miniClock(ms: Long): String {
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
internal fun MiniActivity(kind: IslandState, towardCamera: Boolean = false) {
    val now = tick()
    // The left pill of a split sits right up against the camera: its content hugs that side.
    Row(
        Modifier.fillMaxSize().padding(start = if (towardCamera) 8.dp else 10.dp, end = if (towardCamera) 3.dp else 10.dp),
        horizontalArrangement = if (towardCamera) Arrangement.End else Arrangement.Center,
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
                    if (IslandBus.timerKnown) miniClock(IslandBus.timerRemainingMs(now) + (if (IslandBus.timerStopwatch) 0L else 999L)) else "Timer",
                    color = MiniOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            IslandState.Torch -> AlertGlyph("flashlight", Color(0xFF0A84FF), Modifier.size(15.dp))
            IslandState.Nav, IslandState.NavOpen -> {
                NavIcon(Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    IslandBus.navDistance.ifEmpty { IslandBus.navTitle.take(7) },
                    color = Color(0xFF0A84FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            else -> Unit
        }
    }
}

/** The flashlight is on: the torch icon left of the camera and "On" right of it, in blue. */
@Composable
internal fun TorchCompact() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CamRow(
            Modifier.requiredWidth(172.dp).fillMaxHeight(),
            left = { AlertGlyph("flashlight", Color(0xFF0A84FF), Modifier.size(19.dp)) },
            right = { Text("On", color = Color(0xFF0A84FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
        )
    }
}

/** A short message: an icon on the left, a few words on the right (silent mode, a Bluetooth device...). */
@Composable
internal fun AlertCompact() {
    // Laid out at the pill's final width even while it is still growing, so the words never slide under the hole
    // on the way (the pill grows from a narrow size, and a narrow layout squeezes the middle).
    val w = (IslandBus.camGap + (IslandBus.alertText.length * 7 + 30).dp * 2).coerceIn(172.dp, 320.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The icon is left of the camera, the words right of it: nothing is ever drawn under the hole.
        CamRow(
            Modifier.requiredWidth(w).fillMaxHeight(),
            left = {
                if (IslandBus.alertBattery >= 0) {
                    // A battery that fills up to its level, with the number beside it.
                    val fill = remember(IslandBus.alertKey) { Animatable(0f) }
                    LaunchedEffect(IslandBus.alertKey) { fill.animateTo(IslandBus.alertBattery.toFloat(), tween(900)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BatteryIcon(fill.value.toInt(), Modifier.size(22.dp, 11.dp), IslandBus.alertColor)
                        Spacer(Modifier.width(5.dp))
                        Text("${IslandBus.alertBattery}%", color = IslandBus.alertColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                } else {
                    AlertGlyph(IslandBus.alertIcon, IslandBus.alertColor, Modifier.size(19.dp))
                }
            },
            right = {
                Text(
                    IslandBus.alertText,
                    color = IslandBus.alertColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            },
        )
    }
}

/** Small drawn icons for the short pop-ups (no emoji): [kind] is silent, vibrate, ring, moon, airplane, flashlight. */
@Composable
internal fun AlertGlyph(kind: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw) =
            drawLine(color, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), width, StrokeCap.Round)
        fun bell() {
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(.18f * w, .74f * h); lineTo(.82f * w, .74f * h)
                    cubicTo(.72f * w, .66f * h, .72f * w, .52f * h, .72f * w, .44f * h)
                    cubicTo(.72f * w, .22f * h, .28f * w, .22f * h, .28f * w, .44f * h)
                    cubicTo(.28f * w, .52f * h, .28f * w, .66f * h, .18f * w, .74f * h)
                    close()
                },
                color,
            )
            drawCircle(color, w * 0.08f, Offset(.5f * w, .87f * h))
            drawCircle(color, w * 0.06f, Offset(.5f * w, .17f * h))
        }
        when (kind) {
            "ring" -> bell()
            "silent" -> {
                bell()
                // A slash through it, with a thin gap on either side so it reads as crossed out.
                drawLine(Color.Black, Offset(.12f * w, .06f * h), Offset(.88f * w, .94f * h), sw * 2.6f, StrokeCap.Round)
                line(.14f, .1f, .86f, .92f)
            }
            "vibrate" -> {
                drawRoundRect(
                    color, Offset(.33f * w, .1f * h), androidx.compose.ui.geometry.Size(.34f * w, .8f * h),
                    androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(sw),
                )
                line(.17f, .34f, .17f, .66f); line(.06f, .42f, .06f, .58f)
                line(.83f, .34f, .83f, .66f); line(.94f, .42f, .94f, .58f)
            }
            "moon" -> {
                val cut = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(Offset(.38f * w, .02f * h), androidx.compose.ui.geometry.Size(.74f * w, .74f * h)))
                }
                clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
                    drawCircle(color, w * 0.42f, Offset(.46f * w, .54f * h))
                }
            }
            "airplane" -> {
                line(.5f, .06f, .5f, .94f, w * .15f)
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(.5f * w, .38f * h); lineTo(.97f * w, .64f * h); lineTo(.97f * w, .73f * h); lineTo(.5f * w, .58f * h)
                        lineTo(.03f * w, .73f * h); lineTo(.03f * w, .64f * h); close()
                    },
                    color,
                )
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(.5f * w, .78f * h); lineTo(.74f * w, .93f * h); lineTo(.74f * w, .99f * h); lineTo(.5f * w, .92f * h)
                        lineTo(.26f * w, .99f * h); lineTo(.26f * w, .93f * h); close()
                    },
                    color,
                )
            }
            "flashlight" -> {
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(.27f * w, .08f * h); lineTo(.73f * w, .08f * h); lineTo(.63f * w, .38f * h); lineTo(.37f * w, .38f * h); close()
                    },
                    color,
                )
                drawRoundRect(
                    color, Offset(.37f * w, .44f * h), androidx.compose.ui.geometry.Size(.26f * w, .5f * h),
                    androidx.compose.ui.geometry.CornerRadius(w * .08f),
                )
            }
            "headphones" -> {
                drawArc(
                    color, 180f, 180f, false, Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(w - sw, h - sw),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
                drawRoundRect(color, Offset(0f, h * 0.5f), androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
                drawRoundRect(color, Offset(w * 0.76f, h * 0.5f), androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
            }
            else -> drawCircle(color, w * 0.22f, center)
        }
    }
}