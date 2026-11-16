#!/usr/bin/env python3
"""算版本号并写进 GITHUB_ENV：五段 `yy.m.d.当日号.总序号`。

号源只取 `GITHUB_RUN_NUMBER` 原值（不读运行历史，那份会被清理删到只剩 5 条）：
总序号＝run_number；当日号＝run_number − 当日首条运行号 + 1，当日首条由
「当日已有产物反推」与「今日运行表最小值」两处取小，两者都缺时退化成 1。

产物名 `Gself-<版本>` 的第三、四段就是这两个号，清理脚本按同一形状核名。
"""
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
SHAPE = r'\d{2}\.(1[0-2]|[1-9])\.(3[01]|[12][0-9]|[1-9])\.([1-9]\d*)\.([1-9]\d*)'


def local_day(stamp: str) -> str:
    """月与日不补零，也不靠 `%-m` / `%-d`（非 glibc 平台不生效）：直接整数拼接。"""
    d = datetime.fromisoformat(stamp).astimezone(TZ)
    return f'{d.year % 100}.{d.month}.{d.day}'


def fetch(endpoint: str, key: str) -> list:
    import subprocess
    p = subprocess.run(['gh', 'api', f'{endpoint}'], capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f'取号接口 {endpoint} 失败（{p.returncode}）：{p.stderr.strip()[:300]}')
    return json.loads(p.stdout)[key]


def main() -> int:
    repo = os.environ['REPO']
    run_number = int(os.environ['RUN_NUMBER'])
    today = local_day(datetime.now(TZ).isoformat())
    runs = fetch(f'repos/{repo}/actions/workflows/build.yml/runs?per_page=100', 'workflow_runs')
    arts = fetch(f'repos/{repo}/actions/artifacts?per_page=100', 'artifacts')

    todays = [r['run_number'] for r in runs if local_day(r['run_started_at']) == today]
    first = min(todays) if todays else run_number
    pat = re.compile(r'^' + re.escape(repo.split('/')[-1]) + r'-(\d{2}\.\d{1,2}\.\d{1,2})\.(\d+)\.(\d+)$')
    for a in arts:
        m = pat.match(a['name'])
        if m and not a.get('expired') and m.group(1) == today:
            first = min(first, int(m.group(3)) - int(m.group(2)) + 1)

    daily = run_number - first + 1
    if daily < 1:
        sys.exit(f'当日号小于 1：run_number={run_number} 当日首条={first}')
    version = f'{today}.{daily}.{run_number}'
    if not re.fullmatch(SHAPE, version):
        sys.exit(f'版本不合五段不补零口径：{version}')
    print(f'取号：SHA={os.environ.get("GITHUB_SHA", "")} run_id={os.environ["RUN_ID"]} '
          f'run_number={run_number} 当日首条={first} 当日号={daily} 版本={version}')
    with open(os.environ['GITHUB_ENV'], 'a') as env:
        env.write(f'VERSION_NAME={version}\nVERSION_CODE={run_number}\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
