package com.controlxia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    /** Botones destacados (detener la alarma) y su texto. */
    val highlight: Color,
    val onHighlight: Color,
    /** Los colores de la esfera del asistente. */
    val orb: OrbColors = AuraOrbColors,
)

/**
 * Los colores de la esfera: el cuerpo (de la luz de arriba al borde oscuro),
 * las luces que giran adentro, el halo y la onda de la voz.
 */
@Immutable
data class OrbColors(
    val light: Color,
    val mid: Color,
    val deep: Color,
    /** Cuatro luces: la de fondo y tres de color. */
    val lobes: List<Color>,
    val halo: Color,
    val wave: List<Color>,
)

val AuraOrbColors = OrbColors(
    light = Color(0xFF7FB2FF),
    mid = Color(0xFF2F5BFF),
    deep = Color(0xFF1A1F8F),
    lobes = listOf(Color(0xFF3D6BFF), Color(0xFF8C4BFF), Color(0xFFFF5FB3), Color(0xFF52E5FF)),
    halo = Color(0xFF5B8CFF),
    wave = listOf(Color(0xFF4D8BFF), Color(0xFF8C4BFF), Color(0xFFFF5FB3)),
)

/** Studio: Canvas #FBFBF8, Mist #DBE3E9, Cornflower #6AA6DA, Pear #E1E5AC, Black. */
val StudioOrbColors = OrbColors(
    light = Color(0xFFEAF3FB),
    mid = Color(0xFF6AA6DA),
    deep = Color(0xFF2B5C88),
    lobes = listOf(Color(0xFF4F8FCB), Color(0xFFE1E5AC), Color(0xFFDBE3E9), Color(0xFF9CC8EE)),
    halo = Color(0xFF6AA6DA),
    wave = listOf(Color(0xFF6AA6DA), Color(0xFFE1E5AC), Color(0xFF9CC8EE)),
)

/** Toscana: Tuscan Red #A91B18, Pale Cerulean #CEE7F3, Noir #181717, Satin Linen #F8FAED. */
val TuscanOrbColors = OrbColors(
    light = Color(0xFFFFB3AA),
    mid = Color(0xFFA91B18),
    deep = Color(0xFF3E0806),
    lobes = listOf(Color(0xFFC2312C), Color(0xFFCEE7F3), Color(0xFFF8FAED), Color(0xFFE5675F)),
    halo = Color(0xFFC2312C),
    wave = listOf(Color(0xFFE5675F), Color(0xFFCEE7F3), Color(0xFFF8FAED)),
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
    highlight = Color(0xFFFF9500),
    onHighlight = Color.Black,
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
    highlight = Color(0xFFFF9F0A),
    onHighlight = Color.Black,
)

val StudioLight = LightAura.copy(
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF5E636B),
    tertiaryLabel = Color(0xFF9CA2AA),
    background = Color(0xFFFBFBF8),
    glass = Color(0xC4FFFFFF),
    blue = Color(0xFF4A8BC8),
    glows = listOf(Color(0xFFDBE3E9), Color(0xFFC3DDF3), Color(0xFFE9ECC4), Color(0xFFDBE3E9)),
    highlight = Color(0xFFE1E5AC),
    onHighlight = Color.Black,
    orb = StudioOrbColors,
)

val StudioDark = DarkAura.copy(
    label = Color(0xFFFBFBF8),
    secondaryLabel = Color(0xFFA9B0B8),
    tertiaryLabel = Color(0xFF6B7178),
    background = Color(0xFF000000),
    elevated = Color(0xFF111214),
    blue = Color(0xFF6AA6DA),
    glows = listOf(Color(0xFF1E3956), Color(0xFF3A3E1F), Color(0xFF253039), Color(0xFF16304A)),
    highlight = Color(0xFFE1E5AC),
    onHighlight = Color.Black,
    orb = StudioOrbColors,
)

val TuscanLight = LightAura.copy(
    label = Color(0xFF181717),
    secondaryLabel = Color(0xFF6E6863),
    tertiaryLabel = Color(0xFFA7A19B),
    background = Color(0xFFF8FAED),
    glass = Color(0xC4FFFFFF),
    blue = Color(0xFFA91B18),
    glows = listOf(Color(0xFFCEE7F3), Color(0xFFF2D2CC), Color(0xFFEDF1D8), Color(0xFFCEE7F3)),
    highlight = Color(0xFFA91B18),
    onHighlight = Color.White,
    orb = TuscanOrbColors,
)

val TuscanDark = DarkAura.copy(
    label = Color(0xFFF8FAED),
    secondaryLabel = Color(0xFFB3ADA5),
    tertiaryLabel = Color(0xFF746E68),
    background = Color(0xFF181717),
    elevated = Color(0xFF222020),
    blue = Color(0xFFD9443D),
    glows = listOf(Color(0xFF5C1513), Color(0xFF1E3A47), Color(0xFF3B1010), Color(0xFF2B2522)),
    highlight = Color(0xFFC2312C),
    onHighlight = Color.White,
    orb = TuscanOrbColors,
)

/** Los colores de [palette] en claro u oscuro. */
fun auraColors(palette: Palette, dark: Boolean): AuraColors = when (palette) {
    Palette.AURA -> if (dark) DarkAura else LightAura
    Palette.STUDIO -> if (dark) StudioDark else StudioLight
    Palette.TUSCAN -> if (dark) TuscanDark else TuscanLight
}

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

/**
 * El tema de la app con la paleta y el modo que eligió el usuario (Ajustes →
 * Apariencia). [forceDark] fija oscuro (la pantalla sobre el bloqueo, la
 * alarma) o claro (capturas); null sigue la elección.
 */
@Composable
fun XiaTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val choice by ThemeSettings.state.collectAsState()
    val dark = forceDark ?: choice.mode.resolve(isSystemInDarkTheme())
    val aura = auraColors(choice.palette, dark)
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
        ) {
            // Sin esto, el texto que no dice su color sale negro (también en oscuro).
            CompositionLocalProvider(LocalContentColor provides aura.label, content = content)
        }
    }
}
