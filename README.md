# Gself

[![Android CI](https://github.com/Sumicya/Gself/actions/workflows/android.yml/badge.svg)](https://github.com/Sumicya/Gself/actions/workflows/android.yml)

基于 LSPosed 的纯 Hook 模块，把 Google 在国产 ROM 上被砍掉的语义补回来：GMS 推送唤醒、通行密钥解限、
Gboard 剪贴板。合并自 fcmself（推送修复）、GooglePasswordManagerUnlock（通行密钥解限）、
GboardHook（剪贴板）三个项目；无界面、无配置、不写文件——装上、勾作用域、重启。

## 要求

- Android 10+（API 29+），已 root + LSPosed（libxposed API 101+）
- 作用域勾 `system` + `com.google.android.gms` + Gboard（`com.google.android.inputmethod.latin`）；
  `scope.list` 已预选这三个包

## 功能（按进程分发）

- **system**：补 `FLAG_INCLUDE_STOPPED_PACKAGES` 唤醒已停止的应用；忽略「包变化」类通知取消；放开 ColorOS 后台限制
- **com.google.android.gms**：移除 Google 密码管理器的「特权浏览器允许列表」检查，非 Chrome 也能用通行密钥
- **com.google.android.inputmethod.latin**：改写 Gboard 剪贴板显示个数（10）与过期时间（放宽到实际不限）

## 下载与安装

本仓库**不发 Release**，产物只有一个 `Gself-<版本>.apk`（debug 签名，可直接安装），
从 Android CI（main 分支最近一次成功构建）的 Actions artifact 取。在手机上（Termux）执行：

```bash
cd /sdcard/Download
TOKEN=$(gh auth token)
RUN=$(curl -sS -H "Authorization: Bearer $TOKEN" \
  'https://api.github.com/repos/Sumicya/Gself/actions/workflows/android.yml/runs?branch=main&status=success&per_page=1' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["workflow_runs"][0]["id"])')
ART=$(curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/Sumicya/Gself/actions/runs/$RUN/artifacts" \
  | python3 -c 'import json,sys; print([a["id"] for a in json.load(sys.stdin)["artifacts"] if a["name"].startswith("Gself-")][0])')
echo "run=$RUN artifact=$ART"
curl -sSL -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/Sumicya/Gself/actions/artifacts/$ART/zip" -o Gself.zip
ls -l Gself.zip
unzip -o Gself.zip                      # 解出 Gself-<版本>.apk
APK=$(ls -t Gself-*.apk | head -1)
su -c "cp '$APK' /data/local/tmp/gself.apk" && su -c "pm install -r /data/local/tmp/gself.apk"
su -c "rm /data/local/tmp/gself.apk"    # 清掉临时文件
```

- `su -c` 需要 root：`pm install` 由 system_server 执行，读不到 FUSE 上的文件，必须先复制到 `/data/local/tmp`。
  不想用 root 也可以在文件管理器里点安装（或 `termux-open "$APK"`）。
- 装完在 LSPosed 里启用模块、勾上 `system` + `com.google.android.gms` + Gboard，然后**重启设备**
  （system_server 里的 Hook 只能靠重启生效）。
- 想用自己的签名密钥（覆盖安装旧包、或改过包内容）时见 [docs/build-and-sign-termux.md](docs/build-and-sign-termux.md)。
- 本地清理：`rm /sdcard/Download/Gself.zip /sdcard/Download/Gself-*.apk`（不删别的文件）。

## 相关文档

- [真机验证清单](docs/verify-on-device.md)：日志点位、三段归因、抓取命令
- [构建与签名（Termux）](docs/build-and-sign-termux.md)：本地构建、自备密钥签名
- [术语表](docs/glossary.md)

## 许可证

GPL-3.0-or-later（并入的 chenyue404/GboardHook 是 GPL-3.0，合并作品须同证）。详见 [`LICENSE`](LICENSE)。
