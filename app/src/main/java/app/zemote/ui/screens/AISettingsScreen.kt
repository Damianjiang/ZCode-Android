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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zemote.R
import app.zemote.state.AISettings

@Composable
fun AISettingsScreen(
    onBack: () -> Unit,
) {
    var selectedProvider by remember {
        mutableStateOf(AISettings.modelProvider)
    }
    var selectedThoughtLevel by remember {
        mutableStateOf(AISettings.thoughtLevel)
    }

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
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                stringResource(R.string.ai_settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel(stringResource(R.string.section_model_provider))
            SettingsCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SettingRow(
                        icon = Icons.Rounded.Settings,
                        title = stringResource(R.string.model_provider_label),
                        subtitle = stringResource(R.string.model_provider_sub),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AISettings.ModelProvider.values().forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedProvider = provider },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedProvider == provider,
                                onClick = {
                                    selectedProvider = provider
                                    AISettings.modelProvider = provider
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                provider.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }

            SectionLabel(stringResource(R.string.section_thought_level))
            SettingsCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    SettingRow(
                        icon = Icons.Rounded.FormatListBulleted,
                        title = stringResource(R.string.thought_level_label),
                        subtitle = stringResource(R.string.thought_level_sub),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AISettings.ThoughtLevel.values().forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedThoughtLevel = level },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedThoughtLevel == level,
                                onClick = {
                                    selectedThoughtLevel = level
                                    AISettings.thoughtLevel = level
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    level.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    level.description(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.ai_settings_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 28.dp),
                textAlign = TextAlign.Justify,
            )
        }
    }
}

private fun AISettings.ThoughtLevel.description(): String = when (this) {
    AISettings.ThoughtLevel.AUTO -> "自动根据任务复杂度选择思考深度"
    AISettings.ThoughtLevel.LIGHT -> "快速响应，适合简单任务"
    AISettings.ThoughtLevel.DEEP -> "深度思考，适合复杂任务"
}
