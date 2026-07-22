package com.controlxia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lenguaje visual de ControlXIA: editorial y técnico.
 * Fondo papel, tinta casi negra, un solo acento azul "blueprint",
 * etiquetas en monospace con tracking amplio y líneas de 1dp.
 */
val Ink = Color(0xFF0B0B0C)
val Paper = Color(0xFFFBFBF9)
val Accent = Color(0xFF2743D6)
val Muted = Color(0xFF75756F)
val HairlineColor = Color(0xFFE3E3DD)

val Mono = FontFamily.Monospace

private val XiaColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Ink,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2F2EC),
    onSurfaceVariant = Muted,
    outline = HairlineColor,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val XiaTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    // Los "label" son el registro técnico: monospace, mayúsculas, tracking.
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.4.sp),
)

// Esquinas casi rectas: estética de plano técnico, no de burbuja.
private val XiaShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

@Composable
fun XiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XiaColors,
        typography = XiaTypography,
        shapes = XiaShapes,
        content = content,
    )
}
