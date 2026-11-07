# Gself

基于 LSPosed 的纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来。合并自三条线：推送修复（本仓前身，其上上游是 `fcmfix`，旧包名见 CHANGELOG「身份迁移」条）、GooglePasswordManagerUnlock（通行密钥解限）、GboardHook（剪贴板）。

三个作用域各做一件事：

- `system`：定向推送绕过「停止态不得投递」（语义层 `Intent#isExcludingStopped` ＋ AMS 广播入口补 `FLAG_INCLUDE_STOPPED_PACKAGES`）；忽略「包变化」类通知取消；放开 ColorOS 后台限制与解锁后的 Google 禁网规则
- `com.google.android.gms`：去掉通行密钥的「特权浏览器允许列表」，非 Chrome 也能用
- `com.google.android.inputmethod.latin`：Gboard 剪贴板显示 10 条、实际不限过期

无界面、无配置、不写文件——装上、勾作用域、重启。

## 安装与使用

前提：Android 16+（API 36，minSdk 36，低版本装不上）、已 root、已装 LSPosed（libxposed API 101+）。
全程在手机上（Termux 等带 `gh` 的终端），不需要电脑、不需要 `adb`。

**包只从这里取**：本仓不发 Release、不打 tag，可装的包只有 Actions 里 `build` 的运行产物，
下满 5 天后自动过期。

**1. 下载到固定路径**（`gh` 已登录时可用；落点是 `Download` 根，不解到别处）

```bash
cd /storage/emulated/0/Download
gh run download -R Sumicya/Gself -n "$(gh api repos/Sumicya/Gself/actions/artifacts --jq '.artifacts[0].name')" -D .
```

**2. 看包在哪**

```bash
find /storage/emulated/0/Download -maxdepth 2 -name '*.apk' -printf '%p  %s 字节\n'
```

**3. 装**（有 root 直接装；没 root 就在文件管理里点开上一步列出的 `.apk`）

```bash
su -c "pm install -r $(find /storage/emulated/0/Download -maxdepth 2 -name '*.apk' | head -1)"
```

**4. 清**

```bash
rm -rf /storage/emulated/0/Download/Gself-*
```

装完不用点开（模块没有界面、没有图标入口）。在 LSPosed 管理器里启用「Gself」，作用域勾 `system`、`com.google.android.gms`、Gboard（`scope.list` 已预选），然后重启——`system` 进程里的挂载只能靠重启生效，停用或卸载同样要重启一次。

验收：按 [`docs/verify-on-device.md`](docs/verify-on-device.md) 第 1 节确认模块被加载（日志 tag `Gself`，出现 `推送达标：语义层在｜标志层在` 即两层都在），再按第 2.1 节那一条命令跑完四项信号。

源码就是本仓：artifact 名第五段＝该次 `build` 的 `run_number`，按号查运行即可拿到 head 提交，那个提交是可构建的对应源码（GPL-3 §6(d)）。

artifact 只留 5 天、每仓最多 5 个；路径里不带空格，所以第 3 步的 `$(find …)` 可以直接嵌进 `su -c`。

## 项目限制

- **不支持锁 bootloader / 未解锁的 User Product 设备**：本模块按「可侧载」前提出包，不承诺在禁止安装修改版系统的设备上能装（GPL-3 §6 的安装信息义务因此不由本仓承担）。
- 只在 Android 16（API 36）+ ColorOS 的一台真机上验证过；其它 ROM 上 `hooks.install` 各自 `hook skip`，推送唤醒之外的功能可能不生效。
- 模块没有界面、没有配置、不写文件；日志是唯一观测手段（logcat tag `Gself`，框架日志前缀 `[gself]`）。
- **名字只有一套**：仓名、artifact 名、`app_name`、`applicationId`、`namespace`、源码目录、入口类全部是 `Gself` / `sumicya.gself`（入口类 `sumicya.gself.XposedMain`）。入口类名被三处同时锁死——`META-INF/xposed/java_init.list`、`proguard-rules.pro` 的 keep、CI 的 dex 断言 `Lsumicya/gself/XposedMain;`——下一次改名必须四处同一提交，靠 CI 断言兜底（改错即红）。
- 通行密钥解限与 Gboard 剪贴板依赖 DexKit 在运行期按字符串定位混淆方法，GMS / Gboard 改版后可能定位失败（届时各自打一条 `hook skip`，不影响推送唤醒）。

## 许可证

GNU General Public License v3.0 or later（`LICENSE` 为 GPL-3.0 全文，35149 字节；APK 内 `assets/LICENSE` 随包附同一份全文）。

选这个许可的原因：本仓合并自推送修复线（本仓前身，源头是 `fcmfix`，包名变迁见 CHANGELOG「身份迁移」条）、GooglePasswordManagerUnlock、GboardHook，其中 chenyue404/GboardHook 以 GPL-3.0 发布，合并作品须同证。上游归属：本仓前身的推送修复线（源头 `fcmfix`）、yangFenTuoZi/GooglePasswordManagerUnlock（通行密钥解限，GPL-3.0）、chenyue404/GboardHook（Gboard 剪贴板，GPL-3.0）。

模块没有可点的「关于」页（本仓无界面），署名与无担保声明以本节与随包的 `LICENSE` 呈现；若将来加了界面，在界面里放许可全文入口。

遵循 [Sumicya/selfs](https://github.com/Sumicya/selfs) 最新 GLOBAL.md。
