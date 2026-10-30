# Gself

基于 LSPosed 的纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来。合并自三个项目：
fcmself（推送修复）、GooglePasswordManagerUnlock（通行密钥解限）、GboardHook（剪贴板）。
无界面、无配置、不写文件——装上、勾作用域、重启。

## 要求

- Android 16+（API 36）：模块只实现 Android 16 起的广播出口，旧版本不做兼容，安装时会被 minSdk 拒绝
- 已 root + LSPosed（libxposed API 101+）
- 作用域勾 `system` + `com.google.android.gms` + Gboard（scope.list 已预选）

## 功能

- **system**：让定向推送绕过「停止态不得投递」——对 `Intent#isExcludingStopped()` 答 false（语义层），并在 AMS 全部广播入口补 `FLAG_INCLUDE_STOPPED_PACKAGES`（标志层）；忽略「包变化」类通知取消；放开 ColorOS 后台限制，并在 Battery 隐藏 Google 联网开关的机型上拦截开机解锁后的 Google 禁网规则
- **com.google.android.gms**：移除 Google 密码管理器的「特权浏览器允许列表」检查，非 Chrome 也能用通行密钥
- **com.google.android.inputmethod.latin**：改写 Gboard 剪贴板显示个数（10）与过期（实际不限时长）

## 构建

```bash
./gradlew test assembleDebug   # 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI（`Build`）跑单测 + 编译 debug + 校验 R8 入口类，产物 `Gself-<版本>.apk` 走 Actions artifact（滚动清理保留最近 5 个发行包，5 天过期）；不发 Release。真机验证见 [`docs/verify-on-device.md`](docs/verify-on-device.md)。

产物的「对应源码」怎么找：artifact 名第四、五段是版本，第五段等于 `Build` 的 `run_number`，按这个号查该次运行即可拿到 head 提交；那个提交就是可构建的源码，构建命令就是上面这条。

## 项目限制

- **不支持锁 bootloader / 未解锁的 User Product 设备**：本模块按「可侧载」前提出包，不承诺在禁止安装修改版系统的设备上能装（GPL-3 §6 的安装信息义务因此不由本仓承担）。
- 只在 Android 16（API 36）+ ColorOS 的一台真机上验证过；其它 ROM 上 `hooks.install` 各自 `hook skip`，推送唤醒之外的功能可能不生效。
- 模块没有界面、没有配置、不写文件；日志是唯一观测手段（tag `FcmSelf`）。
- 通行密钥解限与 Gboard 剪贴板依赖 DexKit 在运行期按字符串定位混淆方法，GMS / Gboard 改版后可能定位失败（届时各自打一条 `hook skip`，不影响推送唤醒）。

## 许可证

GNU General Public License v3.0 or later（`LICENSE` 为 GPL-3.0 全文，35149 字节；APK 内 `assets/LICENSE` 随包附同一份全文）。

选这个许可的原因：本仓合并自 fcmself、GooglePasswordManagerUnlock、GboardHook，其中 chenyue404/GboardHook 以 GPL-3.0 发布，合并作品须同证。上游归属：fcmself（本仓前身）、yangFenTuoZi/GooglePasswordManagerUnlock（通行密钥解限，GPL-3.0）、chenyue404/GboardHook（Gboard 剪贴板，GPL-3.0）。

模块没有可点的「关于」页（本仓无界面），署名与无担保声明以本节与随包的 `LICENSE` 呈现；若将来加了界面，在界面里放许可全文入口。

遵循 [Sumicya/selfs](https://github.com/Sumicya/selfs) 最新 GLOBAL.md。
