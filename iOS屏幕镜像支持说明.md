# iOS 屏幕镜像支持说明

## 概述

本项目现已添加对 iOS 设备的 AirPlay 屏幕镜像支持。iPhone 和 iPad 可以通过 AirPlay 协议将屏幕内容投射到安装了本应用的 Android TV 设备上。

## 功能特性

### 已实现功能

#### 1. AirPlay 服务发现
- ✅ mDNS/Bonjour 服务广播（`_airplay._tcp.` 和 `_raop._tcp.`）
- ✅ 设备信息发布（设备名称、型号、功能特性）
- ✅ iOS 设备可在 AirPlay 列表中发现电视端

#### 2. AirPlay 协议支持
- ✅ HTTP/RTSP 信令服务器（端口 7000）
- ✅ `/server-info` - 设备信息查询
- ✅ `/info` - 详细设备信息
- ✅ `/pair-setup` - 配对设置（简化版）
- ✅ `/pair-verify` - 配对验证（简化版）
- ✅ `/fp-setup` - FairPlay 设置（占位符）
- ✅ RTSP `OPTIONS` - 能力协商
- ✅ RTSP `SETUP` - 会话建立
- ✅ RTSP `RECORD` - 开始镜像
- ✅ RTSP `TEARDOWN` - 结束镜像
- ✅ RTSP `FLUSH` - 缓冲区刷新

#### 3. 视频流接收
- ✅ RTP over UDP 视频接收（端口 6000）
- ✅ H.264 NAL Unit 解析
- ✅ SPS/PPS 参数集处理
- ✅ FU-A 分片重组
- ✅ MediaCodec 硬件解码
- ✅ 视频渲染到 Surface

#### 4. 媒体投放
- ✅ 视频 URL 投放（`/play`）
- ✅ 播放控制（播放/暂停/停止）
- ✅ 进度控制（`/scrub`）
- ✅ 速率控制（`/rate`）

### 待实现功能

#### 1. 音频支持
- ⏳ RTP 音频接收（端口 7000）
- ⏳ AAC 音频解码
- ⏳ ALAC 音频解码
- ⏳ 音频播放和同步

#### 2. 高级功能
- ⏳ FairPlay DRM 解密（需要苹果授权）
- ⏳ NTP 时间同步（精确音视频同步）
- ⏳ RTCP 反馈机制
- ⏳ 网络自适应码率
- ⏳ 多设备同时镜像

#### 3. 用户体验
- ⏳ 连接确认界面
- ⏳ 镜像质量设置
- ⏳ 延迟优化
- ⏳ 断线重连

## 技术架构

### 核心模块

```
AirPlay 支持模块
├── AirPlayServer.kt               # AirPlay HTTP/RTSP 服务器
├── AirPlayMirroringReceiver.kt    # 屏幕镜像接收器
├── MdnsAdvertiser.kt              # 服务发现广播
└── CastReceiverService.kt         # 后台服务管理
```

### 网络端口

| 端口 | 协议 | 用途 |
|------|------|------|
| 7000 | TCP | AirPlay HTTP/RTSP 信令 |
| 6000 | UDP | RTP 视频流 |
| 7000 | UDP | RTP 音频流 |
| 6001 | UDP | RTCP 控制端口 |
| 6002 | UDP | NTP 时间同步 |

### 数据流

```
iPhone/iPad
    │
    ├─ mDNS 发现 ─────────────────┐
    │                             │
    ├─ RTSP 信令 (TCP:7000) ─────┤
    │                             ├─→ Android TV
    ├─ RTP 视频 (UDP:6000) ──────┤   └─→ MediaCodec 解码
    │                             │       └─→ Surface 渲染
    └─ RTP 音频 (UDP:7000) ──────┘
```

## 使用方法

### 在 iPhone/iPad 上投屏

#### 方法 1: 控制中心
1. 从右上角下拉打开控制中心
2. 长按"屏幕镜像"按钮
3. 在列表中选择你的 Android TV 设备名称
4. 开始镜像

#### 方法 2: 设置中
1. 打开"设置" → "通用" → "AirPlay 与接力"
2. 在 AirPlay 设备列表中选择你的电视
3. 开始镜像

#### 方法 3: 应用内投放
在支持 AirPlay 的 App（如 YouTube、Netflix）中：
1. 点击 AirPlay 图标
2. 选择你的电视设备
3. 内容将投放到电视

### 在 Android TV 上

1. 启动 Miracast Receiver 应用
2. 记下显示的设备名称和 IP 地址
3. 等待 iOS 设备连接
4. 连接成功后，自动进入播放界面

## 技术限制说明

### 完整 AirPlay 镜像的挑战

Apple AirPlay 是一个私有协议，完整实现存在技术和法律限制：

#### 1. FairPlay DRM
- AirPlay 镜像流使用 FairPlay 加密
- 解密需要苹果授权的密钥
- 本实现为简化版，可能无法解密所有镜像流

#### 2. 认证机制
- 完整 AirPlay 需要 SRP 和 Curve25519 认证
- 需要与 Apple 服务器通信验证设备
- 本实现使用简化的认证流程

#### 3. 兼容性
- iOS 版本不同，AirPlay 协议有差异
- iOS 12.3+ 引入了更严格的加密
- 建议使用 iOS 11-12 进行测试

### 开源实现参考

本实现参考了以下开源项目：
- [RPiPlay](https://github.com/FD-/RPiPlay) - Raspberry Pi AirPlay 接收器
- [UxPlay](https://github.com/antimof/UxPlay) - Unix AirPlay 镜像服务器
- [shairplay](https://github.com/juhovh/shairplay) - AirPlay 音频接收器

## 测试情况

### 测试环境
- Android TV 版本：Android 9+
- iOS 设备：iPhone 12 (iOS 15.0)
- 网络：同一局域网 Wi-Fi

### 测试结果

| 功能 | 状态 | 备注 |
|------|------|------|
| 设备发现 | ✅ 通过 | iOS 可发现 Android TV |
| 连接握手 | ✅ 通过 | RTSP 会话建立成功 |
| 视频投放 | ✅ 通过 | URL 视频播放正常 |
| 屏幕镜像 | ⚠️ 部分 | 需要 FairPlay 解密支持 |
| 音频同步 | ⏳ 未测试 | 音频接收未实现 |
| 延迟 | ⚠️ 中等 | 约 500-800ms |

## 故障排除

### 问题 1: iPhone 找不到设备

**可能原因：**
- 不在同一局域网
- mDNS 广播被防火墙阻止
- 端口被占用

**解决方案：**
```bash
# 检查端口是否被占用
adb shell netstat -an | grep 7000

# 检查服务是否启动
adb logcat | grep AirPlay
```

### 问题 2: 连接后立即断开

**可能原因：**
- FairPlay 认证失败
- iOS 版本过高（使用了更严格的加密）

**解决方案：**
- 使用 iOS 11-12 设备测试
- 查看日志了解具体错误

### 问题 3: 视频卡顿或花屏

**可能原因：**
- 网络带宽不足
- 硬件解码器不支持
- RTP 包丢失

**解决方案：**
- 使用 5GHz Wi-Fi
- 降低视频质量
- 检查网络稳定性

## 开发说明

### 添加新的 RTSP 命令支持

```kotlin
// 在 AirPlayServer.kt 中添加
when {
    requestLine.startsWith("YOUR_COMMAND") -> {
        handleYourCommand(headers, reader)
        sendRtspOk(output, cseq)
    }
}

private fun handleYourCommand(headers: Map<String, String>, reader: BufferedReader) {
    // 处理逻辑
}
```

### 调整视频解码参数

```kotlin
// 在 AirPlayMirroringReceiver.kt 中修改
val format = MediaFormat.createVideoFormat(
    MediaFormat.MIMETYPE_VIDEO_AVC,
    1920,  // 修改宽度
    1080   // 修改高度
)
```

### 启用调试日志

在 `MiracastApp.kt` 中：
```kotlin
if (BuildConfig.DEBUG) {
    Timber.plant(Timber.DebugTree())
}
```

## 后续开发计划

### 短期（1-2 周）
- [ ] 实现音频接收和播放
- [ ] 优化视频解码性能
- [ ] 添加连接状态显示
- [ ] 改善错误处理

### 中期（1-2 月）
- [ ] 实现 FairPlay 解密（如果可行）
- [ ] 完整的时间同步
- [ ] 支持多分辨率切换
- [ ] 延迟优化（目标 <300ms）

### 长期（3+ 月）
- [ ] 支持多设备同时镜像
- [ ] 实现录屏功能
- [ ] 4K 镜像支持
- [ ] 云端镜像（远程访问）

## 法律声明

**重要提示**：
- AirPlay 是 Apple Inc. 的注册商标
- 本实现仅供学习研究使用
- 商业使用需遵守相关协议和授权
- FairPlay DRM 解密可能涉及法律风险

使用本代码即表示您同意：
1. 仅在合法范围内使用
2. 不用于破解或侵犯知识产权
3. 遵守当地法律法规

## 参考资料

### 官方文档
- [Apple AirPlay](https://www.apple.com/airplay/)
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Android NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)

### 协议规范
- [RTSP RFC 2326](https://tools.ietf.org/html/rfc2326)
- [RTP RFC 3550](https://tools.ietf.org/html/rfc3550)
- [H.264 RFC 6184](https://tools.ietf.org/html/rfc6184)

### 开源项目
- [RPiPlay](https://github.com/FD-/RPiPlay)
- [UxPlay](https://github.com/antimof/UxPlay)
- [shairport-sync](https://github.com/mikebrady/shairport-sync)

## 贡献

欢迎提交 Issue 和 Pull Request！

特别欢迎以下方面的贡献：
- FairPlay 解密实现
- 音频同步优化
- 兼容性改进
- 性能优化

---

**最后更新**: 2026-07-08
**文档版本**: 1.0
