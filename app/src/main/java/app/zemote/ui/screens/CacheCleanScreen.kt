package app.zemote.ui.screens

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zemote.R
import app.zemote.crash.CrashHandler
import app.zemote.state.AppSettings
import app.zemote.ui.logger.ZemoteLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存清理子页面：展示并清理本机缓存数据。
 *
 * 当前项目：应用缓存目录（cacheDir，按定义可丢弃）、调试日志（内存）、
 * 设置数据（SharedPreferences）、崩溃报告（filesDir 下唯一可安全删除的条目）。
 *
 * ⚠️ **不要往这里加"清理应用数据文件 / 清空 filesDir"之类的项目** ——
 * DataStore 的持久化文件就在 `filesDir/datastore/` 下，里面有全部已配对设备。
 * 详见 [scanCacheItems] 里的注释。
 */
@Composable
fun CacheCleanScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var cacheItems by remember { mutableStateOf<List<CacheItem>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(true) }
    var cleaning by remember { mutableStateOf(false) }
    var cleanDone by remember { mutableStateOf(false) }

    // 扫描各缓存项大小。扫描本身在 IO 线程，但 Compose 状态在主线程写。
    LaunchedEffect(Unit) {
        val scanned = withContext(Dispatchers.IO) { scanCacheItems(ctx) }
        cacheItems = scanned
        scanning = false
    }

    // 选中项总大小
    val totalSelectedBytes = remember(selectedIds, cacheItems) {
        cacheItems.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
    }

    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        stringResource(R.string.cache_clean_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(padding),
        ) {
            if (scanning) {
                // 扫描中：显示转圈
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.cache_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (cleaning) {
                // 清理中：显示转圈 + 提示
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.cache_cleaning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (cleanDone) {
                // 清理完成
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.cache_clean_done),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.cache_clean_done_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                cleanDone = false
                                scanning = true
                                selectedIds = emptySet()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        cacheItems = scanCacheItems(ctx)
                                    }
                                    scanning = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.6f),
                        ) {
                            Text(stringResource(R.string.cache_scan_again))
                        }
                    }
                }
            } else {
                // 正常列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (cacheItems.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.cache_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        items(cacheItems, key = { it.id }) { item ->
                            CacheItemRow(
                                item = item,
                                selected = item.id in selectedIds,
                                onToggle = {
                                    selectedIds = if (item.id in selectedIds) {
                                        selectedIds - item.id
                                    } else {
                                        selectedIds + item.id
                                    }
                                },
                            )
                        }
                    }
                }

                // 底部操作栏
                if (cacheItems.isNotEmpty()) {
                    val isSelected = selectedIds.isNotEmpty()
                    Surface(
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            if (isSelected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        stringResource(R.string.cache_selected_total, formatBytes(totalSelectedBytes)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.cache_items_selected, selectedIds.size, cacheItems.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        // 先快照选中的 id：下面立刻要把 selectedIds 清空，
                                        // 若在协程里再读它就会读到空集合。
                                        // 旧实现正是这个顺序错误 —— 循环里 `item.id in selectedIds`
                                        // 恒为 false，于是**清理按钮什么都没删**：转个圈、
                                        // 提示"已完成"，但一个字节都没释放。
                                        val toClean = selectedIds
                                        cleaning = true
                                        selectedIds = emptySet()
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                cacheItems
                                                    .filter { it.id in toClean }
                                                    .forEach { it.clean(ctx) }
                                            }
                                            // 重新扫描以刷新大小（在主线程写 Compose 状态）
                                            cacheItems = scanCacheItems(ctx)
                                            cleaning = false
                                            cleanDone = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteSweep,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.cache_clean_now))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        // 全选
                                        selectedIds = cacheItems.map { it.id }.toSet()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                ) {
                                    Text(stringResource(R.string.cache_select_all))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────── 缓存项模型 ──────────────────────────

/** 可清理的缓存项 */
data class CacheItem(
    val id: String,
    val label: String,
    val subtitle: String,
    val sizeBytes: Long,
    val clean: (Context) -> Unit,
)

// ────────────────────────── 扫描 ──────────────────────────

/** 扫描所有可清理的缓存项 */
fun scanCacheItems(ctx: Context): List<CacheItem> = buildList {
    // 1. 应用缓存目录（getCacheDir）
    val appCache = ctx.cacheDir
    if (appCache.exists()) {
        val size = directorySize(appCache)
        add(CacheItem(
            id = "app_cache",
            label = ctx.getString(R.string.cache_app_dir),
            subtitle = ctx.getString(R.string.cache_app_dir_sub, formatBytes(size)),
            sizeBytes = size,
            clean = { c ->
                if (c.cacheDir.exists()) c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            },
        ))
    }

    // 2. 协议层日志（ZemoteLogger，内存中）
    val logCount = ZemoteLogger.entries.value.size
    val logBytes = (logCount * 256L).coerceAtLeast(0)
    add(CacheItem(
        id = "protocol_logs",
        label = ctx.getString(R.string.cache_logs),
        subtitle = ctx.getString(R.string.cache_logs_sub, logCount),
        sizeBytes = logBytes,
        clean = { ZemoteLogger.clear() },
    ))

    // 3. SharedPreferences 设置数据
    try {
        val prefsFile = File(ctx.dataDir, "shared_prefs/zemote_settings.xml")
        val prefsSize = if (prefsFile.exists()) prefsFile.length() else 0L
        add(CacheItem(
            id = "settings_data",
            label = ctx.getString(R.string.cache_settings),
            subtitle = ctx.getString(R.string.cache_settings_sub, formatBytes(prefsSize)),
            sizeBytes = prefsSize,
            clean = {
                AppSettings.maxMessages = 100
            },
        ))
    } catch (_: Exception) {}

    // 4. 崩溃报告 —— filesDir 中**唯一**可以安全清理的条目
    //
    // ⚠️ 这里绝对不能递归删除整个 filesDir。
    // filesDir 下除了 crash_report.txt，还有 DataStore 的持久化文件
    // `filesDir/datastore/zemote_settings.preferences_pb`：
    // AccountStore 把**全部已配对设备（含加密后的配对 URL）**存在那里，
    // ThemeManager 的主题设置也在同一个文件里。
    //
    // 旧实现是 `filesDir.listFiles()?.forEach { it.deleteRecursively() }`，
    // 而这一项在界面上叫「应用数据文件 / 本地持久化数据文件」——
    // 用户点一下"清理"，重启后所有设备就都不见了，而且凭据不可恢复。
    // （这个 bug 之前一直没暴露，是因为清理按钮本身因为选择集被提前清空而从不生效；
    //   两个 bug 互相掩盖。修好按钮后它就会真的删数据，所以必须同时修掉。）
    //
    // 现在改为只删崩溃报告，走 CrashHandler 自己的清理入口。
    val crashFile = File(ctx.filesDir, "crash_report.txt")
    if (crashFile.exists()) {
        val size = crashFile.length()
        add(CacheItem(
            id = "crash_report",
            label = ctx.getString(R.string.cache_crash_report),
            subtitle = ctx.getString(R.string.cache_crash_report_sub, formatBytes(size)),
            sizeBytes = size,
            clean = { CrashHandler.clear(it) },
        ))
    }
}

/** 递归计算目录大小 */
private fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) directorySize(file) else file.length()
    }
    return size
}

/** 格式化字节数为可读字符串 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val group = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val effectiveGroup = group.coerceIn(0, units.size - 1)
    val unit = units[effectiveGroup]
    val scale = when (effectiveGroup) {
        0 -> 1.0
        1 -> 1024.0
        2 -> 1024.0 * 1024.0
        else -> 1024.0 * 1024.0 * 1024.0
    }
    val scaled = bytes / scale
    return String.format("%.1f %s", scaled, unit)
}

// ────────────────────────── UI 组件 ──────────────────────────

@Composable
private fun CacheItemRow(
    item: CacheItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 勾选框
            RadioButton(
                selected = selected,
                onClick = onToggle,
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 图标
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
