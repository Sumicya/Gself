# 在 Termux 里签名（以及可选的本地构建）

本仓库的 CI 不依赖任何 secrets：它只编译，并上传两个产物。签名在本地做。

| 产物 | 说明 |
| --- | --- |
| `fcmself-<版本>-debug-signed.apk` | debug 签名，**可直接安装**，用于日常测试 |
| `fcmself-<版本>-unsigned.apk` | release 未签名，用下面的命令签完再装 |

## 一次性准备

```bash
pkg update
pkg install -y openjdk-21 apksigner termux-tools
```

`apksigner` 是 Termux 打包的 Android build-tools `apksigner.jar`（依赖 `openjdk-21`，会一起装上）；
`keytool` 由 `openjdk-21` 提供。

## 签名

仓库里带了脚本，一条命令搞定（首次运行自动生成密钥库并提示设密码）：

```bash
git clone https://github.com/Sumicya/fcmself.git
cd fcmself
./scripts/sign-apk.sh ~/下载/fcmself-<版本>-unsigned.apk
```

`~/fcmself.jks` 丢了就再也无法覆盖安装旧版本，**生成后务必备份**。脚本做的事看它自己就行
（61 行，就三步：没有密钥库就生成 → `apksigner sign` → `apksigner verify --print-certs`）。

安装：`termux-open ~/fcmself-signed.apk`（调起系统安装器），已 root 也可以
`su -c pm install -r ~/fcmself-signed.apk`。

不需要单独跑 zipalign：AGP 打包阶段已对齐，`apksigner` 签名保持对齐（Termux 也没有 zipalign 包）。

## 可选：完全在 Termux 里构建

能跑，但重（Android SDK + Gradle 发行版约 2–4 GB，手机上编译很慢）。只在不想依赖 CI 时才需要：

```bash
pkg install -y openjdk-21 git unzip
# 从 developer.android.com/studio 拿 commandlinetools-linux 最新链接，
# 解压成 ~/android-sdk/cmdline-tools/latest，然后：
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

git clone https://github.com/Sumicya/fcmself.git
cd fcmself
./gradlew assembleRelease
./scripts/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

`ANDROID_HOME` / `PATH` 两行写进 `~/.bashrc`，否则新开 shell 找不到 SDK。
