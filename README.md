# ZCode-Android（Zemote）

简体中文 | [English](README_EN.md)

Android 端的 ZCode 远程控制客户端。通过逆向官方 Web 远程控制页面的通信协议实现，
可以在手机上查看和操控桌面端 ZCode 的会话，不依赖浏览器。

Kotlin + Jetpack Compose（Material 3）编写，全部代码为独立实现。

📱 **在线预览**: [damianjiang.github.io/ZCode-Android](https://damianjiang.github.io/ZCode-Android)

<p align="center">
  <img src="screenshots/home_light.png" width="24%" alt="设备列表" />
  <img src="screenshots/add_device_sheet.png" width="24%" alt="添加设备" />
  <img src="screenshots/settings_light.png" width="24%" alt="设置页" />
  <img src="screenshots/home_dark.png" width="24%" alt="深色模式" />
</p>

## ⚠️ 免责声明

- 本项目**不是官方客户端**，与 ZCode 官方没有任何关系。协议来自对官方 Web 页面的抓包与逆向，官方一更新就可能失效。
- 仅供个人连接自己的设备使用，请遵守 ZCode 服务条款和当地法律，使用风险自负。
- 远程控制 URL 里的 `sid` / `hash` 等同于设备凭据，**不要分享给任何人**。泄露后在桌面端重新生成二维码即可作废。
- 本项目不收集任何数据，凭据用 Android Keystore（AES/GCM）加密后只存在手机本地。

## ✨ 功能

- **设备配对**：扫描桌面端配对二维码或粘贴远程控制 URL，支持保存多台设备并随时切换
- **工作区浏览**：查看桌面端打开的目录，按工作区查看各自的会话
- **会话列表**：运行中 / 历史会话，实时推送更新（sessions-index 订阅 + bootstrap 合并）
- **对话**：流式实时输出（思考、回复、工具调用边生成边显示），Markdown 渲染，图片消息内联展示
- **执行过程卡片**：连续工具调用聚合为摘要卡片，展示执行了什么命令、修改了哪些文件，点击展开看原始输出
- **附件上传**：发送图片和文件（分片上传），收到图片消息直接渲染
- **消息排队**：AI 回复期间发送的消息进入队列，支持立即发送、编辑、删除、拖动排序
- **模型与思考等级切换**：顶部状态栏实时显示当前模型、思考等级、上下文用量
- **自动跟随 / 手动浏览**：支持自动滚到底部，也可关闭后手动上翻历史
- **回到最新消息**：折叠式按钮，一键滚回最新内容
- **深色模式**：系统跟随 + 手动切换，支持 Android 12+ 动态取色
- **前台保活服务**：连接期间常驻通知，降低后台被杀导致的掉线
- **子智能体**：工具调用卡片内置「查看子智能体」按钮，点击进入只读子会话，返回时自动恢复父会话
- **权限审批**：完整支持 `permission_request` / `elicitation_request`，在手机上即可审批文件或执行命令
- **任务面板**：聊天页右上角显示后台运行中的任务、待审批交互，支持一键终止运行中任务
- **调试日志**：设置内新增日志页，记录所有协议请求/响应与用户操作，支持一键复制反馈
- **缓存管理**：设置页可单独清除会话桥接缓存（断开工作区连接）和调试日志
- **多语言**：中文 / English / 跟随系统，全部界面文本双语覆盖

## 📦 构建

需要 JDK 17 和 Android SDK 35。

```bash
git clone https://github.com/Damianjiang/ZCode-Android.git
cd ZCode-Android
./gradlew assembleRelease   # Windows 用 gradlew.bat
```

产物在 `app/build/outputs/apk/release/`。release 包开启 R8 与资源收缩，
使用 debug 签名，可以直接安装。日常调试用 `assembleDebug`。

系统要求 Android 12+（minSdk 28），仅支持 arm64-v8a 架构。

## 🚀 使用

1. 桌面端 ZCode 打开远程控制，生成配对链接
2. App 内添加设备，粘贴链接或扫码
3. 配对完成后选择工作区，进入会话即可对话

## 📁 项目结构

```
app/src/main/java/app/zemote/
├── MainActivity.kt                  # 应用入口
├── ZemoteApp.kt                     # Application 类
├── crash/
│   └── CrashHandler.kt              # 崩溃捕获与重启
├── protocol/                        # 协议栈（全部独立实现）
│   ├── ConnectionParams.kt          #   URL 解析（sid/hash/t）
│   ├── Proof.kt                     #   HMAC-SHA256 配对证明
│   ├── IpcCodec.kt                  #   7-bit varint 编解码
│   ├── RpcFrameTransport.kt         #   rpc-frame 分片/CRC32/重组
│   ├── ChannelClient.kt             #   Channel RPC 与事件订阅
│   ├── RelayClient.kt               #   WebSocket 长连接、心跳、重连
│   ├── ZemoteClient.kt              #   bootstrap、bridge 打开与恢复
│   ├── BridgeSession.kt             #   workspace bridge 会话
│   └── ConversationV4.kt            #   对话协议：订阅/流式/队列/附件/权限
├── service/
│   └── KeepAliveService.kt          #   前台保活通知
├── state/                           # 状态层
│   ├── AccountStore.kt              #   设备列表持久化
│   ├── AppSessionViewModel.kt       #   连接与会话仓库管理
│   ├── AppSettings.kt               #   应用设置（消息条数等）
│   ├── CredentialCipher.kt          #   Keystore AES/GCM 加密
│   └── LanguagePrefs.kt             #   语言设置
└── ui/                              # Compose 界面
    ├── theme/                       #   M3 主题、配色、字体
    ├── component/                   #   通用组件
    ├── components/                  #   Markdown 渲染
    ├── logger/
    │   └── ZemoteLogger.kt          #   协议层调试日志
    ├── navigation/
    │   └── ZemoteNavHost.kt         #   导航路由
    └── screens/                     #   各页面
        ├── AccountsScreen.kt        #     设备列表
        ├── ChatAndTasksScreen.kt    #     聊天页 + 任务面板
        ├── ChangelogScreen.kt       #     更新日志
        ├── CrashScreen.kt           #     崩溃报告页
        ├── DeviceSwitchSheet.kt     #     设备切换弹窗
        ├── LogScreen.kt             #     调试日志页
        ├── MainScreen.kt            #     底部导航主屏
        ├── MainShellScreen.kt       #     登录后主页
        ├── PersonalizeScreen.kt     #     主题个性化
        ├── QrScanScreen.kt          #     扫码配对
        └── SettingsScreen.kt        #     设置页
```

## 📡 协议

协议栈与官方 Web 客户端行为一致，实现全部独立完成：

| 层级 | 说明 |
|---|---|
| Relay | wss 长连接，10s 心跳，指数退避重连 |
| 配对 | HMAC-SHA256(nonce ‖ role ‖ deviceSid, passHash) |
| IPC | 7-bit varint，类型标签（String / Int / JSON / Bytes / Array） |
| RpcFrame | 512KB 分片、CRC32 校验、ack 应答、断线重传 |
| Channel RPC | request/response promise + 事件监听 |
| Conversation V4 | 订阅快照 + 增量、wire 帧分片重组、会话队列、附件上传、权限审批 |

对话协议的实现细节参考了同项目原 Flutter 版本（协议行为一致）。

## 📄 许可证

MIT。ZCode 名称及相关商标归其权利人所有，本项目与其无任何隶属关系。
