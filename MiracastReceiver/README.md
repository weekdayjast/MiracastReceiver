# Miracast Receiver - Android TV 投屏应用

## 项目简介

这是一个运行在 Android TV 上的投屏接收端应用，支持 Android 手机、iPhone、Windows 和 Mac 电脑投屏到电视。

## 当前状态

**阶段 0 - MVP 版本已完成**

已实现的功能：

- ✅ Android TV 基础框架搭建
- ✅ 主界面（等待连接页面）
- ✅ mDNS 服务广播
- ✅ 网络工具类（IP 获取、连接码生成）
- ✅ 设备信息提供器
- ✅ 编解码能力检测
- ✅ 后台前台服务
- ✅ 播放页面框架
- ✅ 连接确认页面框架

待实现的核心功能：

- ⏳ WebRTC 信令服务器
- ⏳ WebRTC PeerConnection 接收
- ⏳ 视频流解码和渲染
- ⏳ 音频播放
- ⏳ Android 发送端应用

## 技术栈

### 电视端（已完成基础框架）

- **语言**: Kotlin
- **最低 SDK**: Android 5.0 (API 21)
- **目标 SDK**: Android 14 (API 36)
- **UI 框架**: Android TV Leanback
- **网络**: OkHttp, mDNS/NSD
- **视频**: ExoPlayer, MediaCodec
- **实时通信**: WebRTC
- **日志**: Timber

### 依赖库

```gradle
// AndroidX
androidx.core:core-ktx:1.15.0
androidx.leanback:leanback:1.2.0-alpha04
androidx.tvprovider:tvprovider:1.1.0-alpha01

// 协程
kotlinx-coroutines-android:1.9.0

// 网络
okhttp3:okhttp:4.12.0
retrofit2:retrofit:2.11.0

// 视频播放
androidx.media3:media3-exoplayer:1.5.0

// WebRTC
org.webrtc:google-webrtc:1.0.32006

// 日志
timber:5.0.1
```

## 项目结构

```
MiracastReceiver/
├── app/src/main/
│   ├── java/com/weekd/miracastreceiver/
│   │   ├── MiracastApp.kt              # Application 类
│   │   ├── ui/                         # UI 层
│   │   │   ├── MainActivity.kt         # 主界面（等待连接）
│   │   │   ├── PlayerActivity.kt       # 播放界面
│   │   │   └── ConnectActivity.kt      # 连接确认界面
│   │   ├── discovery/                  # 服务发现
│   │   │   ├── MdnsAdvertiser.kt       # mDNS 广播
│   │   │   └── DeviceInfoProvider.kt   # 设备信息
│   │   ├── signaling/                  # 信令协议
│   │   │   └── MessageProtocol.kt      # 消息定义
│   │   ├── stream/                     # 流处理
│   │   │   ├── WebRtcReceiver.kt       # WebRTC 接收
│   │   │   └── VideoRenderer.kt        # 视频渲染
│   │   ├── security/                   # 安全
│   │   │   └── PinCodeManager.kt       # PIN 码管理
│   │   ├── service/                    # 后台服务
│   │   │   └── CastReceiverService.kt  # 投屏服务
│   │   └── utils/                      # 工具类
│   │       ├── NetworkUtils.kt         # 网络工具
│   │       └── CodecUtils.kt           # 编解码工具
│   ├── res/
│   │   ├── layout/                     # 布局文件
│   │   ├── values/                     # 资源文件
│   │   └── drawable/                   # 图标资源
│   └── AndroidManifest.xml
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建说明

### 环境要求

- Android Studio 2026.1+
- Android SDK API 36
- JDK 17+
- Gradle 8.11.1

### 构建命令

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug

# 清理构建
./gradlew clean
```

### 输出位置

```
app/build/outputs/apk/debug/app-debug.apk
```

## 运行说明

### 在 Android TV 模拟器上运行

1. 打开 Android Studio
2. Tools → Device Manager
3. 创建 Android TV 设备（选择 API 36）
4. 启动模拟器
5. 运行应用

### 在真实 Android TV 设备上运行

1. 在电视上启用开发者选项
2. 启用 USB 调试或 Wi-Fi ADB
3. 连接设备：

```bash
# USB 连接
adb devices

# Wi-Fi 连接
adb connect <TV_IP>:5555
```

4. 安装 APK：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 功能说明

### 主界面功能

- 显示设备名称（自动获取）
- 显示设备 IP 地址
- 显示 6 位连接码
- mDNS 服务广播
- 等待投屏连接状态

### mDNS 服务发现

电视端通过 mDNS 广播以下信息：

- 服务类型：`_miracast-receiver._tcp.`
- 服务名称：设备名称
- 端口：8080
- 设备属性：
  - version: 1.0
  - platform: android-tv
  - protocol: webrtc,rtsp
  - code: 连接码
  - ip: IP 地址
  - h264/h265: 支持的编解码器

## 下一步开发计划

### 阶段 1：WebRTC 信令服务器

- [ ] HTTP/WebSocket 信令服务器
- [ ] 连接请求处理
- [ ] SDP Offer/Answer 交换
- [ ] ICE Candidate 交换

### 阶段 2：WebRTC 接收和播放

- [ ] PeerConnection 创建和配置
- [ ] 视频轨道接收
- [ ] 音频轨道接收
- [ ] SurfaceView 渲染
- [ ] AudioTrack 播放

### 阶段 3：Android 发送端

- [ ] Android 发送端应用
- [ ] MediaProjection 屏幕采集
- [ ] MediaCodec 编码
- [ ] WebRTC 推流

## 许可证

本项目仅用于学习和研究目的。

## 联系方式

如有问题，请查看开发计划文档或提交 Issue。
