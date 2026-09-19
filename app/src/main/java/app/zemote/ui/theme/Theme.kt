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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ─── 品牌色盘 ───

/** 一套品牌色的三色组（主 / 辅 / 点缀，各自含 on 与 container 色） */
data class BrandTrio(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
)

data class PaletteSpec(
    val key: String,
    val label: String,
    val swatch: Color,
    val light: BrandTrio,
    val dark: BrandTrio,
)

val Palettes: List<PaletteSpec> = listOf(
    PaletteSpec(
        "iris", "鸢尾", Color(0xFF5B54D6),
        BrandTrio(
            Color(0xFF5B54D6), Color(0xFFFFFFFF), Color(0xFFE4DFFF), Color(0xFF17066B),
            Color(0xFF00696B), Color(0xFFFFFFFF), Color(0xFF9CF1F2), Color(0xFF002020),
            Color(0xFF7B580B), Color(0xFFFFFFFF), Color(0xFFFFDEA9), Color(0xFF281800),
        ),
        BrandTrio(
            Color(0xFFC5C1FF), Color(0xFF2A1F87), Color(0xFF433CA0), Color(0xFFE3DFFF),
            Color(0xFF80D4D6), Color(0xFF003737), Color(0xFF004F50), Color(0xFF9CF1F2),
            Color(0xFFF2BF48), Color(0xFF402D00), Color(0xFF5C4200), Color(0xFFFFDEA9),
        ),
    ),
    PaletteSpec(
        "blue", "晴空", Color(0xFF415F91),
        BrandTrio(
            Color(0xFF415F91), Color(0xFFFFFFFF), Color(0xFFD6E3FF), Color(0xFF001A41),
            Color(0xFF565F71), Color(0xFFFFFFFF), Color(0xFFDAE2F9), Color(0xFF1A2B41),
            Color(0xFF705575), Color(0xFFFFFFFF), Color(0xFFFAD8FD), Color(0xFF2A122B),
        ),
        BrandTrio(
            Color(0xFFAAC7FF), Color(0xFF002E69), Color(0xFF284777), Color(0xFFD6E3FF),
            Color(0xFFBEC6DC), Color(0xFF283141), Color(0xFF3E4758), Color(0xFFDAE2F9),
            Color(0xFFDDBCE0), Color(0xFF412741), Color(0xFF583E5C), Color(0xFFFAD8FD),
        ),
    ),
    PaletteSpec(
        "green", "青柠", Color(0xFF386A20),
        BrandTrio(
            Color(0xFF386A20), Color(0xFFFFFFFF), Color(0xFFB7F397), Color(0xFF052F00),
            Color(0xFF55624C), Color(0xFFFFFFFF), Color(0xFFD8E7CB), Color(0xFF141E10),
            Color(0xFF38666A), Color(0xFFFFFFFF), Color(0xFFBCEBF0), Color(0xFF002021),
        ),
        BrandTrio(
            Color(0xFF9CD67D), Color(0xFF0E3900), Color(0xFF205105), Color(0xFFB7F397),
            Color(0xFFBCC7B0), Color(0xFF27321F), Color(0xFF3D4836), Color(0xFFD8E7CB),
            Color(0xFFA0CFD3), Color(0xFF00363A), Color(0xFF1F4D52), Color(0xFFBCEBF0),
        ),
    ),
    PaletteSpec(
        "rose", "蔷薇", Color(0xFF8E4957),
        BrandTrio(
            Color(0xFF8E4957), Color(0xFFFFFFFF), Color(0xFFFFD9DE), Color(0xFF3A0713),
            Color(0xFF75565B), Color(0xFFFFFFFF), Color(0xFFFFD9DE), Color(0xFF2B1519),
            Color(0xFF7C5636), Color(0xFFFFFFFF), Color(0xFFFFDCC2), Color(0xFF2E1501),
        ),
        BrandTrio(
            Color(0xFFFFB2C0), Color(0xFF551122), Color(0xFF72333F), Color(0xFFFFD9DE),
            Color(0xFFE4BDC3), Color(0xFF43292E), Color(0xFF5C3F44), Color(0xFFFFD9DE),
            Color(0xFFEFBD94), Color(0xFF472A0D), Color(0xFF653F22), Color(0xFFFFDCC2),
        ),
    ),
    PaletteSpec(
        "orange", "暖橙", Color(0xFF8F4C38),
        BrandTrio(
            Color(0xFF8F4C38), Color(0xFFFFFFFF), Color(0xFFFFDBD1), Color(0xFF3A0B01),
            Color(0xFF775651), Color(0xFFFFFFFF), Color(0xFFFFDAD3), Color(0xFF2C150F),
            Color(0xFF6C5D2F), Color(0xFFFFFFFF), Color(0xFFF5E1A7), Color(0xFF231A00),
        ),
        BrandTrio(
            Color(0xFFFFB59F), Color(0xFF551F10), Color(0xFF73362A), Color(0xFFFFDBD1),
            Color(0xFFE7BDB6), Color(0xFF2C1510), Color(0xFF442A25), Color(0xFFFFDAD3),
            Color(0xFFD8C58C), Color(0xFF241A04), Color(0xFF423F19), Color(0xFFF5E1A7),
        ),
    ),
)

fun paletteSpec(key: String): PaletteSpec = Palettes.firstOrNull { it.key == key } ?: Palettes.first()

// ─── 中性色（各色盘共享） ───

private val LightNeutrals = lightColorScheme(
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnBackgroundLight,
    surface = NeutralBackgroundLight,
    onSurface = NeutralOnBackgroundLight,
    surfaceVariant = NeutralVariantLight,
    onSurfaceVariant = NeutralOnVariantLight,
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

private val DarkNeutrals = darkColorScheme(
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

private fun BrandTrio.lightScheme(): androidx.compose.material3.ColorScheme = LightNeutrals.copy(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
)

private fun BrandTrio.darkScheme(): androidx.compose.material3.ColorScheme = DarkNeutrals.copy(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
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
    val spec = paletteSpec(themeState.palette)
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeState.dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> spec.dark.darkScheme()
        else -> spec.light.lightScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZemoteTypography,
        content = content,
    )
}
