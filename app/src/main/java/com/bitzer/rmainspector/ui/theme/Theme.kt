package com.bitzer.rmainspector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val BitzerLight = lightColorScheme(
    primary = BitzerColors.Green,
    onPrimary = BitzerColors.White,
    primaryContainer = BitzerColors.Green25,
    onPrimaryContainer = BitzerColors.DarkGreen,
    secondary = BitzerColors.DarkGreen,
    onSecondary = BitzerColors.White,
    secondaryContainer = BitzerColors.Green10,
    onSecondaryContainer = BitzerColors.DarkGreen,
    tertiary = BitzerColors.Grey90,
    onTertiary = BitzerColors.White,
    background = BitzerColors.White,
    onBackground = BitzerColors.Black,
    surface = BitzerColors.White,
    onSurface = BitzerColors.Black,
    surfaceVariant = BitzerColors.Grey10,
    onSurfaceVariant = BitzerColors.Grey90,
    outline = BitzerColors.Grey50,
    outlineVariant = BitzerColors.Grey30,
    error = BitzerColors.Error,
)

/** Light theme only for v1 (bright shop floor). */
@Composable
fun RmaInspectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BitzerLight, typography = RmaTypography, content = content)
}
