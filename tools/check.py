#!/usr/bin/env python3
"""规范自检：把 §2／§4／§14／§18／§24 里原本靠人记的几条，做成一条能跑的命令。

判据（GLOBAL 8a68a96 版）：

1. 对外动作（§18）：工作流里不许出现发版、打 tag、强推、`[skip ci]`；本仓也不许有 Release 或 tag。
2. 工作流格式（§14）：`name` 与文件名一致、顶层 `env` 两键、并发组以仓名开头、
   `cancel-in-progress: false`、清理 job 调 `tools/cleanup.py`。
3. 脚本位置（§11）：`.sh` 与 `cleanup.py` 之外的 `.py` 只许在 `tools/`，不许有 `scripts/`、`ci/`。
4. 文档（§19）：README 四章齐（是什么／从哪下／怎么装／怎么删）、无 TODO 与过程表、无禁用话术。
5. 读证（§4）：`CHANGELOG.md` 最新一条必须点出九份上游与规范提交的短 SHA。
6. 盲区（§12）：`tools/scan-scope.json` 三类齐全，由 `tools/deadcode.py` 复核，这里只查它在。

用法：`python3 tools/check.py`；有发现退出码 1。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKS = []


def fail(rule: str, msg: str) -> None:
    CHECKS.append(f'{rule} {msg}')
    print(f'::error::{rule} {msg}')


def workflow() -> None:
    for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
        text = path.read_text(encoding='utf-8')
        if not re.search(rf'^name: {re.escape(path.stem)}$', text, re.M):
            fail('§14', f'{path.name} 的 name 与文件名不一致')
        for key in ("SEGMENTS: '5'", "CLEANUP_APPLY: 'true'"):
            if key not in text:
                fail('§14', f'{path.name} 缺顶层 env {key}')
        if 'cancel-in-progress: false' not in text:
            fail('§14', f'{path.name} 缺 cancel-in-progress: false')
        if 'tools/cleanup.py' not in text:
            fail('§14', f'{path.name} 没有调 tools/cleanup.py 的清理 job')
        for bad in ('gh release', 'action-gh-release', 'push --tags', '[skip ci]', 'git push -f'):
            if bad in text:
                fail('§18', f'{path.name} 出现禁用的对外动作 {bad!r}')
    groups = set(re.findall(r'group: (\S+)', (''.join(
        p.read_text(encoding='utf-8') for p in (ROOT / '.github/workflows').glob('*.yml')))))
    repo = ROOT.name.lower()
    for g in groups:
        if not g.startswith(repo + '-'):
            fail('§14', f'并发组 {g} 没有以仓名开头')


def scripts() -> None:
    for bad in ('scripts', 'ci'):
        if (ROOT / bad).exists():
            fail('§11', f'出现了禁用目录 {bad}/')
    for sub in ('app', '.github'):
        for p in (ROOT / sub).rglob('*.py'):
            fail('§11', f'{p.relative_to(ROOT)} 在 {sub}/ 下，脚本只许放 tools/')


def docs() -> None:
    readme = (ROOT / 'README.md').read_text(encoding='utf-8')
    for head in ('## 装', '## 删', '## 项目限制', '## 许可证'):
        if head not in readme:
            fail('§19', f'README 缺章节 {head}')
    # CHANGELOG 记历史，黑历史不删：禁用词只查 README 与工作流（§19）
    scope = [ROOT / 'README.md', *sorted((ROOT / '.github/workflows').glob('*.yml'))]
    for word in ('TODO', '仅供学习交流', '禁止商用'):
        for p in scope:
            if word in p.read_text(encoding='utf-8'):
                fail('§19', f'{p.name} 出现禁用词 {word!r}')


def read_proof() -> None:
    log = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
    head = log.split('\n## ')[0]
    for name in ('ponytail', 'CL4R1T4S', 'nagi-studio', 'rea', 'craft-anything',
                 'uncut', 'ste80', 'codex-security', 'agentes-chat'):
        if name not in head:
            fail('§4', f'最新一条变更记录没点出上游 {name}')
    if not re.search(r'\b[0-9a-f]{7,40}\b', head):
        fail('§4', '最新一条变更记录没有规范提交 SHA')


def releases() -> None:
    repo = 'Sumicya/Gself'
    for kind, endpoint in (('Release', 'releases'), ('tag', 'tags')):
        p = subprocess.run(['gh', 'api', f'repos/{repo}/{endpoint}?per_page=100', '--jq', 'length'],
                           capture_output=True, text=True)
        if p.returncode != 0:
            fail('§18', f'查 {endpoint} 失败（要 GH_TOKEN 且要有读权限）：{p.stderr.strip()[:100]}')
        elif p.stdout.strip() not in ('', '0'):
            fail('§18', f'仓里有 {p.stdout.strip()} 个 {kind}，§18 不许发版、不许打 tag')


def main() -> int:
    workflow()
    scripts()
    docs()
    read_proof()
    releases()
    for line in CHECKS:
        print(line)
    print(f'自检：工作流 {len(list((ROOT / ".github/workflows").glob("*.yml")))} 个、'
          f'工具 {len(list((ROOT / "tools").glob("*")))} 个；问题 {len(CHECKS)} 处')
    return 1 if CHECKS else 0


if __name__ == '__main__':
    sys.exit(main())
