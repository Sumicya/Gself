#!/data/data/com.termux/files/usr/bin/bash
#
# 用自备密钥给 Gself 的 APK 签名（CI 产物与本地 debug 包都是构建机 / 本机的 debug 证书签名）。
#
# 用途: 让多次出包共用同一个签名证书，可以覆盖安装，不必每次卸载再装。
#
# 用法:
#   ./scripts/sign-apk.sh <apk> [输出.apk]
#
# 依赖（Termux 包，已核实存在）:
#   apksigner  —— Android build-tools 的 apksigner.jar 包装，依赖 openjdk-21
#   openjdk-21 —— 提供 keytool（首次生成密钥库时用）
#
# 环境变量:
#   GSELF_KEYSTORE   密钥库路径，默认 ~/gself.jks
#   GSELF_KEY_ALIAS  密钥别名，默认 gself
#
# 注意: 换签名证书后无法覆盖安装用别的证书签的包（先卸载再装）；密钥库务必备份。
set -euo pipefail

KS="${GSELF_KEYSTORE:-$HOME/gself.jks}"
ALIAS="${GSELF_KEY_ALIAS:-gself}"

IN="${1:-}"
if [ -z "$IN" ]; then
    echo "用法: $0 <apk> [输出.apk]" >&2
    exit 1
fi
[ -f "$IN" ] || { echo "找不到输入文件: $IN" >&2; exit 1; }

OUT="${2:-}"
if [ -z "$OUT" ]; then
    case "$IN" in
        *.apk) OUT="${IN%.apk}-signed.apk" ;;
        *)     OUT="$IN-signed.apk" ;;
    esac
fi

for cmd in apksigner keytool; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "缺少 $cmd，先执行: pkg install apksigner openjdk-21" >&2
        exit 1
    }
done

if [ ! -f "$KS" ]; then
    echo "没有找到密钥库 $KS，现在生成一个（会提示输入密码，请记牢并备份该文件）。"
    keytool -genkeypair -v \
        -keystore "$KS" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Gself, OU=dev, O=sumicya, C=SG"
fi

echo "签名中: $IN -> $OUT"
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --out "$OUT" "$IN"

echo "校验中:"
apksigner verify --print-certs -v "$OUT"

echo
echo "完成: $OUT"
cat <<EOF
安装（root，pm install 读不到 Termux 的 FUSE 文件，必须先拷到 /data/local/tmp）:
  su -c "cp '$OUT' /data/local/tmp/gself.apk" && su -c "pm install -r /data/local/tmp/gself.apk"
  su -c "rm /data/local/tmp/gself.apk"
不用 root: termux-open "$OUT"
EOF
