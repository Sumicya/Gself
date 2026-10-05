# Gself

[![Android CI](https://github.com/Sumicya/Gself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/Gself/actions)

基于 LSPosed 的纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来。合并自三个项目：
fcmself（推送修复）、GooglePasswordManagerUnlock（通行密钥解限）、GboardHook（剪贴板）。
无界面、无配置、不写文件——装上、勾作用域、重启。

## 功能（按进程分发）

- **system**：补 `FLAG_INCLUDE_STOPPED_PACKAGES` 唤醒已停止应用；忽略「包变化」类通知取消；放开 ColorOS 后台限制
- **com.google.android.gms**：移除 Google 密码管理器的「特权浏览器允许列表」检查，非 Chrome 也能用通行密钥
- **com.google.android.inputmethod.latin**：改写 Gboard 剪贴板显示个数（10）与过期时间（放宽到实际不限）

## 要求

- Android 16+（API 36）：模块只实现 Android 16 起的广播出口，旧版本不做兼容，安装时会被 minSdk 拒绝
- 已 root + LSPosed（libxposed API 101+）
- 作用域勾 `system` + `com.google.android.gms` + Gboard（scope.list 已预选）

## 构建

```bash
./gradlew test assembleDebug   # 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI 跑单测 + 编译 debug + 校验 R8 入口类，上传一个 debug 包（零 secrets）。真机验证见 [`docs/verify-on-device.md`](docs/verify-on-device.md)。

## 许可证

GPL-3.0-or-later（并入的 GboardHook 是 GPL-3.0）。详见 [`LICENSE`](LICENSE)。
