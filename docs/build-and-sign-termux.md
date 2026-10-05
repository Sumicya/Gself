# 在 Termux 里构建与签名

本仓库的 CI **不发 Release**：`Build` 工作流只编译 debug 包，并上传一个 artifact `Gself-<版本>.apk`
（debug 签名，可直接安装；取法见会话汇报里的下载命令块，按前缀 `Gself-` 过滤 artifact）。
这份文档讲两件可选的事：不依赖 CI 的本地构建，以及用自己的密钥签名（需要长期覆盖安装、或改过包内容时用）。

## 一次性准备

```bash
pkg update
pkg install -y openjdk-21 apksigner termux-tools
```

`apksigner` 是 Termux 打包的 Android build-tools `apksigner.jar`（依赖 `openjdk-21`，会一起装上）；
`keytool` 由 `openjdk-21` 提供。

## 可选一：完全在 Termux 里构建

能跑，但重（Android SDK + Gradle 发行版约 2–4 GB，手机上编译很慢）。只在不想依赖 CI 时才需要：

```bash
pkg install -y openjdk-21 git unzip
# 从 developer.android.com/studio 拿 commandlinetools-linux 最新链接，
# 解压成 ~/android-sdk/cmdline-tools/latest，然后：
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

git clone https://github.com/Sumicya/Gself.git
cd Gself
./gradlew assembleDebug
```

`ANDROID_HOME` / `PATH` 两行写进 `~/.bashrc`，否则新开 shell 找不到 SDK。

本地构建**不传版本参数**，得到的是非发行版本：`versionName` 是 `dev-1`、`versionCode` 是 1，
产物在 `app/build/outputs/apk/debug/app-debug.apk`。发行版本（五段 `yy.m.d.当日序号.总序号`）只在
main 的 CI 出包链路里产生，查法见 `AGENTS.md`。想让本地包覆盖安装时不降级，把 `versionCode`
设成不比已装包小（已装包的总序号 = 产物名第五段，或看 CI 运行摘要里的 `versionCode`）：

```bash
./gradlew -PversionCode=128 assembleDebug     # 128 只是示例，按上面两处实际值取
```

## 可选二：用自己的密钥签名

CI 产物用构建机上的 debug 证书签名。GitHub 托管的构建机是临时的，debug 证书不保证跨出包一致：
如果新包因为签名不同装不上（报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`），先卸载再装——模块不写数据，
卸载无副作用，但卸载后要在 LSPosed 里重新启用模块。想长期稳定覆盖安装就用自己的密钥库：

```bash
git clone https://github.com/Sumicya/Gself.git
cd Gself
./scripts/sign-apk.sh ~/Gself-<版本>.apk        # 首次运行自动生成密钥库并提示设密码
```

脚本做的事看它自己就行（没有密钥库就 `keytool -genkeypair` → `apksigner sign` → `apksigner verify --print-certs`）。
默认密钥库 `~/gself.jks`、别名 `gself`，可用 `GSELF_KEYSTORE` / `GSELF_KEY_ALIAS` 覆盖。
**密钥库丢了就再也无法覆盖安装用它签过的包，生成后务必备份。**

签名**不改变**包名与代码，只是换签名证书；用自备密钥签的包和 CI 的 debug 包互不覆盖安装
（签名不同），装之前先卸载对方那个。

不需要单独跑 zipalign：AGP 打包阶段已对齐，`apksigner` 签名保持对齐（Termux 也没有 zipalign 包）。

## 安装（root）

`pm install` 由 system_server 执行，读不到 Termux 的 FUSE 文件，必须先复制到 `/data/local/tmp`：

```bash
su -c "cp '$HOME/Gself-<版本>-signed.apk' /data/local/tmp/gself.apk"
su -c "pm install -r /data/local/tmp/gself.apk"
su -c "rm /data/local/tmp/gself.apk"
```

不想用 root 就在文件管理器里点安装，或 `termux-open "$HOME/Gself-<版本>-signed.apk"`（调起系统安装器）。
装完在 LSPosed 里启用模块、勾作用域，然后重启设备。本地清理：`rm ~/Gself-*-signed.apk`。
