#!/usr/bin/env python3
"""算版本号并写进 GITHUB_ENV：五段 `yy.m.d.当日号.总序号`。

**号源是产物内容，不是运行次数**（规范「16 号源」，2026-11-21 起）：
同一份内容重跑算同一个当日号，换掉的只是末段的总序号（＝`GITHUB_RUN_NUMBER`，每次运行递增）；
内容真变了才涨当日号。纯文档、纯 CI 的改动不进包，也就不会涨号。

内容的判据是「进包路径」的树指纹：`app/`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、
`gradle.properties`、`gradlew` 这些路径下的 `mode + 路径 + blob SHA` 排序后取 sha256。
README、`CHANGELOG.md`、`tools/`、`.github/` 不在其中——它们改了不出新包，也不该占新号。

当日号＝本条内容在今日运行序列里第一次出现的位置（从 1 数）。今日没有更早的运行就是 1。
历史提交的树用 GitHub API 取（CI 是浅克隆，取不到旧提交），当前提交用本地 `git ls-tree`。

取不到树就非 0 退出：宁可不出包，也不退回「按运行次数编号」那种自欺的算法。
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
SHAPE = r'\d{2}\.(1[0-2]|[1-9])\.(3[01]|[12][0-9]|[1-9])\.([1-9]\d*)\.([1-9]\d*)'
ARTIFACT = 'Gself'
PACKAGED = ('app', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew')


def local_day(stamp: str) -> str:
    """月与日不补零，也不靠 `%-m` / `%-d`（非 glibc 平台不生效）：直接整数拼接。"""
    d = datetime.fromisoformat(stamp).astimezone(TZ)
    return f'{d.year % 100}.{d.month}.{d.day}'


def fetch(endpoint: str, key: str) -> list:
    p = subprocess.run(['gh', 'api', f'{endpoint}'], capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f'取号接口 {endpoint} 失败（{p.returncode}）：{p.stderr.strip()[:300]}')
    return json.loads(p.stdout)[key]


def write_env(version: str, run_number: int) -> None:
    if not re.fullmatch(SHAPE, version):
        sys.exit(f'版本不合五段不补零口径：{version}')
    with open(os.environ['GITHUB_ENV'], 'a') as env:
        env.write(f'VERSION_NAME={version}\nVERSION_CODE={run_number}\n')


def tree_lines_local(sha: str) -> list[str]:
    p = subprocess.run(['git', 'ls-tree', '-r', sha, '--', *PACKAGED], capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f'取本地树 {sha} 失败：{p.stderr.strip()[:200]}')
    return [line for line in p.stdout.splitlines() if line.strip()]


def tree_lines_api(repo: str, sha: str) -> list[str]:
    """浅克隆里取不到旧提交，改问 API；只留进包路径，输出与 `git ls-tree -r` 同形。"""
    p = subprocess.run(['gh', 'api', f'repos/{repo}/git/trees/{sha}?recursive=1'],
                       capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f'取树 {sha} 失败（{p.returncode}）：{p.stderr.strip()[:200]}  '
                 f'——§15 要求按内容编号，取不到内容就不出包')
    data = json.loads(p.stdout)
    if data.get('truncated'):
        sys.exit(f'树 {sha} 被截断，判不出内容是否变化，不出包')
    out = []
    for e in data['tree']:
        path = e['path']
        if e['type'] != 'blob' or not any(path == p or path.startswith(p + '/') for p in PACKAGED):
            continue
        out.append(f'{e["mode"]} {e["type"]} {e["sha"]}\t{path}')
    return out


def fingerprint(lines: list[str]) -> str:
    return hashlib.sha256('\n'.join(sorted(lines)).encode()).hexdigest()


def main() -> int:
    repo = os.environ['REPO']
    run_number = int(os.environ['RUN_NUMBER'])
    run_id = os.environ['RUN_ID']
    head = os.environ.get('GITHUB_SHA', '')
    today = local_day(datetime.now(TZ).isoformat())

    def started(r):
        # 未开始的运行没有 run_started_at；用 created_at 兜底，与 cleanup.py 同一取法
        return r.get('run_started_at') or r['created_at']

    runs = fetch(f'repos/{repo}/actions/workflows/build.yml/runs?per_page=100', 'workflow_runs')
    earlier = sorted((r for r in runs if r['run_number'] < run_number), key=lambda r: r['run_number'])

    cache: dict[str, list[str]] = {}

    def fp_of(sha: str) -> str:
        if sha not in cache:
            cache[sha] = tree_lines_local(sha) if sha == head else tree_lines_api(repo, sha)
        return fingerprint(cache[sha])

    mine = fp_of(head)

    # §15「同一内容重跑同一号」：先找同一份内容上一次出过包的运行，沿用它那天的 yy.m.d 与当日号。
    # 只在「今天」里找不够——2026-11-23 的 #109 就是同内容跨天重跑，当日号从 1 重来、涨了号。
    for r in reversed(earlier):
        arts = [a for a in fetch(f'repos/{repo}/actions/runs/{r["id"]}/artifacts', 'artifacts')
                if a['name'].startswith(ARTIFACT + '-')]
        if not arts or fp_of(r['head_sha']) != mine:
            continue
        tag = arts[0]['name'][len(ARTIFACT) + 1:]
        if re.fullmatch(SHAPE, tag):
            kept = tag.rsplit('.', 1)[0] + f'.{run_number}'
            print(f'取号：内容同 {r["run_number"]} 号运行（{tag}），沿用其 yy.m.d 与当日号，只换总序号 → {kept}')
            write_env(kept, run_number)
            return 0

    # 新内容：当日号＝它在今日运行序列里第一次出现的位置（从 1 数）。
    seen: dict[str, int] = {}
    for r in earlier:
        if local_day(started(r)) != today:
            continue
        fp = fp_of(r['head_sha'])
        if fp not in seen:
            seen[fp] = len(seen) + 1
    daily = seen.get(mine, len(seen) + 1)

    version = f'{today}.{daily}.{run_number}'
    print(f'取号：SHA={head} run_id={run_id} run_number={run_number} '
          f'更早运行={len(earlier)} 今日不同内容={len(seen)} '
          f'内容指纹={mine[:12]} 当日号={daily} 版本={version}')
    write_env(version, run_number)
    return 0

if __name__ == '__main__':
    sys.exit(main())
