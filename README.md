<p align="center">
  <h1 align="center">📋 纸条 — Soundtransferlower</h1>
  <p align="center"><strong>基于蓝牙 RFCOMM 的点对点通信应用</strong></p>
  <p align="center">无需联网，两台手机配对即用</p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Language-Java-orange" alt="Java">
  <img src="https://img.shields.io/badge/MinSDK-15-green" alt="MinSDK">
  <img src="https://img.shields.io/badge/TargetSDK-34-blue" alt="TargetSDK">
  <img src="https://img.shields.io/badge/Version-2.9.3fix-purple" alt="Version">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License">
  <img src="https://img.shields.io/badge/Autor-秋元-FF6B6B" alt="Author">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Bluetooth-RFCOMM%2F(SPP)-0082FC?style=flat&logo=bluetooth&logoColor=white" alt="Bluetooth RFCOMM">
  <img src="https://img.shields.io/badge/Codec-Concentus%20Opus-yellow" alt="Opus Codec">
  <img src="https://img.shields.io/badge/Android%20Support-4.0.3~16%2F17-success" alt="Android Support">
</p>

---

## 📖 项目简介

**纸条（Soundtransferlower）** 是一款基于**经典蓝牙 RFCOMM（SPP 串口协议）**的安卓点对点通信应用。两台安装本应用的手机配对后，**无需联网**即可实现多种通信功能。

### 二次开发说明

本项目基于秋元的[纸条](https://gitee.com/yonstus/Soundtransferlower)项目进行二次开发，感谢原作者的开源贡献。

This project is a fork/secondary development based on [Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower) by 秋元 (yonstus). Credits to the original author for open-sourcing this project.

本项目由**秋元**开发并维护，源码托管于 Gitee：

> 🔗 [https://gitee.com/yonstus/Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower)

---

## ✨ 功能列表

| 功能 | 说明 |
|------|------|
| 💬 **文字聊天** | 通过 RFCOMM Socket 互发文本消息，支持历史记录持久化（按设备 MAC 分文件保存）、记录导出、删除、复制等 |
| 📁 **文件传输** | 独立服务 + 独立蓝牙 UUID 通道传输任意文件，带进度和速度显示，支持最大约 5GB |
| 🎤 **语音消息** | 按住录音（Opus 编码，8kHz），以文件方式发送，接收端自动接收并可点击播放 |
| 📻 **实时对讲** | 按下说话、松开发送（PTT），Opus 实时编解码 + AudioTrack 流式播放，半双工状态机管理 |
| 📞 **蓝牙语音通话** | 类似电话的"拨号—响铃—接听—挂断"全流程，语音通路复用对讲的 Opus 编解码 |
| 🔔 **召唤提醒** | 给对方发一条高优先级通知（带震动、铃声），点击通知直接进入聊天 |

---

## 🛠️ 技术特点

- **AndroidX 兼容** — 基于 AndroidX 库构建，支持现代化开发特性
- **广泛兼容** — 支持 Android 4.0.3（API 15）到 Android 16/17，覆盖极广设备范围
- **Concentus Opus 编解码** — 纯 Java Opus 实现，无需 JNI 原生库，支持 16kbps 低码率语音编码
- **前台服务保活** — 前台服务 + WakeLock + AlarmManager 心跳 + 开机自启 + 屏幕唤醒，维持蓝牙后台连接
- **独立文件传输通道** — 使用独立 UUID 的 RFCOMM 通道传输文件，不阻塞主聊天通道
- **MD3 风格 UI** — Material Design 3 视觉规范，支持亮色/暗色主题

---

## 📱 兼容性说明

| 项目 | 值 |
|------|------|
| **最低支持版本** | Android 4.0.3（API 15，Ice Cream Sandwich MR1） |
| **最高支持版本** | Android 16/17（已适配运行时权限与前台服务类型） |
| **蓝牙协议** | 经典蓝牙 RFCOMM（SPP），需设备支持蓝牙 2.0+ |
| **编译环境** | compileSdk 34，AGP 8.5.2，Java 8 |

> ⚠️ 本应用使用经典蓝牙 RFCOMM 协议，**不支持 BLE（低功耗蓝牙）**。请确保两台设备均支持经典蓝牙。

---

## 🚀 安装与使用

### 安装方式

1. **直接安装 APK**
   - 从 `app/release/app-release.apk` 获取安装包
   - 在 Android 设备上安装（需开启"允许安装未知来源应用"）

2. **自行编译**
   ```bash
   git clone https://gitee.com/yonstus/Soundtransferlower.git
   cd Soundtransferlower
   ./gradlew assembleDebug
   ```

### 使用步骤

1. **配对设备** — 在两台手机上安装并打开"纸条"，通过蓝牙设置或应用内菜单进行配对
2. **连接设备** — 在"聊天"Tab 中选择已配对的设备，点击连接
3. **开始通信** — 连接成功后即可使用文字聊天、文件传输、语音消息等功能
4. **实时对讲** — 切换到"对讲"Tab，长按对讲按钮进行实时语音通信
5. **拨打电话** — 在聊天页菜单中选择"拨号"，发起蓝牙语音通话
6. **召唤提醒** — 在聊天页"+"菜单中选择"召唤"，向对方发送高优先级提醒通知

---

## 📂 项目结构

```
Soundtransferlower/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/soundtransferlower/
│   │   │   ├── MainActivityNew.java          # 主 Activity（唯一入口）
│   │   │   ├── BluetoothService.java         # 核心 RFCOMM 前台服务
│   │   │   ├── BluetoothFileTransferService.java  # 独立文件传输服务
│   │   │   ├── ChatWorkFragment.java         # 聊天主界面（文字+文件+语音）
│   │   │   ├── TalkbackFragment.java         # 实时对讲界面
│   │   │   ├── CallFragment.java             # 通话中界面
│   │   │   ├── AudioRecorderPlayer.java      # 实时语音引擎（Opus 编解码）
│   │   │   ├── VoiceRecorder.java            # 语音消息引擎
│   │   │   ├── Message.java                  # 消息实体
│   │   │   ├── MessageAdapter.java           # RecyclerView 适配器
│   │   │   ├── BluetoothFinder.java          # 蓝牙扫描封装
│   │   │   ├── BootReceiver.java             # 开机自启广播接收器
│   │   │   └── AlarmManagerHelper.java       # 保活闹钟辅助
│   │   ├── res/                              # 布局、图片、字符串等资源
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── release/
│       └── app-release.apk                   # 预构建 APK
├── gradle/
│   └── libs.versions.toml                    # 依赖版本管理
├── gradle.properties
├── build.gradle
├── settings.gradle
└── README.md
```

---

## 🏗️ 架构概览

```
┌─────────────── MainActivityNew（唯一 Activity）───────────────┐
│  顶部：返回 / 连接状态 / 菜单                                 │
│  ┌─────────────────── fragment_container ──────────────────┐  │
│  │ TalkbackFragment │ ChatWorkFragment │ CallFragment      │  │
│  │ ChatFragment     │ MineFragment                        │  │
│  └────────────────────────────────────────────────────────┘  │
│  底部 Tab：[对讲] [聊天] [我的]                               │
└─────────────────────────────────────────────────────────────┘
         │ bind + MessageCallback
         ▼
┌── BluetoothService（前台服务）──┐   ┌── BluetoothFileTransferService ──┐
│ UUID 00001101 (SPP)            │   │ UUID fa87c0d0（独立传输通道）     │
│ 文本 + 控制信令 + 实时语音     │   │ 文件传输专用                      │
└────────────────────────────────┘   └──────────────────────────────────┘
```

---

## 📄 开源许可

本项目采用 **Apache License 2.0** 开源许可协议。

```
Copyright 2024 秋元

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 👤 作者信息

| 项目 | 信息 |
|------|------|
| **作者** | 秋元 |
| **Gitee** | [https://gitee.com/yonstus/Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower) |
| **当前版本** | 2.9.3fix |

---

<p align="center">
  <strong>如果觉得有用，请给个 ⭐ Star 支持一下！</strong>
</p>

---

# 🌐 English

## 📖 About

**Soundtransferlower** (displayed as "纸条" / "Paper Tape") is a peer-to-peer communication app for Android based on **Bluetooth RFCOMM (SPP - Serial Port Profile)**. Once two phones with this app installed are paired, they can communicate **without any internet connection**.

### Secondary Development Notice

This project is a fork/secondary development based on [Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower) by 秋元 (yonstus). Credits to the original author for open-sourcing this project.

本项目基于秋元的[纸条](https://gitee.com/yonstus/Soundtransferlower)项目进行二次开发，感谢原作者的开源贡献。

Developed and maintained by **秋元 (Qiuyuan)**. Source code is hosted on Gitee:

> 🔗 [https://gitee.com/yonstus/Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower)

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 💬 **Text Chat** | Send and receive text messages via RFCOMM Socket. Supports history persistence (per-device MAC file storage), export, delete, copy, etc. |
| 📁 **File Transfer** | Transfer any file via an independent Bluetooth UUID channel with progress and speed display. Supports files up to ~5GB. |
| 🎤 **Voice Message** | Press and hold to record (Opus encoded, 8kHz), sent as a file. Receiver can play it back with a tap. |
| 📻 **Real-time Walkie-Talkie** | Push-to-Talk (PTT): press to speak, release to send. Opus real-time encoding/decoding + AudioTrack streaming playback with half-duplex state machine. |
| 📞 **Bluetooth Voice Call** | Full call flow similar to a phone call: dial — ring — answer — hang up. Voice path reuses the walkie-talkie Opus codec. |
| 🔔 **Summon / Alert** | Send a high-priority notification to the other party (with vibration and ringtone). Tapping the notification opens the chat directly. |

---

## 🛠️ Technical Highlights

- **AndroidX Compatible** — Built on AndroidX libraries with modern development support
- **Wide Compatibility** — Supports Android 4.0.3 (API 15) through Android 16/17
- **Concentus Opus Codec** — Pure Java Opus implementation, no JNI native libraries required. Supports 16kbps low-bitrate voice encoding
- **Foreground Service Keep-Alive** — Foreground service + WakeLock + AlarmManager heartbeat + boot auto-start + screen wake to maintain Bluetooth background connection
- **Independent File Transfer Channel** — Uses a separate UUID RFCOMM channel for file transfers without blocking the main chat channel
- **MD3 Style UI** — Material Design 3 visual system with light/dark theme support

---

## 📱 Compatibility

| Item | Value |
|------|-------|
| **Minimum Supported** | Android 4.0.3 (API 15, Ice Cream Sandwich MR1) |
| **Maximum Supported** | Android 16/17 (runtime permissions and foreground service types adapted) |
| **Bluetooth Protocol** | Classic Bluetooth RFCOMM (SPP), requires Bluetooth 2.0+ on both devices |
| **Build Environment** | compileSdk 34, AGP 8.5.2, Java 8 |

> ⚠️ This app uses **Classic Bluetooth RFCOMM**, not BLE (Bluetooth Low Energy). Ensure both devices support Classic Bluetooth.

---

## 🚀 Installation & Usage

### Install

1. **Install APK directly**
   - Get the APK from `app/release/app-release.apk`
   - Install on your Android device (enable "Install from unknown sources" if needed)

2. **Build from source**
   ```bash
   git clone https://gitee.com/yonstus/Soundtransferlower.git
   cd Soundtransferlower
   ./gradlew assembleDebug
   ```

### How to Use

1. **Pair devices** — Install and open "纸条" on two phones. Pair via Bluetooth settings or the in-app menu.
2. **Connect** — In the "Chat" tab, select a paired device and tap Connect.
3. **Start chatting** — Once connected, use text chat, file transfer, voice messages, and more.
4. **Walkie-Talkie** — Switch to the "Talkback" tab and hold the PTT button for real-time voice communication.
5. **Voice Call** — In the chat page menu, select "Dial" to initiate a Bluetooth voice call.
6. **Summon** — In the chat page "+" menu, select "Summon" to send a high-priority alert notification.

---

## 📄 License

This project is licensed under the **Apache License 2.0**.

---

## 👤 Author

| Item | Info |
|------|------|
| **Author** | 秋元 (Qiuyuan) |
| **Gitee** | [https://gitee.com/yonstus/Soundtransferlower](https://gitee.com/yonstus/Soundtransferlower) |
| **Current Version** | 2.9.3fix |

---

<p align="center">
  <strong>If you find this useful, please give a ⭐ Star!</strong>
</p>
