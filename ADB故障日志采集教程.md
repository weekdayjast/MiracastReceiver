# ADB 故障日志采集教程

出现搜不到设备、连接后断开、黑屏、有声音无画面、闪退或播放时进入屏保等问题时，请按本教程采集日志，并上传到对应 GitHub Issue。

**优先采集播放设备（接收端：安装 Miracast Receiver 的电视、盒子或安卓设备）的日志。** 发送端是发起投屏的手机或电脑。不要只提交发送端日志，也不要只截一张报错图片。

普通日志采集不需要 root。先记录已安装的应用版本、设备和复现步骤；如果故障已经发生，先导出当前日志，避免清空后丢失现场。

## 1. 准备 ADB

1. 在电脑下载并解压 Google 官方 [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)，无需安装整个 Android Studio。
2. 在接收设备的“设置 → 关于”中，连续点击“版本号 / Build”开启开发者选项，再开启“USB 调试”或厂商提供的“ADB / 网络调试”。不同电视的入口可能不同。
3. 电脑打开命令行，进入解压得到的 `platform-tools` 文件夹。

下文以 **Windows 命令提示符（CMD）** 为例：可以在该文件夹的资源管理器地址栏输入 `cmd` 并回车。使用 PowerShell 时，将 `adb` 写成 `./adb.exe`；macOS / Linux 在工具目录下写成 `./adb`，或先把工具加入 PATH。

```shell
adb version
```

## 2. 连接播放设备

### USB 连接

使用支持数据传输的 USB 线连接电脑与接收设备。在接收设备上确认“允许 USB 调试”，然后执行：

```shell
adb devices -l
```

只有支持 USB 设备调试连接的接口才可使用此方法。电视的普通 USB-A 接口往往只支持 U 盘；不要用 USB-A 对 USB-A 线尝试连接，优先使用电视支持的网络调试。

### 网络连接（电视 / 盒子常用）

电脑与接收设备连接同一个可互通的局域网，在电视设置中开启厂商提供的网络 ADB 调试。下面 IP 和端口仅为示例，请替换成实际值：

```shell
adb connect 192.168.1.100:5555
adb devices -l
```

仅当设备确实启用了该端口时才能连接，不能假定所有电视都使用 `5555`。如果设备仅支持先通过 USB 开启 TCP 模式，需要先完成 USB 授权，再按 [Android 官方 ADB 指南](https://developer.android.com/tools/adb) 操作。

Android 11 及以上设备若提供“无线调试 / 使用配对码配对”，在同一局域网内执行：

```shell
adb pair 192.168.1.100:37123
adb connect 192.168.1.100:39817
adb devices -l
```

按提示输入设备显示的配对码。配对端口与连接端口通常不同，以设备页面为准；部分电视没有此功能。

### 确认连接和目标

`adb devices -l` 中目标设备的状态必须为 `device`：

- `unauthorized`：在电视 / 手机上允许调试；没有弹窗时尝试重新连接并检查开发者选项。
- `offline`：重新连接，网络调试可先 `adb disconnect IP:端口`，再连接。
- 列表为空 / 连接被拒绝：检查数据线、驱动、IP、实际调试端口、设备端调试开关和局域网隔离。

**下面命令中的 `SERIAL` 必须全部替换为接收设备在列表第一列显示的值**，如 USB 序列号或 `192.168.1.100:5555`。即使只连接一台，也建议保留 `-s`，防止误采集发送手机的日志。

## 3. 开始记录，再复现问题

在电脑创建一个单独的日志文件夹，并在此文件夹打开命令行。若 ADB 未加入 PATH，可将下面 `adb` 替换为它的完整路径。

### 故障已经发生、暂时无法重现

先保存设备当前仍保留的日志，再执行下一节的现场状态采集：

```shell
adb -s SERIAL logcat -b all -v threadtime -d > receiver-existing-logcat.txt 2>&1
```

日志缓冲区有限，较早的内容可能已被覆盖。请说明这是事后导出，并补充大概故障时间。

### 可以重新复现（推荐）

1. 保持 ADB 连接，先打开接收设备上的 Miracast Receiver。
2. 如果已经保存了需要保留的旧日志，可选执行以下命令清空旧缓冲区，使这次采集更集中；**故障已经发生但尚未导出时不要清空**：

   ```shell
   adb -s SERIAL logcat -b all -c
   ```

3. 在第一个命令行窗口开始持续记录：

   ```shell
   adb -s SERIAL logcat -b all -v threadtime > receiver-logcat.txt 2>&1
   ```

   命令持续运行、没有提示符返回是正常现象。文件保存在电脑当前文件夹，不在电视上。

4. 开始投屏，按原步骤复现。问题发生后先保持现场，在第二个命令行窗口按下一节导出状态；**不要先退出应用、停止投屏、强行停止或重启设备**。
5. 状态导出后再等待约 10 秒，在第一个窗口按 `Ctrl+C` 停止记录。若观察的是“五分钟无遥控操作后进入屏保”，应从投屏前开始记录，覆盖超过五分钟的等待过程，等待期间不要按遥控器，也不要反复重新连接。

不要只按应用 PID、标签或错误级别过滤。黑屏、后台启动限制、解码器错误、Activity 关闭和屏保常有系统日志参与；PID 也可能随崩溃 / 重启变化。

如果 `-b all` 在厂商系统报错，请保留报错并尝试去掉 `-b all` 后重新采集；某个缓冲区无权限不需要为此 root。

## 4. 故障现场状态（第二个窗口）

在日志文件夹的第二个命令行窗口执行，仍将 `SERIAL` 替换为同一个接收设备。以下命令只读取状态，不修改系统休眠设置或应用权限：

```shell
adb -s SERIAL shell date > receiver-time.txt
adb -s SERIAL shell getprop ro.product.manufacturer > receiver-device.txt
adb -s SERIAL shell getprop ro.product.model >> receiver-device.txt
adb -s SERIAL shell getprop ro.build.version.release >> receiver-device.txt
adb -s SERIAL shell getprop ro.build.version.sdk >> receiver-device.txt
adb -s SERIAL shell getprop ro.product.cpu.abilist >> receiver-device.txt
adb -s SERIAL shell dumpsys package top.jast.miracastreceiver > receiver-package.txt
adb -s SERIAL shell dumpsys activity activities > receiver-activities.txt
adb -s SERIAL shell dumpsys activity services top.jast.miracastreceiver > receiver-services.txt
adb -s SERIAL shell dumpsys window > receiver-window.txt
adb -s SERIAL shell dumpsys power > receiver-power.txt
adb -s SERIAL shell dumpsys media.player > receiver-media-player.txt
adb -s SERIAL shell dumpsys media.audio_flinger > receiver-audio.txt
adb -s SERIAL shell dumpsys SurfaceFlinger --list > receiver-surfaces.txt
```

`receiver-time.txt` 对应设备时间，请在反馈中说明“该时间附近出现黑屏 / 断开 / 屏保”。命令如有“不支持 / 权限不足”的提示，保存提示即可，继续执行其他命令。

网络发现 / 连接失败时，可再补充：

```shell
adb -s SERIAL shell ip addr > receiver-network.txt
adb -s SERIAL shell ip route >> receiver-network.txt
```

**正式版可能没有应用自身的详细调试日志**，但系统日志和以上状态仍有帮助。不要因找不到某个应用标签而不提交。需要诊断版时维护者会在 Issue 中另行提供，不必自行改代码或刷机。

## 5. 如需发送端日志

先交接收端日志。若仍需分析 VLC 转码、手机主动断开或 Cast 指令，维护者可能要求额外的发送端日志。

Android 发送手机可通过 USB 开启调试并授权，在 `adb devices -l` 中确认它的序列号后，另开窗口记录：

```shell
adb -s SENDER_SERIAL logcat -b all -v threadtime > sender-logcat.txt 2>&1
```

将 `SENDER_SERIAL` 替换为**发送手机**的序列号，与接收端同时开始采集，复现后按 `Ctrl+C`。iPhone / iPad 无法用 ADB 采集，请提供设备型号、iOS / iPadOS 版本、发送应用名称版本和具体操作即可。

## 6. 上传到 Issue

检查文件内容不是空文件、设备未选错，也不是只有 ADB 连接错误。将日志和状态文件压缩成一个 ZIP，拖入 GitHub Issue 正文或评论输入框上传。不要只贴日志截图或一两行报错。

同时补充：

- 问题现象、预期结果、可重复的操作步骤、频率，以及正常后多久出现故障。
- 接收设备品牌 / 完整型号、Android 与厂商系统版本，应用版本、APK 架构及正式版 / 诊断版来源。
- 发送设备型号、系统版本、发送应用及版本；投屏入口和方式（AirPlay 镜像 / 视频、DLNA、Miracast / Windows `Win+K`、VLC Cast；不确定可描述点了哪里）。
- 双方使用 Wi-Fi 还是网线、是否同一局域网、是否有访客网络 / AP 隔离 / VPN；断开时网络是否变化。
- 媒体来源（本地、HTTP、SMB、NFS、直播等）、格式 / 编码（知道再填）、其他文件是否正常，以及发送端本地是否可正常播放。
- 故障发生的设备时间及对应截图 / 短视频；上次正常的应用版本、是否仅某台设备或某种投屏方式出现。

日志可能包含媒体地址、账号、访问令牌、设备标识、其他应用信息。**上传前检查并遮蔽隐私**，媒体 URL 查询参数、SMB 密码和可访问的私人链接不要公开。保留时间戳、错误栈、解码器信息和前后事件；敏感字段可以统一替换为占位符。完整 `adb bugreport` 可能包含更多隐私，仅在维护者明确要求且你检查过内容后提供。

不能连接 ADB 时，仍可提交问题：说明连接失败的步骤和提示，先补全设备、版本、复现步骤和截图，之后再配合补日志。

完成后可断开 ADB，并在设备设置中关闭 USB / 网络 / 无线调试；不要把网络调试端口暴露到公网。

参考：[Android ADB 指南](https://developer.android.com/tools/adb)、[Logcat 命令说明](https://developer.android.com/tools/logcat)。
