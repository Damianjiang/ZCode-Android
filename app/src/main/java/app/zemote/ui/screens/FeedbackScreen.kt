package app.zemote.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toFile
import app.zemote.R
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 反馈工单界面：
 * - 问题描述输入
 * - 截图上传
 * - 提交工单
 *
 * 对应官方 Web 中的 FeedbackSubmitForm / ticket 功能。
 */
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var description by remember { mutableStateOf("") }
    var screenshots by remember { mutableStateOf<List<FeedbackScreenshot>>(emptyList()) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<FeedbackSubmitResult?>(null) }

    // 截图选择器
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        screenshots = uris.map { uri ->
            FeedbackScreenshot(uri = uri, id = UUID.randomUUID().toString())
        } + screenshots
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶部导航栏
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
                stringResource(R.string.feedback_title),
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
            // 问题描述
            SectionLabel(stringResource(R.string.feedback_description))
            SettingsCard {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.feedback_description_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    minLines = 4,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { /* 可以在此添加提交逻辑 */ },
                    ),
                )
            }

            // 截图上传
            SectionLabel(stringResource(R.string.feedback_screenshots))
            SettingsCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (screenshots.isNotEmpty()) {
                        screenshots.forEachIndexed { index, item ->
                            ScreenshotRow(
                                item = item,
                                ctx = ctx,
                                onRemove = {
                                    screenshots = screenshots.toMutableList().apply {
                                        removeAt(index)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { launcher.launch("image/*") }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.feedback_add_screenshots),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 提交按钮
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (submitResult != null) {
                        Text(
                            text = when (submitResult) {
                                is FeedbackSubmitResult.Success -> stringResource(R.string.feedback_success)
                                is FeedbackSubmitResult.Failure -> stringResource(R.string.feedback_failed)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (submitResult) {
                                is FeedbackSubmitResult.Success -> Color.Green
                                is FeedbackSubmitResult.Failure -> Color.Red
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            if (description.isBlank()) {
                                return@Button
                            }
                            isSubmitting = true
                            submitResult = null
                            // 提交反馈（模拟）
                            GlobalScope.launch(Dispatchers.IO) {
                                try {
                                    // TODO: 实现实际的反馈提交逻辑（上传截图到服务器）
                                    kotlinx.coroutines.delay(1000)
                                    submitResult = FeedbackSubmitResult.Success
                                } catch (e: Exception) {
                                    submitResult = FeedbackSubmitResult.Failure(e.message ?: "Unknown error")
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.feedback_submit))
                        }
                    }
                }
            }

            // 说明文字
            Text(
                stringResource(R.string.feedback_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 28.dp),
                textAlign = TextAlign.Justify,
            )
        }
    }
}

private data class FeedbackScreenshot(
    val uri: Uri,
    val id: String,
)

@Composable
private fun ScreenshotRow(
    item: FeedbackScreenshot,
    ctx: android.content.Context,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 使用 Coil 加载缩略图
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(item.uri)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.uri.lastPathSegment ?: item.id,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.remove),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed class FeedbackSubmitResult {
    data object Success : FeedbackSubmitResult()
    data class Failure(val message: String) : FeedbackSubmitResult()
}
