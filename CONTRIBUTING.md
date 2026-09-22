# 贡献指南

感谢有兴趣改进本项目。这个仓库规模不大，规则也很简单。

## 开发环境

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | AGP 8.7.3 不支持更高版本 |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0` | |
| Gradle | 8.13（由 wrapper 提供） | 不必单独安装 |

构建：

```powershell
# Windows：自动固定 JDK 17 与 Android SDK 路径
tools\build.cmd assembleDebug

# 其它平台
./gradlew assembleDebug
```

真机调试（需要 Android 8.0+ 且支持 BLE 的设备）：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat | findstr waterctl   # 或直接看应用内的日志面板
```

## 代码约定

- 纯 Kotlin + Android framework，**不引入第三方运行时依赖**（唯一依赖是 Kotlin stdlib）。
- 界面用代码构建（不写 XML 布局），风格与现有页面保持一致：`LinearLayout` + `dp()` + `GradientDrawable` 圆角。
- 新增 Activity 需在 `AndroidManifest.xml` 中声明；页面顶部必须调用 `applySystemInsets()`，否则在刘海屏上会被遮挡。
- 注释用中文，解释**为什么**这么做（尤其是与 BLE 栈、系统限制相关的绕行写法）。

## 协议相关

- `app/src/main/kotlin/com/waterctl/android/protocol/` 是与 [celesWuff/waterctl](https://github.com/celesWuff/waterctl) 保持同步的移植层，改动前请先对照上游 `src/` 的对应文件。
- `DeputyData.kt` 是自动生成的查找表（`tools/gen-deputy-kotlin.mjs`），**不要手工修改**。
- `Payloads.kt` 中的固定数据包改动会直接影响握手是否成功，务必在真机上验证完整会话（开始 → 结束）。
- 修改协议层后，建议在 Issue 或 PR 中附上真机日志，方便他人复核。

## 提交信息

遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
<类型>(<作用域>): <中文描述>

- 具体改动
```

常用类型：`feat`、`fix`、`docs`、`refactor`、`build`、`ci`、`chore`；作用域可取 `ble`、`ui`、`protocol` 等。

## 提交前检查

- [ ] `tools\build.cmd assembleDebug`（或 `./gradlew assembleDebug`）构建通过
- [ ] 涉及 BLE / 协议的改动已在真机上实测：会话能正常开始、锁屏后不掉线、能正常结束
- [ ] 面向用户的改动已同步更新 `README.md` 与 `CHANGELOG.md`