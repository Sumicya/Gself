#!/usr/bin/env python3
"""Gself artifact / run 清理：五判据，单脚本双模式。

CLEANUP_APPLY=true  → 真删。出包工作流 build.yml 与 PR 校验工作流
  pr-check.yml 的 cleanup job 都以此模式调用同一份脚本（同 concurrency
  group 串行），会话推送即触发。
CLEANUP_APPLY=false → 只打印将删名单与判据理由（备用预演形态）。
DELETE 失败按原因分：403 权限类（fork PR 只读令牌等）打 ::warning:: 降级
为预演并继续，不让 job 红；403 以外当轮 ::error:: 点名（对象与返回码）并
让 job 红。预演名单不作清理已做的证据。

判定顺序：先算每个工作流应保留的最新 5 条 run（硬上限，不设例外），
再算 artifact（命名口径与号源口径→来源→重复当日序号→数量，全仓保留
最新 5 个），最后按运行记录上限优先校正 artifact 留存（保留某 artifact
会让其工作流留第 6 条 run 的，删该 artifact）。未完成且超限的 run 先
取消较旧者。号源核验：五段产物的名义序号须与来源 run 的
head sha 按取号同式算出的提交数一致，不符按旧口径删；一个合规产物都
没有时暂留最新一个旧口径产物，等新口径产物出成再删。排序一律按来源
run 的 run_started_at（UTC+8 日界），id 只作同刻 tiebreak。逐对象打印
判据结论与豁免原因。
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


def delete(kind, obj_id, label, why):
    """真删一个对象，失败按原因分级：
    403 权限类 → ::warning:: 降级，不红 job，返回 "perm"；
    其余失败   → ::error:: 点名（对象与返回码），返回 "fail"，调用方让 job 红。
    """
    endpoint = f"DELETE repos/{REPO}/actions/{kind}/{obj_id}"
    p = subprocess.run(["gh", "api", "-X", "DELETE",
                        f"repos/{REPO}/actions/{kind}/{obj_id}"],
                       capture_output=True, text=True)
    if p.returncode == 0:
        print(f"  已删 {label}（{why}）")
        return "ok"
    err = p.stderr.strip()
    if p.returncode == 403 or "403" in err or "Resource not accessible" in err:
        print(f"::warning::{label} 删除失败：端点 {endpoint}，返回码 "
              f"{p.returncode}（403 权限类）：{err[:160]}（本次降级为预演）")
        return "perm"
    print(f"::error::{label} 删除失败：端点 {endpoint}，返回码 "
          f"{p.returncode}：{err[:260]}")
    return "fail"


def cancel(r0):
    """取消一个未完成且超限的 run，失败按 403 / 非 403 分级（同 delete）。"""
    label = f"run {r0['id']} {r0['name']}"
    endpoint = f"POST repos/{REPO}/actions/runs/{r0['id']}/cancel"
    p = subprocess.run(["gh", "api", "-X", "POST",
                        f"repos/{REPO}/actions/runs/{r0['id']}/cancel"],
                       capture_output=True, text=True)
    if p.returncode == 0:
        print(f"  已取消 {label}")
        return "ok"
    err = p.stderr.strip()
    if p.returncode == 403 or "403" in err or "Resource not accessible" in err:
        print(f"::warning::{label} 取消失败：端点 {endpoint}，返回码 "
              f"{p.returncode}（403 权限类）：{err[:160]}")
        return "perm"
    print(f"::error::{label} 取消失败：端点 {endpoint}，返回码 "
          f"{p.returncode}：{err[:260]}")
    return "fail"


def main():
    mode = "真删" if APPLY else "预演（CLEANUP_APPLY=false，不删）"
    print(f"清理模式：{mode}；SEGMENTS={SEGMENTS}；CLEANUP_APPLY={'true' if APPLY else 'false'}；保留上限 {KEEP}（artifact 全仓、run 每工作流）；今日(UTC+8)={TODAY8}；PREFIX=Gself-；号源核验命令：git rev-list --count <head sha> 与 git rev-list --count --since=<当日0点 UTC+8> <head sha>")

    runs = api_list("actions/runs", "workflow_runs")
    run_by_id = {r["id"]: r for r in runs}

    def runinfo(a):
        wr = (a.get("workflow_run") or {}).get("id")
        r0 = run_by_id.get(wr) if wr else None
        if not r0:
            return "", "", ""
        return (r0.get("path", ""), r0.get("event", ""),
                r0.get("run_started_at") or r0.get("created_at", ""))

    def commit_counts(sha, daystr):
        """号源核验：按 artifact 名里的日期与来源 run 的 head sha，用与取号
        相同的两条 git 命令算应得的 (总序号, 当日序号)。sha 不在本地历史
        （需 fetch-depth: 0）或命令失败返回 None，按未核验处理、不删。"""
        try:
            y, m, d = daystr.split(".")
            since = f"20{int(y):02d}-{int(m):02d}-{int(d):02d}T00:00:00+08:00"
        except (ValueError, IndexError):
            return None
        t = subprocess.run(["git", "rev-list", "--count", sha],
                           capture_output=True, text=True)
        if t.returncode != 0:
            return None
        dd = subprocess.run(["git", "rev-list", "--count", f"--since={since}", sha],
                            capture_output=True, text=True)
        if dd.returncode != 0:
            return None
        try:
            return int(t.stdout.strip()), int(dd.stdout.strip())
        except ValueError:
            return None

    # ---------- run 保留集先算：每工作流最新 KEEP 条，硬上限不设例外 ----
    rgroups = defaultdict(list)
    for r0 in runs:
        rgroups[r0.get("path", "")].append(r0)
    run_keepers, rcancel, rdelete = set(), [], []
    for path, lst in rgroups.items():
        lst.sort(key=lambda r0: (r0.get("run_started_at") or r0["created_at"], r0["id"]),
                 reverse=True)
        for i, r0 in enumerate(lst):
            if i < KEEP:
                run_keepers.add(r0["id"])
    self_exempt = None
    if SELF not in run_keepers and any(r["id"] == SELF for r in runs):
        # 本次运行正在执行清理，不取消、不删除
        self_exempt = next(r for r in runs if r["id"] == SELF)
        run_keepers.add(SELF)
    for path, lst in rgroups.items():
        for r0 in lst:
            if r0["id"] in run_keepers:
                continue
            if r0.get("status") != "completed":
                rcancel.append(r0)
            else:
                rdelete.append(r0)

    # ---------- artifact：命名/号源口径 → 来源 → 重复当日序号 → 数量 ----------
    arts = api_list("actions/artifacts", "artifacts")
    verified, unverified = [], []
    for a in arts:
        if runinfo(a)[0] in ALLOWED:
            verified.append(a)
        else:
            unverified.append(a)
            print(f"  跳过(归属未核验) {a['id']} {a['name']} run={(a.get('workflow_run') or {}).get('id')}")

    victims, survivors, source_victims = [], [], []
    for a in verified:
        _p, event, _s = runinfo(a)
        ver = a["name"][len("Gself-"):] if a["name"].startswith("Gself-") else ""
        seg = ver.count(".") + 1 if ver else 0
        v = version_of(a["name"])
        if seg != SEGMENTS:
            victims.append((a, f"口径 {seg} 段 != SEGMENTS {SEGMENTS}"))
        elif v is not None:
            wr = (a.get("workflow_run") or {}).get("id")
            sha = (run_by_id.get(wr) or {}).get("head_sha", "") if wr else ""
            cc = commit_counts(sha, v[0]) if sha else None
            if cc is not None and cc != (v[2], v[1]):
                source_victims.append(
                    (a, f"号源口径 名义总序号 {v[2]} != 提交数 {cc[0]}"
                        f"（当日序号 {v[1]} vs {cc[1]}）"))
            elif event == "pull_request":
                victims.append((a, "来源 pull_request 运行"))
            else:
                if cc is None:
                    print(f"  号源未核验（保留） {a['id']} {a['name']} head_sha={sha or '无'}")
                survivors.append(a)
        elif event == "pull_request":
            victims.append((a, "来源 pull_request 运行"))
        else:
            survivors.append(a)
    # 换号源过渡：一个号源合规的产物都没有时，最新一个旧口径产物保留，
    # 等新口径产物出成再删——不许出现「一个可装的包都没有」的状态。
    if not survivors and source_victims:
        newest = max(source_victims, key=lambda x: x[0]["created_at"])
        source_victims.remove(newest)
        survivors.append(newest[0])
        print(f"  过渡保留 {newest[0]['id']} {newest[0]['name']} "
              "[号源切换过渡：暂留最新旧口径，待新口径产物出成]")
    victims.extend(source_victims)

    # 重复当日序号：同日同序列出现相同当日序号 → 按来源 run 的 run_started_at
    # 删旧留新（不按总序号大小判：换号源后总序号口径会变，按大小判会误删）
    groups = defaultdict(list)
    for a in survivors:
        v = version_of(a["name"])
        if v:
            groups[(v[0], v[1])].append(a)
    dup_ids = set()
    for (day, daily), g in groups.items():
        if len(g) > 1:
            g.sort(key=lambda a: (runinfo(a)[2] or a["created_at"], a["id"]),
                   reverse=True)
            for a in g[1:]:
                victims.append((a, f"重复当日序号 {day}.{daily}，"
                                   "保留 run_started_at 最新者"))
                dup_ids.add(a["id"])
    survivors = [a for a in survivors if a["id"] not in dup_ids]

    # 判据五（数量）：全仓 artifact 按 created_at 从新到旧保留最新 KEEP 个
    survivors.sort(key=lambda a: (a["created_at"], a["id"]), reverse=True)
    keepers = []
    for i, a in enumerate(survivors):
        if i >= KEEP:
            victims.append((a, f"数量 超出全仓上限 {KEEP}"))
        else:
            keepers.append(a)
            print(f"  保留 artifact {a['id']} {a['name']} [-]")

    # 运行记录上限优先：保留某 artifact 若会让其工作流必须留第 6 条 run，
    # 删该 artifact 并同步校正留存清单（任何关联关系不豁免 run 上限）
    final_keepers = []
    for a in keepers:
        wr = (a.get("workflow_run") or {}).get("id")
        if wr in run_keepers:
            final_keepers.append(a)
        else:
            victims.append((a, f"运行记录上限优先：来源 run {wr} 超限，"
                               "留存清单同步校正"))
    keepers = final_keepers

    for a, why in victims:
        print(f"  {'删除' if APPLY else '将删'} artifact {a['id']} {a['name']} [{why}]")
        if not APPLY:
            print(f"::notice::预演将删 artifact {a['name']} [{why}]")
    print(f"artifact：保留 {len(keepers)}，{'删除' if APPLY else '将删'} {len(victims)}，未核验 {len(unverified)}")

    # ---------- run：打印计划（产物已在 artifact 段判定，执行顺序在后） ----
    run_arts = defaultdict(list)
    for a in arts:
        wr = (a.get("workflow_run") or {}).get("id")
        if wr:
            run_arts[wr].append(a["name"])
    rkeepers = [r0 for r0 in runs if r0["id"] in run_keepers]
    rexempt = [self_exempt] if self_exempt else []
    rvictims = rdelete
    for path, lst in rgroups.items():
        for i, r0 in enumerate(lst):
            if r0["id"] in run_keepers:
                tag = "当前运行" if r0["id"] == SELF else f"最新 {i + 1}/{KEEP}"
                print(f"  保留 run {r0['id']} {r0['name']} [{tag}]")
    for r0 in rcancel:
        print(f"  {'取消' if APPLY else '将取消'} run {r0['id']} {r0['name']} "
              f"[未完成且超限，先取消较旧者；产物："
              f"{', '.join(run_arts.get(r0['id'], [])) or '无'}]")
    for r0 in rvictims:
        print(f"  {'删除' if APPLY else '将删'} run {r0['id']} {r0['name']} "
              f"[数量 超出 {r0.get('path', '')} 上限 {KEEP}；产物："
              f"{', '.join(run_arts.get(r0['id'], [])) or '无'}"
              "（超限产物已先删）]")
        if not APPLY:
            print(f"::notice::预演将删 run {r0['id']} {r0['name']} [数量]")
    print(f"run：保留 {len(rkeepers)}，豁免 {len(rexempt)}，取消 {len(rcancel)}，"
          f"{'删除' if APPLY else '将删'} {len(rvictims)}")
    print(f"artifact 剩余（全仓）：{len(keepers)}")
    rseq_left = defaultdict(int)
    for r0 in rkeepers:
        rseq_left[r0.get("path", "")] += 1
    print("各工作流剩余 run："
          + "，".join(f"{k or '?'}={n}" for k, n in sorted(rseq_left.items())))
    print(f"::notice::cleanup {'真删' if APPLY else '预演'}：artifact 将删/已删 {len(victims)}，"
          f"run 将删/已删 {len(rvictims)}、取消 {len(rcancel)}（保留 artifact {len(keepers)}）")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"### Artifact/run 清理（{'真删' if APPLY else '预演'}，五判据）\n\n")
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
            for r0 in rcancel:
                f.write(f"| {'取消' if APPLY else '将取消'} | run {r0['id']} | {r0['name']} | 未完成且超限 |\n")
            for r0 in rvictims:
                f.write(f"| {'删除' if APPLY else '将删'} | run {r0['id']} | {r0['name']} | 数量 |\n")

    if not APPLY:
        print("预演结束：未执行任何删除（CLEANUP_APPLY=false）。")
        return

    perm, fail = 0, 0
    for r0 in rcancel:
        r = cancel(r0)
        perm += r == "perm"
        fail += r == "fail"
    for a, why in victims:
        r = delete("artifacts", a["id"], f"artifact {a['name']}", why)
        perm += r == "perm"
        fail += r == "fail"
    for r0 in rvictims:
        r = delete("runs", r0["id"], f"run {r0['id']} {r0['name']}", "数量")
        perm += r == "perm"
        fail += r == "fail"
    if perm:
        print(f"::warning::本次 {perm} 个对象因 403 权限类删除失败，已降级为预演"
              "（未声称清理完成）")
    if fail:
        raise SystemExit(f"{fail} 个对象删除失败（非 403），对象与返回码见上 "
                         "::error:: 行")
    if not perm and not fail:
        print("清理完成。")


if __name__ == "__main__":
    main()
