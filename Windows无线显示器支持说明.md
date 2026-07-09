# Windows 无线显示器（Miracast）支持说明

## 已实现的功能

### 1. Wi-Fi Direct (P2P) 支持
- ✅ 创建 Wi-Fi Direct 组（作为 Group Owner）
- ✅ P2P 服务发现
- ✅ P2P 连接管理
- ✅ WFD RTSP 服务器（端口 7236）
- ✅ RTP H.264 视频流接收和解码

### 2. 核心组件
- `WifiDirectManager.kt` - Wi-Fi Direct P2P 管理
- `WfdServer.kt` - WFD RTSP 协议服务器
- `WfdSessionHandler.kt` - RTSP 会话处理（OPTIONS/GET_PARAMETER/SET_PARAMETER/SETUP/PLAY/PAUSE/TEARDOWN）
- `RtpReceiver.kt` - RTP 包解析和 H.264 解码

### 3. 必需权限
已添加到 `AndroidManifest.xml`：
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />
```

## 重要限制

### Android 应用层的限制
普通 Android 应用**无法完全模拟**系统级的 Wi-Fi Display Sink，原因如下：

1. **WFD IE（Information Element）广播**
   - Windows 的 Miracast 发现依赖于 Wi-Fi beacon/probe response 中的 WFD IE
   - 只有系统级服务（如 `wifip2pd`）才能在 Wi-Fi 帧中插入 WFD IE
   - 普通应用无法直接操作 Wi-Fi 底层帧

2. **系统 API 限制**
   - `WifiP2pManager.setWFDInfo()` 等关键 API 需要系统权限
   - 设备名称设置需要系统权限
   - Wi-Fi Display 角色广播需要系统服务支持

3. **Android TV 系统集成**
   - 原生 Android TV 通常已有内置的 Cast/Miracast 接收器
   - 第三方应用难以替代或增强系统功能

## 实际应用场景

### ✅ 可以工作的场景

1. **Android 到 Android TV（自定义发送端）**
   - 使用我们的自定义协议（mDNS 发现 + WebRTC/RTSP）
   - 完全可控

2. **iOS AirPlay 到 Android TV**
   - ✅ 已实现并测试通过
   - 使用 AirPlay 协议栈

3. **DLNA/UPnP 投屏**
   - ✅ 已实现并测试通过
   - 支持视频/音频/图片

4. **局域网内已配对的 Wi-Fi Direct 设备**
   - 如果设备已通过 Wi-Fi Direct 配对
   - 可以建立 RTSP 连接并接收流

### ❌ 无法直接工作的场景

1. **Windows "连接"功能自动发现**
   - Windows 通过扫描 Wi-Fi beacon 中的 WFD IE 来发现设备
   - 普通应用无法插入 WFD IE
   - **需要系统级支持或 Root 权限**

2. **标准 Miracast 源设备自动发现**
   - 同样依赖 WFD IE 广播
   - 需要系统服务支持

## 解决方案和替代方案

### 方案 1：手动配对（推荐用于测试）

在 Android TV 上：
1. 进入系统设置
2. 启用 Wi-Fi Direct
3. 等待设备出现

在 Windows 上：
1. 设置 → 设备 → 蓝牙和其他设备
2. 添加蓝牙或其他设备
3. 选择"无线显示器或扩展坞"
4. 手动选择 Android TV 设备

### 方案 2：使用 Root 权限或系统应用

如果设备已 Root：
- 可以直接调用 `wpa_cli` 或 `wpa_supplicant` 设置 WFD IE
- 需要修改系统配置文件

编译为系统应用：
- 使用平台签名
- 获取 `CHANGE_WIFI_STATE` 等系统级权限

### 方案 3：使用替代协议（已实现）

使用本应用的替代投屏方式：
- ✅ **AirPlay**（iPhone/iPad/Mac）
- ✅ **DLNA/UPnP**（Windows Media Player、VLC 等）
- ✅ **自定义 WebRTC**（开发自定义发送端）

### 方案 4：修改系统配置（需要 ADB）

通过 ADB 启用系统级 Wi-Fi Display：

```bash
# 启用 Wi-Fi Display
adb shell settings put global wifi_display_on 1

# 设置 WFD 设备信息（需要 Root）
adb shell setprop wifi.direct.wfd.enabled 1
```

## 使用建议

### 对于终端用户
1. **iOS 设备** → 使用 AirPlay（完美支持）
2. **Windows 设备** → 使用 DLNA 投屏（VLC、Windows Media Player）
3. **Android 设备** → 开发配套的发送端应用

### 对于开发者
1. 如果要完整支持 Windows 无线显示器，考虑：
   - 开发系统级应用
   - 或者与设备厂商合作集成

2. 使用本应用的替代方案：
   - AirPlay 协议栈（已实现）
   - DLNA/UPnP 协议（已实现）
   - 自定义 WebRTC 协议（RTSP 已实现）

## 技术细节

### Windows 无线显示器发现流程

1. Windows 扫描 Wi-Fi 环境
2. 检查 beacon/probe response 中的 **WFD IE**
3. WFD IE 包含：
   - 设备类型（Source/Sink）
   - RTSP 端口
   - 支持的编解码器
   - 会话管理能力

4. 如果发现 WFD Sink，Windows 显示在"连接"列表中

### 为什么普通应用无法实现

```java
// 以下代码需要系统权限
WifiP2pManager.setWFDInfo(channel, wfdInfo, listener);

// WFD Info 结构
WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
wfdInfo.setWfdEnabled(true);
wfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);  // 或 WFD_PRIMARY_SINK
wfdInfo.setSessionAvailable(true);
wfdInfo.setControlPort(7236);  // RTSP 端口
wfdInfo.setMaxThroughput(50);  // Mbps
```

普通应用调用上述 API 会抛出 `SecurityException`。

## 当前实现状态

### ✅ 已完成
- Wi-Fi Direct P2P 组创建
- WFD RTSP 服务器
- RTP H.264 视频接收和解码
- 完整的 WFD 协议栈实现

### ⚠️ 系统限制
- WFD IE 广播（需要系统权限）
- 自动设备发现（依赖 WFD IE）

### 🎯 实际可用
- AirPlay 投屏（iOS 设备）
- DLNA 投屏（各平台）
- 手动配对后的 Miracast 连接

## 测试方法

### 方法 1：检查 Wi-Fi Direct 状态
```bash
adb shell dumpsys wifip2p
```

### 方法 2：检查服务是否运行
```bash
adb shell ps | grep miracastreceiver
adb logcat | grep "WifiDirect\|WFD\|Miracast"
```

### 方法 3：测试 RTSP 端口
```bash
# 从 PC 测试
telnet <ANDROID_TV_IP> 7236
```

## 结论

本应用已实现完整的 **WFD RTSP 协议栈和 RTP 接收**，可以处理 Miracast 视频流。

**限制**：Windows 无线显示器的**自动发现**需要系统级支持，普通应用无法实现。

**推荐方案**：
- 使用 **AirPlay**（iOS）或 **DLNA**（Windows）进行投屏
- 或通过手动 Wi-Fi Direct 配对后使用 Miracast
- 开发配套的自定义发送端应用（Android）

## 参考资料

- [Wi-Fi Display Technical Specification](https://www.wi-fi.org/discover-wi-fi/wi-fi-display)
- [Android Wi-Fi P2P API](https://developer.android.com/guide/topics/connectivity/wifip2p)
- [RTSP RFC 2326](https://tools.ietf.org/html/rfc2326)
- [RTP RFC 3550](https://tools.ietf.org/html/rfc3550)
