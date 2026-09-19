# 更新日志

## v1.9.2 — 2026-09-15

第四轮性能与修复：针对「UI 卡顿 / 历史持续加载失败 / 渲染慢 / 行为不符预期 / bridge 取信息慢」
逐项定位根因并修复。全部改动已通过 `:app:compileDebugKotlin` 验证。

### 1）UI 卡顿、交互响应迟缓
- **根因：高频状态订阅在聊天页顶层**。`ChatScreen` 顶层订阅了 13 个 StateFlow，其中
  `usage` 在流式输出期间随每个 `state.updated` 补丁更新（约 16 次/秒）。每次更新都会重组
  整个聊天页 —— 包括 `MessageTimeline` 和所有可见消息（Markdown 文本重新布局）。
- **修复**：把 `convConfig` / `usage` / `modelOptions` / `stopWorkId` / `followupMode` /
  `queueItems` / `autoDrain` 全部下沉到新的 `ComposerSection`，这些状态只在发送区内部订阅。
  聊天页顶层现在只保留页面骨架真正需要的低频状态（`working` / `historyState` / `activeId` /
  会话标题 / 交互与后台任务）。
- **根因：传给时间线的 lambda 每次重组都是新实例**。`loadAttachment` 直接写在组合体里，
  而它一路传到每个可见消息条目；`onToggleAutoFollow = { autoFollow = it }` 同理。参数恒不
  相等 → `MessageTimeline` 永远无法跳过重组。
- **修复**：`loadAttachment` 用 `rememberUpdatedState` 持有最新的 `repo` / `activeId`，
  再用 `remember` 固定唯一实例；`autoFollow` 改用显式 `MutableState` 以便 remember 出稳定的
  setter。
- **顶栏与任务面板同样拆成独立重组域**（第二轮）：
  - 会话标题 → `ChatHeaderTitle`，只订阅 `sessionEntries`；
  - 任务面板入口按钮 → `ChatHeaderActions`，只订阅 `pendingInteractions` / `backgroundWorks`；
  - 任务面板 → `TaskPanelHost`，`visible=false` 时**直接返回、不建立任何订阅**。
  `ScreenHeader` 新增 `titleContent` 插槽以支持标题自带订阅。
  至此 `ChatScreen` 顶层只剩 `working` / `historyState` / `activeId` 三个低频订阅
  —— 会话列表刷新、后台任务状态变化、交互请求到达都不再波及时间线。

### 2）会话历史记录持续加载失败
- **根因 A：软失败从不被上报**。`loadRows` 在「响应结构不对 / 缺少 `rows` 字段」时只写日志
  然后 `return _rows.value`，**从不抛异常**；而所有调用方都是
  `runCatching { loadRows(...) }.onFailure { _historyState = FAILED }`。于是 `FAILED`
  永远不会被设置 —— 页面永久停在 `LOADING` 转圈，连重试按钮都出不来。
  新增 `markHistoryLoadFailed()`，软失败也显式落到 `HistoryState.FAILED`。
- **根因 B：订阅成功 ≠ 数据到了**。`retryHistory` 只看 `subscribeConversation` 的返回值，
  订阅成功但快照没推下来时同样永久停在 `LOADING`。现在订阅成功后给 3s 快照窗口，仍未填充
  就主动拉一次历史窗口。
- **根因 C：三条兜底路径并发重复加载**。打开会话时「订阅失败回退 / 2s 安全网 / 手动重试」
  可能同时发起 `loadRows`，同一条 RPC 被重复下发且响应互相覆盖。现在用 `historyLoadMutex`
  串行化，并在初始加载前检查「是否已有人填过历史」直接跳过。
- **响应形状兼容**：`rowsRange` 的 `{rows:[...]}` 与 `{rows:{rows:[...]}}` 两种形状都支持。
  旧实现只从内层 `container` 读 `hasMore` / `atLogEpoch`，遇到外层形状时取不到值，
  会导致翻页提前终止。

### 3）页面渲染速度慢
- **`row.upserted` 由 O(n log n) 降为 O(log n) 查找**。旧实现每次 upsert 都
  `associateBy { rowId }` 建整表 map 再全表 `sortedBy`；流式期间工具行会被反复
  upsert（running → complete），一次长会话每秒几十次，是渲染慢的主要来源。
  改为按 rowId 二分定位 + 就地替换/插入，并维持「内容未变则复用同一 `ConvRow` 实例」
  的不变量（不变量被破坏会连带 `buildDisplayItems` 的 `===` 复用池失效）。
- **重复行不再产生重复 key**。`row.appended` 现在会检测乱序/重放（`rowId <= 末行`），
  退化为 upsert。旧实现直接追加，resync 后服务端重发同一行会产生重复 `rowId`，
  LazyColumn 重复 key 会抛异常或把条目错位。

### 4）部分功能行为与预期不符
- **「执行过程」卡片反复闪烁**。`DisplayItem.ToolGroup` 的 key 是
  `g-首行-末行`，组内每新增一个工具调用 key 就变（`g-1-5` → `g-1-6`），LazyColumn
  视为全新条目：旧条目被销毁重建、`FadeInContainer` 重新淡入、`ToolGroupCard` 的
  展开状态被重置。改为 key 只取**首行** rowId，组增长时保持稳定。
- **「加载更早消息」按钮与真实状态脱节**。`hasMore` 是普通 `var`，UI 里
  `repo.hasOlderHistory` 在组合中读取它却不产生订阅，服务端翻页结果变化不会触发重组
  （明明还有更早历史，按钮却不出现）。新增可观察的 `hasMoreFlow: StateFlow<Boolean>`，
  时间线订阅它后再计算 `hasOlderHistory`。
- **流式文本丢段**。`flushPendingDeltas` 一进来就清空整个 `pendingDeltas`，本轮没在
  `rows` 里找到对应行的 delta 被静默丢弃（`row.delta` 先于 `row.appended` 到达、
  或行位于尚未加载的历史窗口都会命中）。现在只移除真正落到行上的那些，
  `row.appended` 落地时就地补上该行已缓冲的 delta。
- **权限审批弹窗永远不会出现**（第二轮发现）。`ChatScreen` 里用 `selectedInteraction`
  状态驱动 `InteractionDialog`，但**全仓库没有任何地方把它置为非 null** —— 这是一条死路径。
  桌面端发起权限审批时，界面只在任务面板按钮上多一个小红点，用户不主动点开就完全看不到，
  而 Agent 会一直阻塞等待答复。新增 `InteractionDialogHost`：订阅 `pendingInteractions`，
  出现新的待响应请求就自动弹出，每个 `requestId` 只自动弹一次（用户手动关掉后不再弹回来）。

### 5）通过 bridge 获取信息慢
- **根因：慢订阅者反压整条 relay 入站管线**。`RelayClient.payloads` 是
  `extraBufferCapacity=256`、默认 `SUSPEND` 的 `SharedFlow`，而 `BridgeSession` 的
  收集协程在 `collect` 体内**同步**做重活：Base64 解码（单帧最大 512KB → 683KB 字符串）、
  CRC32 校验、整条 IPC 消息的 value-list 解码。一条多兆的历史响应能让它几十到几百毫秒
  回不到 `collect`，缓冲区被拖满后 `emit` 挂起，**后续所有帧（含新的会话帧）只能排队**。
  给 `BridgeSession` 与 `ZemoteClient` 的 payload 收集各加 `.buffer(Channel.UNLIMITED)`：
  插入一个只搬运引用的中转通道，`emit` 立刻返回，慢解码不再反推 relay。
- **分片重组去重**。`RpcFrameTransport.acceptPayload` 旧实现无条件先做 Base64 解码再写入，
  服务端重传分片时白做一次最大 512KB 的解码。现在先查重再解码；用 `received` 计数器
  代替 `fragments.all { it != null }` 的整数组扫描；补上分片下标越界与 `messageBytes`
  上限校验（旧实现可能抛 `ArrayIndexOutOfBounds` / 大 `ByteArray` 分配）。
  `assemblies` 改用 `ConcurrentHashMap`（`dispose` 会从另一线程 clear）。
- **碎片清理节流**。`purgeStaleFragments()` 原先对**每个**入站帧都跑一次全表
  `removeIf`。TTL 是 60s，改为最多 1s 清理一次，行为不变但去掉了逐帧开销。
- **IPC 日志降级**。`ChannelClient.call` 每次 RPC 写 2 条 `info` 日志（含时间格式化 +
  加锁 + 队列操作），改为 `debug` 且仅在日志开关打开时才拼字符串。

### 第三轮补充：IPC 编解码热路径（同日，编译已验证）
`IpcCodec` 是**每一帧双向都必经**的路径（历史窗口、附件分片的帧动辄几百 KB 到几 MB），
这里逐字节的开销会被直接放大成「bridge 取信息慢」。
- **写入端换掉 `ByteArrayOutputStream`**：它的 `write(int)` / `write(byte[],int,int)`
  都带 `synchronized`，而 `ValueWriter` 只在单线程内使用 —— 一次 512KB 的分片等于
  五十多万次取监视器锁，扩容还要反复整块复制。改为容量翻倍的**无锁**可增长数组。
- **读取端不再为读一个字节分配数组**：`decodeValue` 旧实现是 `r.read(1)[0]`，
  每解出一个值就要一个 `ByteArray(1)` —— 一条历史帧里成千上万个嵌套值就是成千上万次分配。
  新增 `readByte()`。
- **大字段不再白复制一份字节数组**：`String(read(len), UTF_8)` 改为
  `String(data, pos, len, UTF_8)`（新增 `readUtf8()`）。tag 5 的 JSON 对象字符串
  可能有好几 MB，旧写法等于每次解码都多复制一份同样大的数组。
- **列表预分配**：tag 4 由 `mutableListOf()` 动态扩容改为
  `ArrayList(min(count, 64))`（设上限是防止被伪造的超大 `count` 直接预分配大块内存）。
- **边界修正**：`read(n)` 补上 `n < 0` 校验。`readVarint` 在极端输入下可以返回负数
  （`0xFFFFFFFF` 落在 Int 上），旧实现会走到 `ByteArray(负数)` 抛
  `NegativeArraySizeException`。
- **Long 编码去掉中间对象**：`JsonPrimitive(value.toString()).toString()` 换成等价的
  `"\"" + value + "\""`（纯数字不需要任何 JSON 转义），省掉 JsonPrimitive + JsonWriter 分配。

### 第三轮补充：其他
- **日志单条长度上限 4000 字符**。`ZemoteLogger` 的环形缓冲保留 1000 条，不设上限时
  **一条**几 MB 的 payload dump（例如 `bootstrap` 整份响应 `toString()`）就能吃掉几十 MB
  内存，并把有用的日志全部挤出缓冲。现在统一在 `log()` 里截断，保护所有调用点。
- **组合期不再编译正则**：`MainShellScreen` 的工作区卡片把
  `split("[\\\\/]".toRegex())` 写在组合体里，而 Kotlin 的 `String.toRegex()` **没有缓存**，
  每次重组都会重新编译一次（工作区数量 × 重组次数）。改为纯字符切分的
  `lastPathSegment()`，连正则都不需要。

### 第三轮补充：新增可复跑的验证脚本
`IpcCodec` 是协议关键路径，改动后光靠推理不够。新增
`tools/verify-ipc-codec/`（`run.sh` + `CodecVerify.kt`），**把编解码器脱离 Android 单独跑**：

```bash
bash tools/verify-ipc-codec/run.sh   # 退出码 0 = 全部通过
```

原理：`IpcCodec.kt` 只依赖 kotlin-stdlib + gson，没有任何 Android API，所以可以直接用
Gradle 缓存里已有的 `kotlin-compiler-embeddable` 编译成 class 再执行 ——
不需要模拟器、不需要给 app 模块引入测试依赖、不需要联网。脚本会按
`gradle/libs.versions.toml` 里声明的 Kotlin 版本挑选编译器。

覆盖 60 项断言，重点是**证明新实现与旧实现在字节层面完全等价**：
- varint 编码在全部边界值（0/127/128/16383/16384/…/`Int.MAX_VALUE`）上与旧
  `ByteArrayOutputStream` 实现逐字节一致，解码往返一致；
- 整帧布局 `tag + varint(len) + payload` 与手工按旧实现拼装的结果逐字节一致；
- 各类值往返：`null` / 多字节 String / `Int` 边界 / Long / ByteArray / 空值 / 嵌套
  List+Map（真实帧的形状）；
- 大块数据：1 MiB ByteArray 逐字节一致、600KB 多字节 String、5 万元素 List；
- `readUtf8` 与 `String(read(n))` 结果一致且正确推进 `pos`；
- 异常路径：空帧、未知 tag、截断帧、`read(-1)`、`readUtf8(-5)` 均抛
  `IllegalArgumentException` 而不是崩溃；**负长度 varint 确认不再抛
  `NegativeArraySizeException`**（旧实现的崩溃点）。

### 第四轮补充：全仓审计（同日，编译已验证）
前几轮都围绕聊天页，这轮把没检查过的文件过了一遍，修掉 4 个真实缺陷 + 3 处死代码。

- **导航路由没做 URL 编码 —— 点进任务页会崩**（严重）。
  `workspaceKey` 常常是**文件系统路径**（`MainShellScreen` 取
  `workspaceIdentity ?: workspacePath`，任务列表又优先用 `workspacePath`），
  而旧实现把路径原样拼进路由：`"tasks/$workspaceKey"`。
  Navigation 按 `/` 切分路径段匹配 `{占位符}`，`/home/me/proj` 会被切成 3 段，
  而模板 `tasks/{workspaceKey}` 只接受 1 段 —— 匹配失败，`navigate()` 直接抛
  `IllegalArgumentException: Navigation destination that matches request ... cannot be found`。
  **只要桌面端报的是 POSIX 风格路径，从工作区列表点进任务页就会崩。**
  现在构建路由时对每个参数 `Uri.encode`（`/` → `%2F`），读取时 `Uri.decode` 还原。
  涉及 `MainShell` / `Tasks` / `Chat` / `Subagent` 四个路由的全部参数。
  （已知边界：路径里若**字面包含** `%XX` 形式的文本会被多解码一次；Navigation
  内部对 path 参数也会解码一次，但对普通路径重复解码是幂等的。）

- **`POST_NOTIFICATIONS` 声明了但从未申请**。Android 13+ 通知是运行时权限，
  清单里声明了它，但全仓没有任何地方调用过申请 —— 结果是保活前台服务的常驻通知
  被系统**静默丢弃**：用户看不到"正在保持连接"的提示，也无法点通知回到 App。
  现在在 `MainShellScreen`（真正建立连接的页面）申请，而不是一启动就弹窗打断用户。

- **前台服务 `START_STICKY` 重启会伪造一条划不掉的假通知**。
  进程被系统回收后，服务会以 **null intent** 被重启；此时 relay 连接已随进程消失，
  没有任何东西需要保活，但旧实现照旧 `startForeground` ——
  用户看到一条 `setOngoing(true)`、内容为"正在保持连接"却完全无法划掉的通知。
  现在 `intent == null` 时 `stopSelf()` 并返回 `START_NOT_STICKY`。

- **死代码清理**：
  - `RpcFrameTransport` 里一个从未 `launch` 过的 `CoroutineScope`（连同 4 个 import）；
  - `ConversationV4Session` 的 `loading` StateFlow —— 只被写、从未被任何 UI 消费
    （UI 用的是 `historyState`），留着容易让人误以为它在驱动加载动画；
  - `MainActivity` 的两个死 import（`Manifest` / `ActivityCompat`，
    是权限申请被挪走后的残留）。

### 第五轮补充：缓存清理页的两个致命 bug（同日，编译已验证）
这一轮的重点是**清理缓存页**。它有两个 bug，而且互相掩盖 —— 单独修任何一个都会出事。

- **🔴 「清理」按钮其实是空操作**。按钮回调里先 `selectedIds = emptySet()`，
  然后才在协程里遍历 `if (item.id in selectedIds) item.clean(ctx)` ——
  读到的永远是空集合，**一个字节都没删过**。界面照样转圈、照样提示"已完成"。
  修复：进协程前先把选中的 id 快照出来（`val toClean = selectedIds`）。
- **🔴 修好按钮后会立刻抹掉所有已配对设备**。清理项里有一项递归删除整个 `filesDir`：
  ```kotlin
  filesDir.listFiles()?.forEach { it.deleteRecursively() }
  ```
  而 `filesDir` 下除了 `crash_report.txt`，还有 DataStore 的持久化文件
  `filesDir/datastore/zemote_settings.preferences_pb` ——
  **`AccountStore` 把全部已配对设备（含 Keystore 加密后的配对 URL）存在那里**，
  `ThemeManager` 的主题设置也在同一个文件里。
  更糟的是这一项在界面上叫「**应用数据文件 / 本地持久化数据文件**」，
  还带一个勾选框和一个红色「清理」按钮，且**没有二次确认**。
  用户勾上它点一下，重启后所有设备消失，凭据不可恢复。
  修复：这一项改为只清理**崩溃报告**（`filesDir` 里唯一可安全删除的条目），
  走 `CrashHandler.clear()`；字符串换成 `cache_crash_report` / `cache_crash_report_sub`。
  代码里留了醒目注释说明为什么绝不能递归删 `filesDir`。
  （两个 bug 的因果：清理功能从没真正执行过，所以数据丢失路径从未被触发 ——
  这也解释了为什么它一直没被发现。修好按钮就必须同时修掉这个地雷。）
- **`AccountStore.load()` 非幂等**。`AccountsScreen` 位于 `AnimatedContent` 里，
  每次在「设备 / 设置」两个 Tab 间切换都会重新组合并触发 `LaunchedEffect`，
  旧实现每次都重跑「DataStore 读 + JSON 解析 + 逐个账户的 Keystore AES-GCM 解密」——
  账号多时每次切 Tab 都是上百毫秒的无用功。现在首次调用才真正读盘。
- **流式 delta 的兜底提交**。`flushPendingDeltas` 现在只移除真正落到行上的 delta
  （见第三轮），于是"行还没到"的 delta 会留在缓冲里等下一次 flush；
  若该行是随后由 `row.upserted` 补进来的，且之后不再有 `row.delta`，
  这段文本就会一直不显示。`applyDeltas` 末尾加了安全网：本轮结束若缓冲非空就排一次 flush。
- **清理页扫描时在 IO 线程写 Compose 状态**。`LaunchedEffect` 里
  `withContext(Dispatchers.IO) { cacheItems = scanCacheItems(ctx) }` —— 改为在 IO 线程
  只做扫描、回到主线程再赋值（与上面清理按钮的修法保持一致）。
  同时修正了过时的 KDoc（原文写的"会话内存缓存"并不存在），并在注释里写明
  **不要再加"清空 filesDir"类的清理项**及原因。

### 第六轮补充：模拟器冒烟验证（同日）
在 `Medium_Phone` AVD 上实际安装运行了本次构建，验证结果：

- ✅ **安装成功**、**启动成功**（`Status: ok`，冷启动 `TotalTime: 9743ms`）
- ✅ **无崩溃** —— `logcat -b crash` 与 `FATAL EXCEPTION` 过滤均为空
- ⚠️ 系统弹出「Android 应用兼容性」对话框拦截了 UI，因此**缓存清理页未能实际点到**，
  该项改动仍只有编译级验证

#### 🔶 由此发现的待处理问题：原生库不符合 16KB 页对齐
系统弹窗原文：
```
此应用不符合 16 KB 对齐要求。LOAD 区段对齐检查失败。
此应用将以页面大小兼容模式运行。
以下库未进行 16 KB 对齐：
• lib/arm64-v8a/libimage_processing_util_jni.so：LOAD 区段未对齐
• lib/arm64-v8a/libdatastore_shared_counter.so：未知错误
• lib/arm64-v8a/libandroidx.graphics.path.so：未知错误
```
这三个 `.so` 都来自依赖库（CameraX / DataStore / Compose），不是本项目编译的。
影响：在 16KB 页设备（部分 Android 15+ 机型）上会以**兼容模式**运行；
Google Play 也已要求新提交支持 16KB。

注意 `app/build.gradle.kts` 里 `jniLibs { useLegacyPackaging = true }` 的注释写着
"确保 16KB 页面对齐兼容"，但实测**并未达成对齐** —— 注释与事实不符，容易误导。
对齐要求来自 `.so` 自身 ELF LOAD 段的构建参数（`-Wl,-z,max-page-size=16384`），
不是打包方式能解决的。

**建议处理方式**（未实施，需单独验证）：升级 `androidx.camera` / `datastore` /
compose 到已提供 16KB 对齐产物的版本，然后重跑一次模拟器验证该弹窗消失。

### 构建
- versionCode 23 / versionName 1.9.2

## v1.9.1 — 2026-09-15

本轮集中修复**卡顿 / 渲染慢 / 历史加载不出来 / bridge 取信息慢**四类问题。

### 性能（卡顿、渲染慢）
- **流式输出不再逐 token 重建整张消息列表**：`ConversationV4` 每次 `setRows` 都会对
  整张列表做 `row.copy()`，而流式文本每个 token 都会触发一次——200 条消息 × 每秒几十个
  token，等于每秒上万次对象拷贝。现在改为**流式 delta 先缓冲、每 60ms 批量提交一次**
  （`row.delta` 合并到字段级拼接），提交时也不再拷贝未变化的行。
- **结构版本与内容版本分离**：旧实现每个 delta 都递增 `displayGroupVersion`，导致每个
  token 都重跑一次工具调用分组。现在只有增/删/替换行才递增结构版本。
- **重组范围收敛**：聊天页整页订阅 `rows`，每个 token 都会带着顶栏、输入栏、队列卡片
  一起重组。现在时间线抽成独立的 `MessageTimeline`，只有时间线内部订阅 `rows`。
- **日志写入去抖**：`ZemoteLogger` 旧实现每条日志都要对 1000 条环形缓冲做一次
  `toList().reversed()` 并写 Compose 状态；现在改为约 3 次/秒批量发布，并换用线程安全
  的时间格式化（原来的 `SimpleDateFormat` 在多 IO 线程下会错乱）。
- **移除 `remember(rows) { derivedStateOf { … } }` 反模式**（key 本身就是每次变化的新列表，
  等于每帧重建）。

### 修复（会话历史一直加载不出来）
- **订阅超时从 10s 提到 45s 并重试一次**：桌面端预热会话运行时可能超过 10s，超时后拿不到
  `ack.subscriptionId`，之后所有历史帧都被丢弃——这就是"一直加载不出来"的直接原因。
- **订阅失败立即降级**：订阅失败时主动调 `conversationRowsRangeV4` 拉一次历史窗口，
  而不是静默失败留一片空白。
- **新增历史状态机** `HistoryState`（加载中 / 就绪 / 空 / 失败）。旧代码里的
  `historyUnavailable` 变量从未被置为 `true`，"会话为空"提示永远不会出现；失败时页面
  完全是空白，也没有重试入口。现在失败会显示提示 + 重试按钮。
- **快照窗口为空时不再清空已加载的更早历史**（旧实现 `older` 会退化成空列表）。
- **翻页游标修正**：旧实现拿服务端返回的 `firstRowId`（服务端最早的行）当 `beforeRowId`，
  问"比它更早的行"必然返回空，翻页永远拿不到数据。现在用**当前已加载的最早一行 rowId**
  作为游标，并结合 `hasMore` 判断。
- **会话仓库 LRU 上限 4 → 8**：在任务列表里依次点开 5 个工作区，最早那个（可能正是当前
  可见的聊天页）会被淘汰 dispose，页面从此收不到任何推送。

### 修复（bridge 获取信息慢 / 功能错误）
- **入站帧不再每条新建协程**：`RelayClient` 旧实现对每个 payload 都 `scope.launch { emit }`，
  订阅者跟不上时协程会不断堆积，帧的投递延迟越拉越大，且跨协程投递**不保证顺序**
  （分片重组的 messageSeq 会乱序）。现在改为无界 `Channel` + 单一消费者按序转发。
- **请求 ID 竞态**：`ChannelClient.lastRequestId` 是非原子 `var`，而 `call()` 与
  `addEventListener()` 会从不同线程并发取号。撞号时后写的 completer 会覆盖前一个，
  前一个请求永远等不到应答 → 30s 超时。改用 `AtomicInteger`。
- **事件注销标志修正**：`addEventListener` 的 `cancelled` 标志既表示"已发送"又表示"已取消"，
  注销时判断恒为真，会给桌面端发无意义的 DISPOSE。现在拆成 `sent` / `disposed` 两个标志，
  且注销幂等。
- **「排队」按钮此前完全无效**：`sendText` 接收了 `requestedDelivery` 参数却从未放进
  协议 payload，导致"排队"和"发送"在协议层行为完全相同。现在会把该字段带给桌面端。
  （已对照官方 bundle 的 `sendText` schema 与调用点确认：该字段只在存在时下发。）
- **发送结果不再被忽略**：官方在 `sendText` 后校验 `status`，只有
  `accepted` / `duplicate` / `noop` 才算成功，其余一律按失败处理。本 App 之前完全忽略
  返回值——消息被服务端拒绝时输入框已经清空、界面毫无反馈，用户以为"发出去了"。
  现在 `sendText` 返回 `SendOutcome`，被拒时**恢复输入框与待发附件**并给出原因提示。
- **队列冲突自动处置**：服务端返回 `guard.heldQueueConfirmationStale`（队列被占用，需客户端
  明确处置）时，自动按「保留队列」重试一次（`heldQueueDisposition=keepQueueAndSend` +
  `expectedHeldQueueItemIds`），绝不静默丢弃用户已排队的消息。
- **分页丢弃跨纪元脏页**：官方会校验分页响应的 `atLogEpoch` 是否与当前快照一致，不一致说明
  期间发生过重建/resync，该页属于旧纪元必须整体丢弃。本 App 之前没做这个校验，
  会把旧数据拼进当前时间线。
- **流式输出自动贴底修正**：旧实现用 `rows.last().inputText.length` 作为触发信号，但流式
  追加的是 `text` 字段，所以其实一直不触发。现在列表末尾加了 0 高度锚点项，
  "滚到最后一个 index"即等价于贴到底部。

### 第二轮补充（同日，编译已验证）
- **展示项列表改为实例复用**：`buildDisplayItems` 此前每 60ms 重建一次，会为**全部行**
  重新分配 `DisplayItem` 对象。长会话下等于每帧上千次分配，GC 抖动直接表现为滚动掉帧。
  现在传入上一次的结果做槽位比对，命中即复用旧对象；由于 `ConversationV4` 在内容未变时
  本就复用同一个 `ConvRow` 实例，比对用 `===` 即可，零额外成本。
- **工具步骤不再各自挂无限动画**：`ToolGroupCard` 里每个"运行中"步骤都有一个独立的
  `ThinkingDot()`（= 一个 `rememberInfiniteTransition`），N 个步骤就有 N 个逐帧动画。
  动画语义已由卡片头的单个指示器承担，步骤行改为静态色点。
- **lambda 提升出 `items`**：`openSub` 原先在条目内部构造，每次重组都是新实例，导致捕获它的
  `FadeInContainer` / `TimelineRow` 参数恒不相等，**永远无法跳过重组**。现在用
  `remember` 提升到列表外层。
- **翻页加载状态竞态**：`isLoadingOlder` 在 `scope.launch` 之后被立刻复位，等于加载中标志
  形同虚设——按钮和滚动触底可能在同一帧内重复发起翻页。改为在协程内 `finally` 复位。
- **翻页按钮不再跳变**：`showOlderButton` 原先包含 `!isLoadingOlder`，加载开始/结束时条目被
  移除又插入，列表会整体跳动一次。现在加载中按钮原地变转圈。
- **删除死代码**：`structureVersion` / `contentVersion` 两个 StateFlow 无任何消费者；
  `ConvRow.version` 字段与 `displayGroupVersion` 别名同样零引用，一并移除。

### 第三轮补充（同日，编译已验证）
- **Markdown 解析不再逐行编译正则**：`Markdown.kt` 把 `Regex` 写在 `parseBlocks` 的
  逐行循环里（每行 5 个），`buildAnnotatedString` 每次调用又构造 5 个。而
  `Pattern.compile` 没有缓存——一条 200 行的回复等于上千次正则编译，这是流式输出时
  渲染慢的主要来源之一。全部改为顶层 `val`，样式常量同样提取。
- **行内样式解析跟随缓存**：`inline()` 要对每段跑 5 次正则扫描，之前没有跟着 `blocks`
  一起 `remember`，父级每次重组都会把所有可见段落重新解析一遍。现在与 `blocks`
  同生命周期缓存（`MdBlock` 增加 `inlineSrc`，Code / Rule 为 null）。
- **工具步骤文案缓存**：`toolSentence` 内部要 `JSONObject` 解析 `inputText`，而工具参数是
  流式追加的——卡片每帧重组时一个 8 步的组就要 8 次 JSON 解析。现在按
  `rowId + toolName + inputText + summaryText` 缓存。
- **翻页提前终止的隐患（功能）**：`loadRows` 会把快照给的服务端 `firstRowId`（整条日志
  最早的行）覆盖成本地已加载的最早行，使 `hasOlderHistory` 的兜底项退化成
  `oldest > oldest`（恒 false）。一旦服务端某次分页响应没带 `hasMore`，翻页就会提前
  终止、更早的历史再也拉不出来。现在只在 `firstRowId` 未知时回填，对齐官方
  `firstRowId ?? row.rowId` 语义。

### 版本
- versionCode 22 / versionName 1.9.1

### 说明
- 以上协议相关改动均对照仓库内官方 Web 客户端逆向产物
  （`zcode_downloaded/remote/v4/assets/index-*.js`）逐条核对：
  `sendText` 的 schema 与调用点、`requestedDelivery` 可选性、`rowsRange` 的
  `beforeRowId` 取值（官方传 `window[0].rowId`，即当前最早一行）、`hasMore` /
  `atLogEpoch` 字段、`wb()` 的"是否还有更早历史"判定、`row.appended` / `row.removed` /
  `row.delta` 的状态合并语义。

## v1.8.0 — 2026-09-13

### 修复
- **加载动画卡死**：给握手、订阅、拉历史每个步骤加了独立超时（15s~20s），不再因单步挂起导致整个页面卡在"正在加载对话…"长达数分钟；整体会话打开总超时从 100s 缩短到 45s
- **子智能体 childSessionId 类型错误**：服务端返回 String 而非 Number，修复后「查看子智能体」按钮正常显示
- **权限审批弹窗不响应**：修复 pendingInteractions 在快照合并后丢失的问题，permission_request / elicitation_request 弹窗可正常点击
- **清除日志无效**：同时更新 entries 和 entriesFlow，日志页立即刷新
- **回到最新消息按钮无效果**：改用 KeyboardArrowDown 图标（与自动跟随 ArrowDownward 区分），点击后立即滚动到底部

### 新增
- **任务面板**：聊天页右上角新增任务按钮，显示后台运行任务、待审批交互，支持一键取消运行中任务
- **权限/交互响应**：支持官方协议中的 permission_request 和 elicitation_request，弹窗提供允许/拒绝选项
- **子智能体历史查看**：从工具调用卡片进入子智能体只读会话，返回时自动恢复父会话
- **消息数量限制**：默认最多展示 200 条消息，设置页可调至 100/200/500/1000 条，超长会话渲染更流畅
- **中文/英文双语**：全部界面文本补充英文翻译，跟随系统语言自动切换
- **调试日志页**：设置 → 调试 → 查看日志，记录所有协议层请求/响应及用户操作，支持一键复制

## v1.7.0 — 2026-09-12

### 修复
- **模型选项无法加载**：`prepareWorkspace` 返回的 configOptions 对象字段从 `id` 改为 `category`，已兼容两种格式；自愈逻辑改为只看 rows 是否为空，不再因模型列表为空而陷入死循环
- **子智能体解析错误**：`childSessionId` 服务端返回的是 Long 数值而非字符串，修复解析类型后按钮正常显示
- **返回子智能体后状态丢失**：导航路由新增 `parentSessionId` 参数，back 时通过 `force=true` 强制切回父会话

## v1.6.0 — 2026-09-11

### 新增
- 进入应用自动申请相机权限（用于扫码配对）
- QR 码配对（ZXing 本地解码，无需 Google Play Services）
- 前台保活服务（dataSync 类型通知，连接期间常驻）

### 修复
- 共享 bridge 重复打开导致会话 ping-pong
- Initialize 帧未就绪时请求被丢弃
- 握手过期后缺少自愈重连
- 账户加密解密在主线程执行导致启动卡顿
- PNG 导出图片颜色异常
- Canvas 尺寸计算错误

## v1.5.0 ~ v1.5.9

详见 GitHub Releases。主要迭代：流式输出、文件附件上传、消息排队、自动跟随、多设备管理、i18n 补全。
