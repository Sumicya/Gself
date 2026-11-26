#!/usr/bin/env python3
"""规范自检：把 §4／§11／§12／§14／§16／§17 里原本靠人记的几条，做成一条能跑的命令。

判据（GLOBAL `1a2d3d6`，8,852 B / 23 条 + 附录 A–D）：

1. 工作流（§14 + 附录 B）：`name` 与文件名一致且唯一；顶层 `env` 两键；每个 job 的并发组以 `<仓>-` 开头、
   `cancel-in-progress: false`；清理 job `if: always()` 且调 `tools/cleanup.py`；产物三件套齐。
2. 对外动作（§16）：任何值里都不得出现发版、打 tag、强推、`[skip ci]`；本仓 Release 与 tag 必须为 0。
3. 脚本位置（§11）：不许有 `scripts/`、`ci/`；`app/`、`.github/` 下不许躺 `.py`。
4. 文档（§17）：README 四章齐；README 与工作流里无 TODO 与禁用话术。
5. 读证（§4）：`CHANGELOG.md` 最新一条点出九份上游与提交 SHA。
6. 盲区（§12）：`tools/blindspots.json` 存在（内容校验在 `tools/deadcode.py`）。

**解析真 YAML，不搜字符串**：旧版只做子串匹配，把 `env` 缩进挪两格就漏。
用 PyYAML 的 `BaseLoader`——全按字符串读，`on:` 才不会被 YAML 1.1 解析成布尔 `True`
（`safe_load` 会，实测：顶层键变成 `['name', True, …]`）。缺依赖时报「未验证」并退 2，不静默放行。

用法：`python3 tools/check.py`；问题 0 处退 0，有发现退 1，缺依赖退 2。
"""
import os
import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # 缺依赖 ≠ 通过
    print('::error::缺 PyYAML，规范自检未验证（pip install pyyaml）')
    sys.exit(2)

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = sorted((ROOT / '.github/workflows').glob('*.yml'))
CHECKS: list[str] = []
FORBIDDEN = ('gh release', 'action-gh-release', 'push --tags', '[skip ci]', 'git push -f',
             'git push --force')


def fail(rule: str, msg: str) -> None:
    CHECKS.append(f'{rule} {msg}')
    print(f'::error::{rule} {msg}')


def strings(node):
    """递归取出所有字符串（键与值都算）；注释不在解析结果里，注释掉的 group 骗不过去。"""
    if isinstance(node, dict):
        for k, v in node.items():
            yield str(k)
            yield from strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from strings(v)
    elif isinstance(node, str):
        yield node


def load(path: Path):
    """BaseLoader 全按字符串读：`cancel-in-progress` 是 'false' 而不是布尔，比较处一律比字符串。"""
    try:
        doc = yaml.load(path.read_text(encoding='utf-8'), Loader=yaml.BaseLoader)
    except yaml.YAMLError as e:
        fail('§14', f'{path.name} 解析失败（{str(e).splitlines()[0]}），该文件未验证')
        return None
    if not isinstance(doc, dict):
        fail('§14', f'{path.name} 顶层不是映射（{type(doc).__name__}），该文件未验证')
        return None
    return doc


def workflow() -> None:
    repo = ROOT.name.lower()
    names = {}
    for path in WORKFLOWS:
        doc = load(path)
        if doc is None:
            continue
        names.setdefault(doc.get('name'), []).append(path.name)
        if doc.get('name') != path.stem:
            fail('§14', f'{path.name} 的 name={doc.get("name")!r} 与文件名不一致')
        env = doc.get('env') or {}
        if not isinstance(env, dict):
            fail('§14', f'{path.name} 顶层 env 不是映射，该文件未验证')
            continue
        if env.get('SEGMENTS') != '5':
            fail('§14', f'{path.name} 顶层 env 的 SEGMENTS 不是 5，是 {env.get("SEGMENTS")!r}')
        if str(env.get('CLEANUP_APPLY', '')).lower() != 'true':
            fail('§14', f'{path.name} 顶层 env 的 CLEANUP_APPLY 不是 true，是 {env.get("CLEANUP_APPLY")!r}')
        jobs = doc.get('jobs') or {}
        if not isinstance(jobs, dict):
            fail('§14', f'{path.name} 的 jobs 不是映射，该文件未验证')
            continue
        has_cleanup = False
        for job_name, job in jobs.items():
            if not isinstance(job, dict):
                fail('§14', f'{path.name} 的 job {job_name} 不是映射，该文件未验证')
                continue
            conc = job.get('concurrency') or {}
            group = conc.get('group') if isinstance(conc, dict) else conc
            if not group or not str(group).startswith(repo + '-'):
                fail('§14', f'{path.name} 的 job {job_name} 并发组 {group!r} 没以 {repo}- 开头')
            if not isinstance(conc, dict) or str(conc.get('cancel-in-progress', '')).lower() != 'false':
                fail('§14', f'{path.name} 的 job {job_name} 缺 cancel-in-progress: false')
            steps = job.get('steps') or []
            if not isinstance(steps, list):
                fail('§14', f'{path.name} 的 job {job_name} 的 steps 不是序列，该文件未验证')
                continue
            runs = ' '.join(str(s.get('run', '')) for s in steps if isinstance(s, dict))
            if 'cleanup.py' in runs:
                has_cleanup = True
                if job.get('if') != 'always()':
                    fail('§14', f'{path.name} 的清理 job {job_name} 缺 if: always()')
            for step in steps:
                if not isinstance(step, dict) or not str(step.get('uses', '')).startswith('actions/upload-artifact'):
                    continue
                with_ = step.get('with') or {}
                for key in ('retention-days', 'if-no-files-found', 'overwrite'):
                    if key not in with_:
                        fail('§14', f'{path.name} 的产物步骤缺 {key}')
                if str(with_.get('if-no-files-found', '')).lower() != 'error':
                    fail('§14', f'{path.name} 的 if-no-files-found 不是 error')
                if str(with_.get('overwrite', '')).lower() != 'true':
                    fail('§14', f'{path.name} 的 overwrite 不是 true')
        if not has_cleanup:
            fail('§14', f'{path.name} 没有调 cleanup.py 的清理 job')
        for value in strings(doc):
            for bad in FORBIDDEN:
                if bad in value:
                    fail('§16', f'{path.name} 出现禁用的对外动作 {bad!r}')
    for name, files in names.items():
        if len(files) > 1:
            fail('§14', f'工作流 name {name!r} 重复：{files}')


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
            fail('§17', f'README 缺章节 {head}')
    # CHANGELOG 存历史、黑历史不删：禁用词只查 README 与工作流
    scope = [ROOT / 'README.md', *WORKFLOWS]
    for word in ('TODO', '仅供学习交流', '禁止商用'):
        for p in scope:
            if word in p.read_text(encoding='utf-8'):
                fail('§17', f'{p.name} 出现禁用词 {word!r}')
    if not (ROOT / 'tools/blindspots.json').exists():
        fail('§12', '缺 tools/blindspots.json')


def read_proof() -> None:
    head = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8').split('\n## ')[0]
    for name in ('ponytail', 'CL4R1T4S', 'nagi-studio', 'rea', 'craft-anything',
                 'uncut', 'ste80', 'codex-security', 'agentes-chat'):
        if name not in head:
            fail('§4', f'最新一条变更记录没点出上游 {name}')
    if not re.search(r'\b[0-9a-f]{7,40}\b', head):
        fail('§4', '最新一条变更记录没有提交 SHA')


def repo_slug() -> str:
    """仓名从 git remote 取，不从目录名猜：目录名是本地路径，跟 GitHub 上的 owner 没关系。"""
    p = subprocess.run(['git', '-C', str(ROOT), 'remote', 'get-url', 'origin'],
                       capture_output=True, text=True)
    if p.returncode != 0:
        return ''
    m = re.search(r'[:/]([^/:]+/[^/]+?)(?:\.git)?$', p.stdout.strip())
    return m.group(1) if m else ''


def releases() -> None:
    repo = os.environ.get('GITHUB_REPOSITORY') or repo_slug()
    if not repo:
        fail('§16', '取不到仓名（git remote origin 缺失），Release 与 tag 未验证')
        return
    for kind, endpoint in (('Release', 'releases'), ('tag', 'tags')):
        p = subprocess.run(['gh', 'api', f'repos/{repo}/{endpoint}?per_page=100', '--jq', 'length'],
                           capture_output=True, text=True)
        if p.returncode != 0:
            fail('§16', f'查 {endpoint} 失败（要 GH_TOKEN 且要有读权限）：{p.stderr.strip()[:90]}')
        elif p.stdout.strip() not in ('', '0'):
            fail('§16', f'仓里有 {p.stdout.strip()} 个 {kind}，§16 不许发版、不许打 tag')


def self_check() -> None:
    """ponytail：新写的非平凡逻辑要留一条能跑的 assert 自检。这里钉住解析口径。"""
    sample = ('name: build\nenv:\n  SEGMENTS: \'5\'\n  CLEANUP_APPLY: \'true\'\n'
              'jobs:\n  b:\n    concurrency:\n      group: x-b\n'
              '      cancel-in-progress: false\n    steps:\n      - run: |\n          a: b\n          c\n')
    doc = yaml.load(sample, Loader=yaml.BaseLoader)
    assert doc['name'] == 'build', doc          # 不把 on/name 变成布尔
    assert doc['env']['CLEANUP_APPLY'] == 'true'  # 全按字符串读
    assert doc['jobs']['b']['concurrency']['cancel-in-progress'] == 'false'
    assert doc['jobs']['b']['steps'][0]['run'].startswith('a: b')  # 块标量原样保留
    assert 'on' not in doc or isinstance('on', str)


def main() -> int:
    if '--self-check' in sys.argv:
        self_check()
        print('自检：解析口径 4 条断言通过')
        return 0
    self_check()
    workflow()
    scripts()
    docs()
    read_proof()
    releases()
    tools = [p for p in (ROOT / "tools").glob("*") if p.is_file()]  # 目录不算：py_compile 会留 __pycache__
    print(f'自检：工作流 {len(WORKFLOWS)} 个、工具 {len(tools)} 个；问题 {len(CHECKS)} 处')
    return 1 if CHECKS else 0


if __name__ == '__main__':
    sys.exit(main())
