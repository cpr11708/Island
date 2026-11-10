package com.example.island

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The lowest the camera can be put (dp from the top edge, to the middle of the island). */
const val CAM_MAX_Y = 130f

/**
 * The positioning editor, full screen. The top of the screen turns white, fading out just below the lowest place the
 * island can go, so the black island is easy to see. The island can be dragged anywhere on the screen, nudged with
 * the joystick, or set with the sliders at the bottom. Save keeps it; Cancel puts everything back.
 * Positions are in dp: [startX] from the left edge, [startY] from the top edge (to the middle of the island).
 */
@Composable
fun CameraEditor(
    startX: Float,
    startY: Float,
    startSize: Float,
    defaultX: Float,
    defaultY: Float,
    diameter: Float,
    screenW: Float,
    onSizeLive: (Float) -> Unit,
    onSave: (x: Float, y: Float, size: Float) -> Unit,
    onCancel: () -> Unit,
) {
    var x by remember { mutableFloatStateOf(startX) }
    var y by remember { mutableFloatStateOf(startY) }
    var size by remember { mutableFloatStateOf(startSize) }
    var stick by remember { mutableStateOf(Offset.Zero) }
    var confirmReset by remember { mutableStateOf(false) }
    // After Save or Cancel the panel slides down off the bottom edge, and the white fade (with the island on it) lifts
    // away towards the top edge. 0 = all there, 1 = gone.
    var leaving by remember { mutableStateOf(false) }
    val out by androidx.compose.animation.core.animateFloatAsState(
        if (leaving) 1f else 0f, tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "editor out",
    )
    val d = diameter.coerceAtLeast(14f)

    // The joystick moves the island for as long as it is held away from the middle.
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            val s = stick
            if (s != Offset.Zero) {
                x = (x + s.x * 110f * dt).coerceIn(diameter / 2f, screenW - diameter / 2f)
                y = (y + s.y * 80f * dt).coerceIn(8f, CAM_MAX_Y)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The rest of the screen is dimmed, and dragging on it moves the island.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - out }.background(Color(0x73000000)).pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    x = (x + drag.x / density).coerceIn(diameter / 2f, screenW - diameter / 2f)
                    y = (y + drag.y / density).coerceIn(8f, CAM_MAX_Y)
                }
            },
        )
        // White at the top, fading out a little below the lowest the island can be.
        Box(
            Modifier.fillMaxWidth().height((CAM_MAX_Y + size / 2 + 26f).dp)
                .graphicsLayer { alpha = 1f - out; translationY = -out * 48f * density }
                .background(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f)))),
        )
        // The idle island (a circle), with a red ring around it.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - out; translationY = -out * 48f * density }) {
            val c = Offset(x * density, y * density)
            // The circle grows and shrinks with the height setting (36 dp is the standard).
            val r = d / 2 * (size / 36f)
            drawCircle(Color.Black, radius = r * density, center = c)
            drawCircle(Color(0xFFFF453A), radius = (r + 6f) * density, center = c, style = Stroke(2.5f * density))
        }
        // The controls, near the bottom.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 28.dp)
                .graphicsLayer { translationY = out * 460f * density }
                .clip(RoundedCornerShape(26.dp)).background(Color(0xF21C1C1E)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Move the island", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Drag anywhere on the screen, use the joystick, or use the sliders below.",
                        color = Color(0xFFAEAEB2), fontSize = 12.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditButton("Save", Color.White, Color.Black, Modifier.weight(1f)) { if (!leaving) { leaving = true; onSave(x, y, size) } }
                        EditButton("Reset", Color(0xFF3A3A3C), Color.White, Modifier.weight(1f)) { confirmReset = true }
                        EditButton("Cancel", Color(0xFF3A3A3C), Color.White, Modifier.weight(1f)) { if (!leaving) { leaving = true; onCancel() } }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Joystick(onStick = { stick = it })
            }
            EditSlider("Across", "${(x / screenW * 100).roundToInt()}%", x, 0f, screenW) { x = it.coerceIn(diameter / 2f, screenW - diameter / 2f) }
            EditSlider("Down", "${y.roundToInt()} dp", y, 8f, CAM_MAX_Y) { y = it }
            EditSlider("Island height", "${size.roundToInt()} dp", size, 28f, 46f) { size = it; onSizeLive(it) }
        }
        // A small confirmation, drawn here (a normal dialog cannot open inside an overlay). It fades and springs in.
        androidx.compose.animation.AnimatedVisibility(
            visible = confirmReset,
            enter = androidx.compose.animation.fadeIn(tween(180)),
            exit = androidx.compose.animation.fadeOut(tween(140)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectDragGestures { c, _ -> c.consume() } }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = confirmReset,
                    enter = androidx.compose.animation.fadeIn(tween(200)) +
                            androidx.compose.animation.scaleIn(spring(dampingRatio = 0.68f, stiffness = 420f), initialScale = 0.82f),
                    exit = androidx.compose.animation.fadeOut(tween(120)) +
                            androidx.compose.animation.scaleOut(tween(140), targetScale = 0.92f),
                ) {
                    Column(
                        Modifier.padding(horizontal = 40.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2C2C2E)).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Reset the island?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "This puts it back where the phone's own camera cutout is, at the standard height.",
                            color = Color(0xFFAEAEB2), fontSize = 14.sp,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditButton("Keep", Color(0xFF48484A), Color.White, Modifier.weight(1f), 42.dp, 15.sp) { confirmReset = false }
                            EditButton("Reset", Color(0xFFFF453A), Color.White, Modifier.weight(1f), 42.dp, 15.sp) {
                                x = defaultX; y = defaultY.coerceIn(8f, CAM_MAX_Y); size = 36f; onSizeLive(36f)
                                confirmReset = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(label: String, value: String, v: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color(0xFFAEAEB2), fontSize = 13.sp)
        }
        Slider(
            value = v.coerceIn(from, to), onValueChange = onChange, valueRange = from..to,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF48484A),
            ),
        )
    }
}

/**
 * A small grid with a stick in the middle. Push the stick any way and the island moves that way for as long as it is
 * held there (further = faster); let go and the stick springs back to the middle.
 */
@Composable
private fun Joystick(onStick: (Offset) -> Unit) {
    val padDp = 112.dp
    val knobDp = 44.dp
    val density = LocalDensity.current
    val reach = with(density) { (padDp - knobDp).toPx() / 2f }
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.size(padDp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C2C2E)).pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { knob = Offset.Zero; onStick(Offset.Zero) },
                onDragCancel = { knob = Offset.Zero; onStick(Offset.Zero) },
            ) { change, drag ->
                change.consume()
                var k = knob + drag
                val len = hypot(k.x, k.y)
                if (len > reach) k = Offset(k.x / len * reach, k.y / len * reach)
                knob = k
                onStick(Offset(k.x / reach, k.y / reach))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // The grid.
        Canvas(Modifier.fillMaxSize()) {
            val line = Color(0xFF48484A)
            for (i in 1..3) {
                val p = size.width * i / 4f
                drawLine(line, Offset(p, 0f), Offset(p, size.height), 1f * this.density)
                drawLine(line, Offset(0f, p), Offset(size.width, p), 1f * this.density)
            }
        }
        Box(
            Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(knobDp).clip(CircleShape).background(Color.White),
        )
    }
}

/** A pill button for the editor: it shrinks a little while pressed and springs back. */
@Composable
private fun EditButton(
    label: String, color: Color, textColor: Color, modifier: Modifier, height: Dp = 40.dp, size: TextUnit = 14.sp, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(height).pressScale(source, 0.9f).clip(CircleShape).background(color)
            .clickable(interactionSource = source, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = textColor, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}