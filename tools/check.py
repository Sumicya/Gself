#!/usr/bin/env python3
"""规范自检：把 §4／§11／§12／§14／§16／§17 里原本靠人记的几条，做成一条能跑的命令。

判据（GLOBAL `ccb9aeb`，11,261 B / 25 条）：

1. 工作流（§14）：`name` 与文件名一致且唯一；顶层 `env` 两键；每个 job 的并发组以 `<仓>-` 开头、
   `cancel-in-progress: false`；清理 job `if: always()` 且调 `tools/cleanup.py`；产物三件套齐。
2. 对外动作（§16）：任何字符串值里都不得出现发版、打 tag、强推、`[skip ci]`；本仓 Release 与 tag 必须为 0。
3. 脚本位置（§11）：不许有 `scripts/`、`ci/`；`app/`、`.github/` 下不许躺 `.py`。
4. 文档（§17）：README 四章齐；README 与工作流里无 TODO 与禁用话术。
5. 读证（§4）：`CHANGELOG.md` 最新一条点出九份上游与提交 SHA。
6. 盲区（§12）：`tools/blindspots.json` 存在（内容校验在 `tools/deadcode.py`）。

**解析真 YAML，不搜字符串**：2026-11-16 实测旧版只做子串匹配，
把 `env` 缩进挪两格、或把并发组换掉，旧的字符串判据会漏。这里按缩进建树再判。

不引第三方依赖：runner 上有没有 PyYAML 没验证过，赌它不如自己解析本仓工作流用到的子集
（块映射、块序列、行内标量、引号、注释）。解析不了就报错退出，不静默放行。

用法：`python3 tools/check.py`；问题 0 处退 0，有发现退 1，解析不了退 2。
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = sorted((ROOT / '.github/workflows').glob('*.yml'))
CHECKS: list[str] = []
FORBIDDEN = ('gh release', 'action-gh-release', 'push --tags', '[skip ci]', 'git push -f',
             'git push --force')
SCALAR = re.compile(r'^(?P<key>[^:#]+?):(?:\s+(?P<val>.*))?$')


class BadYaml(Exception):
    pass


def strip_comment(line: str) -> str:
    """去掉行尾注释；引号内的 `#` 不算注释。"""
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
        elif ch in '"\'':
            quote = ch
        elif ch == '#' and (i == 0 or line[i - 1] in ' \t'):
            return line[:i]
    return line


def scalar(text: str):
    text = text.strip()
    if not text:
        return None
    if len(text) >= 2 and text[0] == text[-1] and text[0] in '"\'':
        inner = text[1:-1]
        return inner.replace("\\'", "'").replace('\\n', '\n') if text[0] == "'" else inner
    if text in ('true', 'True'):
        return True
    if text in ('false', 'False'):
        return False
    if text in ('null', '~'):
        return None
    if re.fullmatch(r'-?\d+', text):
        return int(text)
    if text.startswith('[') and text.endswith(']'):
        return [scalar(x) for x in text[1:-1].split(',') if x.strip()]
    return text


def parse(text: str):
    """把本仓工作流用到的 YAML 子集解析成 dict/list。遇到不认识的写法抛 BadYaml。"""
    rows = []
    lines = text.split('\n')
    i = 0
    while i < len(lines):
        raw = lines[i]
        i += 1
        line = strip_comment(raw).rstrip()
        if not line.strip() or line.lstrip().startswith('---'):
            continue
        indent = len(line) - len(line.lstrip(' '))
        if '\t' in line[:indent + 1]:
            raise BadYaml('缩进里有制表符')
        body = line.strip()
        # `run: |` / `run: >` 的块标量：整段原样收成一条字符串，块内不做 YAML 解析
        m = re.match(r'^(?P<key>[^:#]+?):\s+(?P<flag>[|>])[-+]?$', body)
        if m:
            chunk = []
            while i < len(lines):
                nxt = lines[i]
                if not nxt.strip():
                    chunk.append('')
                    i += 1
                    continue
                if len(nxt) - len(nxt.lstrip(' ')) <= indent:
                    break
                chunk.append(nxt)
                i += 1
            pad = min((len(x) - len(x.lstrip(' ')) for x in chunk if x.strip()), default=0)
            text_block = '\n'.join(x[pad:] for x in chunk).strip('\n')
            rows.append((indent, f'{m.group("key")}: {text_block!r}'))
            continue
        rows.append((indent, body))
    pos = 0

    def block(indent: int):
        nonlocal pos
        if pos >= len(rows):
            return None
        if rows[pos][0] < indent:
            return None
        if rows[pos][1].startswith('- '):
            return sequence(rows[pos][0])
        return mapping(rows[pos][0])

    def mapping(indent: int):
        nonlocal pos
        out = {}
        while pos < len(rows):
            ind, body = rows[pos]
            if ind < indent:
                break
            if ind > indent:
                raise BadYaml(f'缩进对不齐：{body[:40]}')
            if body.startswith('- '):
                break
            m = SCALAR.match(body)
            if not m:
                raise BadYaml(f'认不出的行：{body[:40]}')
            key, val = m.group('key').strip(), m.group('val')
            pos += 1
            if val is None or val == '':
                # 下一行缩进更深才是子结构，否则是空值
                out[key] = block(rows[pos][0]) if pos < len(rows) and rows[pos][0] > ind else None
            else:
                out[key] = scalar(val)
        return out

    def sequence(indent: int):
        nonlocal pos
        out = []
        while pos < len(rows):
            ind, body = rows[pos]
            if ind != indent or not body.startswith('- '):
                break
            rest = body[2:].strip()
            pos += 1
            if not rest:
                out.append(block(rows[pos][0]) if pos < len(rows) and rows[pos][0] > ind else None)
                continue
            if SCALAR.match(rest) and not rest.startswith('- '):
                # `- key: value` 起头的映射项
                item = {}
                m = SCALAR.match(rest)
                key, val = m.group('key').strip(), m.group('val')
                pos -= 1
                rows[pos] = (ind + 2, rest)
                item = mapping(ind + 2)
                out.append(item)
            else:
                out.append(scalar(rest))
        return out

    doc = block(rows[0][0]) if rows else None
    if pos != len(rows):
        raise BadYaml(f'没解析完，停在：{rows[pos][1][:40]}')
    return doc


def fail(rule: str, msg: str) -> None:
    CHECKS.append(f'{rule} {msg}')
    print(f'::error::{rule} {msg}')


def strings(node):
    """递归取出所有字符串（键与值都算），注释不在解析结果里。"""
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
    try:
        doc = parse(path.read_text(encoding='utf-8'))
    except BadYaml as e:
        fail('§14', f'{path.name} 解析失败（{e}），该文件未验证')
        return None
    except Exception as e:  # parser 的意外形状：同样按「未验证」处理，不许静默放行也不许崩栈
        fail('§14', f'{path.name} 解析异常（{type(e).__name__}: {e}），该文件未验证')
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
        if str(env.get('SEGMENTS')) != '5':
            fail('§14', f'{path.name} 顶层 env 的 SEGMENTS 不是 5，是 {env.get("SEGMENTS")!r}')
        if str(env.get('CLEANUP_APPLY')).lower() != 'true':
            fail('§14', f'{path.name} 顶层 env 的 CLEANUP_APPLY 不是 true，是 {env.get("CLEANUP_APPLY")!r}')
        has_cleanup = False
        jobs = doc.get('jobs') or {}
        if not isinstance(jobs, dict):
            fail('§14', f'{path.name} 的 jobs 不是映射，该文件未验证')
            continue
        for job_name, job in jobs.items():
            if not isinstance(job, dict):
                fail('§14', f'{path.name} 的 job {job_name} 不是映射，该文件未验证')
                continue
            conc = job.get('concurrency') or {}
            group = conc.get('group') if isinstance(conc, dict) else conc
            if not group or not str(group).startswith(repo + '-'):
                fail('§14', f'{path.name} 的 job {job_name} 并发组 {group!r} 没以 {repo}- 开头')
            if isinstance(conc, dict) and conc.get('cancel-in-progress') is not False:
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
                if str(with_.get('if-no-files-found')) != 'error':
                    fail('§14', f'{path.name} 的 if-no-files-found 不是 error')
                if with_.get('overwrite') is not True:
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


def main() -> int:
    workflow()
    scripts()
    docs()
    read_proof()
    releases()
    print(f'自检：工作流 {len(WORKFLOWS)} 个、工具 {len(list((ROOT / "tools").glob("*")))} 个；'
          f'问题 {len(CHECKS)} 处')
    return 1 if CHECKS else 0


if __name__ == '__main__':
    sys.exit(main())
