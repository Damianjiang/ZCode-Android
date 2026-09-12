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
        "v1.5.5", "2026-09-12",
        listOf(
            "🎉 对话页顶部显示会话标题（sessions-index 实时数据，桌面端重命名实时跟随），不再只显示「任务会话」",
            "🎉 排队消息卡片对齐官方 web：AI 回复中发送的内容实时展示为队列卡片，支持立即发送、编辑、删除和自动发送开关",
            "🎉 排队消息支持长按拖动排序：拖过相邻行即交换位置，松手按新顺序提交 reorderQueueItem，官方语义前后生效",
            "🎨 执行过程汇总卡片：连续工具调用不再逐条刷原始 toolcall，聚合为「执行过程 N 步」卡片，一句话展示执行了什么命令、修改了哪个文件，点击展开看原始输出",
            "🎨 AI 回复消息淡入显示；思考块展开/收起改为直切、去掉动画",
            "🔖 新增自动跟随开关：消息区右下角箭头按钮，控制是否自动滚动到最新内容；流式输出增长时持续贴底，上翻阅读历史自动暂停跟随",
            "🎨 输入框支持回车换行（发送走按钮）；上下文用量按钮更换为 M3 饼图图标",
            "🐛 任务会话列表按工作区过滤：选择指定工作区只显示该工作区内的会话，不再混入其他目录（对齐官方行为）",
            "🐛 附件上传加每块进度百分比与分块诊断日志，分块超时放宽到 60s，多图上传不再像卡死",
            "🐛 修复快速点击任务卡片后对话页永久空白：设备未就绪时自动重试并显示「正在打开会话…」，失败时给出明确提示，不再无提示白屏",
            "🎨 工作区加载动画改用 M3 标准 CircularProgressIndicator，替换自绘脉冲圆点",
            "🐛 修复键盘弹出时输入框与输入法之间出现大片空白：窗口软输入模式固定为 adjustResize，消除系统平移与 imePadding 的双重避让",
        ),
    ),
    ChangelogEntry(
        "v1.5.4", "2026-09-12",
        listOf(
            "🎉 协议层对齐原版 Flutter 实现订阅帧 wire 封装：complete/fragment 分片重组 + subscriptionId/topic 路由，流式输出与实时更新从此真正生效（此前订阅帧全被丢弃，只能靠轮询兜底）",
            "🎉 任务会话列表接入 sessions-index 实时订阅：会话标题、运行状态、新增会话实时推送，与 bootstrap 数据双源合并",
            "🎉 附件上传打通：加号唤起系统选择器（图片/任意文件），官方 begin/chunk/commit 三段式分片上传（384KB + sha256 校验），随消息发送；消息内图片按 ref 拉取渲染、文件显示卡片",
            "握手修正为 mobileApp + 协议能力版本 3.6.5（此前上报 App 版本号会导致 V4 能力协商降级）",
            "命令信封补齐 CAS baseRevision，服务端报 stale 时按 revisionAtDecision 自动重试；模型切换新增思考档位兼容回退",
            "seq 断层自动 resyncConversationV4 补快照 + 运行中静默 20s 看门狗；快照保留已加载的更早历史行，不再被窗口覆盖",
            "首发消息随 createSession 的 firstInput 一起发送（官方语义，避免 send-before-subscribe 竞态）；模型菜单改由 prepareWorkspace configOptions 提供真实数据",
            "移除发送后轮询刷新 hack：订阅推送已完整覆盖实时更新",
        ),
    ),
    ChangelogEntry(
        "v1.5.3", "2026-09-12",
        listOf(
            "🎉 流式输出支持：text_delta / reasoning_delta 增量帧实时渲染，边生成边显示",
            "历史消息单次加载数量提升到 200 行",
            "订阅帧解析优化，快照/增量帧分流处理",
        ),
    ),
    ChangelogEntry(
        "v1.5.2", "2026-09-12",
        listOf(
            "🐛 继续修复 16KB 页大小设备崩溃：native 库改用 legacy packaging 打包",
        ),
    ),
    ChangelogEntry(
        "v1.5.1", "2026-09-12",
        listOf(
            "🐛 修复 16KB 页大小（Android 15+）设备上的启动崩溃",
            "恢复发送栏完整按钮（附件/权限/模型/思考/上下文/发送/停止）",
            "minSdk 提升至 28",
        ),
    ),
    ChangelogEntry(
        "v1.5.0", "2026-09-12",
        listOf(
            "🎉 对话协议全面打通：通过裸 socket 逆向定位根因——rpc-frame 必须携带裸 IPC 编码（去掉 13 字节帧头），桌面端此前静默丢弃我们的消息",
            "🎉 历史消息完整可见：userInput / assistantText / toolCall / reasoning 全部渲染",
            "🎉 新增 hello + clientHello 握手，修复 fault.connection.handshakeRequired 拒绝",
            "🎉 对话发送实测可用：发出消息、AI 正常回复并实时显示（官方 queue/startNow 语义）",
            "工具调用行按实测数据优化：识别 Bash/Edit/Read 等工具名，自动提取命令描述摘要，展示成功/失败状态",
            "任务打开时使用各自所属工作区，跨目录任务不再错绑",
        ),
    ),
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
