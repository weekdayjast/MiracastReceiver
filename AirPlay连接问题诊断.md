# AirPlay 连接问题诊断与解决

## 常见问题和解决方案

### 1. 网络权限问题

检查是否缺少必要的权限。需要在 AndroidManifest.xml 中添加：

```xml
<!-- 可能缺少的权限 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 2. 端口监听问题

AirPlay 需要监听多个端口，确保没有被其他应用占用：

**检查命令：**
```bash
# 在 Android TV 上执行
adb shell netstat -an | grep -E "7000|6000|6001|6002"
```

**应该看到：**
- TCP 7000 (AirPlay HTTP/RTSP)
- UDP 6000 (RTP Video)
- UDP 7000 (RTP Audio - 可能冲突)
- UDP 6001 (RTCP Control)
- UDP 6002 (NTP Timing)

### 3. mDNS 广播问题

检查 mDNS 服务是否正确注册：

**查看日志：**
```bash
adb logcat | grep -E "AirPlay|mDNS|NsdManager"
```

**应该看到：**
```
I/MdnsAdvertiser: Starting AirPlay advertising: [DeviceName] on port 7000
I/MdnsAdvertiser: AirPlay service registered: [DeviceName]
I/MdnsAdvertiser: Starting RAOP advertising: [MAC@DeviceName] on port 7000
I/MdnsAdvertiser: RAOP service registered: [MAC@DeviceName]
```

### 4. iOS 设备看不到 Android TV

可能的原因：
1. **不在同一网络**：确保 iPhone 和 Android TV 连接到同一个 Wi-Fi
2. **路由器隔离了设备**：某些路由器有 AP 隔离功能
3. **mDNS 被阻止**：防火墙或路由器设置阻止了 mDNS 广播

**解决方案：**
- 检查路由器设置，关闭 AP 隔离
- 确保 5353 端口（mDNS）未被阻止
- 尝试重启路由器

### 5. iOS 发现设备但无法连接

这是最常见的问题。可能的原因：

#### 5.1 音频端口冲突

**问题：**
- RTP 视频端口：UDP 6000 ✅
- RTP 音频端口：UDP 7000 ⚠️（与 AirPlay 信令端口冲突）

**解决方案：**修改音频端口

```kotlin
// 在 AirPlayAudioReceiver.kt 中修改
companion object {
    const val AUDIO_RTP_PORT = 7001  // 改为 7001 避免冲突
    // ...
}
```

#### 5.2 RTSP Transport 端口不匹配

**问题：**iOS 在 SETUP 请求中指定端口，服务器返回的端口必须匹配。

**检查日志：**
```bash
adb logcat | grep "Transport:"
```

**应该看到 iOS 发送的 Transport 头，例如：**
```
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002
```

**解决方案：**确保服务器返回正确的端口

```kotlin
// 在 AirPlayServer.kt 的 sendRtspSetup 方法中
private fun sendRtspSetup(output: OutputStream, cseq: String?) {
    val response = "RTSP/1.0 200 OK\r\n" +
        (cseq?.let { "CSeq: $it\r\n" } ?: "") +
        "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002\r\n" +
        "Session: 1\r\n" +
        "Audio-Jack-Status: connected\r\n" +
        "Audio-Latency: 0\r\n" +
        "Server: AirTunes/366.0\r\n" +
        "\r\n"
    output.write(response.toByteArray())
    output.flush()
}
```

#### 5.3 缺少关键响应头

某些 iOS 版本需要特定的响应头。

**添加到 /info 响应：**
```kotlin
private fun sendRtspInfo(output: OutputStream, cseq: String?) {
    val body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>deviceid</key><string>A6:9F:ED:FA:68:AE</string>
            <key>features</key><integer>0x5A7FFFF7,0x1E</integer>
            <key>model</key><string>AppleTV6,2</string>
            <key>name</key><string>Miracast Receiver</string>
            <key>pi</key><string>A6:9F:ED:FA:68:AE</string>
            <key>pk</key><string>0000000000000000000000000000000000000000000000000000000000000000</string>
            <key>sourceVersion</key><string>366.0</string>
            <key>statusFlags</key><integer>68</integer>
            <key>vv</key><integer>2</integer>
            <key>audioFormats</key>
            <array>
                <dict>
                    <key>type</key><integer>96</integer>
                    <key>audioInputFormats</key><integer>0x01000000</integer>
                    <key>audioOutputFormats</key><integer>0x01000000</integer>
                </dict>
            </array>
            <key>displays</key>
            <array>
                <dict>
                    <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>
                    <key>width</key><integer>1920</integer>
                    <key>height</key><integer>1080</integer>
                    <key>widthPhysical</key><integer>0</integer>
                    <key>heightPhysical</key><integer>0</integer>
                    <key>widthPixels</key><integer>1920</integer>
                    <key>heightPixels</key><integer>1080</integer>
                    <key>refreshRate</key><real>60.0</real>
                    <key>rotation</key><integer>0</integer>
                    <key>overscanned</key><false/>
                </dict>
            </array>
        </dict>
        </plist>
    """.trimIndent()
    sendRtspResponse(output, "200 OK", cseq, "text/x-apple-plist+xml", body.toByteArray())
}
```

#### 5.4 配对认证失败

iOS 可能需要配对认证。

**查看日志：**
```bash
adb logcat | grep "pair"
```

**如果看到 /pair-setup 或 /pair-verify 请求但连接失败，可能需要实现基本的配对响应。**

### 6. 连接建立但没有画面

**可能原因：**
1. Surface 未正确传递给解码器
2. PlayerActivity 未启动
3. 解码器初始化失败

**诊断步骤：**

```bash
# 1. 检查 PlayerActivity 是否启动
adb logcat | grep "PlayerActivity"

# 2. 检查镜像接收器状态
adb logcat | grep "AirPlayMirroringReceiver"

# 3. 检查是否收到 RTP 包
adb logcat | grep "Video packets received"

# 4. 检查解码器状态
adb logcat | grep "decoder"
```

### 7. iOS 版本兼容性

不同 iOS 版本的 AirPlay 协议有差异：

| iOS 版本 | 兼容性 | 说明 |
|---------|--------|------|
| iOS 9-11 | ✅ 较好 | 使用较旧的认证机制 |
| iOS 12.0-12.2 | ⚠️ 中等 | 认证更严格 |
| iOS 12.3+ | ❌ 较差 | 引入 FairPlay 加密 |
| iOS 13+ | ❌ 差 | 更严格的加密和认证 |
| iOS 14+ | ❌ 很差 | 需要完整的配对流程 |

**建议：**
- 使用 iOS 11-12.2 设备进行测试
- iOS 12.3+ 需要实现 FairPlay 解密

## 调试步骤

### 步骤 1: 启用详细日志

```bash
# 清除旧日志
adb logcat -c

# 查看完整日志
adb logcat | grep -E "AirPlay|Miracast|NsdManager|RTP"
```

### 步骤 2: 测试 mDNS 广播

在同一网络的电脑上运行：

**macOS/Linux:**
```bash
dns-sd -B _airplay._tcp .
```

**Windows (需要 Bonjour SDK):**
```bash
dns-sd -B _airplay._tcp
```

**应该看到：**
```
Timestamp     A/R  Flags  if Domain    Service Type    Instance Name
10:30:45.123  Add  2      4  local.    _airplay._tcp.  Miracast Receiver
```

### 步骤 3: 测试 AirPlay HTTP 服务器

从电脑访问 AirPlay 端口：

```bash
# 替换 TV_IP 为你的 Android TV IP 地址
curl http://TV_IP:7000/info

# 应该返回 XML 格式的设备信息
```

### 步骤 4: 抓包分析

如果以上都正常，使用 Wireshark 抓包：

```bash
# 在 Android TV 上启用 tcpdump（需要 root）
adb shell tcpdump -i wlan0 -w /sdcard/airplay.pcap

# 或在路由器上抓包
```

**查找关键信息：**
1. mDNS 查询和响应（端口 5353）
2. RTSP 握手（端口 7000）
3. RTP 数据包（端口 6000, 7000）

## 快速修复代码

创建以下修复文件并应用：

```bash
# 修复音频端口冲突
adb shell "logcat -c"
adb install -r app-debug.apk
adb logcat | grep -E "AirPlay|error|fail"
```

## 总结

最常见的 3 个问题：

1. **端口冲突**（音频端口 7000）
   - 修改 `AUDIO_RTP_PORT = 7001`

2. **Transport 头不匹配**
   - 检查并修复 `sendRtspSetup` 方法

3. **iOS 版本过高**
   - 使用 iOS 11-12.2 测试

如果问题仍然存在，请提供：
- Android TV 系统版本
- iPhone/iPad 型号和 iOS 版本
- 完整的 logcat 日志
- 网络拓扑（路由器型号、是否有 AP 隔离）
