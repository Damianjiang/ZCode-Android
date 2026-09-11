package app.zemote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

// ─── Zemote 品牌配色「Iris」：电光紫 × 青绿 × 琥珀 ───

private val LightScheme = lightColorScheme(
    primary = IrisPrimaryLight,
    onPrimary = IrisOnPrimaryLight,
    primaryContainer = IrisPrimaryContainerLight,
    onPrimaryContainer = IrisOnPrimaryContainerLight,
    secondary = TealSecondaryLight,
    onSecondary = TealOnSecondaryLight,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = TealOnContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnBackgroundLight,
    surface = NeutralBackgroundLight,
    onSurface = NeutralOnBackgroundLight,
    surfaceVariant = NeutralVariantLight,    onSurfaceVariant = NeutralOnVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = Scrim,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

private val DarkScheme = darkColorScheme(
    primary = IrisPrimaryDark,
    onPrimary = IrisOnPrimaryDark,
    primaryContainer = IrisPrimaryContainerDark,
    onPrimaryContainer = IrisOnPrimaryContainerDark,
    secondary = TealSecondaryDark,
    onSecondary = TealOnSecondaryDark,
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = TealOnContainerDark,
    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = NeutralBackgroundDark,
    onBackground = NeutralOnBackgroundDark,
    surface = NeutralBackgroundDark,
    onSurface = NeutralOnBackgroundDark,
    surfaceVariant = NeutralVariantDark,
    onSurfaceVariant = NeutralOnVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = Scrim,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)

@Composable
fun ZemoteTheme(
    themeManager: ThemeManager,
    content: @Composable () -> Unit,
) {
    val themeState by themeManager.state.collectAsState()
    val darkTheme = when (themeState.mode) {
        ThemeManager.ThemeMode.LIGHT -> false
        ThemeManager.ThemeMode.DARK -> true
        ThemeManager.ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeState.dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZemoteTypography,
        content = content,
    )
}
