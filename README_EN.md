# ZCode-Android (Zemote)

[简体中文](README.md) | English

A native Android client for ZCode remote control. Built by reverse-engineering the
communication protocol of the official web remote-control page, it lets you view and
control desktop ZCode sessions from your phone — no browser needed.

Written in Kotlin with Jetpack Compose (Material 3). All code is an independent
implementation.

<p align="center">
  <img src="screenshots/home_light.png" width="24%" alt="Devices" />
  <img src="screenshots/add_device_sheet.png" width="24%" alt="Add device" />
  <img src="screenshots/settings_light.png" width="24%" alt="Settings" />
  <img src="screenshots/home_dark.png" width="24%" alt="Dark mode" />
</p>

## Read this first

- This is not an official client and has no affiliation with ZCode. The protocol comes
  from packet capture and reverse engineering of the official web page — an official
  update can break it at any time.
- Use it only with your own devices. Follow the ZCode terms of service and local laws.
  You assume all risk.
- The `sid` / `hash` in a remote-control URL are device credentials. Never share them.
  If leaked, regenerate the QR code on the desktop to invalidate them.
- This project collects no data. Credentials are encrypted with Android Keystore
  (AES/GCM) and stored only on your phone.

## Features

- Device pairing: scan the desktop pairing QR code or paste the remote-control URL;
  multiple devices supported with one-tap switching
- Workspaces: browse directories opened on the desktop, sessions filtered per workspace
- Session list: running / history sessions with live updates (sessions-index
  subscription merged with bootstrap)
- Chat: streaming output (thinking, replies and tool calls render as they are
  generated), Markdown rendering
- Execution activity: consecutive tool calls aggregated into one card showing which
  commands ran and which files changed, with raw output on tap
- Attachments: send images and files (chunked upload), received images rendered inline
- Queue: messages sent while the AI is busy are queued — send now, edit, delete, and
  drag-to-reorder
- Model / thinking-level switching and context usage display
- Dark mode and dynamic color (Android 12+)
- Foreground keep-alive service: an ongoing notification while connected reduces background disconnections
- **Subagents**: "View subagent" button inside tool call cards; opens a read-only sub-session page, restores parent on back
- **Debug Logs**: Settings → Debug → View Logs records all protocol requests/responses and user actions, one-tap copy for sharing
- In-app language: follow system / 中文 / English

## Build

Requires JDK 17 and Android SDK 35.

```bash
git clone https://github.com/Damianjiang/ZCode-Android.git
cd ZCode-Android
./gradlew assembleRelease   # gradlew.bat on Windows
```

The APK is written to `app/build/outputs/apk/release/`. Release builds use R8 and
resource shrinking, signed with the debug key so they install directly. Use
`assembleDebug` for development.

Requires Android 9.0+ (minSdk 28).

## Usage

1. Open remote control in desktop ZCode and generate a pairing link
2. Add a device in the app and paste the link
3. Once paired, pick a workspace and start chatting

## Project structure

```
app/src/main/java/app/zemote/
├── MainActivity.kt
├── protocol/                  # Reverse-engineered protocol stack
│   ├── ConnectionParams.kt    #   URL parsing (sid/hash/t)
│   ├── Proof.kt               #   HMAC-SHA256 pairing proof
│   ├── IpcCodec.kt            #   7-bit varint codec
│   ├── RpcFrameTransport.kt   #   rpc-frame fragmentation / CRC32 / reassembly
│   ├── ChannelClient.kt       #   Channel RPC and event subscriptions
│   ├── RelayClient.kt         #   WebSocket connection, heartbeat, reconnect
│   ├── ZemoteClient.kt        #   bootstrap, bridge open and recovery
│   ├── BridgeSession.kt       #   workspace bridge session
│   └── ConversationV4.kt      #   Conversation protocol: subscriptions, streaming,
│                              #   queue, attachments
├── state/                     # State layer
│   ├── AccountStore.kt        #   Persisted device list
│   ├── AppSessionViewModel.kt #   Connection and conversation-repo management
│   └── CredentialCipher.kt    #   Keystore AES/GCM encryption
└── ui/                        # Compose UI
    ├── theme/                 #   M3 theme and palettes
    ├── component/             #   Shared components
    ├── components/            #   Markdown rendering
    ├── navigation/            #   Navigation
    └── screens/               #   Devices / workspaces / sessions / chat /
                               #   settings / changelog
```

## Protocol

The stack mirrors the official web client's behavior; the implementation is entirely
independent:

| Layer | Notes |
|---|---|
| Relay | wss connection, 10s heartbeat, exponential-backoff reconnect |
| Pairing | HMAC-SHA256(nonce ‖ role ‖ deviceSid, passHash) |
| IPC | 7-bit varint, type tags (String / Int / JSON / Bytes / Array) |
| RpcFrame | 512KB fragments, CRC32 verification, acks, retransmit on fault |
| Channel RPC | request/response promise + event subscriptions |
| Conversation V4 | snapshot + delta subscriptions, wire-frame reassembly, session queue, attachment upload |

Implementation details of the conversation protocol cross-reference the original
Flutter version of this project (same wire behavior).

## License

MIT. The ZCode name and related trademarks belong to their respective owners; this
project has no affiliation with them.
