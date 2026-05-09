package com.orquestrador.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalDarkScheme = darkColorScheme(
    primary            = ElectricBlue,
    onPrimary          = Color.White,
    primaryContainer   = BlueDim,
    onPrimaryContainer = BlueLight,
    secondary          = BlueLight,
    onSecondary        = Color.White,
    background         = Obsidian,
    onBackground       = TextPrimary,
    surface            = CardSurface,
    onSurface          = TextPrimary,
    surfaceVariant     = DeepNavy,
    onSurfaceVariant   = TextSecondary,
    outline            = CardBorderIdle,
    outlineVariant     = TextDim,
    error              = StatusRed,
)

@Composable
fun OrquestradorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TacticalDarkScheme,
        typography = OrquestradorTypography,
        content = content,
    )
}
