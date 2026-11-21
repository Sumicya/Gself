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
                 f'——§16 要求按内容编号，取不到内容就不出包')
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

    runs = fetch(f'repos/{repo}/actions/workflows/build.yml/runs?per_page=100', 'workflow_runs')
    def started(r):
        # 未开始的运行没有 run_started_at；用 created_at 兜底，与 cleanup.py 同一取法
        return r.get('run_started_at') or r['created_at']

    earlier = sorted((r for r in runs
                      if r['run_number'] < run_number and local_day(started(r)) == today),
                     key=lambda r: r['run_number'])

    # 今日更早的运行按时间排，同一份内容只记一次；本条内容出现的位置就是当日号。
    seen: dict[str, int] = {}
    cache: dict[str, list[str]] = {}
    for r in earlier:
        sha = r['head_sha']
        if sha not in cache:
            cache[sha] = tree_lines_local(sha) if sha == head else tree_lines_api(repo, sha)
        fp = fingerprint(cache[sha])
        if fp not in seen:
            seen[fp] = len(seen) + 1

    mine = fingerprint(tree_lines_local(head))
    daily = seen.get(mine, len(seen) + 1)

    version = f'{today}.{daily}.{run_number}'
    if not re.fullmatch(SHAPE, version):
        sys.exit(f'版本不合五段不补零口径：{version}')
    same = [r['run_number'] for r in earlier if cache.get(r['head_sha']) and
            fingerprint(cache[r['head_sha']]) == mine]
    print(f'取号：SHA={head} run_id={run_id} run_number={run_number} '
          f'今日更早运行={len(earlier)} 今日不同内容={len(seen)} '
          f'内容指纹={mine[:12]} 同内容运行={same} 当日号={daily} 版本={version}')
    with open(os.environ['GITHUB_ENV'], 'a') as env:
        env.write(f'VERSION_NAME={version}\nVERSION_CODE={run_number}\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
