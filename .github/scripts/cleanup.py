#!/usr/bin/env python3
"""Gself artifact / run 清理：四判据，单脚本双模式。

CLEANUP_APPLY=true  → 真删（出包工作流 android.yml，actions: write）
CLEANUP_APPLY=false → 只打印将删名单与判据理由（PR 校验预演，actions: read）
真删只有这一处实现，预演与真删共用同一份判定逻辑。

判定顺序固定：先算 artifact（口径→来源→重复当日序号→数量），再算 run
（承载→数量）。排序一律按来源 run 的 run_started_at（UTC+8 日界），id 只作
同刻 tiebreak。逐对象打印判据结论与豁免原因。
"""
import json
import os
import subprocess
import sys
import datetime
from collections import defaultdict

KEEP = int(os.environ.get("KEEP", "5"))
APPLY = os.environ.get("CLEANUP_APPLY", "false") == "true"
REPO = os.environ.get("REPO") or os.environ["GITHUB_REPOSITORY"]
SELF = int(os.environ.get("SELF") or os.environ["GITHUB_RUN_ID"])
_raw_seg = os.environ.get("SEGMENTS", "5")
try:
    SEGMENTS = int(_raw_seg)
    if SEGMENTS <= 0:
        raise ValueError
except ValueError:
    print(f"::error::SEGMENTS 须为正整数，得到 {_raw_seg!r}")
    raise SystemExit(1)

TZ8 = datetime.timezone(datetime.timedelta(hours=8))
TODAY8 = datetime.datetime.now(TZ8).date()
ALLOWED = (".github/workflows/android.yml", ".github/workflows/build.yml")


def d8(iso):
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(TZ8).date()


def api_list(path, key):
    r = subprocess.run(["gh", "api", "--paginate",
                        f"repos/{REPO}/{path}?per_page=100"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(f"::error::读取 {path} 失败：{r.stderr.strip()}")
        raise SystemExit(1)
    items, seen = [], set()
    raw = r.stdout
    dec = json.JSONDecoder()
    idx, n = 0, len(raw)
    while idx < n:
        while idx < n and raw[idx] in " \r\n\t":
            idx += 1
        if idx >= n:
            break
        obj, idx = dec.raw_decode(raw, idx)
        for it in obj.get(key, []):
            if it["id"] not in seen:
                seen.add(it["id"])
                items.append(it)
    return items


def version_of(name):
    """五段版本名 → (yy.m.d, 当日序号, 总序号)；段数或数值不对返回 None。"""
    if not name.startswith("Gself-"):
        return None
    parts = name[len("Gself-"):].split(".")
    if len(parts) != 5:
        return None
    try:
        return ".".join(parts[:3]), int(parts[3]), int(parts[4])
    except ValueError:
        return None


def main():
    mode = "真删" if APPLY else "预演（CLEANUP_APPLY=false，不删）"
    print(f"清理模式：{mode}；SEGMENTS={SEGMENTS}；每序列保留 {KEEP}；今日(UTC+8)={TODAY8}")

    runs = api_list("actions/runs", "workflow_runs")
    run_by_id = {r["id"]: r for r in runs}

    def runinfo(a):
        wr = (a.get("workflow_run") or {}).get("id")
        r0 = run_by_id.get(wr) if wr else None
        if not r0:
            return "", "", ""
        return (r0.get("path", ""), r0.get("event", ""),
                r0.get("run_started_at") or r0.get("created_at", ""))

    # ---------- artifact：口径 → 来源 → 重复当日序号 → 数量 ----------
    arts = api_list("actions/artifacts", "artifacts")
    verified, unverified = [], []
    for a in arts:
        if runinfo(a)[0] in ALLOWED:
            verified.append(a)
        else:
            unverified.append(a)
            print(f"  跳过(归属未核验) {a['id']} {a['name']} run={(a.get('workflow_run') or {}).get('id')}")

    victims, survivors = [], []
    for a in verified:
        _p, event, _s = runinfo(a)
        ver = a["name"][len("Gself-"):] if a["name"].startswith("Gself-") else ""
        seg = ver.count(".") + 1 if ver else 0
        if seg != SEGMENTS:
            victims.append((a, f"口径 {seg} 段 != SEGMENTS {SEGMENTS}"))
        elif event == "pull_request":
            victims.append((a, "来源 pull_request 运行"))
        else:
            survivors.append(a)

    # 判据四：同日同序列出现相同当日序号 → 只留总序号最大的那个
    groups = defaultdict(list)
    for a in survivors:
        v = version_of(a["name"])
        if v:
            groups[(v[0], v[1])].append((v[2], a))
    dup_ids = set()
    for (day, daily), g in groups.items():
        if len(g) > 1:
            g.sort(key=lambda x: x[0], reverse=True)
            for total, a in g[1:]:
                victims.append((a, f"重复当日序号 {day}.{daily}，保留总序号最大者 {g[0][0]}"))
                dup_ids.add(a["id"])
    survivors = [a for a in survivors if a["id"] not in dup_ids]

    # 判据三（数量）：当日之外、每条工作流序列保留最近 KEEP 个；当日豁免
    byseq = defaultdict(list)
    for a in survivors:
        byseq[runinfo(a)[0]].append(a)
    keepers = []
    for path, lst in byseq.items():
        lst.sort(key=lambda a: (runinfo(a)[2] or a["created_at"], a["id"]), reverse=True)
        for i, a in enumerate(lst):
            if i >= KEEP and d8(a["created_at"]) != TODAY8:
                victims.append((a, f"数量 超出 {path} 序列 {KEEP}"))
            else:
                why = "当日豁免（数量）" if i >= KEEP else "-"
                keepers.append(a)
                print(f"  保留 artifact {a['id']} {a['name']} [{why}]")

    for a, why in victims:
        print(f"  {'删除' if APPLY else '将删'} artifact {a['id']} {a['name']} [{why}]")
        if not APPLY:
            print(f"::notice::预演将删 artifact {a['name']} [{why}]")
    print(f"artifact：保留 {len(keepers)}，{'删除' if APPLY else '将删'} {len(victims)}，未核验 {len(unverified)}")

    # ---------- run：承载 → 数量 ----------
    carrier = {(a.get("workflow_run") or {}).get("id") for a in keepers}
    carrier.discard(None)
    rgroups = defaultdict(list)
    for r0 in runs:
        rgroups[r0.get("path", "")].append(r0)
    rkeepers, rvictims, rexempt = [], [], []
    for path, lst in rgroups.items():
        lst.sort(key=lambda r0: (r0.get("run_started_at") or r0["created_at"], r0["id"]),
                 reverse=True)
        counted = 0
        for r0 in lst:
            if r0["id"] in carrier:
                rkeepers.append(r0)
                print(f"  保留 run {r0['id']} {r0['name']} [承载保留产物，不计入上限]")
                continue
            counted += 1
            if counted <= KEEP:
                rkeepers.append(r0)
                print(f"  保留 run {r0['id']} {r0['name']} [数量 {counted}/{KEEP}]")
            elif r0["id"] == SELF:
                rexempt.append((r0, "当前运行"))
                print(f"  豁免 run {r0['id']} {r0['name']} [当前运行]")
            elif r0.get("status") != "completed":
                rexempt.append((r0, "未完成"))
                print(f"  豁免 run {r0['id']} {r0['name']} [未完成]")
            elif d8(r0.get("run_started_at") or r0["created_at"]) == TODAY8:
                rexempt.append((r0, "当日豁免"))
                print(f"  豁免 run {r0['id']} {r0['name']} [当日豁免]")
            else:
                rvictims.append(r0)
                print(f"  {'删除' if APPLY else '将删'} run {r0['id']} {r0['name']} "
                      f"[数量 超出 {path} 序列 {KEEP}（不承载保留产物）]")
                if not APPLY:
                    print(f"::notice::预演将删 run {r0['id']} {r0['name']} [数量]")
    print(f"run：保留 {len(rkeepers)}，豁免 {len(rexempt)}，{'删除' if APPLY else '将删'} {len(rvictims)}")
    print(f"::notice::cleanup {'真删' if APPLY else '预演'}：artifact 将删/已删 {len(victims)}，"
          f"run 将删/已删 {len(rvictims)}（保留 artifact {len(keepers)}）")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"### Artifact/run 清理（{'真删' if APPLY else '预演'}，四判据）\n\n")
            f.write("| 动作 | 对象 | 名称/工作流 | 判据结论 |\n| --- | --- | --- | --- |\n")
            for a in keepers:
                f.write(f"| 保留 | artifact {a['id']} | {a['name']} | - |\n")
            for a, why in victims:
                f.write(f"| {'删除' if APPLY else '将删'} | artifact {a['id']} | {a['name']} | {why} |\n")
            for a in unverified:
                f.write(f"| 跳过(归属未核验) | artifact {a['id']} | {a['name']} | - |\n")
            for r0 in rkeepers:
                f.write(f"| 保留 | run {r0['id']} | {r0['name']} | - |\n")
            for r0, why in rexempt:
                f.write(f"| 豁免 | run {r0['id']} | {r0['name']} | {why} |\n")
            for r0 in rvictims:
                f.write(f"| {'删除' if APPLY else '将删'} | run {r0['id']} | {r0['name']} | 数量 |\n")

    if not APPLY:
        print("预演结束：未执行任何删除（CLEANUP_APPLY=false）。")
        return

    errors = []
    for a, _why in victims:
        r = subprocess.run(["gh", "api", "-X", "DELETE",
                            f"repos/{REPO}/actions/artifacts/{a['id']}"],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"::error::DELETE artifact {a['id']} ({a['name']}) 失败：{r.stderr.strip()}")
            errors.append(str(a["id"]))
    for r0 in rvictims:
        r = subprocess.run(["gh", "api", "-X", "DELETE",
                            f"repos/{REPO}/actions/runs/{r0['id']}"],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"::error::DELETE run {r0['id']} 失败：{r.stderr.strip()}")
            errors.append(str(r0["id"]))
    if errors:
        raise SystemExit(f"清理失败（不声称达标）：{','.join(errors)}")
    print("清理完成。")


if __name__ == "__main__":
    main()
