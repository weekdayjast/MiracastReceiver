# Android 开发环境配置指南

## 已安装组件

### 1. Java Development Kit (JDK)
- **版本**: Java 25.0.2
- **状态**: ✅ 已安装并配置

### 2. Android Studio
- **版本**: 2026.1.1.10
- **安装路径**: `C:\Program Files\Android\Android Studio`
- **大小**: 3.3 GB
- **状态**: ✅ 已安装

### 3. Android SDK
- **安装路径**: `D:\develop\android-sdk`
- **大小**: 5.9 GB
- **已安装组件**:
  - ✅ Android SDK Platform 36 (API Level 36)
  - ✅ Android SDK Build-Tools 36.0.0
  - ✅ Android SDK Platform-Tools 37.0.0
  - ✅ Android SDK Command-line Tools
  - ✅ Android Emulator
  - ✅ Google APIs Intel x86_64 System Image (Android 36)

## 环境变量配置

请按照以下步骤配置环境变量：

### Windows 系统环境变量设置

1. **打开环境变量配置**
   - 右键点击"此电脑" → "属性"
   - 点击"高级系统设置"
   - 点击"环境变量"按钮

2. **添加 ANDROID_HOME 变量**
   - 在"系统变量"中点击"新建"
   - 变量名: `ANDROID_HOME`
   - 变量值: `D:\develop\android-sdk`

3. **添加 Android SDK 路径到 Path**
   - 在"系统变量"中找到 `Path` 变量
   - 点击"编辑"
   - 添加以下路径（每行一个）:
     ```
     D:\develop\android-sdk\platform-tools
     D:\develop\android-sdk\cmdline-tools\latest\bin
     D:\develop\android-sdk\emulator
     ```

4. **保存并重启终端**
   - 点击"确定"保存所有更改
   - 重新打开命令行或 Git Bash

## 验证安装

在新的终端窗口中运行以下命令验证安装：

```bash
# 验证 Java
java -version
javac -version

# 验证 Android SDK
adb version

# 验证 SDK Manager
sdkmanager --version

# 列出已安装的 SDK 包
sdkmanager --list
```

## 启动 Android Studio

1. 打开 Android Studio: `C:\Program Files\Android\Android Studio\bin\studio64.exe`
2. 首次启动时配置向导:
   - 选择 "Custom" 安装类型
   - SDK 路径设置为: `D:\develop\android-sdk`
   - 选择需要的组件（默认即可）

## 投屏应用开发所需关键技术

### 1. Miracast / Wi-Fi Display
- **用途**: 实现无线投屏
- **Android API**: `DisplayManager`, `MediaProjection`
- **所需权限**:
  ```xml
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
  <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  ```

### 2. 支持的投屏协议
- **Miracast/Wi-Fi Direct**: Android 原生支持
- **DLNA**: 需要第三方库（如 Cling）
- **AirPlay**: 需要第三方库（如 AirPlay SDK）
- **自定义 RTSP/RTP**: 可用于跨平台投屏

### 3. 关键技术栈
- **视频编解码**: H.264/H.265 (MediaCodec)
- **音频编解码**: AAC (MediaCodec)
- **流媒体传输**: RTSP, RTP, UDP
- **网络发现**: mDNS/DNS-SD (NSD Service)
- **屏幕捕获**: MediaProjection API

### 4. 推荐开发库
```gradle
dependencies {
    // 网络通信
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // 视频处理
    implementation 'com.google.android.exoplayer:exoplayer:2.19.1'

    // WebRTC (可选，用于实时通信)
    implementation 'org.webrtc:google-webrtc:1.0.+'

    // mDNS 服务发现
    // Android 内置 NsdManager
}
```

## 创建投屏项目步骤

1. **启动 Android Studio**
2. **创建新项目**
   - File → New → New Project
   - 选择 "Empty Activity"
   - 项目名称: MiracastReceiver (或自定义)
   - 语言: Kotlin (推荐) 或 Java
   - 最低 SDK: API 21 (Android 5.0) 或更高
   - 目标 SDK: API 36

3. **配置 AndroidManifest.xml**
   - 添加必要的权限
   - 配置为 TV 应用（如果是电视端）

4. **主要功能模块**
   - 网络服务发现
   - Wi-Fi Direct 连接管理
   - 视频流接收和解码
   - UI 显示和控制

## 故障排查

### 问题：adb 无法识别
**解决方案**:
- 确保已添加环境变量
- 重启终端或电脑
- 运行 `where adb` 检查路径

### 问题：模拟器无法启动
**解决方案**:
- 在 BIOS 中启用虚拟化技术 (Intel VT-x / AMD-V)
- 安装 HAXM (Hardware Accelerated Execution Manager)
- 检查 Hyper-V 设置

### 问题：Gradle 构建失败
**解决方案**:
- 检查网络连接
- 配置国内镜像源（阿里云、腾讯云）
- 更新 Gradle 版本

## 下一步

1. ✅ 配置环境变量
2. ✅ 启动 Android Studio
3. ✅ 创建第一个投屏项目
4. 📱 开始开发投屏接收端应用

## 参考资源

- [Android Developer 官方文档](https://developer.android.com/)
- [Android TV 开发指南](https://developer.android.com/tv)
- [MediaProjection API](https://developer.android.com/reference/android/media/projection/MediaProjection)
- [Wi-Fi Direct 开发](https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)

---

**配置日期**: 2026-07-08
**文档版本**: 1.0
