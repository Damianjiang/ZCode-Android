# 项目长期记忆 — ZCode-Android (Zemote)

## 项目性质
Android 端 ZCode 远程控制客户端。Kotlin + Jetpack Compose (Material 3)，
通过逆向官方 Web 远程控制页面的协议实现。代码全部独立实现。
包名 `app.zemote`，源码在 `app/src/main/java/app/zemote/`。

## 关键约定
- 协议版本号：`ConversationV4Session.PROTOCOL_APP_VERSION = "3.6.5"`，
  发本 App 版本号会导致 V4 能力协商失败。
- 单工作区单 bridge，多会话复用同一条桥（多开会互相顶掉）。
- `onDynamicConversationFrame` 是热路径：任何在此路径上的逐条日志/逐条协程/逐条列表重建
  都会直接变成卡顿，改动前先确认是否在热路径上。

## 协议层怎么验证（不要只靠推理）
`app.zemote.protocol` 下的编解码/签名类代码**没有 Android 依赖**，可以脱离 Android 单独跑：
```bash
bash tools/verify-ipc-codec/run.sh    # 退出码 0 = 全部通过
```
做法：用 Gradle 缓存里已有的 `kotlin-compiler-embeddable` 编译成 class 再 `java -cp` 执行，
不需要模拟器、不需要给 app 模块加测试依赖、不需要联网。

两个必踩的坑：
1. Git Bash/MSYS **不会**把 POSIX 路径（`/c/Users/...`）转成 Windows 路径传给 `java`，
   拼进 `-cp` 会 ClassNotFoundException —— 必须过 `cygpath -w`。
2. `${TMPDIR:-/tmp}` 在 Windows 下 TMPDIR 是 `C:\...` 形式，拼进 bash 路径会变成相对路径，
   并触发沙箱的 `rm -rf` 安全拦截。用 `mktemp -d` 且不要删除。

## 模拟器冒烟验证（怎么做）
AVD：`Medium_Phone`。**模拟器活不过工具调用边界**（它是后台任务 shell 的子进程，
任务结束即被回收），所以「启动→安装→操作→截图→关模拟器」必须放进**同一个**后台命令，
并在 `trap EXIT` 里 `adb emu kill`。
```bash
"$EMULATOR" -avd Medium_Phone -no-snapshot -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect &
# 等 sys.boot_completed=1（约 60s）
adb install -r -t "$(cygpath -w .../app-debug.apk)"   # ← 必须 cygpath，adb 是 Windows 二进制
adb shell uiautomator dump /sdcard/ui.xml              # 拿真实控件坐标，别猜
```
已知坑：① 不 `cygpath` 会 `failed to stat /c/Users/...`，然后静默地跑了**旧版本**；
② 系统级弹窗（如 16KB 对齐警告）会挡住 `uiautomator dump`，此时校验结果是无效的，
不要当成通过。

## 已知待处理问题
- **原生库不符合 16KB 页对齐**（模拟器实测弹窗）：`libimage_processing_util_jni.so`(CameraX)、
  `libdatastore_shared_counter.so`(DataStore)、`libandroidx.graphics.path.so`(Compose)。
  均来自依赖库。影响 16KB 页设备与 Play 新提交。
  **`jniLibs { useLegacyPackaging = true }` 的注释声称"确保 16KB 对齐"是错的** ——
  对齐由 `.so` 的 ELF LOAD 段构建参数决定，打包方式解决不了。需升级依赖版本。

## 构建
```bash
./gradlew :app:assembleDebug --console=plain
```
本机 Git Bash 下原先会报 `找不到或无法加载主类 org.gradle.wrapper.GradleWrapperMain`，
原因是 `gradlew` 里 `if $cygwin ; then` 只覆盖 Cygwin、漏了 MSYS/MinGW（Git Bash 走
`MINGW*` 分支只设了 `msys=true` 却从未使用），导致 classpath 分隔符没被 `cygpath` 转换。
已改成 `if $cygwin || $msys ; then`，**不要改回去**。

JDK 17 + Android SDK 35，`local.properties` 里 `sdk.dir=C:/Users/orang/AppData/Local/Android/Sdk`。
产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 版本管理
`app/build.gradle.kts` 的 versionCode/versionName 与 `CHANGELOG.md` 顶部分节保持同步。

## 逆向产物（改协议前必读）
官方 Web 客户端 bundle 就在仓库里，**改协议前先对照它，不要靠猜**：
- `zcode_downloaded/remote/v4/assets/index-nOVzQNKW.js`（主副本）
- `bundle.js`（根目录，同一份）

常用检索方式（文件是压缩过的单行 JS，用定长上下文 grep）：
```bash
grep -o -E ".{200}<关键词>.{200}" zcode_downloaded/remote/v4/assets/index-nOVzQNKW.js
```
已确认的关键语义：
- `sendText` schema：`{text, attachments?, requestedDelivery?: ['startNow','queue','guide'],
  heldQueueDisposition?: ['clearQueueAndSend','keepQueueAndSend'], expectedHeldQueueItemIds?}`；
  `requestedDelivery` 只在存在时下发。
- **必须校验 `sendText` 返回的 `status`**：成功集合 `{accepted, duplicate, noop}`，
  其余（含 `blocked`）按失败处理。`reasonCode === 'guard.heldQueueConfirmationStale'`
  表示队列被占用，需带 `heldQueueDisposition` + `expectedHeldQueueItemIds` 重发。
- 翻页：`beforeRowId = 当前已加载最早一行的 rowId`（不是服务端 `firstRowId`）。
- "还有更早历史"判定 `wb()`：`firstRowId === null → false`，否则 `window[0].rowId > firstRowId`。
- `rowsRange` 响应 `{rows, atSeq, atLogEpoch, hasMore}`，**没有 totalCount**；
  `atLogEpoch` 与当前快照 `logEpoch` 不一致时整页丢弃。

## 性能红线（历史踩坑）
1. 不要在 rows 的发布路径上做整表 `row.copy()`。
2. **内容未变的行必须复用同一个 `ConvRow` 实例**（`publishRows` 的不变量）——
   `buildDisplayItems(rows, prev)` 靠 `===` 做条目复用池，破坏它会让流式期间
   每 60ms 重建整张展示项列表并全量分配对象。
3. 不要在 `RelayClient.handleRawMessage` 里为每条 payload `launch` 协程。
4. 日志写入必须节流；`ZemoteLogger` 已是 300ms 批量发布。
5. 聊天页只有 `MessageTimeline` 订阅 `repo.rows`，不要在 `ChatScreen` 顶层订阅。
6. 不要在 `LazyColumn` 的 `items` 内部构造 lambda（用 `remember` 提到外面），
   也不要给每个条目挂 `rememberInfiniteTransition`——N 个条目 = N 个逐帧动画。
7. **高频状态一律下沉到使用它的子 composable**。`ChatScreen` 顶层只允许订阅
   `working` / `historyState` / `activeId` 三个低频状态；其余全部走独立重组域：
   - `ComposerSection` → `convConfig/usage/modelOptions/stopWorkId/followupMode/queueItems/autoDrain`
   - `ChatHeaderTitle` → `sessionEntries`
   - `ChatHeaderActions` → `pendingInteractions/backgroundWorks`
   - `TaskPanelHost` → 同上，但 `visible=false` 时直接 return 不建立订阅
   - `InteractionDialogHost` → `pendingInteractions`（新请求自动弹出）
   新增状态订阅时请先想清楚放哪一层，不要往 ChatScreen 顶层加。
8. `ScreenHeader` 有 `titleContent: (@Composable () -> Unit)?` 插槽：标题需要自带订阅时
   传它（传了则忽略 `title: String`）。
9. 传给 `MessageTimeline` 的 lambda 必须 `remember` 成稳定实例
   （`loadAttachment` 用 `rememberUpdatedState` 持有最新 repo/activeId）。
10. `mergeRow` 必须是按 rowId 的二分插入，不要退回 `associateBy + sortedBy`。
11. `row.appended` 前必须做 `rowId <= 末行` 的乱序检测并退化为 upsert，
    否则 resync 重放会产生重复 rowId → LazyColumn 重复 key。
12. **relay 的 `payloads` 订阅者必须带 `.buffer(Channel.UNLIMITED)`**。
    SharedFlow 是 `extraBufferCapacity=256` + 默认 SUSPEND，而 bridge 的收集协程在
    collect 体内同步做 Base64/CRC32/value-list 解码，慢订阅者会反压到整条入站管线。
13. `DisplayItem.ToolGroup.key` 只能含**首行** rowId（含末行会让组增长时 key 变化，
    导致「执行过程」卡片被销毁重建 + 展开状态丢失）。
14. **`IpcCodec` 是每帧双向必经的热路径**，改动前先读文件头的性能约定：
    写入端不许用 `ByteArrayOutputStream`（逐字节 `synchronized`），
    读取端不许用 `read(1)[0]` 读 tag、不许用 `String(read(len))` 复制大字段
    （用 `readByte()` / `readUtf8(n)`）。
15. `ZemoteLogger` 单条日志上限 4000 字符；不要把大 payload 直接 `toString()` 写日志
    （环形缓冲 1000 条，一条几 MB 就能吃光内存并挤掉有用日志）。
16. Kotlin `String.toRegex()` **没有缓存**，绝不要写在组合体里（`MainShellScreen`
    的工作区卡片踩过）。

## 危险操作红线（清理/删除类功能）
- **`filesDir` 下存着 DataStore 的持久化文件**：`filesDir/datastore/zemote_settings.preferences_pb`，
  `AccountStore` 把全部已配对设备（Keystore 加密后的配对 URL）放在那里，
  `ThemeManager` 的主题设置也在同一文件。
  **绝不要写 `filesDir.listFiles()?.forEach { it.deleteRecursively() }`** —— 那会抹掉所有设备。
  `filesDir` 里唯一可安全删除的是 `crash_report.txt`（走 `CrashHandler.clear()`）。
  `cacheDir` 可以整体清（按定义就是可丢弃的）。
- 异步清理前**先把选中集合快照出来**：`val toClean = selectedIds`，
  不要先清空 state 再在协程里读它（`CacheCleanScreen` 踩过，导致清理按钮变成空操作）。

## 导航路由约定（加新路由必读）
`workspaceKey` 常常是**文件系统路径**（可能含 `/`、`\`、空格、`#`）。
Navigation 按 `/` 切段匹配 `{占位符}`，路径原样拼进路由会导致**段数不匹配 → navigate() 抛
`IllegalArgumentException: Navigation destination ... cannot be found`**（点进任务页直接崩）。
所以：
- `Screen.createRoute(...)` 里每个参数都要 `Uri.encode`；
- 读取一律用 `ZemoteNavHost.kt` 里的私有扩展 `Bundle?.arg(key)`（内含 `Uri.decode`），
  不要直接用 `arguments?.getString(...)`。

## 状态可观察性约定
- `hasMore` 是私有 `hasMoreRaw` + 可观察的 `hasMoreFlow: StateFlow<Boolean>`。
  UI **不要**直接读 `repo.hasOlderHistory`（它读普通 var，变化不触发重组），
  要订阅 `hasMoreFlow` 后再 `remember(rows, hasMoreFlag) { repo.hasOlderHistory }`。
- 历史加载失败必须走 `markHistoryLoadFailed()`：`loadRows` 的软失败分支
  （响应形状不对 / 缺 rows 字段）**不会抛异常**，只靠 `runCatching.onFailure`
  永远设不上 `HistoryState.FAILED`，页面会永久停在 LOADING 转圈。
- `loadRows` 由 `historyLoadMutex` 串行化；初始加载前会检查「是否已有人填过历史」。

