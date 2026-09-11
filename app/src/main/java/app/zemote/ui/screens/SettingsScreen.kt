package app.zemote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.zemote.BuildConfig
import app.zemote.ui.theme.ThemeManager

/** 设置页：外观（主题模式分段按钮 + 动态取色）+ 关于（版本 / 更新日志） */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit = {},
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("外观")
            SettingsCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SettingRow(
                        icon = Icons.Rounded.Palette,
                        title = "主题模式",
                        subtitle = when (themeState.mode) {
                            ThemeManager.ThemeMode.LIGHT -> "始终使用浅色主题"
                            ThemeManager.ThemeMode.DARK -> "始终使用深色主题"
                            ThemeManager.ThemeMode.FOLLOW_SYSTEM -> "跟随系统自动切换"
                        },
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.LIGHT,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.LIGHT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                            icon = { Icon(Icons.Rounded.WbSunny, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("浅色") },
                        )
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.DARK,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.DARK) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                            icon = { Icon(Icons.Rounded.Contrast, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("深色") },
                        )
                        SegmentedButton(
                            selected = themeState.mode == ThemeManager.ThemeMode.FOLLOW_SYSTEM,
                            onClick = { themeManager.setMode(ThemeManager.ThemeMode.FOLLOW_SYSTEM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                            label = { Text("跟随系统") },
                        )
                    }
                }
            }

            SettingsCard {
                SettingRow(
                    icon = Icons.Rounded.Contrast,
                    title = "动态取色",
                    subtitle = "Android 12+ 根据壁纸自动配色",
                    trailing = {
                        Switch(
                            checked = themeState.dynamicColor,
                            onCheckedChange = { themeManager.setDynamicColor(it) },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }

            SectionLabel("关于")
            SettingsCard {
                Column {
                    SettingRow(
                        icon = Icons.Rounded.Info,
                        title = "版本",
                        subtitle = BuildConfig.VERSION_NAME,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    SettingRow(
                        icon = Icons.Rounded.HistoryEdu,
                        title = "更新日志",
                        subtitle = "查看各版本的更新内容",
                        onClick = onOpenChangelog,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }

            Text(
                "Zemote · ZCode 远程控制客户端（协议复刻，独立实现）\n仅用于连接你自己的设备，请遵守服务条款与当地法律。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
