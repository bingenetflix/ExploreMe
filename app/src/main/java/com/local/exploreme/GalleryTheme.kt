package com.local.exploreme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm Beige & Deep Espresso Palette
val BeigeBg = Color(0xFFF4ECE1)
val BeigeCard = Color(0xFFFAF5EE)
val BeigePillSurface = Color(0xFFEBE0D1)
val BeigeActivePill = Color(0xFFDFD0BC)
val EspressoInk = Color(0xFF1E1814)
val TaupeMuted = Color(0xFF7B6F63)
val HairlineSand = Color(0xFFE2D6C5)
val OverlayEspresso = Color(0xCC241C16)
val HeartRose = Color(0xFFD94862)

private val ExploreMeColorScheme = lightColorScheme(
    primary = EspressoInk,
    onPrimary = BeigeCard,
    background = BeigeBg,
    onBackground = EspressoInk,
    surface = BeigeCard,
    onSurface = EspressoInk,
    surfaceVariant = BeigePillSurface,
    onSurfaceVariant = TaupeMuted,
    outline = HairlineSand
)

@Composable
fun ExploreMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ExploreMeColorScheme,
        content = content
    )
}
