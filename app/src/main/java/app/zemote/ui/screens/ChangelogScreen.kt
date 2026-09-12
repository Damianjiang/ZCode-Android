package app.zemote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 版本更新日志条目 */
private data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

private val Changelogs = listOf(
    ChangelogEntry(
        "v1.4.1", "2026-09-12",
        listOf(
            "⚡ 性能优化：分发版改用 R8 深度优化 + 资源收缩，启动更快、运行更流畅、体积更小",
            "⚡ 冷启动预置品牌底色，消除白屏闪烁",
            "🐛 被其他客户端抢占连接时自动重连（最多 2 次），不再直接断开",
            "🎨 时间线图片消息优雅占位，杜绝破图",
            "🧹 代码清理：移除实验性探测代码与无效会话订阅",
        ),
    ),
    ChangelogEntry(
        "v1.4.0", "2026-09-12",
        listOf(
            "新增底部导航栏：设备 / 设置一键切换，移除右上角齿轮按钮",
            "新增「个性化」设置页：品牌色盘（鸢尾/晴空/青柠/蔷薇/暖橙）、亮暗模式、动态取色",
            "全局页面切换动画升级为 M3 FadeThrough，更丝滑流畅",
            "对话发送栏支持排队发送：AI 回复中时输入内容自动排队，停止按钮独立显示",
        ),
    ),
    ChangelogEntry(
        "v1.3.0", "2026-09-11",
        listOf(
            "新增崩溃报告页：应用崩溃自动记录日志，启动时展示并支持一键复制",
            "新增「更新日志」页面（就是你现在看到的这个）",
            "任务会话页接入真实数据：运行中 / 历史会话列表",
            "全新对话发送栏布局：附件 / 权限 / 模型 / 深度思考 / 发送-停止",
            "仓库更名为 ZCode-Android",
        ),
    ),
    ChangelogEntry(
        "v1.2.0", "2026-09-11",
        listOf(
            "修复添加设备时应用闪退（Keystore 兼容性问题）",
            "修复配对链接 t 参数为毫秒时间戳时解析失败的问题",
            "修复 rpc-frame CRC32 校验算法错误，IPC 通道握手打通",
            "补全 rpc-frame-ack 应答，避免服务端误判传输故障",
            "Channel RPC 增加超时保护，调用移至 IO 线程",
        ),
    ),
    ChangelogEntry(
        "v1.1.1", "2026-09-11",
        listOf(
            "移除「检查更新」功能",
            "设置页与依赖精简",
        ),
    ),
    ChangelogEntry(
        "v1.1.0", "2026-09-11",
        listOf(
            "全新 Material 3 Expressive 界面：Iris 渐变配色 / 脉冲状态点 / M3 转场动画",
            "新版渐变自适应图标",
            "多设备管理、一键切换设备",
            "凭据 Keystore AES/GCM 加密存储",
            "纯 Gradle 标准流程打包签名",
        ),
    ),
    ChangelogEntry(
        "v1.0.0", "2026-09-11",
        listOf(
            "首个版本：设备配对、工作区列表、会话通道协议栈",
            "Kotlin + Jetpack Compose Material 3 + OkHttp + Gradle",
        ),
    ),
)

/** 更新日志页：卡片按版本倒序，点击展开明细 */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
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
            Text("更新日志", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(Changelogs.size) { index ->
                val entry = Changelogs[index]
                ChangelogCard(entry = entry, isLatest = index == 0)
            }
            item {
                Text(
                    "Zemote · ZCode 远程控制客户端（协议复刻，独立实现）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry, isLatest: Boolean) {
    var expanded by remember(entry.version) { mutableStateOf(false) }

    Surface(
        color = if (isLatest) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.version,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isLatest) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (isLatest) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            "最新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    entry.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLatest) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = if (isLatest) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.highlights.forEach { line ->
                        Row {
                            Box(
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(5.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isLatest) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxSize(),
                                ) {}
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isLatest) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
