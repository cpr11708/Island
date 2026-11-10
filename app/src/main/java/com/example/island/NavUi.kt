package com.example.island

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavBlue = Color(0xFF0A84FF)
private val NavGray = Color(0xFF8E8E93)
private val NavDark = Color(0xFF3A3A3C)

/** The turn arrow Google Maps puts in its notification (white), or a plain arrow when it gives none. */
@Composable
internal fun NavIcon(modifier: Modifier) {
    val icon = IslandBus.navIcon
    if (icon != null) {
        Image(icon, null, modifier, colorFilter = ColorFilter.tint(Color.White))
    } else {
        Canvas(modifier) {
            val w = size.width
            val h = size.height
            drawPath(
                Path().apply {
                    moveTo(w * .5f, 0f); lineTo(w * .95f, h); lineTo(w * .5f, h * .74f); lineTo(w * .05f, h); close()
                },
                NavBlue,
            )
        }
    }
}

/** Collapsed: the turn arrow on the left, the distance to the next turn on the right. */
@Composable
internal fun NavCompact() {
    CamRow(
        Modifier.fillMaxSize(),
        left = { NavIcon(Modifier.size(18.dp)) },
        right = {
            Text(
                IslandBus.navDistance.ifEmpty { IslandBus.navTitle.take(9) },
                color = NavBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            )
        },
    )
}

/** Expanded: arrival time, big distance, the full instruction, whatever trip summary Maps gives, and Exit. */
@Composable
internal fun NavOpenContent() {
    val distance = IslandBus.navDistance
    // Maps' text is often "350 ft · Turn right onto Main St": the distance is already shown big, so show the rest.
    val instruction = if (IslandBus.navTitle == distance || IslandBus.navTitle.isEmpty()) {
        IslandBus.navText.substringAfter(" · ", IslandBus.navText).substringAfter(" • ", IslandBus.navText.substringAfter(" · ", IslandBus.navText))
    } else IslandBus.navTitle
    val more = listOf(IslandBus.navMore, if (IslandBus.navTitle != distance && IslandBus.navText != instruction) IslandBus.navText else "")
        .filter { it.isNotBlank() }.joinToString("\n")
    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, bottom = 12.dp)) {
        CamRow(
            Modifier.fillMaxWidth().height(COMPACT), pad = 0.dp,
            left = { Text("Maps", color = NavGray, fontSize = 13.sp, maxLines = 1) },
            right = {
                Text(
                    IslandBus.navSub, color = Color.White, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                )
            },
        )
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            NavIcon(Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val big = distance.ifEmpty { IslandBus.navTitle }
                Text(
                    big,
                    // A long distance (or a street name standing in for one) gets smaller instead of being cut off.
                    color = Color.White, fontSize = if (big.length <= 8) 30.sp else if (big.length <= 14) 24.sp else 19.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(instruction, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                more, color = NavGray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IslandBus.navActions.take(1).forEach { a ->
                val navSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.height(40.dp).pressScale(navSource).clip(CircleShape).background(NavDark)
                        .clickable(interactionSource = navSource, indication = null) { a.intent?.let { IslandBus.sendPI(it) } ?: IslandBus.demoNavPress() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(a.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}