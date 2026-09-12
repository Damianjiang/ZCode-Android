package app.zemote.ui.screens

import app.zemote.R

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
import androidx.compose.ui.res.stringResource
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
        "v1.5.8", "2026-09-13",
        listOf(
            "修复同一工作区多条会话桥互相踢的问题：仓库按工作区共享，一条桥复用全部会话订阅（对齐官方架构）",
            "握手状态过期自动重握手并重试，桌面端重启后不再整页拉不到数据",
            "会话打开后历史为空时自动补拉两次，并触发服务端强制快照重推",
            "模型列表拉取为空时自动重试，桌面端冷启动不再拿到空列表",
            "加载链路增加诊断日志（拉取行数、失败形态），便于定位问题",
        ),
    ),
    ChangelogEntry(
        "v1.5.7", "2026-09-13",
        listOf(
            "修复进入会话后一直加载、模型列表和历史拉不到的问题：连接恢复事件不再打断正在打开的会话，加载增加总超时兜底",
            "新增界面语言设置：跟随系统 / 中文 / English，设置中可手动切换",
            "大部分界面文案提供英文翻译",
            "关于页新增作者信息与项目主页跳转",
        ),
    ),
    ChangelogEntry(
        "v1.5.6", "2026-09-13",
        listOf(
            "连接被其他客户端抢占时立即自动抢回：持续重连（1s 起步、8s 封顶），不再两次失败就永久下线",
            "抢回成功后自动重建所有会话通道（对话、会话列表订阅随 bridge 恢复自动重建）",
            "恢复后自动重新加载工作区列表，无需手动刷新",
        ),
    ),
    ChangelogEntry(
        "v1.5.5", "2026-09-12",
        listOf(
            "对话页顶部显示会话标题，桌面端重命名会实时跟随",
            "排队消息卡片：支持立即发送、编辑、删除、拖动排序和自动发送开关",
            "工具调用聚合为执行过程卡片，展示执行了什么命令、修改了哪些文件，可展开原始输出",
            "AI 回复淡入显示；思考块展开收起改为直切",
            "消息区右下角新增自动跟随开关",
            "输入框支持回车换行",
            "任务会话列表按工作区过滤",
            "附件上传显示分块进度，超时放宽到 60 秒",
            "连接被其他客户端抢占时立即自动抢回：持续重连直到恢复，恢复后自动重建会话通道并重新加载工作区",
            "修复快速进入会话时可能白屏的问题",
            "修复键盘弹出后输入框与键盘之间空隙过大",
            "上下文用量与工作区加载改用标准组件",
        ),
    ),
    ChangelogEntry(
        "v1.5.4", "2026-09-12",
        listOf(
            "对话协议对齐原版实现：修复订阅帧 wire 封装解析，流式输出和实时更新从此生效",
            "任务列表接入 sessions-index 实时订阅，与 bootstrap 数据合并",
            "附件上传：图片和文件分片上传，消息内图片直接显示",
            "握手参数修正为 mobileApp / 协议版本 3.6.5",
            "命令信封补充 baseRevision，服务端报过期时自动重试",
            "快照断层自动重新同步；已加载的历史行不再被快照覆盖",
            "首条消息随 createSession 一起发送；模型列表来自 prepareWorkspace",
            "移除发送后的轮询刷新",
        ),
    ),
    ChangelogEntry(
        "v1.5.3", "2026-09-12",
        listOf(
            "流式输出：思考与回复内容增量渲染",
            "历史消息一次加载 200 条",
        ),
    ),
    ChangelogEntry(
        "v1.5.2", "2026-09-12",
        listOf(
            "修复 16KB 页大小设备崩溃，native 库改用 legacy packaging",
        ),
    ),
    ChangelogEntry(
        "v1.5.1", "2026-09-12",
        listOf(
            "修复 16KB 页大小（Android 15+）设备启动崩溃",
            "恢复发送栏完整按钮",
            "minSdk 提升到 28",
        ),
    ),
    ChangelogEntry(
        "v1.5.0", "2026-09-12",
        listOf(
            "对话协议打通：rpc-frame 必须携带裸 IPC 编码，此前桌面端静默丢弃我们的消息",
            "历史消息可见：userInput / assistantText / toolCall / reasoning 全部渲染",
            "新增 hello + clientHello 握手，修复 handshakeRequired 拒绝",
            "对话发送可用，AI 回复中发送的内容进入队列",
            "工具调用行识别 Bash / Edit / Read 等工具名并提取摘要",
            "任务打开时使用各自所属工作区",
        ),
    ),
    ChangelogEntry(
        "v1.4.1", "2026-09-12",
        listOf(
            "分发版启用 R8 与资源收缩",
            "冷启动预置背景色，消除白屏闪烁",
            "被其他客户端抢占连接时自动重连（最多 2 次）",
            "图片消息改为占位展示",
            "清理实验性探测代码",
        ),
    ),
    ChangelogEntry(
        "v1.4.0", "2026-09-12",
        listOf(
            "底部导航栏，设备与设置一键切换",
            "个性化设置：品牌色、亮暗模式、动态取色",
            "页面切换改为 M3 转场动画",
            "AI 回复期间发送的内容进入队列",
        ),
    ),
    ChangelogEntry(
        "v1.3.0", "2026-09-11",
        listOf(
            "新增崩溃报告页，启动时展示并支持复制",
            "新增更新日志页",
            "任务会话页接入真实数据",
            "新版对话发送栏",
            "仓库更名为 ZCode-Android",
        ),
    ),
    ChangelogEntry(
        "v1.2.0", "2026-09-11",
        listOf(
            "修复添加设备时应用闪退（Keystore 兼容性问题）",
            "修复配对链接 t 参数为毫秒时间戳时解析失败",
            "修复 rpc-frame CRC32 校验算法错误",
            "补全 rpc-frame-ack 应答",
            "Channel RPC 增加超时保护",
        ),
    ),
    ChangelogEntry(
        "v1.1.1", "2026-09-11",
        listOf(
            "移除检查更新功能",
        ),
    ),
    ChangelogEntry(
        "v1.1.0", "2026-09-11",
        listOf(
            "Material 3 界面",
            "多设备管理与切换",
            "凭据 Keystore AES/GCM 加密存储",
            "新渐变图标",
        ),
    ),
    ChangelogEntry(
        "v1.0.0", "2026-09-11",
        listOf(
            "首个版本：设备配对、工作区列表、会话通道协议栈",
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
            Text(stringResource(R.string.changelog), style = MaterialTheme.typography.titleLarge)
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
