package app.zemote.ui.screens

import app.zemote.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.Palettes
import app.zemote.ui.theme.ThemeManager

/** 个性化页：主题颜色色盘 + 亮暗模式 + 动态取色 */
@Composable
fun PersonalizeScreen(
    onBack: () -> Unit,
    themeManager: ThemeManager,
) {
    val themeState by themeManager.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.personalize), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel(stringResource(R.string.theme_color))
            SettingsCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SettingRow(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.brand_color),
                        subtitle = stringResource(R.string.pick_palette),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Palettes.forEach { spec ->
                            ColorSwatch(
                                colors = listOf(spec.swatch, spec.light.secondaryContainer),
                                label = spec.label,
                                selected = themeState.palette == spec.key,
                                onClick = { themeManager.setPalette(spec.key) },
                            )
                        }
                    }
                }
            }

            SettingsCard {
                SettingRow(
                    icon = Icons.Rounded.Contrast,
                    title = stringResource(R.string.dynamic_color),
                    subtitle = stringResource(R.string.dynamic_color_sub),
                    trailing = {
                        Switch(
                            checked = themeState.dynamicColor,
                            onCheckedChange = { themeManager.setDynamicColor(it) },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }

            SectionLabel(stringResource(R.string.mode))
            SettingsCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SettingRow(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.light_dark),
                        subtitle = when (themeState.mode) {
                            ThemeManager.ThemeMode.LIGHT -> stringResource(R.string.always_light)
                            ThemeManager.ThemeMode.DARK -> stringResource(R.string.always_dark)
                            ThemeManager.ThemeMode.FOLLOW_SYSTEM -> stringResource(R.string.follow_system_auto)
                        },
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.LIGHT,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.LIGHT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                            label = { Text(stringResource(R.string.light)) },
                        )
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.DARK,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.DARK) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                            label = { Text(stringResource(R.string.dark)) },
                        )
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.FOLLOW_SYSTEM,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.FOLLOW_SYSTEM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                            label = { Text(stringResource(R.string.mode_follow_system)) },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.auto_saved),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 28.dp),
            )
        }
    }
}

/** 色盘圆形色卡：渐变底 + 选中勾 */
@Composable
private fun ColorSwatch(
    colors: List<Color>,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .background(Brush.linearGradient(colors), CircleShape)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
        ) {
            if (selected) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
