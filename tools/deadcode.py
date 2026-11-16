#!/usr/bin/env python3
"""残留代码闸门：本地与 CI 同一个入口，非 0 不出包。

判据（GLOBAL「12 清旧」）：

1. 标识符只出现 1 次＝只有定义、没有调用点。主码与测试**分池**计数：主码里的名字只被测试
   调用也算残留（测试是调用方，不是用户）。
2. import 零用：导入后在文件里再没出现。
3. 变量只写不读、模块级常量零引用。
4. 死引用：README、本工作流与源码注释里指向已删文件的路径。
5. 顶层函数零调用：`tools/*.sh` 里定义了却没被同目录任何脚本或工作流调用的函数；
   整份 `.sh` 没人引用（工作流、README、别的脚本都不提）也算残留：2026-11-16 删掉的签名
   脚本就是这样躺了半年，全仓三条 `grep` 都查不到谁调过它。

计数前先剥注释：死代码只要在注释里被提一次就会凑够两次。Kotlin 的 `"$name"` 模板是真引用，
所以计数视图保留字符串、只把三引号原始字符串里的声明抹掉。

盲区不写在这里，写在 `tools/scan-scope.json`：三类分开记（excluded 有意排除 / deferred
延期未审 / open_questions 待答问题），每条带「为什么」和「剩下什么限制」。清单里点的文件
必须真实存在，否则按「清单已过期」报错——清单自己也会烂。

用法：`python3 tools/deadcode.py`；有发现退出码 1。
"""
import ast
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_MAIN = ROOT / 'app/src/main/kotlin'
KOTLIN_TEST = ROOT / 'app/src/test/kotlin'
README = ROOT / 'README.md'
SCOPE = ROOT / 'tools/scan-scope.json'
SCOPES = ('excluded', 'deferred', 'open_questions')
PY_DIRS = (ROOT / 'tools',)
REF_FILES = (ROOT / 'README.md', ROOT / '.github/workflows/build.yml')
PATH_REF = re.compile(r'(?:docs|tools|\.github)/[\w./-]+\.\w+')
MODIFIERS = (r'(?:private|internal|public|protected|override|inline|const|lateinit|tailrec|'
             r'operator|infix|suspend|external|abstract|open|sealed|data)')
DECL = re.compile(
    r'^[ \t]*(?:@\w+[ \t]*)*(?:' + MODIFIERS + r'[ \t]+)*'
    r'(fun|val|var|class|object|interface|typealias)[ \t]+(?:<[^>]+>[ \t]*)?'
    r'(?:[\w.]+\.)?(\w+)', re.M)
IMPORT = re.compile(r'^import ([\w.]+)(?: as (\w+))?$', re.M)
PY_IMPORT = re.compile(r'^(?:import ([\w.]+)(?: as (\w+))?|from ([\w.]+) import (.+))$', re.M)
WORD = re.compile(r'[A-Za-z_]\w*')


def strip_comments(src: str, py: bool) -> str:
    """注释换成空格，保留换行与字符串字面量，行号不变。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i) or (py and src.startswith("'''", i)):
            q = src[i:i + 3]
            j = src.find(q, i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
            continue
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        if (py and c == '#') or (not py and src.startswith('//', i)):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if not py and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def blank_strings(src: str) -> str:
    """抹掉字符串内容，保留引号与换行：写在字符串里的 `fun foo` 不是声明。"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            out.append('"""' + re.sub(r'[^\n]', ' ', src[i + 3:j]) + '"""')
            i = j + 3
            continue
        c = src[i]
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(c + re.sub(r'[^\n]', ' ', src[i + 1:j]) + c)
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def words(text: str) -> Counter:
    return Counter(WORD.findall(text))


def kotlin_problems() -> tuple[list[str], int, int]:
    problems: list[str] = []
    files = sorted(KOTLIN_MAIN.rglob('*.kt'))
    tests = sorted(KOTLIN_TEST.rglob('*.kt'))
    raw = {p: p.read_text(encoding='utf-8') for p in files + tests}
    decls = {p: blank_strings(strip_comments(v, False)) for p, v in raw.items()}
    counted = {p: strip_comments(v, False) for p, v in raw.items()}
    main_cnt = words('\n'.join(v for p, v in counted.items() if p in files))
    test_cnt = words('\n'.join(v for p, v in counted.items() if p in tests))

    for path, body in decls.items():
        in_main = path in files
        lines = body.split('\n')
        for m in DECL.finditer(body):
            ln = body[:m.start()].count('\n')
            decl, ident = lines[ln].strip(), m.group(2)
            above = '\n'.join(lines[max(0, ln - 3):ln])
            # 框架按名字加载的入口、框架回调、JUnit 按注解发现的方法，都不是本仓的调用点
            if 'override' in decl or 'XposedModule' in decl or '@Test' in above:
                continue
            if not in_main and m.group(1) in ('class', 'object', 'interface'):
                continue
            pool = main_cnt if in_main else test_cnt
            if pool[ident] <= 1:
                hint = '，只被测试调用' if in_main and test_cnt[ident] else ''
                problems.append(f'{path.name}:{ln + 1} 无人引用{hint}  {decl[:80]}')
        file_words = set(WORD.findall(counted[path]))
        for m in IMPORT.finditer(body):
            used = m.group(2) or m.group(1).split('.')[-1]
            if used not in file_words:
                problems.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} import 无人使用  {used}')
    return problems, len(files), len(tests)


def python_problems() -> tuple[list[str], int]:
    problems: list[str] = []
    files = sorted({p for d in PY_DIRS if d.exists() for p in d.glob('*.py')})
    raw = {p: p.read_text(encoding='utf-8') for p in files}
    code = {p: strip_comments(v, True) for p, v in raw.items()}
    cnt = words('\n'.join(code.values()))

    for path, body in code.items():
        tree = ast.parse(body, filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                if cnt[node.name] <= 1:
                    problems.append(f'{path.name}:{node.lineno} 模块级无人引用  {node.name}')
            elif isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and cnt[target.id] <= 1:
                        problems.append(f'{path.name}:{node.lineno} 常量只写不读  {target.id}')
            elif isinstance(node, (ast.For, ast.With, ast.AsyncWith)):
                names = []
                if isinstance(node, ast.For):
                    if isinstance(node.target, ast.Name):
                        names.append(node.target)
                else:
                    names = [i.optional_vars for i in node.items
                             if isinstance(i.optional_vars, ast.Name)]
                for name in names:
                    if name.id != '_' and cnt[name.id] <= 1:
                        problems.append(f'{path.name}:{name.lineno} 循环/上下文变量只写不读  {name.id}')
            elif isinstance(node, ast.ExceptHandler) and node.name and cnt[node.name] <= 1:
                problems.append(f'{path.name}:{node.lineno} 异常变量只写不读  {node.name}')
        for m in PY_IMPORT.finditer(body):
            if m.group(1):
                names = [m.group(2) or m.group(1).split('.')[0]]
            else:
                names = [t.strip().split(' as ')[-1].strip() for t in m.group(4).split(',')]
            line = body[:m.start()].count('\n') + 1
            for name in names:
                if name != '*' and cnt[name] <= 1:
                    problems.append(f'{path.name}:{line} import 无人使用  {name}')
    return problems, len(files)


SH_FUNC = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)[ \t]*\(\)[ \t]*\{', re.M)


def shell_problems() -> tuple[list[str], int]:
    """`.sh` 两条判据：整份脚本没人引用、顶层函数没人调用。

    引用面＝全部 `tools/*.sh` 的正文＋全部工作流＋README。注释先剥掉：只在注释里被提一次
    不算被用到。函数与文件名都按词边界匹配，定义处本身扣除。
    """
    problems: list[str] = []
    files = sorted((ROOT / 'tools').glob('*.sh'))
    if not files:
        return problems, 0
    bodies = {p: strip_comments(p.read_text(encoding='utf-8'), True) for p in files}
    # 工作流与 README 也要剥注释：只在注释里被提一次不算被用到
    surface = '\n'.join(list(bodies.values()) + [
        strip_comments(p.read_text(encoding='utf-8'), True)
        for p in sorted((ROOT / '.github/workflows').glob('*.yml'))
    ] + ([strip_comments(README.read_text(encoding='utf-8'), True)] if README.exists() else []))

    def used(name: str) -> bool:
        hits = len(re.findall(r'(?<![\w.-])' + re.escape(name) + r'(?![\w-])', surface))
        definitions = len(re.findall(r'^[ \t]*' + re.escape(name) + r'[ \t]*\(\)[ \t]*\{',
                                    surface, re.M))
        return hits - definitions > 0

    for path, body in bodies.items():
        alive = used(path.stem)
        if not alive:
            problems.append(f'{path.name} 整份脚本无人引用（工作流、README、别的脚本都不提它）')
            continue    # 整份都没人用，里面的函数不必逐条再报
        for m in SH_FUNC.finditer(body):
            if not used(m.group(1)):
                line = body[:m.start()].count('\n') + 1
                problems.append(f'{path.name}:{line} 函数无人调用  {m.group(1)}')
    return problems, len(files)


def dead_paths() -> list[str]:
    problems: list[str] = []
    for path in REF_FILES:
        if not path.exists():
            continue
        for ref in sorted(set(PATH_REF.findall(path.read_text(encoding='utf-8')))):
            if not (ROOT / ref).exists():
                problems.append(f'{path.name} 指向已删文件  {ref}')
    return problems


def scope_problems() -> tuple[list[str], int]:
    """盲区清单本身要能验：三类齐全、每条有 why 与 limit、点的文件真实存在。"""
    problems: list[str] = []
    if not SCOPE.exists():
        return [f'缺盲区清单 {SCOPE.name}'], 0
    data = json.loads(SCOPE.read_text(encoding='utf-8'))
    total = 0
    for kind in SCOPES:
        rows = data.get(kind) or []
        if not rows:
            problems.append(f'盲区清单 {kind} 类为空')
        for row in rows:
            total += 1
            item = row.get('item', '')
            if not row.get('why') or not row.get('limit'):
                problems.append(f'盲区清单 {kind} 的 {item} 缺 why 或 limit')
            if item and not (ROOT / item).exists():
                problems.append(f'盲区清单已过期：{kind} 里点的 {item} 已不在仓里')
    return problems, total


def main() -> int:
    kt, n_main, n_test = kotlin_problems()
    py, n_py = python_problems()
    sh, n_sh = shell_problems()
    ref = dead_paths()
    scope, n_scope = scope_problems()
    bad = kt + py + sh + ref + scope
    for line in bad:
        print(line)
    print(f'残留：Kotlin {n_main} 主 + {n_test} 测、Python {n_py} 个、Shell {n_sh} 个、'
          f'引用面 {len(REF_FILES)} 个文件、盲区 {n_scope} 条；问题 {len(bad)} 处')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
