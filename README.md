# Zemote — ZCode Android 远程控制客户端

> 📱 **手机远程控制桌面 ZCode 的开源 Android 客户端** —— 随时随地掌控你的 AI 编程助手

<p align="center">
  <img src="screenshots/home_light.png" width="24%" alt="设备列表 浅色" />
  <img src="screenshots/add_device_sheet.png" width="24%" alt="添加设备" />
  <img src="screenshots/settings_light.png" width="24%" alt="设置页" />
  <img src="screenshots/home_dark.png" width="24%" alt="深色模式" />
</p>

---

## ⚠️ 免责声明（必读）

> ### 本项目与 ZCode 官方 **没有任何关联**
>
> - ❌ **这不是官方客户端**，也**不是官方 API**
> - 🔍 本项目是通过对 ZCode 官方 Web 远程控制功能进行 **协议逆向 / 抓包分析（Reverse Engineering）** 得到的**独立第三方实现**，全部代码从零编写
> - 🧪 协议行为基于公开流量的观测与复刻，**随时可能因官方更新而失效**，不保证任何稳定性与可用性
> - ⚖️ 本项目仅供**个人学习、研究与自己设备间的互联**使用。使用本项目即表示你已了解并同意自行承担全部风险，请遵守 ZCode 服务条款与所在地区法律法规
> - 🔐 远程控制 URL 中包含你设备的访问凭据（`sid` / `hash`），相当于设备钥匙，**切勿分享给他人**；如泄露请在桌面端重新生成二维码使旧凭据失效
> - 📦 本项目不收集任何数据：所有凭据经 **Android Keystore（AES/GCM）** 加密后仅存储在你的手机本地

---

## ✨ 功能特性

- 🔌 **扫码 / 链接配对** —— 粘贴桌面 ZCode 生成的远程控制 URL 即可完成配对
- 📶 **自动重连** —— WebSocket 长连接 + 心跳保活 + 指数退避自动重连，断网恢复后自动回到会话
- 🖥️ **多设备管理** —— 同时保存多台桌面设备，底部弹窗一键切换，连接状态实时可见
- 📂 **工作区列表** —— 查看桌面端打开的工作区，点击进入任务与对话
- 🔒 **凭据加密存储** —— Android Keystore AES/GCM 加密，root 后也无法直接读取明文
- 🌗 **Material You** —— 浅色 / 深色 / 跟随系统，Android 12+ 支持壁纸动态取色
- 🎨 **M3 Expressive 设计** —— 全新设计的渐变图标、脉冲状态点、弹性转场动画
- 🌐 **在线检查更新** —— 通过 GitHub Releases 检测新版本

## 🧱 技术栈

| 组件 | 技术 |
|---|---|
| 语言 | Kotlin 2.1（JVM 17） |
| UI | Jetpack Compose + **Material 3（Material You / M3 Expressive）** |
| 网络 | **OkHttp 4**（WebSocket + HTTP） |
| 构建 | **Gradle** 8.11（Kotlin DSL，版本目录 Version Catalog） |
| 异步 | Kotlin Coroutines + Flow（全链路可观察状态） |
| 持久化 | Android DataStore（Preferences） |
| 加密 | Android Keystore + AES/GCM |
| 序列化 | Gson |
| 系统要求 | Android 8.0+（minSdk 26，targetSdk 35 / Android 15） |

## 📲 下载安装

### 方式一：下载 Releases（推荐）

前往 [**Releases 页面**](../../releases) 下载最新的 `app-debug.apk`，直接安装即可。
安装时如提示"未知来源应用"，请允许浏览器/文件管理器安装应用。

### 方式二：自己构建

```bash
git clone https://github.com/Damian2012/zemote-android.git
cd zemote-android

# Linux / macOS
./gradlew assembleDebug
# Windows
gradlew.bat assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK 35。构建全程只使用 **Gradle 标准流程打包签名**，不依赖任何第三方重打包工具。

## 🚀 快速上手

1. 在桌面端 ZCode 打开 **远程控制**，生成配对二维码 / 链接
2. 手机端点右下角 **➕ 添加设备**，粘贴链接（扫码功能开发中）
3. 等待配对完成（首次最长 90 秒），即可看到桌面端的工作区列表
4. 多台电脑？重复添加即可，底部 **切换设备** 弹窗随时跳转

## 🏗️ 项目结构

```
app/src/main/java/app/zemote/
├── MainActivity.kt              # 入口：Edge-to-Edge + Compose
├── protocol/                    # 逆向复刻的协议栈
│   ├── ConnectionParams.kt      #   URL 解析（sid/hash/t）
│   ├── Proof.kt                 #   HMAC-SHA256 配对证明 + CRC32
│   ├── IpcCodec.kt              #   7-bit varint 编解码 + IPC 帧
│   ├── RpcFrameTransport.kt     #   大消息分片 / CRC 校验 / 重组
│   ├── ChannelClient.kt         #   Channel RPC（call / listen）
│   ├── RelayClient.kt           #   WebSocket 长连接 + 心跳 + 重连
│   ├── ZemoteClient.kt          #   高层门面（bootstrap 等）
│   └── BridgeSession.kt         #   workspace bridge 会话
├── state/                       # 可观察状态层（StateFlow）
│   ├── AccountStore.kt          #   设备列表 + 加密持久化
│   ├── AppSessionViewModel.kt   #   多设备连接状态机
│   └── CredentialCipher.kt      #   Keystore AES/GCM
├── ui/
│   ├── theme/                   # M3 主题：Iris 品牌配色 / 排版 / 模式管理
│   ├── component/               # 脉冲状态点 / 渐变头像等组件
│   ├── navigation/              # Navigation Compose + M3 转场动画
│   └── screens/                 # 设备列表 / 工作区 / 任务 / 对话 / 设置
└── update/UpdateChecker.kt      # GitHub Releases 更新检测（OkHttp）
```

## 🔍 协议逆向说明

协议栈层级与官方 Web 客户端行为一致，但**全部为本项目独立实现**：

| 层级 | 职责 |
|---|---|
| URL 解析 | 从远程控制 URL 提取 `sid` / `hash` / `t` / `mid` / `name` |
| 配对证明 | `HMAC-SHA256(nonce ‖ role ‖ deviceSid, passHash)` |
| Relay | `wss` WebSocket 长连接，10s 心跳，指数退避重连 |
| IPC 编解码 | 7-bit varint、类型标签（String / Int / JSON / Bytes / Array） |
| IPC 帧 | 13 字节头 `[type ‖ id ‖ ack ‖ len]` + body |
| RpcFrame | 512KB 分片、CRC32 校验、乱序重组 |
| Channel RPC | request/response Promise + 事件订阅 |
| Conversation | sessions-index 快照 + 增量、流式消息（开发中） |

## 🗺️ 路线图

- [x] 设备配对与多设备管理
- [x] 工作区列表与连接状态机
- [x] Material You 动态取色 / 深色模式
- [ ] 扫码配对（QR Scanner）
- [ ] 任务列表与对话流（协议层已就绪，UI 对接中）
- [ ] 后台保活通知（Foreground Service）
- [ ] 应用内更新下载安装

## 🔑 关键词 / Keywords

ZCode 远程控制、ZCode 手机客户端、ZCode Android、ZCode mobile、ZCode remote control、
ZCode app、ZCode 客户端、AI 编程远程控制、AI coding agent remote、手机控制电脑写代码、
远程控制开源、Android remote control client、Kotlin Material 3、Material You、
Jetpack Compose M3、OkHttp WebSocket、协议逆向、protocol reverse engineering、
第三方客户端、unofficial ZCode client、开源 Android 应用、开源远程控制工具

## 👤 作者

**Damian2012** · 独立开发，出于学习与自用目的对 ZCode 远程控制协议做了逆向与复刻。
如果这个项目对你有帮助，欢迎点个 ⭐ Star！

## 📄 许可证

本项目以 MIT 许可证开源，仅供参考与学习。使用产生的任何后果由使用者自行承担。
ZCode 名称及相关商标归其权利人所有，本项目与其无任何隶属关系。
