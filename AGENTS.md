# Gself 项目条目

- Gself 是基于 libxposed 的 Android Hook 模块；Hook 分别运行于 `system`、`com.google.android.gms` 和 Gboard 进程，作用域以 `app/src/main/resources/META-INF/xposed/scope.list` 为准。
- 构建与单测：`./gradlew test assembleDebug`。依赖 ROM 私有 API 的行为需在真机验证，流程见 `docs/verify-on-device.md`。
- 全局规范：[Sumicya/selfs `GLOBAL.md`](https://github.com/Sumicya/selfs/blob/main/GLOBAL.md)。本仓库上次同步 = 第十四版。
