# Gself

[![Android CI](https://github.com/Sumicya/fcmself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/fcmself/actions)

基于 LSPosed 的纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来。合并自三个项目：
fcmself（推送修复）、GooglePasswordManagerUnlock（通行密钥解限）、GboardHook（剪贴板）。
无界面、无配置、不写文件——装上、勾作用域、重启。

## 功能（按进程分发）

- **system**：补 `FLAG_INCLUDE_STOPPED_PACKAGES` 唤醒已停止应用；忽略「包变化」类通知取消；放开 ColorOS 后台限制
- **com.google.android.gms**：移除 Google 密码管理器的「特权浏览器允许列表」检查，非 Chrome 也能用通行密钥
- **com.google.android.inputmethod.latin**：改写 Gboard 剪贴板显示个数（10）与过期（3 天）

## 要求

- Android 10+（API 29+），已 root + LSPosed（libxposed API 101+）
- 作用域勾 `system` + `com.google.android.gms` + Gboard（scope.list 已预选）

## 构建

```bash
./gradlew test assembleDebug   # 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI 对 `main` 的 push 和 PR 运行单测、debug 编译及 R8 入口类检查，并上传 debug 包（无需 secrets）。`main` 成功构建或从 `main` 手动运行 `Android CI` 成功后，工作流会清理自身生成的 GitHub Actions 产物，只保留最新 1 个；范围包含 PR 与其他分支的历史产物（包括旧 `fcmself-*` 命名），不触碰其他工作流、Release 或标签。每个产物设 5 天过期兜底；PR 检查不执行清理。清理脚本见 [`.github/scripts/cleanup_artifacts.py`](.github/scripts/cleanup_artifacts.py)。真机验证见 [`docs/verify-on-device.md`](docs/verify-on-device.md)。

## 许可证

GPL-3.0-or-later（并入的 GboardHook 是 GPL-3.0）。详见 [`LICENSE`](LICENSE)。
