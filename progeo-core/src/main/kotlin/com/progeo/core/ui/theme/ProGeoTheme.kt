package com.progeo.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.progeo.core.R

/**
 * The ProGeo corporate colors: the tokens of the web portal (frontend/scss/main.css) and its
 * mails (templates/emails/base.html).
 */
object ProGeoColors {
    val Blue = Color(0xFF0B3659)
    val Orange = Color(0xFFEB633B)
    val White = Color(0xFFFFFFFF)

    /** Page behind the panels. */
    val PageBg = Color(0xFFE6E3E2)

    /** Panels and cards. */
    val PanelBg = Color(0xFFF3F1F0)

    /** Inputs and tables on a panel. */
    val Surface = Color(0xFFFBFAFA)
    val Track = Color(0xFFE9E6E5)
    val TrackSoft = Color(0xFFEFECEC)

    val WarmGray1 = Color(0xFFEEEBEB)
    val WarmGray2 = Color(0xFFDCD7D8)
    val WarmGray3 = Color(0xFFCAC4C4)
    val WarmGray4 = Color(0xFFB8B0B0)

    /** Secondary text (footer) and muted text (hints, the subtitle under the logo). */
    val TextSecondary = Color(0xFF4F4949)
    val TextMuted = Color(0xFF6E6868)

    val Green = Color(0xFF8DC160)
    val Violet = Color(0xFF9577B3)
    val Yellow = Color(0xFFFBBC15)
    val Cyan = Color(0xFF61AAC5)
}

/** Success / warning colors that Material 3 does not name, readable on the light panels. */
object StatusColors {
    val ok = Color(0xFF3F7A1E)
    val warn = Color(0xFFB45309)
}

val PlusJakartaSans = FontFamily(
    Font(R.font.progeo_jakarta_regular, FontWeight.Normal),
    Font(R.font.progeo_jakarta_medium, FontWeight.Medium),
    Font(R.font.progeo_jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.progeo_jakarta_bold, FontWeight.Bold),
)

private val Scheme = lightColorScheme(
    primary = ProGeoColors.Blue,
    onPrimary = ProGeoColors.White,
    primaryContainer = Color(0xFFDCE6EF),
    onPrimaryContainer = ProGeoColors.Blue,
    inversePrimary = Color(0xFFA9C7E3),
    secondary = ProGeoColors.Orange,
    onSecondary = ProGeoColors.White,
    secondaryContainer = Color(0xFFFBE1D8),
    onSecondaryContainer = Color(0xFF5C1E0B),
    tertiary = ProGeoColors.Cyan,
    onTertiary = ProGeoColors.White,
    tertiaryContainer = Color(0xFFDDEEF4),
    onTertiaryContainer = Color(0xFF0E3D4F),
    background = ProGeoColors.PageBg,
    onBackground = ProGeoColors.Blue,
    surface = ProGeoColors.PanelBg,
    onSurface = ProGeoColors.Blue,
    surfaceVariant = ProGeoColors.Track,
    onSurfaceVariant = ProGeoColors.TextSecondary,
    surfaceTint = ProGeoColors.Blue,
    inverseSurface = ProGeoColors.Blue,
    inverseOnSurface = ProGeoColors.White,
    // Cards are panels on the page; menus and dialogs nearly white, like the portal's surfaces.
    surfaceContainerLowest = ProGeoColors.White,
    surfaceContainerLow = ProGeoColors.Surface,
    surfaceContainer = ProGeoColors.PanelBg,
    surfaceContainerHigh = ProGeoColors.Surface,
    surfaceContainerHighest = ProGeoColors.PanelBg,
    surfaceBright = ProGeoColors.Surface,
    surfaceDim = ProGeoColors.WarmGray2,
    outline = ProGeoColors.WarmGray4,
    outlineVariant = ProGeoColors.WarmGray2,
    error = Color(0xFFB3261E),
    onError = ProGeoColors.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

private fun Typography.withFont(): Typography {
    fun TextStyle.f(weight: FontWeight? = null) = copy(fontFamily = PlusJakartaSans, fontWeight = weight ?: fontWeight)
    return copy(
        displayLarge = displayLarge.f(FontWeight.SemiBold),
        displayMedium = displayMedium.f(FontWeight.SemiBold),
        displaySmall = displaySmall.f(FontWeight.SemiBold),
        headlineLarge = headlineLarge.f(FontWeight.Bold),
        headlineMedium = headlineMedium.f(FontWeight.Bold),
        headlineSmall = headlineSmall.f(FontWeight.Bold),
        titleLarge = titleLarge.f(FontWeight.Bold),
        titleMedium = titleMedium.f(FontWeight.SemiBold),
        titleSmall = titleSmall.f(FontWeight.Bold),
        bodyLarge = bodyLarge.f(),
        bodyMedium = bodyMedium.f(),
        bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(FontWeight.Bold),
        labelMedium = labelMedium.f(FontWeight.SemiBold),
        labelSmall = labelSmall.f(FontWeight.SemiBold),
    )
}

private val ProGeoTypography = Typography().withFont()

/** Panels 18 dp like the portal; buttons stay pills (Material's full shape). */
private val ProGeoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Small uppercase label under the logo ("MONITORING-PORTAL" in the portal and its mails). */
val ProGeoEyebrow = TextStyle(
    fontFamily = PlusJakartaSans,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.3.sp,
    color = ProGeoColors.TextMuted,
)

/**
 * The ProGeo corporate design for Material 3: navy text on warm-gray panels, navy pill
 * buttons, orange accents, Plus Jakarta Sans. Light only, like the portal.
 */
@Composable
fun ProGeoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = ProGeoTypography, shapes = ProGeoShapes, content = content)
}
