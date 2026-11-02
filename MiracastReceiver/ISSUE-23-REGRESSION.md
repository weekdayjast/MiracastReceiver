# Issue #23 回归验证

问题：https://github.com/weekdayjast/MiracastReceiver/issues/23

## 自动测试

在 MiracastReceiver 目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PbuildArm32
```

- AirPlayLifecycleTest：播放中 mDNS 的 ADVERTISING / DISABLED / ERROR 不覆盖会话状态，也不关闭播放页；真正会话结束只通知一次；探测连接断开不关闭播放页；下一次连接正常。
- RtspTeardownTest：视频流分辨率切换保留音频和会话；移除最后一条视频流或所有指定流仍保留会话以便重新 SETUP；整会话 TEARDOWN 才触发全量停止。

## Sony BRAVIA / iPhone 实机步骤

1. 开始屏幕镜像，确认音视频正常；连续切换投屏分辨率，确认播放页不退出或反复重开，画面恢复且声音正常。
2. 对仅视频的镜像会话执行同样操作，确认最后一条视频流被移除再添加后仍能恢复。
3. 保持手机画面静止，让电视播放页切到后台再返回，或手动旋转画面；确认 Surface 恢复后无需再次切换分辨率即可显示画面。有缓存关键帧时应直接重放；缓存不可用时需等待发送端下一关键帧。
4. 在手机端结束整个镜像会话，确认播放页关闭、音频停止，随后可以再次连接。
5. 切换全屏 AirPlay URL 视频后返回镜像，确认两个解码器不会同时占用 Surface。
6. 在音频仍播放、播放页已不可见时点击通知中的“停止投屏”，确认声音停止、播放页关闭且接收服务停止；重新打开应用后可以再次投屏。通知主体可打开应用。
7. 对比 mDNS 的 deviceid、RAOP 服务名的地址前缀、GET /info 的 deviceID / macAddress 和 /server-info 的 deviceid：应为同一个持久标识；重新注册、Wi-Fi Direct 接口变化和应用重启后保持一致（清除应用数据除外）。

此修复将 AirPlay 身份从当前网卡 MAC 改为由已有持久 UUID 派生的本地单播地址；升级后标识会改变一次，之后保持稳定。
