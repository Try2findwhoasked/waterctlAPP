# waterctl-android

蓝牙水控器 FOSS 的安卓原生版本，基于已验证的 [waterctl](https://github.com/celesWuff/waterctl)（TypeScript）协议实现直译为 Kotlin。

## 下载与安装

从 [Releases](https://github.com/Try2findwhoasked/waterctlAPP/releases) 下载最新 APK 并安装（无需 root，不申请网络权限）。

| 文件 | 包名 | 说明 |
| --- | --- | --- |
| `app-release.apk` | `com.waterctl.android` | 发布版，已用 `waterctl-release.keystore` 签名 |
| `app-debug.apk` | `com.waterctl.android` | 调试版，可直接 `adb install` |

要求：Android 8.0（API 26）及以上，且设备支持蓝牙 4.0（BLE）。

也可以自行构建，见[构建](#构建)章节。

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

`local.properties` 含本机 SDK 路径，不入版本库（由 Android Studio 或 `sdk.dir=` 自行生成）。

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

> 签名说明：`waterctl-release.keystore` 若不存在（例如 fork 后未带私钥），
> `assembleRelease` 会回退到 debug 签名，构建依旧可用。可用环境变量
> `WATERCTL_KEYSTORE`、`WATERCTL_STORE_PASSWORD`、`WATERCTL_KEY_ALIAS`、
> `WATERCTL_KEY_PASSWORD` 覆盖签名配置。

## 发布流程

1. 更新 `app/build.gradle.kts` 中的 `versionCode` / `versionName`，并在 `CHANGELOG.md` 记录改动。
2. 提交并推送，确认 [Actions](../../actions/workflows/build.yml) 构建通过。
3. 打标签并推送，工作流会自动创建 Release 并附上两个 APK：

```bash
git tag v1.0.2
git push origin v1.0.2
```

## 界面

- 统一青绿色卡片风格，主界面与设置页共享配色、圆角与按钮触控反馈。
- 连接状态卡：显示准备连接、正在连接、正在用水、正在重连、正在结束及设备信息。
- 一键连接：连接上次使用的设备，无记录时禁用，记录保存在本机。
- 附近设备：显示扫描反馈、设备数量、名称与 MAC 地址；点击设备可看到选中标记，再点击「连接所选设备」。
- 操作随状态联动：扫描期间禁止重复扫描和启动连接；「停止重连」仅在重连时可用。
- 日志默认收起，展开后可滚动、选择复制，最多保留最近 200 条记录；旋转屏幕保留展开状态。
- 页面可整体滚动，适配小屏、横屏与大字体；内容避让状态栏、导航栏和刘海。
- 设置页提供数据包日志开关，自动保存并立即生效。
- 更多菜单与返回按钮采用居中的矢量图标；关于弹窗可滚动查看应用说明、隐私、协议来源及免责声明，提供项目主页、版本下载、反馈和许可证链接。

## 设置

设置是**全局**的（不区分设备），保存在 `SharedPreferences`（文件 `waterctl`，由 `Prefs.kt` 统一读写）。
`BleService` 每次发包前都会重新读取偏好，因此切换后立即生效，进行中的会话无需重连。

| 设置项 | 默认 | 说明 |
| --- | --- | --- |
| 在日志中显示发送的数据包 | 开 | 以 `TX` 前缀显示本机发出的数据包（十六进制）。关闭后日志更简洁；接收到的数据包（`RXD`）始终显示。 |

## 权限

应用只申请与蓝牙会话直接相关的权限，**不申请网络权限**，所有数据都留在本机。

| 权限 | 用途 |
| --- | --- |
| `BLUETOOTH` / `BLUETOOTH_ADMIN`（API ≤ 30） | 传统蓝牙访问 |
| `BLUETOOTH_SCAN`（`neverForLocation`）/ `BLUETOOTH_CONNECT`（API 31+） | 扫描与连接 BLE 设备 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（API ≤ 30） | Android 11 及以下，系统要求扫描 BLE 必须授予位置权限（应用本身不做定位） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 会话期间的前台服务，保证锁屏后连接不中断 |
| `WAKE_LOCK` | 锁屏后维持 CPU 运行 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求加入电池优化白名单，避免 Doze 冻结重连定时器 |

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
4. **自动重连**：断链后指数退避（1s 起、每次 ×2、上限 30s）；服务常驻，故锁屏期间
   仍能按计划重连。
5. **60s 探针**：会话活跃期周期性检查 GATT 连接状态。
6. **电池优化豁免**：启动会话时通过 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   请求一次豁免，避免 Doze / App Standby 限制后台定时器与蓝牙访问。

> 注意：部分国产 ROM（MIUI/EMUI/HyperOS 等）除电池优化外，还需手动开启"自启动"
> 与"后台弹出界面"权限，否则系统可能在息屏后冻结整个进程。

## 免责声明

- 本程序仅供对**已获授权**的水控设备进行操作，请遵守设备管理方的规定。
- 请勿用于任何非法用途；因使用本程序造成的设备损坏、费用损失或其它后果，
  由使用者自行承担。
- 本项目与任何水控设备厂商无关，也未获得其授权或认可。

## 许可与致谢

本项目以 [MIT 许可证](LICENSE) 发布。

- 协议实现移植自 [celesWuff/waterctl](https://github.com/celesWuff/waterctl)（MIT），
  版权归原作者所有，详见 [LICENSE](LICENSE)。
- 感谢上游作者对水控器 BLE 协议的分析与开源。