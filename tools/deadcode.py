#!/usr/bin/env python3
"""残留代码闸门：本地与 CI 同一个入口，非 0 不出包。

判据（GLOBAL「9 目录与清旧」）：

1. 标识符只出现 1 次＝只有定义、没有调用点。主码与测试**分池**计数：主码里的名字只被测试
   调用也算残留（测试是调用方，不是用户）。
2. import 零用：导入后在文件里再没出现。
3. 变量只写不读、模块级常量零引用。
4. 死引用：README、本工作流与源码注释里指向已删文件的路径。

计数前先剥注释：死代码只要在注释里被提一次就会凑够两次。Kotlin 的 `"$name"` 模板是真引用，
所以计数视图保留字符串、只把三引号原始字符串里的声明抹掉。

固有盲区（补不掉，只能列出来）：

- 反射按字符串调用的成员：`Class.forName("…").getMethod("x")` 里的 `"x"` 只是个字符串。
  本仓只有 Hook 目标的运行期查找，那些名字来自 ROM，不在本仓源码里。
- 只在构建脚本或资源文件里被引用的符号：`.xml` / `.gradle.kts` 不在抽取范围内。
  `ic_launcher_monochrome` 等资源名只由 aapt2 使用。
- 资源字段（`res/values/strings.xml` 的 `app_name`）由清单引用，不计。
- 本仓不写文件、不用键值存储，所以「键只写或只读」这条判据不适用。

用法：`python3 tools/deadcode.py`；有发现退出码 1。
"""
import ast
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_MAIN = ROOT / 'app/src/main/kotlin'
KOTLIN_TEST = ROOT / 'app/src/test/kotlin'
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


def dead_paths() -> list[str]:
    problems: list[str] = []
    for path in REF_FILES:
        if not path.exists():
            continue
        for ref in sorted(set(PATH_REF.findall(path.read_text(encoding='utf-8')))):
            if not (ROOT / ref).exists():
                problems.append(f'{path.name} 指向已删文件  {ref}')
    return problems


def main() -> int:
    kt, n_main, n_test = kotlin_problems()
    py, n_py = python_problems()
    ref = dead_paths()
    for line in kt + py + ref:
        print(line)
    print(f'残留：Kotlin {n_main} 主 + {n_test} 测、Python {n_py} 个、引用面 {len(REF_FILES)} 个文件；'
          f'问题 {len(kt) + len(py) + len(ref)} 处')
    return 1 if kt or py or ref else 0


if __name__ == '__main__':
    sys.exit(main())
