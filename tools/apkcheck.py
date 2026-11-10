#!/usr/bin/env python3
"""核 APK：入口类、原生库存储方式、ABI、许可全文、图标层、体积与构成（CI 与手机同一份）。

判据只看**编译产物**，不看源码：源文件写对了不等于 aapt2 / R8 编出来还在。

- 入口类 `Lsumicya/gself/XposedMain;` 必须在 dex 里：R8 改名或裁剪后构建仍然成功，
  模块却会静默不加载。
- `lib/arm64-v8a/libdexkit.so` 必须按 ZIP_STORED 存：`LspModuleClassLoader` 从
  `base.apk!/lib/arm64-v8a` 直接 mmap，压过的 .so 会抛 `UnsatisfiedLinkError`（2026-11-07 真机实测）。
  ABI 只许 arm64-v8a（Android 16 设备全是 64 位）。
- `assets/LICENSE` 必须与仓根 `LICENSE` 逐字节相同（GPL-3 §6 要求随包附许可）。
- 单色图标层要真的编进 `mipmap-anydpi-v26`：AXML 的字符串池可能是 UTF-8 或 UTF-16LE，两种都试。
- 体积预算：APK 700000、dex 250000、原生 400000 字节。

用法：`python3 tools/apkcheck.py [apk]`，默认取 `app/build/outputs/apk/debug/Gself-*.apk`。
"""
import glob
import os
import re
import sys
import zipfile

BUDGET = {'apk': 700_000, 'dex': 250_000, 'native': 400_000}
ENTRY = b'Lsumicya/gself/XposedMain;'
SO = 'lib/arm64-v8a/libdexkit.so'


def has(blob: bytes, s: str) -> bool:
    return s.encode('utf-8') in blob or s.encode('utf-16-le') in blob


def check_icon(z: zipfile.ZipFile, problems: list[str]) -> str:
    src = open('app/src/main/res/drawable/ic_launcher_monochrome.xml', encoding='utf-8').read()
    pathdata = re.findall(r'android:pathData="([^"]+)"', src)
    if len(pathdata) != 1:
        problems.append(f'源文件应有且只有一条 path，实际 {len(pathdata)} 条')
        return '源文件形状不合'
    names = z.namelist()
    anydpi = sorted(n for n in names if 'mipmap-anydpi-v26' in n)
    mono = sorted(n for n in names if n.endswith('ic_launcher_monochrome.xml'))
    layers = {n: all(has(z.read(n), k) for k in ('background', 'foreground', 'monochrome'))
              for n in anydpi}
    exact = len(mono) == 1 and has(z.read(mono[0]), pathdata[0])
    arsc = 'resources.arsc' in names and has(z.read('resources.arsc'), 'ic_launcher_monochrome')
    if len(anydpi) != 2 or len(mono) != 1 or not all(layers.values()) or not exact or not arsc:
        problems.append(f'图标层核验不通过：anydpi-v26={len(anydpi)} 三层={list(layers.values())} '
                        f'monochrome={len(mono)} 逐字节相同={exact} arsc 登记={arsc}')
    return f'anydpi-v26 {len(anydpi)} 个、monochrome {len(mono)} 份、arsc 登记 {arsc}'


def main() -> int:
    apks = sys.argv[1:] or sorted(glob.glob('app/build/outputs/apk/debug/Gself-*.apk'))
    if len(apks) != 1:
        sys.exit(f'期望恰好 1 个 APK，实际 {len(apks)} 个：{apks}')
    apk = apks[0]
    size = os.path.getsize(apk)
    problems: list[str] = []
    with zipfile.ZipFile(apk) as z:
        infos = {i.filename: i for i in z.infolist()}
        dex = b''.join(z.read(n) for n in infos if n.startswith('classes') and n.endswith('.dex'))
        native = infos[SO].compress_size
        stored = infos[SO].compress_type == zipfile.ZIP_STORED
        abis = sorted({n.split('/')[1] for n in infos if n.startswith('lib/')})
        licensed = z.read('assets/LICENSE') == open('LICENSE', 'rb').read()
        icon = check_icon(z, problems)
        top = sorted(infos.values(), key=lambda i: i.compress_size, reverse=True)[:3]

    if ENTRY not in dex:
        problems.append('dex 里没有入口类 Lsumicya/gself/XposedMain;（R8 改名或裁剪）')
    if not stored:
        problems.append(f'{SO} 是压缩存储，必须 ZIP_STORED')
    if abis != ['arm64-v8a']:
        problems.append(f'lib/ 下的 ABI 是 {abis}，只允许 arm64-v8a')
    if not licensed:
        problems.append('assets/LICENSE 与仓根 LICENSE 不一致')
    for key, got in (('apk', size), ('dex', len(dex)), ('native', native)):
        if got > BUDGET[key]:
            problems.append(f'{key} {got} 字节超出预算 {BUDGET[key]}')

    print(f'{apk}: APK {size}/{BUDGET["apk"]} 字节；dex {len(dex)}/{BUDGET["dex"]}；'
          f'原生 {native}/{BUDGET["native"]}（STORED={stored}）；条目 {len(infos)} 个；'
          f'许可随包 {licensed}；{icon}；前三大 '
          + '、'.join(f'{i.filename}={i.compress_size}' for i in top))
    for line in problems:
        print(f'::error::{line}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
