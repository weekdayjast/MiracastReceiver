# 华为 Cast+ 集成可行性分析

分析日期：2026-11-04。范围为本地 JAR、反编译结果及用户提供的 Demo，未完成手机到电视的真机投屏测试。

## 结论

**具备实验性集成的技术基础。单独的本地 JAR 不完整，结合 ScreenCastPlusDemo 的原生库和服务配置，可以进入独立验证阶段。现在还不能宣称本项目已经支持 Cast+，也不能承诺所有华为系统版本都可用。**

重要更正：这份实现既有华为 Kit Framework 路径，也有将接收服务内嵌进普通应用的路径。因此，“必须安装华为系统预装服务才能运行”不符合这份代码的实际分支。Java 启动路径也没有发现必须执行 `su` 的要求；是否能在目标设备上免 root 工作，仍须实际测试原生库、网络和配对流程。

本轮仅分析并生成本地材料，未将第三方 SDK 加入正式应用，未改变应用包名、签名或 target SDK，未提交 / 发布 SDK 二进制。

## 分析材料与可复现结果

| 材料 / 检查 | 结果 |
| --- | --- |
| 本地 `HuaweiCastPlusEngine.jar` | 418,866 字节，280 个 `.class`，没有 Manifest、资源、原生库或许可说明等非 class 条目 |
| JAR SHA256 | `27359bfa0d5cced357cc317e411e33e8bd33c0018fd1623ed73acdc977feee3f` |
| Demo | [ljlVink/ScreenCastPlusDemo](https://github.com/ljlVink/ScreenCastPlusDemo)，检查的提交为 `6bc1c9b0e93b2329e54aa38bd8d75456fae11745` |
| Demo 发行包 | `V1.3/HwCastPlus.apk`，Manifest 中应用版本为 `1.0.0.300`，min SDK 19，target SDK 28 |
| 反编译 | 使用 IntelliJ Java Decompiler 和 CFR 0.152 交叉检查；存在混淆，反编译 Java 不应当直接作为可编译源码使用 |
| Java 依赖扫描 | class 常量池未发现未包含的第三方类引用，排除 Android、Java / Javax、JSON 与数组类型；不覆盖反射和 JNI 隐式依赖 |
| D8 转换 | Android Build Tools 36.0.0，使用 android-36 库和 min API 23，生成 513,572 字节 `classes.dex`，无转换错误；有 832 条缺少 stack map 等警告 |
| 原生依赖 | ARM32、ARM64 各 11 个 `.so`，名称覆盖 Java 代码直接加载的库；递归 DT_NEEDED 之外仅剩 Android 平台库 |

以上检查不是完整 APK 构建、JNI 参数兼容证明，也不是运行成功证明。尤其不能将 D8 通过理解为配对或解码已验证。

## 接收架构与启动分支

```text
应用接入层
  PlayerClient / IEventListener / ProjectionDevice
      ↓
  PlayerSinkService（可内嵌当前应用）
      ├─ NearFieldManager → 本地 Nearby 服务 → onehop / nstackx 原生库
      ├─ HiChainAuthControl → ConnectAuthNative → 设备认证 / 密钥原生库
      └─ HiSight 会话 → FtSocket 传输 → 视频解码 / 音频输出 → Surface
```

### 可内嵌服务，不必一律依赖华为预装包

`com/huawei/castpluskit/PlayerClient.java` 的 `bindPlayerSinkService()`：

- 检测到 `com.huawei.hms.kitframework` 时，尝试通过框架绑定 `com.huawei.castplus.service_sink`。
- 未检测到框架时，明确执行 `setClass(context, PlayerSinkService.class)`，绑定应用自己声明的服务。

`com/huawei/connection/nearby/c.java` 的 `g()` 也有两条路径：发现 `com.huawei.dmsdpdevice` 时绑定其服务，否则使用本地 `NearFieldManagerService`。在有华为框架的设备上，还需要检查分支选择、版本与绑定结果，不能仅验证“无框架”的设备。

### 发现与配对不是现有 Google Cast 协议

`NearFieldManagerUtilsNative` 将设备注册、服务数据、设备发现、连接和数据发送交给 JNI。`HiChainAuthControl` 调用原生设备认证，包含 PIN、可信设备与认证数据交换。这些不能用现有 mDNS / Cast V2 代码直接替代。

Java 层保留了会话控制、能力协商和解码适配代码，可以追踪流程；发现传输与认证的核心仍有原生实现。仅阅读 Java 无法完整重写出兼容 Cast+ 的协议栈。

## Demo 补齐的原生库

Demo 的 `app/src/main/jniLibs/armeabi-v7a/` 和 `arm64-v8a/` 均包含：

| 作用 | 库 |
| --- | --- |
| 发现、连接、控制数据 | `libnear_field_onehop_jni.so`、`libnf_onehop.so`、`libnstackx_ctrl.so`、`libnstackx_dmsg.so`、`libnstackx_util.so` |
| PIN / 信任关系 / 设备认证 | `libConnectAuthNative.so`、`libHwDeviceAuthSDK.so`、`libHwKeystoreSDK.so` |
| 媒体接收传输 | `libjniFtSocketSink.so` |
| 音频处理 | `libnative_codec_jni.so` |
| 基础依赖 | `libsecurec.so` |

ARM32 库合计 2,369,492 字节，ARM64 合计 3,103,456 字节。静态 DT_NEEDED 检查未发现需要额外提供的华为专用库；剩余依赖包含 `libc`、`libm`、`libdl`、`liblog`、`libstdc++`、`libandroid`、`libOpenSLES`、`libOpenMAXAL` 等平台库。运行时通过 `dlopen` 或 JNI 使用的依赖仍需真机确认。

ELF LOAD 段对齐：ARM32 为 `0x1000`，ARM64 为 `0x10000`。不能笼统认定这些库都是 4 KB 对齐；64 位库的段对齐本身满足 16 KB 页要求，但仍需检查最终 APK 对齐与实际装载。

## 当前项目的接入方式

建议新增可选 `castplus` 接入层，先保留原始 JAR，通过适配器调用 SDK；不直接复制混淆反编译 Java，也不把 JAR 文件与反编译类一起打包，避免类重复。

| 当前项目位置 | 建议修改 |
| --- | --- |
| 构建 | 增加 SDK JAR 和匹配版本的两种 ABI 原生库，保持现有正式签名与包名；检查混淆保留规则 |
| `CastReceiverService` | 管理初始化、可发现状态、设备能力、会话断开和注销；将 SDK 异常与其他协议隔离 |
| `ReceiverStatus` / 首页 | 增加 Cast+ 初始化、依赖缺失、等待授权、已连接及错误状态；不以服务绑定成功代替完整可用性 |
| 连接确认页 | 处理 PIN 显示、仅本次允许、始终允许和拒绝；保留设备对象，避免跨会话误用 |
| `PlayerActivity` | 复用镜像 Surface、常亮、旋转和退出入口；为 Cast+ 增加独立输出拥有者，不让 AirPlay / Miracast / ExoPlayer 同时操作 Surface |
| 网络管理 | SDK 自行管理部分发现和网络状态，与现有 Wi-Fi Direct 组和 Miracast 广播并存时需测试冲突和清理顺序 |

### 显示与播放时序

Demo 在服务绑定后设置 `HiSightCapability`、设备名称、`setDiscoverable` 和认证模式；连接请求到达后启动播放页面。只有 SDK 已进入可播放状态，且 Surface 同时就绪，才执行：

```java
playerClient.setHiSightSurface(surface);
playerClient.play(new TrackControl(device.getDeviceId(), TrackControl.TRACK_ALL));
```

Surface 销毁时暂停，恢复后重新设置；用户退出时执行断开，并处理 SDK 的断开回调。这一门槛要保留，避免先播放后出现 Surface 导致黑屏。

镜像音视频传输和音频处理已有 SDK 实现，因此不应先假设能全部交给 ExoPlayer。当前 ExoPlayer 适合继续处理现有 URL 媒体播放路径。

## 必须处理的适配点

1. **Nearby 服务必须隔离进程。** Demo 给 `NearFieldManagerService` 单独指定进程；其 `onDestroy()` 会调用 `Process.killProcess(Process.myPid())`。直接放到本应用主进程会有连带结束整个应用的风险。
2. **权限按路径验证。** `PlayerSinkService` 的 Binder `onTransact()` 检查 `com.huawei.permission.HISIGHT_ACCESS`；仅看到权限名不能断言它在目标系统上的授权级别。先按 Demo 的本地同进程接入路径验证；若拆分进程 / 跨应用调用，还需验证该检查。不要通过删鉴权来替代适配。
3. **Android 新版本行为。** Demo target 28，当前项目 target 36。蓝牙运行时权限、网络权限、设备标识可见性、后台拉起 PIN / 播放页、服务生命周期、广播注册和包可见性均须在目标版本检查。不能通过把整个应用 target 降回 28 来规避。
4. **能力门槛。** 官方旧接入教程要求大屏 API 23+。建议先将实验性功能限制在 API 23+，保持当前主应用 min SDK 21，后续再验证低版本。
5. **设备名称。** 不直接照抄 Demo 通过蓝牙名称取得设备名的方式；复用本项目的 `DeviceInfoProvider`，并处理无蓝牙设备。
6. **资源与重新打包。** 本地 JAR 没有资源，包含旧资源 ID 的 `R` 类不等于资源已随包提供。优先复用自己的 Surface / 界面，仍需排查 SDK 内部实际访问的资源。
7. **原生库版本。** 当前只核对了库名称、ELF 链接依赖和可见 JNI 入口；本地 JAR 与 Demo 中的 SO 是否完全同版、所有参数签名是否兼容，尚未证明。原始完整 AAR 优于从不同来源拼装。
8. **异常与重连。** 不照搬 Demo 在服务 `onDestroy()` 中再次 `startService()` 的自拉起逻辑；接入现有统一启停，并验证停止后不再发现、重新连接不串旧会话。
9. **SDK 分发条件。** Demo 仓库的 Apache-2.0 许可不能单独证明 Huawei SDK 二进制的再分发授权；本地 JAR 无许可条目，公开打包发布前需要确认 SDK 使用与分发条件。

## 建议验证顺序

1. 在独立测试包中，用本地 JAR 和 Demo 的 SO 运行最小接收流程，观察本地服务能否绑定、所有原生库能否加载。
2. 用明确型号 / 系统版本的华为手机测试发现、PIN / 信任确认、镜像画面与声音；不能仅用“手机搜到了设备”作为通过标准。
3. 测试锁屏、旋转、分辨率切换、Surface 重建、网络切换、手机停止、接收端退出和重新投屏。
4. 再接到本项目的服务与播放页，验证 AirPlay、DLNA、Google Cast、Miracast 原有能力没有被 SDK 的网络或进程生命周期影响。
5. 分别验证 ARM32 / ARM64、老 Android 电视和较新系统；华为手机旧 EMUI 与新 HarmonyOS 的兼容范围应按实测记录。

本轮没有连接可用 ADB 设备，尚未执行第 1 步的运行时验证。结论为“可以开始实验性接入验证”，并非“已完成集成”。

## 本地分析产物

- CFR 反编译目录：`HuaweiCastPlusEngine/decompiled-cfr/`。
- IntelliJ 交叉反编译目录：`HuaweiCastPlusEngine/decompiled/sources/`。
- Demo 源码：`_release/ScreenCastPlusDemo/`；发行 APK：`_release/ScreenCastPlusDemo-release/HwCastPlus.apk`。
- 原生库依赖、对齐与 JNI 导出清单：`_release/huawei-native-dependencies.json`。
- D8 结果：`_release/huawei-d8/classes.dex`；警告：`_release/huawei-d8.log`。
- 复现脚本位于 `_release/`：`decompile-huawei-castplus.bat`、`decompile-huawei-cfr.bat`、`inspect_huawei_jar.py`、`huawei_class_dependencies.py`、`inspect_huawei_native.py`、`check-huawei-d8.bat`、`summarize_huawei_checks.py`。

## 参考

- [用户提供的 Demo](https://github.com/ljlVink/ScreenCastPlusDemo)。
- [华为官方 Cast Engine 接入教程](https://developer.huawei.com/consumer/cn/codelab/CastEngine/index.html)。
- [华为官方示例](https://github.com/huaweicodelabs/CastEngine)。
