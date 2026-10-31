#!/usr/bin/env python3
"""Gself 启动器图标审计出图器：只用标准库，`python3 tools/icon_figure.py` 直接产 docs/icon-mask.png。

所有口径都写在这里，第三个人可以照着重算：
  · 网格：108dp 画布 → 1080×1080 像素，即 10 px/dp；像素中心采样。
  · 填充：把 pathData 三次贝塞尔按 64 段采样成多边形，再按 nonzero 规则做扫描线填充
    （子路径绕向由有符号面积定，`.z` 闭合）。fillType="nonZero" 与源文件一致。
  · 渐变：线性渐变 alpha = A0 * (1 - clamp(投影 t, 0, 1))，A0 = 0x44/255 ≈ 0.2667；
    起点 (42.9492, 49.59793)，终点 (85.84757, 92.4963)，均在 dp 坐标。
  · 遮罩：按 AOSP AdaptiveIconDrawable 的规则——遮罩 100 单位铺满 drawable 边界、
    图层按 1.5 倍画，所以可见设计区半径 = 108/2/1.5 = 36dp，安全圆 = 36 × 66/72 = 33dp。
    四种形状：circle r=36；squircle 超椭圆 n=4；(72dp 方 - 圆角 r=16)；square 半宽 36。
  · 降采样：面积平均（目标像素覆盖的源像素块求均值），不做 gamma 校正，
    与系统把 108dp 矢量栅格化到 48/96/192px 的口径一致到一阶。
"""
import glob
import hashlib
import math
import os
import re
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
OUT = os.path.join(ROOT, 'docs/icon-mask.png')

GRID = 1080
SCALE = GRID / 108.0
CX = CY = 54.0
SAFE_R, MASK_R = 33.0, 36.0
BG = (0x3D, 0xDC, 0x84)
DARK = (0x1B, 0x1B, 0x1B)

TOKEN = re.compile(r'([MmLlHhVvCcSsQqTtZz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)')


# ---------- pathData → 多边形 ----------
def subpaths(d, curve_samples=64):
    """pathData → 子路径点表（每条已闭合、绝对坐标、画布单位 dp）。
    支持 M L H V C S Q T Z 的大小写形式与隐式重复；曲线按 curve_samples 段折线化。"""
    toks = TOKEN.findall(d)
    seq = [(c, None) if c else (None, float(n)) for c, n in toks]

    sub, result = [], []
    x = y = sx = sy = 0.0
    cmd = None
    args = []
    prev_c2 = None

    def add(pt):
        nonlocal x, y
        sub.append(pt)
        x, y = pt

    def flush():
        nonlocal sub
        if len(sub) > 1:
            if sub[0] != sub[-1]:
                sub.append(sub[0])
            result.append(list(sub))
        sub = []

    def cubic(p1, p2, p3):
        p0 = (x, y)
        for k in range(1, curve_samples + 1):
            t = k / curve_samples
            u = 1 - t
            add((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                 u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
        return p2

    def quad(p1, p2):
        p0 = (x, y)
        for k in range(1, curve_samples + 1):
            t = k / curve_samples
            u = 1 - t
            add((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                 u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))

    for c, n in seq:
        if c:
            cmd = c
            args = []
            if c in 'Zz':
                add((sx, sy))
                flush()
                prev_c2 = None
            continue
        args.append(n)
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'Z': 0}[cmd.upper()]
        if len(args) < need:
            continue
        a, args = args[:need], args[need:]
        C, rel = cmd.upper(), cmd.islower()
        if C == 'M':
            px, py = a
            if rel:
                px += x
                py += y
            flush()
            sub.append((px, py))
            x, y = px, py
            sx, sy = px, py
            cmd = 'l' if rel else 'L'
            prev_c2 = None
        elif C == 'L':
            px, py = a
            if rel:
                px += x
                py += y
            add((px, py))
            prev_c2 = None
        elif C == 'H':
            add((a[0] + (x if rel else 0), y))
            prev_c2 = None
        elif C == 'V':
            add((x, a[0] + (y if rel else 0)))
            prev_c2 = None
        elif C == 'C':
            px1, py1, px2, py2, px3, py3 = a
            if rel:
                px1 += x; py1 += y; px2 += x; py2 += y; px3 += x; py3 += y
            prev_c2 = cubic((px1, py1), (px2, py2), (px3, py3))
        elif C == 'S':
            px2, py2, px3, py3 = a
            if rel:
                px2 += x; py2 += y; px3 += x; py3 += y
            p1 = (2 * x - prev_c2[0], 2 * y - prev_c2[1]) if prev_c2 else (x, y)
            prev_c2 = cubic(p1, (px2, py2), (px3, py3))
        elif C == 'Q':
            px1, py1, px2, py2 = a
            if rel:
                px1 += x; py1 += y; px2 += x; py2 += y
            quad((px1, py1), (px2, py2))
            prev_c2 = None
        elif C == 'T':
            px2, py2 = a
            if rel:
                px2 += x; py2 += y
            quad((x, y), (px2, py2))
            prev_c2 = None
    flush()
    return result


def scanline(polys, w, h, scale):
    edges = []
    for p in polys:
        for a, b in zip(p, p[1:]):
            x0, y0 = a[0] * scale, a[1] * scale
            x1, y1 = b[0] * scale, b[1] * scale
            if y0 == y1:
                continue
            if y0 < y1:
                edges.append((y0, y1, x0, (x1 - x0) / (y1 - y0), 1))
            else:
                edges.append((y1, y0, x1, (x0 - x1) / (y0 - y1), -1))
    spans = []
    for y in range(h):
        yc = y + 0.5
        xs = sorted((xa + (yc - ya) * dx, wd) for (ya, yb, xa, dx, wd) in edges if ya <= yc < yb)
        wind, start = 0, None
        for x, wd in xs:
            prev = wind
            wind += wd
            if prev == 0 and wind != 0:
                start = x
            elif prev != 0 and wind == 0 and start is not None:
                spans.append((start, x, y))
                start = None
    return spans


def fill(spans, w, h):
    m = bytearray(w * h)
    for x0, x1, y in spans:
        a, b = max(0, int(math.floor(x0))), min(w, int(math.ceil(x1)))
        if b > a:
            m[y * w + a:y * w + b] = b'\x01' * (b - a)
    return m


# ---------- 遮罩 ----------
def m_circle(x, y, r):
    return (x - CX) ** 2 + (y - CY) ** 2 <= r * r


def m_squircle(x, y, half, n=4.0):
    return abs((x - CX) / half) ** n + abs((y - CY) / half) ** n <= 1.0


def m_rounded(x, y, half, r):
    dx, dy = abs(x - CX), abs(y - CY)
    if dx > half or dy > half:
        return False
    if dx <= half - r or dy <= half - r:
        return True
    return (dx - (half - r)) ** 2 + (dy - (half - r)) ** 2 <= r * r


def m_square(x, y, half):
    return abs(x - CX) <= half and abs(y - CY) <= half


MASKS = [
    ('CIRCLE r36', lambda x, y: m_circle(x, y, MASK_R)),
    ('SQUIRCLE n4 r36', lambda x, y: m_squircle(x, y, MASK_R)),
    ('ROUNDED c16', lambda x, y: m_rounded(x, y, MASK_R, 16)),
    ('SQUARE 72', lambda x, y: m_square(x, y, MASK_R)),
]


# ---------- 3x5 点阵字体（标签用；不用外部字体，保证换台机器一字不差） ----------
FONT = {
    'A': '.#. #.# ### #.# #.#', 'B': '##. #.# ##. #.# ##.', 'C': '.## #.. #.. #.. .##',
    'D': '##. #.# #.# #.# ##.', 'E': '### #.. ##. #.. ###', 'F': '### #.. ##. #.. #..',
    'G': '.## #.. #.# #.# .##', 'H': '#.# #.# ### #.# #.#', 'I': '### .#. .#. .#. ###',
    'J': '..# ..# ..# #.# .#.', 'K': '#.# #.# ##. #.# #.#', 'L': '#.. #.. #.. #.. ###',
    'M': '#.# ### ### #.# #.#', 'N': '#.# ##. #.# .## #.#', 'O': '.#. #.# #.# #.# .#.',
    'P': '##. #.# ##. #.. #..', 'Q': '.#. #.# #.# ##. .##', 'R': '##. #.# ##. #.# #.#',
    'S': '.## #.. .#. ..# ##.', 'T': '### .#. .#. .#. .#.', 'U': '#.# #.# #.# #.# ###',
    'V': '#.# #.# #.# #.# .#.', 'W': '#.# #.# ### ### #.#', 'X': '#.# #.# .#. #.# #.#',
    'Y': '#.# #.# .#. .#. .#.', 'Z': '### ..# .#. #.. ###',
    '0': '### #.# #.# #.# ###', '1': '.#. ##. .#. .#. ###', '2': '##. ..# .#. #.. ###',
    '3': '##. ..# .#. ..# ##.', '4': '#.# #.# ### ..# ..#', '5': '### #.. ##. ..# ##.',
    '6': '.## #.. ### #.# ###', '7': '### ..# .#. .#. .#.', '8': '### #.# ### #.# ###',
    '9': '### #.# ### ..# ##.',
    ' ': '... ... ... ... ...', '.': '... ... ... ... .#.', '/': '..# ..# .#. #.. #..',
    '=': '... ### ... ### ...', ':': '... .#. ... .#. ...', '-': '... ... ### ... ...',
    '(': '.#. #.. #.. #.. .#.', ')': '.#. ..# ..# ..# .#.', '+': '... .#. ### .#. ...',
    '%': '#.# ..# .#. #.. #.#', '#': '#.# ### #.# ### #.#', ',': '... ... ... .#. #..',
    'DEG': '.#. #.# .#. ... ...',
}


def text_w(s, scale):
    return len(s) * 4 * scale - scale


def draw_text(buf, w, h, x0, y0, s, scale, color):
    for k, ch in enumerate(s.upper()):
        glyph = FONT.get(ch, FONT[' '])
        rows = glyph.split(' ')
        for ry, row in enumerate(rows):
            for rx, bit in enumerate(row):
                if bit != '#':
                    continue
                for dy in range(scale):
                    for dx in range(scale):
                        px = x0 + (k * 4 * scale) + rx * scale + dx
                        py = y0 + ry * scale + dy
                        if 0 <= px < w and 0 <= py < h:
                            o = 3 * (py * w + px)
                            buf[o:o + 3] = bytes(color)


# ---------- PNG ----------
def write_png(path, w, h, rgb):
    raw = b''.join(b'\x00' + bytes(rgb[y * w * 3:(y + 1) * w * 3]) for y in range(h))

    def chunk(t, d):
        c = t + d
        return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    open(path, 'wb').write(png)


def downsample(rgb, size):
    out = bytearray(size * size * 3)
    step = GRID / size
    for j in range(size):
        y0, y1 = int(j * step), min(GRID, int((j + 1) * step))
        for i in range(size):
            x0, x1 = int(i * step), min(GRID, int((i + 1) * step))
            acc, cnt = [0, 0, 0], 0
            for y in range(y0, y1):
                base = 3 * (y * GRID + x0)
                for x in range(x0, x1):
                    o = base + 3 * (x - x0)
                    acc[0] += rgb[o]
                    acc[1] += rgb[o + 1]
                    acc[2] += rgb[o + 2]
                    cnt += 1
            o = 3 * (j * size + i)
            for k in range(3):
                out[o + k] = acc[k] // max(1, cnt)
    return out


# ---------- 主流程 ----------
def build():
    fg = open(os.path.join(RES, 'drawable-v24/ic_launcher_foreground.xml'), encoding='utf-8').read()
    mono = open(os.path.join(RES, 'drawable/ic_launcher_monochrome.xml'), encoding='utf-8').read()
    pds = re.findall(r'android:pathData="([^"]+)"', fg)
    shadow_polys = subpaths(pds[0])
    robot_polys = subpaths(pds[1])
    mono_polys = subpaths(re.findall(r'android:pathData="([^"]+)"', mono)[0])
    sx, sy, ex, ey = 42.9492, 49.59793, 85.84757, 92.4963
    a0 = 0x44 / 255.0

    shadow_spans = scanline(shadow_polys, GRID, GRID, SCALE)
    white = fill(scanline(robot_polys, GRID, GRID, SCALE), GRID, GRID)
    monos = fill(scanline(mono_polys, GRID, GRID, SCALE), GRID, GRID)

    rgb = bytearray(GRID * GRID * 3)
    for i in range(GRID * GRID):
        rgb[3 * i:3 * i + 3] = bytes(BG)
    for x0, x1, y in shadow_spans:
        for x in range(max(0, int(x0)), min(GRID, int(math.ceil(x1)))):
            px, py = (x + 0.5) / SCALE, (y + 0.5) / SCALE
            t = ((px - sx) * (ex - sx) + (py - sy) * (ey - sy)) / ((ex - sx) ** 2 + (ey - sy) ** 2)
            al = a0 * (1 - min(1.0, max(0.0, t)))
            if al > 0:
                o = 3 * (y * GRID + x)
                for k in range(3):
                    rgb[o + k] = int(rgb[o + k] * (1 - al))
    for i in range(GRID * GRID):
        if white[i]:
            rgb[3 * i:3 * i + 3] = b'\xff\xff\xff'
    return rgb, white, monos, shadow_spans, (sx, sy, ex, ey), a0


def masked(rgb, monos, fn, mode='color'):
    """按遮罩抠图；遮罩外填 DARK。mode='mono' 时只留单色剪影（主题图标的样子）。"""
    out = bytearray(GRID * GRID * 3)
    for y in range(GRID):
        py = (y + 0.5) / SCALE
        for x in range(GRID):
            px = (x + 0.5) / SCALE
            o = 3 * (y * GRID + x)
            if not fn(px, py):
                out[o:o + 3] = bytes(DARK)
            elif mode == 'mono':
                out[o:o + 3] = bytes((255, 255, 255)) if monos[y * GRID + x] else bytes(DARK)
            else:
                out[o:o + 3] = rgb[o:o + 3]
    return out


def main():
    rgb, white, monos, shadow_spans, grad, a0 = build()

    # 指标（与图同源，跑这一条命令就能复算）
    maxr, at = 0.0, None
    for y in range(GRID):
        for x in range(GRID):
            if white[y * GRID + x]:
                px, py = (x + 0.5) / SCALE, (y + 0.5) / SCALE
                r = math.hypot(px - CX, py - CY)
                if r > maxr:
                    maxr, at = r, (px, py)
    print(f'白图形最大半径 {maxr:.4f}dp @ ({at[0]:.3f},{at[1]:.3f})；安全圆 {SAFE_R} 余 {SAFE_R-maxr:.4f}')

    hole = []
    body = subpaths(re.findall(r'android:pathData="([^"]+)"',
                               open(os.path.join(RES, 'drawable-v24/ic_launcher_foreground.xml'),
                                    encoding='utf-8').read())[1].rsplit('M43.4', 1)[0] + 'z')
    bodyb = fill(scanline(body, GRID, GRID, SCALE), GRID, GRID)
    for i in range(GRID * GRID):
        if bodyb[i] and not white[i]:
            hole.append(i)
    print(f'眼睛洞 {len(hole)} px² = {len(hole)/100:.2f} dp²，单眼等效直径 '
          f'{2*math.sqrt(len(hole)/2/math.pi)/10:.3f}dp')

    for name, f in MASKS:
        cut = sum(1 for y in range(GRID) for x in range(GRID)
                  if white[y * GRID + x] and not f((x + 0.5) / SCALE, (y + 0.5) / SCALE))
        print(f'  {name:16s} 裁掉白像素 {cut}')

    # ---------- 画布 ----------
    TILE, GAP, LAB, TOP = 216, 16, 20, 56
    W = GAP + 5 * (TILE + GAP)
    rowh = [TILE, 48, TILE]                      # 三行内容高度：设计视图 / 真实 48px / 真实尺寸阶梯
    H = TOP + sum(h + LAB + GAP for h in rowh) + 4
    img = bytearray(W * H * 3)
    for i in range(W * H):
        img[3 * i:3 * i + 3] = bytes(DARK)

    draw_text(img, W, H, GAP, 10, 'GSELF ICON AUDIT  GRID 10PX/DP  SAFE R33  MASK R36', 2, (255, 255, 255))
    draw_text(img, W, H, GAP, 32, 'ROW1 108DP DESIGN 2PX/DP | ROW2 REAL 48PX | ROW3 REAL 96/144/192PX',
              2, (150, 150, 150))

    blocks = []

    def tile(col, row, pixels, size, label, ring=False):
        x0 = GAP + col * (TILE + GAP)
        y0 = TOP + sum(h + LAB + GAP for h in rowh[:row])
        ow = TILE if row != 1 else 48
        ox = x0 + (ow - size) // 2
        oy = y0 + (ow - size) // 2
        for j in range(size):
            for i in range(size):
                o = 3 * (j * size + i)
                px, py = ox + i, oy + j
                if 0 <= px < W and 0 <= py < H:
                    t = 3 * (py * W + px)
                    img[t:t + 3] = pixels[o:o + 3]
        if ring:
            for r, col in ((SAFE_R, (255, 255, 255)), (MASK_R, (255, 210, 60))):
                for k in range(7200):
                    a = 2 * math.pi * k / 7200
                    px = ox + int(size / 2 + r * size / 108 * math.cos(a))
                    py = oy + int(size / 2 + r * size / 108 * math.sin(a))
                    if 0 <= px < W and 0 <= py < H:
                        t = 3 * (py * W + px)
                        img[t:t + 3] = bytes(col)
        draw_text(img, W, H, x0, y0 + ow + 4, label, 2, (235, 235, 235))
        blocks.append((label, size, hashlib.sha256(bytes(pixels)).hexdigest()[:12]))

    # 第一行：设计视图（2 px/dp 展示）+ 四种遮罩
    tile(0, 0, downsample(rgb, TILE), TILE, 'REF 108DP SAFE33 MASK36', ring=True)
    for k, (name, f) in enumerate(MASKS):
        tile(k + 1, 0, downsample(masked(rgb, monos, f), TILE), TILE, name)
    # 第二行：四种遮罩在真实 48px 下（互不相同）
    for k, (name, f) in enumerate(MASKS):
        tile(k, 1, downsample(masked(rgb, monos, f), 48), 48, 'REAL 48PX ' + name.split()[0])
    # 第三行：真实尺寸阶梯 + 单色层
    for k, size in enumerate((96, 144, 192)):
        tile(k, 2, downsample(masked(rgb, monos, MASKS[0][1]), size), size, f'REAL {size}PX CIRCLE')
    tile(3, 2, downsample(masked(rgb, monos, MASKS[0][1], mode='mono'), 192), 192,
         'REAL 192PX MONOCHROME')

    print('逐块 sha256 前 12 位（同尺寸块哈希必须互不相同）：')
    for label, size, h in blocks:
        print(f'  {size:4d}px {h}  {label}')
    small = [h for (label, size, h) in blocks if size == 48]
    print('48px 四块：', '互不相同' if len(set(small)) == len(small) else '有重复！')

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    write_png(OUT, W, H, img)
    print(f'已写 {OUT} {W}x{H} {os.path.getsize(OUT)} 字节')


if __name__ == '__main__':
    main()
