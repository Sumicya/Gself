# Gself

LSPosed 纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来：推送能唤醒已停止的应用、通知不被「包变化」清掉、ColorOS 放行 Google、通行密钥不限特权浏览器、Gboard 剪贴板不限条数。

无界面、无配置、不写文件——装上、勾作用域、重启。

## 装

前提：Android 16+（API 36），已 root，已装 LSPosed（libxposed API 101+）。

包只从 Actions 取（本仓不发版、不打 tag），产物名 `Gself-<版本>`，默认落到 `Download` 根：

```bash
cd /storage/emulated/0/Download
gh run download -R Sumicya/Gself -n "$(gh api repos/Sumicya/Gself/actions/artifacts --jq '.artifacts[0].name')" -D .
su -c "pm install -r $(find . -maxdepth 2 -name '*.apk' | head -1)"
```

在 LSPosed 里启用「Gself」，作用域勾 `system`、`com.google.android.gms`、Gboard（`scope.list` 已预选），重启。`system` 进程里的挂载只能靠重启生效，停用或卸载同样要重启一次。

## 删

```bash
su -c "pm uninstall sumicya.gself"
rm -rf /storage/emulated/0/Download/Gself-*
```

## 项目限制

- 不支持锁 bootloader 的 User Product 设备：按「可侧载」前提出包，GPL-3 §6 的安装信息义务不由本仓承担。
- 只在一台 Android 16 / ColorOS 真机上验证过；别的 ROM 上各挂载点独立 `hook skip`，推送之外的组可能不生效。
- 通行密钥解限与 Gboard 剪贴板靠 DexKit 运行期按字符串定位混淆方法，宿主改版后可能定位失败（各打一条 `hook skip`，不影响推送）。
- 名字只有一套：仓名、artifact 名、`applicationId`、`namespace`、入口类 `sumicya.gself.XposedMain`；入口类名同时写在 `META-INF/xposed/java_init.list`、`proguard-rules.pro` 与 CI 的 dex 断言里，改名必须同一提交。

## 许可证

GNU General Public License v3.0 or later：`LICENSE` 为 GPL-3.0 全文，APK 内 `assets/LICENSE` 随包附同一份。选它是因为合并了 GPL-3.0 的 `yangFenTuoZi/GooglePasswordManagerUnlock`（通行密钥解限）与 `chenyue404/GboardHook`（Gboard 剪贴板），推送线源自 `fcmfix`；改上游处与日期见 `CHANGELOG.md`。

规范：[Sumicya/selfs](https://github.com/Sumicya/selfs) 最新 `GLOBAL.md`（有 open 规范 PR 时为其 head，无则为 `main`）。
