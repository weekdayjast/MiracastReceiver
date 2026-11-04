# 华为 Cast+ 实验性接收

本功能已从 `codex/huawei-castplus` 合入 `main`，默认关闭。使用本地提供的 Huawei Cast+ SDK JAR，以及 ScreenCastPlusDemo 中的 ARM32 / ARM64 原生库。当前只进行构建、单元测试和静态打包验证，尚未进行真实华为手机投屏验证。

## 启用

1. 在 ARM 设备上安装此分支的 APK。Cast+ 功能要求 Android 6（API 23）及以上；主应用仍保留 Android 5 支持。
2. 打开首页“华为 Cast+ 接收（实验性）”。
3. 允许定位权限；Android 13 及以上还需要“附近的设备”权限。后台出现连接确认或播放页面仍受系统后台启动 / 悬浮窗授权限制。
4. 手机与接收设备连接可互通的局域网，在华为手机的系统无线投屏入口搜索设备。
5. 接收端确认“允许”后，若显示 PIN，在手机上输入。勾选“记住设备”会通过 SDK 的“始终允许”选项管理信任关系。

显示“接收已就绪”仅表示 SDK 的服务、能力和发现调用返回成功，不代表已验证所有华为系统版本的发现与连接。

## 已接入的流程

- 开关、后台接收服务统一启停，20 秒初始化超时，SDK 失败状态显示。
- 注册设备名与显示能力、通用认证模式；不依赖蓝牙设备名。
- 显示能力使用接收设备当前显示模式的实际像素尺寸；播放页每旋转 90° 会交换声明宽高，并保存角度。视频分辨率按硬件解码能力等比回退，不再使用固定方形画布。SDK 更新能力缓存后，需停止投屏并重新连接才能重新协商；实际流分辨率以发送端选择及回调为准。
- 临时允许 / 始终允许 / 拒绝、PIN 展示、配对超时。
- 连接后启动独立 Cast+ 播放页，原有镜像和 ExoPlayer 页面退出；其他协议打开播放页时断开 Cast+。
- Surface 与 SDK 解码就绪必须同时满足才播放；支持先收到回调、先创建 Surface 两种顺序。
- 页面切到后台暂停音视频并释放屏幕常亮；恢复后重新绑定有效 Surface。
- 接收端退出、发送端断开、服务停止、SDK Binder 失联时清理会话；断开等待超时会重新绑定 SDK。
- 按 SDK 分辨率更新适配画面比例。没有宣称实现遥控反向控制、桌面模式、多设备同时投屏或所有资源投播能力。

## 结构

- `castplus/CastPlusReceiver.kt`：原始 SDK API 适配、回调主线程化、会话标识、配对及 Surface 控制。
- `castplus/CastPlusPlaybackGate.kt`：播放前置条件与旧页面回调隔离。
- `ui/ConnectActivity.kt`：确认、记住设备与 PIN 等待界面；不记录 PIN 日志。
- `ui/CastPlusPlayerActivity.kt`：独立 Surface 和播放生命周期，不与 AirPlay / Miracast 的 Surface 共享输出。
- `service/CastReceiverService.kt`：持有控制器，通过现有前台服务管理启停。
- Manifest 中 `NearFieldManagerService` 位于 `:castplus_nearby` 私有进程；该 SDK 服务停止时会结束所在进程，不能放进 UI 主进程。
- `PlayerSinkService` 与适配器保持同进程，沿用 SDK 的本地服务路径。SDK 也保留华为 Kit Framework 的自动检测分支，尚未在有该框架的设备验证。

SDK 中的原生代码仍在对应 Android 进程运行；Java 异常或依赖加载错误可以反馈到 UI，但原生崩溃不保证能由 Java 捕获。

## 本地验证

从仓库根目录运行：

```shell
MiracastReceiver\gradlew.bat -p MiracastReceiver :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

播放时序测试覆盖：解码回调先到、Surface 先到、后台回调不会恢复播放、Surface 重建、旧会话无法结束新会话，以及暂停回调早于 Surface 销毁后仍可恢复。

JAR 存在大量缺少 stack map 的 D8 警告；应保留原始二进制并结合真机验证，不能将 D8 转换成功当成运行兼容证明。Release 构建产物是未签名 APK，不能当作正式发布版直接分发。

## 后续真机验收

至少记录发送手机型号 / 系统、接收设备型号 / Android 版本 / ABI、APK 构建版本，并按 [ADB 日志教程](../ADB故障日志采集教程.md) 保留接收端日志：

1. 在无华为预装框架的普通 Android 接收设备上验证原生库加载、服务绑定及设备发现。
2. 分别测试拒绝、允许一次、始终允许、PIN 正确 / 错误 / 超时；确认未允许前不会自动播放。
3. 确认实际音画、横竖屏比例、锁屏、旋转及分辨率切换。
4. 测试 Home 后暂停、回到页面恢复、遥控返回断开、手机停止、再次投屏及切换其他协议。
5. 检查 Wi-Fi Direct 与现有 Miracast 广播是否冲突，验证网络断开和重新连接。
6. 分别验证 ARM32、ARM64，以及目标 EMUI / HarmonyOS 版本。原生库接口匹配与现代 Android 限制仍是运行时验证项目。

## 来源与分发

来源和哈希见 [SDK 说明](app/libs/CASTPLUS-SDK.md)。反编译及初步可行性分析见 [分析文档](HUAWEI-CASTPLUS-ANALYSIS.md)；其中“尚未接入”的描述针对分析当时的状态，当前分支的实现以本文为准。

SDK 二进制的可再分发条件尚未确认，本轮仅做分支集成和本地构建，不创建公开 Release。

### Android 11+ 网络发现兼容

目标 API 固定为 36，已移除 API 29 诊断构建开关。旧版 SDK 重复使用 Java 网络通知和 native `SmartGenius` 的 NETLINK_ROUTE 监听。Android 11+ 禁止现代目标版本普通应用绑定该 netlink socket，导致 SDK 输出 `bind for netlink socket failed: 13`、`NSTACK_Init fail` 和 `start listen failed`。上层 `setDiscoverable(true)` 的返回值仅代表请求接受。

`castplus_network_compat.c` 通过 Android 链接器支持的 `DF_1_GLOBAL` 符号优先级，只替代 SDK 的 `SmartGeniusInit` / `SmartGeniusClean` 两个监听入口。Android 11+ 在 Application 中先于 SDK 加载，包含主进程和 `:castplus_nearby` 进程。网络地址、掩码和变化继续通过 SDK 的 Java 网络通知传入原有 native 发现模块。更早系统保留原实现。SDK JAR、原始 so、socket/bind、发现协议、认证和播放均不修改。

验证：Sony 116（Android 12）已安装 API 36 修复包，日志确认兼容入口被调用，并出现 `Register device success`、`RegisterModule OK`、`succeeded to StartListening`。每次升级 SDK 都需重新核验这两个入口的 ABI、PLT 重定位，以及 Java 网络通知路径；该适配层依赖当前记录的 SDK 版本。SDK 的 `setsockopt fail, errno = 1` 在 API 29 可正常发现版本中同样存在，随后继续注册，不能单独当作发现失败。用户已在此 API 36 修复包确认手机能够发现并成功投屏。网络断开重连、不同手机和系统版本仍应作为实机回归项目。

参考：[Android 网络接口限制](https://developer.android.com/identity/user-data-ids#mac-11-plus)、[Android 链接器符号查找顺序](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md#changes-to-library-search-order)。
