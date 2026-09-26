# fcmself

[![Android CI](https://github.com/Sumicya/fcmself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/fcmself/actions)

基于 LSPosed 的 FCM/GCM 推送修复模块，针对国内定制 ROM。纯 Hook：没有界面、没有白名单、没有配置项、**不写任何文件**——装上、作用域勾 `system`、重启。

设计基准是**还原原生 AOSP 的推送行为**：原生系统对 GMS 推送链路上的广播（c2dm `RECEIVE` / `REGISTRATION`、Firebase `MESSAGING_EVENT` / `INSTANCE_ID_EVENT` / `NEW_TOKEN`）不加任何自启动、后台与通知限制。本模块把各家 ROM 丢掉的这部分语义补回来，不是绕过安全。

## 功能

- **唤醒已停止的应用**：在系统广播出口补上 `FLAG_INCLUDE_STOPPED_PACKAGES`，解决 `Failed to broadcast to stopped app`
- **通知不被自动清除**：忽略 ROM 以「包变化」为由（含 ColorOS 自造的 10020 / 10021）对通知的取消
- **解除自启动限制**：MIUI 12/13、HyperOS、ColorOS 的七个自启动/广播闸门对推送放行
- **解除厂商后台与电源限制**：MIUI PowerKeeper（Millet 名单、`gms_control`）、ColorOS 代理广播与进程冻结、Hans 后台管理

厂商覆盖 OPPO / OnePlus 与小米；其它 ROM 上对应挂载点自动跳过。

## 要求

- Android 10+（API 29+），已 root 并安装 LSPosed（libxposed API 101+）
- 作用域只需 `system`——全部 Hook 都在 system_server 里

## 安装

装 APK → LSPosed 启用 → 作用域勾 `system` → 重启。开机约 60 秒后（`Boot Complete` 日志）才开始介入，避开开机广播风暴。

## 代码

四个文件，一个包，548 行：

| 文件 | 职责 |
| --- | --- |
| `XposedMain.kt` | 入口、开机闸门、日志、挂载点清单（新增一组 Hook = 加一行） |
| `Hook.kt` | 全部基建：按名字查类/方法/字段/构造器 + 唯一的拦截原语 `hook` |
| `Push.kt` | 「是不是一条 FCM 推送」的唯一判据 + 实参扫描 |
| `Fixes.kt` | 五组挂载点，同形状的闸门用表驱动（两张表 + 一个循环覆盖七个点） |

没有模块基类、没有静态实例表、没有就绪握手、**没有按 SDK_INT 硬编码的参数下标表**：参数一律按类型或按值在实参里认（Intent 只有一个，包名和取消原因按形态/取值识别），认不出就不介入并打日志。适配新系统从「改代码」变成「看日志」。

刻意砍掉的角以 `ponytail:` 注释标在原地，写明代价与加回来的条件。

## 构建

```bash
./gradlew test assembleRelease   # 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

CI 跑单元测试、编译 debug / release、校验 R8 没有裁掉入口类、上传两个产物（零 secrets）。本地签名见 [`docs/build-and-sign-termux.md`](docs/build-and-sign-termux.md)，真机验证清单见 [`docs/verify-on-device.md`](docs/verify-on-device.md)。

## 许可证

仅供学习交流使用。

## 鸣谢

Xposed / LSPosed 团队与所有贡献者，以及上游 fcmfix。
