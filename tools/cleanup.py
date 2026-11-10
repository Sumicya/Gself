#!/usr/bin/env python3
"""清理本仓超量 artifact 与运行记录（规范「12 工作流格式」「13 产物」）。

判据五条：命名、来源、重复当日号、号源口径、数量。
artifact 全仓按 created_at 保留最新 5 个；运行记录每工作流按开始时间保留最新 5 条。
先列完整计划，再删产物；超限的未完成运行先取消，超限运行承载的候选产物也删。
运行上限优先：不为承载关系扩容。

`CLEANUP_APPLY='true'` 才真删；`false` 只列计划（禁止拿预演当清理已做）。

失败分级（规范「11 对外动作」）：403 打 `::warning::` 写明对象、端点、返回码后继续，不红 job；
403 以外的失败逐条点名并红 job。本仓不发版、不打 tag，所以没有 Release／tag 档。
"""
import json
import os
import re
import subprocess
from collections import defaultdict
from datetime import datetime, timedelta, timezone

REPO = os.environ["REPO"]
SELF = int(os.environ["RUN_ID"])
SEGMENTS = int(os.environ["SEGMENTS"])
APPLY = os.environ.get("CLEANUP_APPLY", "false").strip().lower() == "true"
PREFIX = REPO.split("/")[-1] + "-"  # 项目名前缀由 REPO 推导，不写死
KEEP = 5                       # 全仓 artifact 5 个、每工作流 run 5 条
NEED = "actions: write"
CST = timezone(timedelta(hours=8))
TODAY = datetime.now(CST).strftime("%Y-%m-%d")
VERB = "删" if APPLY else "将删"
print(f"cleanup.py 启动参数：SEGMENTS={SEGMENTS} CLEANUP_APPLY={'true' if APPLY else 'false'} "
      f"PREFIX={PREFIX} KEEP={KEEP} RUN_ID={SELF} TODAY={TODAY} REPO={REPO}")
print("删除失败分级口径：DELETE 403 → ::warning:: 降级不红；403 以外 → ::error:: 累积，脚本末尾红 job")


def gh(*args):
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("gh " + " ".join(args) + " 失败：" + p.stderr.strip())
    return p.stdout


def list_all(url, key):
    out = gh("api", "--paginate", url, "--jq", key + "[]")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def stamp(run):
    """排序用的开始时间：run_started_at 缺失才退回 created_at。"""
    return run.get("run_started_at") or run["created_at"]


DENIED = []
FAILED = []                     # 非 403 的删除失败：(端点, 标签, 返回码, stderr)；末尾一律红 job


def http_code(stderr):
    m = re.search(r"\(HTTP (\d+)\)", stderr)
    return m.group(1) if m else "?"


def mutate(method, endpoint, label):
    """403 仅记录权限缺口，其它错误累积后使任务失败；返回真实执行结果。"""
    p = subprocess.run(["gh", "api", "-X", method, endpoint], capture_output=True, text=True)
    if p.returncode == 0:
        return True
    code = http_code(p.stderr)
    if code == "403":
        DENIED.append((endpoint, label))
        print(f"::warning::{label} 未完成：{endpoint} HTTP 403，缺 {NEED} 权限")
    else:
        FAILED.append((endpoint, label, code, p.stderr.strip()[:200]))
    return False


def delete(kind, obj_id, label, why):
    print(f"  {'删' if APPLY else '将删'} {label}（{why}）")
    return not APPLY or mutate("DELETE", f"repos/{REPO}/actions/{kind}/{obj_id}", label)


def finish():
    """收尾：非 403 失败红 job，403 只报缺口；两档共用。"""
    if FAILED:
        for endpoint, label, code, err in FAILED:
            print(f"  ::error::{label} 删除失败（HTTP {code}，非权限类）：{endpoint} — {err}")
        raise SystemExit(f"清理失败 {len(FAILED)} 个对象（非 403，当轮红 job）")
    if DENIED:
        print(f"清理未完成：{len(DENIED)} 个对象存在 HTTP 403 权限缺口")
    elif APPLY:
        print("清理完成：计划内删除全部成功")
    else:
        print("只读扫描完成：未执行删除")


runs = list_all(f"repos/{REPO}/actions/runs?per_page=100", ".workflow_runs")
run_by_id = {r["id"]: r for r in runs}
self_run = run_by_id.get(SELF)
print(f"本次 run {SELF} 所属序列：{self_run['path'] if self_run else '(未出现在本次拉取的运行列表里)'}")
arts = [a for a in list_all(f"repos/{REPO}/actions/artifacts?per_page=100", ".artifacts")
        if not a["expired"]]



# ---------- artifact：口径 → 来源 → 重复当日序号 → 数量 ----------
drop_a, survivors = [], []                # survivors 是过了前两条关的候选
for a in sorted(arts, key=lambda x: (x["created_at"], x["id"]), reverse=True):
    name = a["name"]
    seg = name[len(PREFIX):].split(".") if name.startswith(PREFIX) else []
    r = run_by_id.get(a["workflow_run"]["id"])
    ws = (r or {}).get("path", "(未知来源)")
    if not name.startswith(PREFIX):
        drop_a.append((a, ws, f"口径：名不是 {PREFIX} 前缀"))
    elif len(seg) != SEGMENTS or not all(part.isdigit() for part in seg):
        drop_a.append((a, ws, f"口径：段数={len(seg)} 要求={SEGMENTS}，各段须为数字"))
    elif any(part != "0" and part.startswith("0") for part in seg):
        # 规范 2026-10-31 补：月、日、当日序号、总序号都不带前导零，26.11.01 这类按不合规产物删
        drop_a.append((a, ws, f"口径：出现补零段 {name}，只允许 yy.m.d.当日序号.总序号 的不补零写法"))
    elif r and r["event"] == "pull_request":
        drop_a.append((a, ws, "来源：workflow_run 事件是 pull_request"))
    else:
        survivors.append((a, ws, seg))

# 判据三：同日同当日序号重复 —— 按 artifact created_at 删旧留新。
# 不按总序号大小判：改文件名、换号源后总序号会回头，按大小判会误删新产物。
# 删前把两个 artifact 的名与 id、两次运行的 rn 打进日志（删除清单由日志保存）。
if SEGMENTS >= 5:
    groups = defaultdict(list)
    for a, ws, seg in survivors:
        groups[(ws, ".".join(seg[:3]), int(seg[3]))].append((a, ws))
    dup_ids = set()
    for (ws, day, daily), g in sorted(groups.items()):
        if len(g) < 2:
            continue
        g.sort(key=lambda t: (t[0]["created_at"], t[0]["id"]), reverse=True)
        newest = g[0][0]
        keep_rn = run_by_id.get(newest["workflow_run"]["id"], {}).get("run_number", "?")
        for a, ws in g[1:]:
            dup_ids.add(a["id"])
            rn = run_by_id.get(a["workflow_run"]["id"], {}).get("run_number", "?")
            drop_a.append((a, ws, f"重复当日序号 {day}.{daily}：按 artifact created_at 删旧留新，"
                                  f"留 {newest['name']}（id={newest['id']} rn={keep_rn}），"
                                  f"删 {a['name']}（id={a['id']} rn={rn}）"))
    survivors = [(a, ws, seg) for a, ws, seg in survivors if a["id"] not in dup_ids]

# 判据四：号源口径 —— 产物名末段总序号必须等于其来源运行的 run_number（版本口径已改为 CI 运行数）。
quota, old_style = [], []
for a, ws, seg in survivors:
    rn = run_by_id.get(a["workflow_run"]["id"], {}).get("run_number")
    print(f"五条判据扫描：{a['name']} id={a['id']} 来源运行 run_number={rn} 名字总序号={seg[-1]}")
    if rn is not None and seg[-1].isdigit() and int(seg[-1]) == rn:
        quota.append((a, ws))
    else:
        old_style.append((a, ws, f"号源口径：名字总序号 {seg[-1]} ≠ 来源运行 run_number {rn}"))
if quota:
    for a, ws, why in old_style:
        drop_a.append((a, ws, why))
else:
    # 过渡期：全仓还没有一个满足新口径的产物时，暂留最新 1 个旧产物，等新口径产物上传后再清零。
    old_style.sort(key=lambda t: (t[0]["created_at"], t[0]["id"]), reverse=True)
    quota = [(a, ws) for a, ws, _ in old_style[:1]]
    for a, ws, why in old_style[1:]:
        drop_a.append((a, ws, why))
keep_a = []
for i, (a, ws) in enumerate(sorted(quota, key=lambda t: (t[0]["created_at"], t[0]["id"]), reverse=True)):
    if i < KEEP:
        keep_a.append((a, ws, "数量：全仓最新 5 个"))
    else:
        drop_a.append((a, ws, "数量：超出全仓上限 5"))

# 运行先按完整序列排队：承载、本次、未完成运行均占名额，不再扩容。
drop_r, keep_r = [], []
counted = defaultdict(list)
for r in sorted(runs, key=lambda x: (stamp(x), x["id"]), reverse=True):
    counted[r["path"]].append(r)
for ws, lst in counted.items():
    for i, r in enumerate(lst):
        (keep_r if i < KEEP else drop_r).append((r, ws, "数量：每工作流最新 5 条" if i < KEEP else "数量：超出每工作流上限 5"))

# 运行上限优先：校正 artifact 候选保留清单，不因承载关系扩容。
excess_runs = {r["id"] for r, _, _ in drop_r}
for a, ws, why in keep_a:
    if a["workflow_run"]["id"] in excess_runs:
        drop_a.append((a, ws, "运行上限优先：承载运行已超过最新 5 条"))
keep_a = [(a, ws, why) for a, ws, why in keep_a
          if a["workflow_run"]["id"] not in excess_runs]

print(f"artifact：{VERB} {len(drop_a)} 个，保留 {len(keep_a)} 个")
for a, ws, why in drop_a:
    print(f"  待删 {a['name']} id={a['id']} run={a['workflow_run']['id']}（{why}）")
for a, ws, why in keep_a:
    print(f"  留 {a['name']} id={a['id']}（{why}）")
print(f"运行记录：{VERB} {len(drop_r)} 条，保留 {len(keep_r)} 条")
for r, ws, why in drop_r:
    carried = [(a["name"], a["id"]) for a in arts if a["workflow_run"]["id"] == r["id"]]
    print(f"  待删 run={r['id']} rn={r['run_number']} {ws} status={r['status']} 产物={carried}（{why}）")
for r, ws, why in keep_r:
    print(f"  留 run={r['id']} {ws}（{why}）")
for ws, lst in counted.items():
    print(f"扫描：{ws} 总数={len(lst)} 保留={min(KEEP, len(lst))} 超限={max(0, len(lst)-KEEP)}")

# 本清理若自身已超限，取消自己会中断清理；明确失败，由更新的清理运行收口。
if SELF in excess_runs:
    raise SystemExit(f"::error::本次 run={SELF} 已超限，由更新的清理运行取消并删除")

# 先删产物；403 或其它失败后不得借删除承载运行绕过失败。
blocked_runs = set()
for a, ws, why in drop_a:
    if not delete("artifacts", a["id"], f"{a['name']} id={a['id']}", why):
        blocked_runs.add(a["workflow_run"]["id"])
for r, ws, why in drop_r:
    if r["id"] in blocked_runs:
        print(f"run={r['id']} 未删：其产物删除失败，禁止级联删除")
        continue
    if r["status"] != "completed":
        endpoint = f"repos/{REPO}/actions/runs/{r['id']}"
        print(f"  {'取消' if APPLY else '将取消'} 超限运行 {r['id']}，完成后再删")
        if APPLY:
            if not mutate("POST", endpoint + "/cancel", f"取消 run={r['id']}"):
                continue
            subprocess.run(
                ["gh", "run", "watch", str(r["id"]), "--repo", REPO, "--interval", "2"],
                capture_output=True,
                text=True,
            )
            r = json.loads(gh("api", endpoint))
            if r["status"] != "completed":
                FAILED.append((endpoint, f"取消 run={r['id']}", "等待超时", "取消后尚未完成，未删除"))
                continue
            # 运行在扫描与取消之间可能上传产物，重读后再删，避免无清单级联删除。
            for a in list_all(endpoint + "/artifacts?per_page=100", ".artifacts"):
                if not delete("artifacts", a["id"], f"{a['name']} id={a['id']}", "超限运行取消后的产物复查"):
                    blocked_runs.add(r["id"])
            if r["id"] in blocked_runs:
                continue
    delete("runs", r["id"], f"run={r['id']} {ws}", why)

finish()
