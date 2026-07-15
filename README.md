# Miracast Receiver for Android TV

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-5.0%2B-green.svg)](https://developer.android.com)
[![GitHub release](https://img.shields.io/github/v/release/weekdayjast/MiracastReceiver)](https://github.com/weekdayjast/MiracastReceiver/releases)

> 由于电视投屏广告日益猖獗，乐播投屏只允许本人快乐几分钟，本人深感震惊、愤怒、无奈，并在反复挣扎、彻夜难眠、认真思考三秒钟后，做出了一个违背祖宗的决定：
>
> 自己开发一个投屏 APK。
>
> 从此以后，我的电视我做主，我的投屏我掌控。
> 拒绝广告绑架，拒绝几分钟体验卡，拒绝看个视频还要先学习理财、买车、装宽带。
>
> 本项目将秉持“能用就行、能跑就好、不卡算赢”的核心理念，坚定走自主投屏、自研可控、自娱自乐的发展路线。
>
> 谨以此 APK，献给所有被投屏广告折磨过的灵魂。
>
> —— 一个被广告逼成开发者的人

一个功能强大的 Android TV 投屏接收端应用，支持多种投屏协议，让您的电视轻松接收来自手机、平板和电脑的投屏内容。

**已测试支持**：Bilibili、优酷、爱奇艺、Emby 等主流应用投屏

[English](#english) | [中文](#中文)

---

## 中文

### ✨ 功能特性

- **🍎 AirPlay 支持**：完美支持 iPhone、iPad、Mac 投屏
  - 屏幕镜像（FairPlay 解密 + H.264 硬解码）
  - 视频投屏
  - 音频流播放

- **📺 DLNA/UPnP 支持**：兼容各平台 DLNA 客户端
  - Bilibili、优酷、爱奇艺等视频 App
  - Windows Media Player
  - VLC Player
  - Emby、Plex 等媒体服务器

- **🖥️ Miracast/WFD**：Windows 无线显示器协议（基础支持）
  - RTSP 服务器
  - RTP/H.264 视频流接收
  - Wi-Fi Direct P2P

- **🎬 强大的播放能力**
  - 支持 HTTP/HTTPS 视频流
  - 支持 HLS（.m3u8）直播流
  - 支持多种视频格式（MP4、MKV、AVI 等）
  - 支持图片幻灯片播放
  - 倍速播放（0.5x - 2x）
  - 画质选择（480p - 1080p）

### 📱 支持的投屏来源

| 平台 | 协议 | 状态 | 说明 |
|------|------|------|------|
| iPhone/iPad | AirPlay | ✅ 完美支持 | 屏幕镜像、视频、音频投屏 |
| Android 手机 | DLNA | ✅ 完美支持 | Bilibili、优酷等 App |
| Windows | DLNA | ✅ 完美支持 | 媒体播放器投屏 |
| Windows | Miracast | ⚠️ 受限支持 | 需手动 Wi-Fi Direct 配对 |
| Mac | AirPlay | ✅ 完美支持 | 系统原生支持 |
| Emby/Plex | DLNA | ✅ 兼容 | 媒体服务器投屏 |

### 🚀 快速开始

#### 下载安装

1. 前往 [Releases](https://github.com/weekdayjast/MiracastReceiver/releases) 下载最新版本 APK
   - **ARM64 (推荐)**：适用于大多数现代 Android TV
   - **ARM32**：适用于老旧设备

2. 通过以下方式安装到 Android TV：
   - U 盘安装：复制 APK 到 U 盘，插入电视，用文件管理器打开安装
   - ADB 安装：`adb install app-release.apk`
   - 远程 ADB：`adb connect <TV_IP>:5555 && adb install app-release.apk`

#### 使用方法

1. 在 Android TV 上启动 **Miracast Receiver** 应用
2. 应用会显示设备名称、IP 地址和连接码
3. 根据你的设备选择投屏方式：

**iPhone/iPad 投屏**：
- 打开控制中心 → 屏幕镜像 → 选择 "Sony BRAVIA 4K VH22"（或你的设备名）
- 或在视频 App 中点击 AirPlay 图标

**Android 手机投屏**：
- 在支持 DLNA 的 App（如 Bilibili）中点击投屏按钮
- 选择显示的设备名称

**Windows 投屏**：
- 使用 VLC 或 Windows Media Player 的"播放到"功能
- 或使用 Emby、Plex 等媒体服务器

### 🛠️ 从源码构建

#### 环境要求

- Android Studio 2026.1+
- Android SDK API 36
- JDK 17+
- Gradle 8.11.1

#### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/weekdayjast/MiracastReceiver.git
cd MiracastReceiver

# 构建 Debug APK
cd MiracastReceiver
./gradlew assembleDebug

# 构建 Release APK（需要配置签名）
./gradlew assembleRelease

# 生成 64 位和 32 位 APK
./gradlew assembleRelease -PbuildArm64=true
./gradlew assembleRelease -PbuildArm32=true
```

生成的 APK 位于：`app/build/outputs/apk/`

### 📖 文档

- [开发与调试指南](开发与调试指南.md) - 完整的开发、打包、安装和调试说明
- [Windows 无线显示器](Windows无线显示器支持说明.md) - Miracast 协议说明和限制

### 🔧 技术栈

- **语言**：Kotlin
- **最低 SDK**：Android 5.0 (API 21)
- **目标 SDK**：Android 14 (API 36)
- **UI 框架**：Android TV Leanback
- **视频播放**：ExoPlayer (Media3)
- **网络**：OkHttp, mDNS/NSD
- **协议**：AirPlay, DLNA/UPnP, Miracast/WFD, RTSP, RTP

### 🙏 致谢与参考

- AirPlay 屏幕镜像实现借鉴并参考了 [PhairPlay](https://github.com/philippe44/PhairPlay) 项目的协议流程与实现思路。

### 🐛 已知问题

1. **Windows 无线显示器自动发现**
   - 由于 Android 系统限制，普通应用无法完全模拟 Miracast Sink
   - Windows "Win+K" 无法自动发现设备
   - 需要通过手动 Wi-Fi Direct 配对或使用 DLNA 投屏

2. **Emby 投屏兼容性**
   - 部分 Emby 客户端的 SOAP 请求格式可能需要特殊处理

### 🗺️ 后续计划

- **完善 AirPlay 支持**
  - 优化屏幕镜像稳定性和延迟
  - 优化音视频同步

- **开发 Windows 无线投屏**
  - 深入适配 Miracast / Wi-Fi Display 协议
  - 完善 RTSP/RTP/H.264 接收链路
  - 探索 Windows "无线显示器" 自动发现和连接方案

### 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## English

### ✨ Features

- **🍎 AirPlay Support**: Perfect compatibility with iPhone, iPad, and Mac
  - Screen mirroring (FairPlay decryption + H.264 hardware decoding)
  - Video casting
  - Audio streaming

- **📺 DLNA/UPnP Support**: Compatible with DLNA clients across platforms
  - Bilibili, Youku, iQiyi video apps
  - Windows Media Player
  - VLC Player
  - Emby, Plex media servers

- **🖥️ Miracast/WFD**: Windows Wireless Display protocol (basic support)
  - RTSP server
  - RTP/H.264 video stream reception
  - Wi-Fi Direct P2P

- **🎬 Powerful Playback**
  - HTTP/HTTPS video streams
  - HLS (.m3u8) live streams
  - Multiple video formats (MP4, MKV, AVI, etc.)
  - Image slideshow
  - Playback speed control (0.5x - 2x)
  - Quality selection (480p - 1080p)

### 📱 Supported Sources

| Platform | Protocol | Status | Notes |
|----------|----------|--------|-------|
| iPhone/iPad | AirPlay | ✅ Perfect | Screen mirroring, video, audio casting |
| Android | DLNA | ✅ Perfect | Bilibili, Youku apps |
| Windows | DLNA | ✅ Perfect | Media player casting |
| Windows | Miracast | ⚠️ Limited | Manual Wi-Fi Direct pairing required |
| Mac | AirPlay | ✅ Perfect | Native system support |
| Emby/Plex | DLNA | ✅ Compatible | Media server casting |

### 🚀 Quick Start

#### Download & Install

1. Download the latest APK from [Releases](https://github.com/weekdayjast/MiracastReceiver/releases)
   - **ARM64 (Recommended)**: For most modern Android TVs
   - **ARM32**: For older devices

2. Install on Android TV:
   - USB: Copy APK to USB drive, plug into TV, install via file manager
   - ADB: `adb install app-release.apk`
   - Remote ADB: `adb connect <TV_IP>:5555 && adb install app-release.apk`

#### Usage

1. Launch **Miracast Receiver** on your Android TV
2. The app displays device name, IP address, and connection code
3. Cast from your device:

**iPhone/iPad**:
- Control Center → Screen Mirroring → Select your TV name
- Or tap AirPlay icon in video apps

**Android Phone**:
- Tap cast button in DLNA-enabled apps (e.g., Bilibili)
- Select the displayed device name

**Windows**:
- Use "Play To" in VLC or Windows Media Player
- Or cast from Emby, Plex media servers

### 🛠️ Build from Source

#### Requirements

- Android Studio 2026.1+
- Android SDK API 36
- JDK 17+
- Gradle 8.11.1

#### Build Steps

```bash
# Clone repository
git clone https://github.com/weekdayjast/MiracastReceiver.git
cd MiracastReceiver

# Build debug APK
cd MiracastReceiver
./gradlew assembleDebug

# Build release APK (requires signing configuration)
./gradlew assembleRelease

# Build 64-bit and 32-bit APKs
./gradlew assembleRelease -PbuildArm64=true
./gradlew assembleRelease -PbuildArm32=true
```

Output APKs: `app/build/outputs/apk/`

### 📖 Documentation

- [Development Guide](开发与调试指南.md) - Complete development, packaging, installation, and debugging instructions (Chinese)
- [Windows Wireless Display](Windows无线显示器支持说明.md) - Miracast protocol explanation and limitations (Chinese)

### 🔧 Tech Stack

- **Language**: Kotlin
- **Min SDK**: Android 5.0 (API 21)
- **Target SDK**: Android 14 (API 36)
- **UI Framework**: Android TV Leanback
- **Video Player**: ExoPlayer (Media3)
- **Networking**: OkHttp, mDNS/NSD
- **Protocols**: AirPlay, DLNA/UPnP, Miracast/WFD, RTSP, RTP

### 🙏 Acknowledgements & References

- AirPlay screen mirroring is inspired by and references the protocol flow and implementation ideas from [PhairPlay](https://github.com/philippe44/PhairPlay).

### 🐛 Known Issues

1. **Windows Wireless Display Auto-Discovery**
   - System limitations prevent full Miracast Sink emulation
   - Windows "Win+K" cannot auto-discover the device
   - Use manual Wi-Fi Direct pairing or DLNA casting

2. **Emby Casting Compatibility**
   - Some Emby clients may require special SOAP format handling

### 🗺️ Roadmap

- **Enhance AirPlay Support**
  - Optimize screen mirroring stability and latency
  - Improve audio-video synchronization

- **Develop Windows Wireless Display**
  - Deep adaptation of Miracast / Wi-Fi Display protocol
  - Improve RTSP/RTP/H.264 reception pipeline
  - Explore Windows "Wireless Display" auto-discovery and connection solutions

### 🤝 Contributing

Issues and Pull Requests are welcome!

### 📄 License

This project is licensed under the MIT License - see [LICENSE](LICENSE) file for details

---

## 📞 Contact

- GitHub Issues: [https://github.com/weekdayjast/MiracastReceiver/issues](https://github.com/weekdayjast/MiracastReceiver/issues)

## ⭐ Star History

If you find this project helpful, please give it a star!
