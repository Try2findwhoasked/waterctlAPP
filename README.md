# waterctl-android

蓝牙水控器 FOSS 的安卓原生版本，基于已验证的 [waterctl](https://github.com/celesWuff/waterctl)（TypeScript）协议实现直译为 Kotlin。

## 技术栈

- 原生 Kotlin（无第三方运行时依赖，仅 Kotlin stdlib）
- Android 原生 BLE（`BluetoothGatt`）
- `ForegroundService` + `PARTIAL_WAKE_LOCK` 后台保活

## 协议移植

移植自 [celesWuff/waterctl](https://github.com/celesWuff/waterctl)（默认分支 `2.x`）的 `src/` 协议实现。

`app/src/main/kotlin/com/waterctl/android/protocol/` 下：

| 文件 | 对应源文件 | 说明 |
|------|-----------|------|
| `Deputy.kt` | [`src/deputy.wat`](https://github.com/celesWuff/waterctl/blob/2.x/src/deputy.wat) | `makeKey` 直译 |
| `DeputyData.kt` | `src/deputy.wat` | 2096 字节查找表（自动生成） |
| `Algorithms.kt` | [`src/algorithms.ts`](https://github.com/celesWuff/waterctl/blob/2.x/src/algorithms.ts) | CRC-16/ChangGong、CRC-16/CGAEAF |
| `Payloads.kt` | [`src/payloads.ts`](https://github.com/celesWuff/waterctl/blob/2.x/src/payloads.ts) | 固定 BLE 载荷 |
| `Utils.kt` | [`src/utils.ts`](https://github.com/celesWuff/waterctl/blob/2.x/src/utils.ts) | `dec_as_hex`、hex 字符串 |
| `Solvers.kt` | [`src/solvers.ts`](https://github.com/celesWuff/waterctl/blob/2.x/src/solvers.ts) | unlock response / start epilogue |

`DeputyData.kt` 由 `tools/gen-deputy-kotlin.mjs` 从 [celesWuff/waterctl](https://github.com/celesWuff/waterctl/blob/2.x/src/deputy.wat) 的 `deputy.wat` 数据段自动生成，勿手改。

## 图标

源图放在 `tools/icon/images.png`（**不能放进 `res/`**，res 只接受已识别的资源类型与 `.xml` 文件），
由 `tools/gen-icons.py`（Python + Pillow）生成：

```powershell
python tools\gen-icons.py
```

生成内容（每种密度：mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi）：

| 文件 | 尺寸 | 用途 |
| --- | --- | --- |
| `mipmap-*/ic_launcher.png` | 48–192 px | API 25 及以下的传统图标 |
| `mipmap-*/ic_launcher_round.png` | 48–192 px | 传统圆形图标（`android:roundIcon`） |
| `mipmap-*/ic_launcher_foreground.png` | 108–432 px | 自适应图标前景层，画面只占中心 72dp，任何启动器遮罩下都完整可见 |

自适应图标配置：`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`，
背景层使用 `@color/ic_launcher_background`（`#FBF9FC`，取自源图左上角底色，换图后需同步 `values/colors.xml`）。
未声明 `monochrome` 层——彩色插画被系统取 alpha 后会变成纯色方块。

## 构建

前置：JDK 17、Android SDK（`platforms;android-35`、`build-tools;35.0.0`）、Gradle 8.13。

`tools\build.cmd` 会固定使用 JDK 17（系统默认 `JAVA_HOME` 可能指向更高版本，AGP 8.7.3 不支持）：

```powershell
tools\build.cmd assembleDebug             # 调试版（已签名，可直接 adb install）
tools\build.cmd assembleDebug assembleRelease
```

等价的手动方式：

```powershell
$env:JAVA_HOME = "D:\java\jdk-17"
$env:Path = "D:\java\jdk-17\bin;$env:Path"
$env:ANDROID_HOME = "D:\Documents\AndroidSDK"
$env:ANDROID_SDK_ROOT = "D:\Documents\AndroidSDK"
.\gradlew.bat assembleDebug   # 调试版（已签名，可直接 adb install）
.\gradlew.bat assembleRelease # 发布版（用 waterctl-release.keystore 签名）
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## 界面

- 顶部标题栏：左侧应用名，右侧 `⋮` 打开溢出菜单，含「设置」与「关于」（关于页包含版本、协议来源、许可证与免责声明）。
- 状态卡：实时显示会话状态（空闲 / 连接中 / 会话进行中 / 正在重连 / 正在结束）与当前设备，并按状态着色。
- 2×2 操作按钮：扫描设备 / 开始会话 / 结束会话 / 停止重连；按钮可用性随状态联动（例如会话进行中时禁用扫描与开始，空闲时禁用结束）。
- 一键连接：按钮位于操作区下方，点击后直接以「上次成功开始的设备」建立会话，无需重新扫描。设备地址与名称保存在 `SharedPreferences`（`waterctl` / `last_address`、`last_name`），标签会显示上次设备名（如「一键连接: 浴室水控」），无记录时禁用。
- 设备列表：占据主区域（权重 3），单击即选中并高亮，显示 `名称` + `MAC` 两行。
- 日志面板：等宽字体、自动滚动到最新一行，可通过右上角「收起 / 展开」折叠以腾出空间。

## 设置

设置是**全局**的（不区分设备），保存在 `SharedPreferences`（文件 `waterctl`，由 `Prefs.kt` 统一读写）。
`BleService` 每次发包前都会重新读取偏好，因此切换后立即生效，进行中的会话无需重连。

| 设置项 | 默认 | 说明 |
| --- | --- | --- |
| 在日志中显示发送的数据包 | 开 | 以 `TX` 前缀显示本机发出的数据包（十六进制）。关闭后日志更简洁；接收到的数据包（`RXD`）始终显示。 |

## 会话结束

- 会话进行中（`active`）点击「结束会话」：发送结束前导（`END_PROLOGUE`）→ 收到 `B3` 后回复 `END_EPILOGUE` → 断开 GATT → 停止前台服务并移除通知。
- 连接中 / 正在重连 / 正在结束期间点击「结束会话」：直接中止（取消所有定时器、断开 GATT、停止服务），避免重连定时器把会话"复活"。
- 「停止重连」为硬停止，语义与中止一致。
- 结束时统一调用 `stopForeground(STOP_FOREGROUND_REMOVE)`，确保状态栏通知不会残留。

## 保活机制

1. **已启动的前台服务（关键）**：会话通过 `Context.startForegroundService()` +
   `BleService.onStartCommand(ACTION_START)` 启动，而不是只依赖 `bindService()`。
   纯绑定服务会在最后一个客户端 `unbindService()` 时被系统立即销毁，而 Activity 在
   息屏 / 切后台时正好会 `onStop()` → `unbindService()`。这正是早期版本"锁屏就断、
   亮屏显示空闲"的根因（服务连同 GATT、WakeLock、重连定时器一起被销毁）。
2. **`connectedDevice` 类型 + `stopWithTask="false"`**：前台服务不随任务栈移除而停止，
   划掉最近任务不会结束会话。
3. **`PARTIAL_WAKE_LOCK`**：锁屏后保持 CPU 运行，BLE GATT 连接持续存活。
4. **自动重连**：断链后指数退避（3s 起、每次 ×2、上限 30s）；服务常驻，故锁屏期间
   仍能按计划重连。
5. **60s 探针**：会话活跃期周期性检查 GATT 连接状态。
6. **电池优化豁免**：启动会话时通过 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   请求一次豁免，避免 Doze / App Standby 限制后台定时器与蓝牙访问。

> 注意：部分国产 ROM（MIUI/EMUI/HyperOS 等）除电池优化外，还需手动开启"自启动"
> 与"后台弹出界面"权限，否则系统可能在息屏后冻结整个进程。
