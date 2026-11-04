# Miracast 旋转与分辨率协商

播放页的旋转按钮仍使用统一的浮动图标与四秒隐藏逻辑。Miracast 单独保存手动角度和最终显示方向，断开重连后恢复，不影响 AirPlay 或 Cast+ 的角度设置。

下次 RTSP M3 能力查询时，接收端读取本机当前显示模式的实际分辨率。横向使用长边 × 短边；旋转 90° / 270° 后使用短边 × 长边。视频模式必须同时满足硬件 H.264 解码器的尺寸、帧率、High Profile 和 Level 限制；不支持原生尺寸时等比回退。

自定义分辨率使用 Microsoft WFD 扩展，仅在发送端明确查询时返回：

- `microsoft_custom_video_formats`：以四位十六进制分别编码宽、高、刷新率。
- `wfdx_video_formats`：使用扩展字段宽度及对应的 H.264 Level，支持超过原有 Level 4.2 上限的模式。
- 标准 `wfd_video_formats` 保留原有 1080p60 / 1080p30 / 720p60 回退。未查询扩展的发送端不会收到扩展声明。

旋转不会主动断开当前会话。停止投屏并重新连接后才能重新协商。发送端可以选择标准回退模式；是否采用自定义竖屏分辨率取决于发送端支持及其选择，不保证所有 Android 手机都支持 Microsoft 扩展。

日志中的 `WFD: custom receive declaration` 是接收端声明，`WFD: negotiated custom mode` 是 M4 中发送端实际选择。未声明或畸形的自定义 M4 选择会收到 400 响应，避免误用不支持的解码模式。

协议参考：

- [Microsoft 自定义视频格式](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/e886ba33-f731-4f01-ba5f-6e9204804865)
- [扩展视频格式](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/584aadfe-f0db-4dec-9612-6a1d7bae1b77)
- [扩展字段与 Level 位图](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/5806ab5b-10de-4aa7-90b2-72d5293c28a3)

验证覆盖原生与旋转尺寸、等比回退、硬件限制、Level 宏块约束、扩展字段编码、M3 真实字节响应、传统发送端回退及 M4 非法选择拒绝。真实发送端是否选择竖屏模式仍需设备联调。
