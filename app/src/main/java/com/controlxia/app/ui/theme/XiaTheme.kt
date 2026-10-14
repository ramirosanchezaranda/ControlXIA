package com.controlxia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controlxia.app.R

/**
 * Lenguaje visual de ControlXIA, inspirado en iOS y su "Liquid Glass": fondos
 * con luz de color que se mueve despacio, tarjetas de vidrio, la esfera del
 * asistente, colores del sistema de Apple y una tipografía limpia. Claro y
 * oscuro según el teléfono.
 */
@Immutable
data class AuraColors(
    val dark: Boolean,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val background: Color,
    val elevated: Color,
    /** Relleno del vidrio: blanco translúcido en claro, gris translúcido en oscuro. */
    val glass: Color,
    val glassStrong: Color,
    /** Borde con brillo del vidrio (arriba más claro). */
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val purple: Color,
    val pink: Color,
    val indigo: Color,
    val teal: Color,
    val gray: Color,
    /** Luces del fondo (de arriba-izquierda a abajo-derecha). */
    val glows: List<Color>,
)

val LightAura = AuraColors(
    dark = false,
    label = Color(0xFF0B0B0F),
    secondaryLabel = Color(0xFF6B6B73),
    tertiaryLabel = Color(0xFFA1A1A8),
    separator = Color(0x1F3C3C43),
    background = Color(0xFFF2F3F8),
    elevated = Color.White,
    glass = Color(0xB8FFFFFF),
    glassStrong = Color(0xE6FFFFFF),
    glassEdgeTop = Color(0xFFFFFFFF),
    glassEdgeBottom = Color(0x33FFFFFF),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55),
    indigo = Color(0xFF5856D6),
    teal = Color(0xFF30B0C7),
    gray = Color(0xFF8E8E93),
    glows = listOf(Color(0xFFBFD7FF), Color(0xFFE4D2FF), Color(0xFFFFD6E8), Color(0xFFCFF3FF)),
)

val DarkAura = AuraColors(
    dark = true,
    label = Color(0xFFF5F5F7),
    secondaryLabel = Color(0xFFA0A0AA),
    tertiaryLabel = Color(0xFF6C6C76),
    separator = Color(0x29FFFFFF),
    background = Color(0xFF05060D),
    elevated = Color(0xFF15161F),
    glass = Color(0x1FFFFFFF),
    glassStrong = Color(0x33FFFFFF),
    glassEdgeTop = Color(0x52FFFFFF),
    glassEdgeBottom = Color(0x0FFFFFFF),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F),
    indigo = Color(0xFF5E5CE6),
    teal = Color(0xFF40C8E0),
    gray = Color(0xFF8E8E93),
    glows = listOf(Color(0xFF1B3FA8), Color(0xFF5A22A8), Color(0xFF9B1F6A), Color(0xFF0E5E8C)),
)

val LocalAuraColors = staticCompositionLocalOf { LightAura }

/** Acceso corto a los colores del tema: `Aura.colors.blue`. */
object Aura {
    val colors: AuraColors
        @Composable @ReadOnlyComposable get() = LocalAuraColors.current
}

// Nombres de antes, ahora según el tema (claro u oscuro).
val Accent: Color @Composable @ReadOnlyComposable get() = LocalAuraColors.current.blue
val Muted: Color @Composable @ReadOnlyComposable get() = LocalAuraColors.current.secondaryLabel

val Mono = FontFamily.Monospace

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/**
 * Inter: la tipografía libre más parecida a SF Pro (que Apple no deja usar
 * fuera de sus equipos). Se baja con Google Fonts; sin Google Play, queda la
 * del sistema.
 */
private val Inter = GoogleFont("Inter").let { font ->
    FontFamily(
        Font(font, fontProvider, FontWeight.Normal),
        Font(font, fontProvider, FontWeight.Medium),
        Font(font, fontProvider, FontWeight.SemiBold),
        Font(font, fontProvider, FontWeight.Bold),
    )
}

// La escala de texto de iOS (Large Title 34, Title 28/22/20, Headline 17…).
private val AuraTypography = Typography(
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.3).sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = (-0.1).sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp),
)

/** Esquinas redondeadas y continuas, como las tarjetas de iOS. */
private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun XiaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val aura = if (dark) DarkAura else LightAura
    val scheme = if (dark) {
        darkColorScheme(
            primary = aura.blue,
            onPrimary = Color.White,
            primaryContainer = aura.blue.copy(alpha = 0.22f),
            onPrimaryContainer = aura.label,
            secondary = aura.indigo,
            onSecondary = Color.White,
            background = aura.background,
            onBackground = aura.label,
            surface = aura.elevated,
            onSurface = aura.label,
            surfaceVariant = Color(0xFF22232D),
            onSurfaceVariant = aura.secondaryLabel,
            surfaceContainerHighest = Color(0xFF2A2B36),
            outline = aura.separator,
            outlineVariant = aura.separator,
            error = aura.red,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = aura.blue,
            onPrimary = Color.White,
            primaryContainer = aura.blue.copy(alpha = 0.12f),
            onPrimaryContainer = aura.label,
            secondary = aura.indigo,
            onSecondary = Color.White,
            background = aura.background,
            onBackground = aura.label,
            surface = aura.elevated,
            onSurface = aura.label,
            surfaceVariant = Color(0xFFE9E9EF),
            onSurfaceVariant = aura.secondaryLabel,
            surfaceContainerHighest = Color(0xFFE3E3EA),
            outline = Color(0xFFD1D1D6),
            outlineVariant = aura.separator,
            error = aura.red,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalAuraColors provides aura) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AuraTypography,
            shapes = AuraShapes,
            content = content,
        )
    }
}
