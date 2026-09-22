# 安全策略

## 报告漏洞

如果你发现了安全问题，请**不要**直接开公开 Issue，改用 GitHub 的
[私密漏洞报告](https://github.com/Try2findwhoasked/waterctlAPP/security/advisories/new) 提交。

请在报告中说明：影响的版本、复现步骤、可能的后果，以及（如有）修复建议。
我会在确认后尽快回应并在修复发布时致谢。

## 支持的版本

仅维护最新发布版本。旧版本的问题请在确认最新版仍存在后再报告。

## 设计上的隐私取舍

- 应用**不申请网络权限**，不会也无法上传任何数据。
- 所有数据（上次连接的设备、设置项）只存在本机 `SharedPreferences` 中。
- 扫描 BLE 时系统在 Android 11 及以下要求位置权限，但应用自身不做定位，也未申请后台定位。

## 已知风险：仓库内的签名密钥

本仓库包含 `waterctl-release.keystore`，且口令以默认值写在 `app/build.gradle.kts` 中。
这样做是为了让使用者能直接复现官方签名、方便对照安装包，但**它意味着任何人都可以
用同一密钥签出与官方 APK 签名一致的安装包**。

因此在安装时请注意：

- 只从本仓库的 [Releases](https://github.com/Try2findwhoasked/waterctlAPP/releases) 页面下载 APK。
- 不要安装来自第三方渠道、声称是本应用的 APK。
- 若要用于分发，请自行用**自己的**密钥重新签名：删除或替换 `waterctl-release.keystore`，
  或通过环境变量覆盖（见 README 的「签名说明」）：

  ```bash
  export WATERCTL_KEYSTORE=/path/to/your.keystore
  export WATERCTL_STORE_PASSWORD=...
  export WATERCTL_KEY_ALIAS=...
  export WATERCTL_KEY_PASSWORD=...
  ```

## 关于协议本身

本应用仅实现与已获授权设备通信所需的 BLE 协议，不针对任何服务器或第三方系统。
请勿将其用于未获授权的设备。