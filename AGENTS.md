# Gself 项目规则

本文件只记项目事实、必要限制与规范指针，不复制全局规则、不带版本号。全局工程
规范以 Sumicya/selfs 的 GLOBAL.md 现文为准；本轮规范按主人指示取自其会话分支
`arena/2d001a47-selfs`（PR #6，head a9fc529，尚未合入默认分支，合并后回默认分支）。
冲突按权威顺序处理：主人当轮指示 > GLOBAL.md 现文 > 本文件。

## 项目事实

- Gself：LSPosed 纯 Hook 模块，applicationId `sumicya.gself`；内部包名与入口类
  `sumicya.fcmself.XposedMain` 不变，CI 的 R8 入口校验查这个类名。
- 构建：`./gradlew test assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- CI（`.github/workflows/android.yml`）：零 secrets，push 与 PR 都出包；跑单测 → 编译
  debug → 校验 R8 入口类 → 上传 artifact。CI 只出包不发版，Release 只在主人明确
  授权后人工创建。
- 本仓现无 Release、无 tag。

## 版本计数口径（本仓实现）

- 工作流固定两个：出包 `android.yml`（只接 push(main) 与 `workflow_dispatch`，
  全仓唯一号源）与 PR 校验 `pr-check.yml`（只接 `pull_request`，独立 run_number
  序列，永不参与版本号）。禁止一个工作流同时接 push 与 pull_request：
  run_number 在运行创建时按工作流分配，步骤级 `if` 挡不住 PR 运行吃号。
  PR 校验不取号、不上传、不清理，构建用 gradle 回落版本。
- 当日序号：当日该仓出包运行的序号（android.yml 的 push / workflow_dispatch 运行
  按开始时间从 1 递增，本次运行的位次）；PR 运行不计入。取不到当日序号时退化
  四段 `yy.m.d.总序号`，CI 日志以 `::warning` 写明退化原因。禁止按 head 提交
  位次取数（并发必然竞态，实测出现重复与乱序）。
- 总序号：本次 run 的 `run_number`（取自运行上下文，不查历史）。本仓 workflow 未
  重建、未记录基线，用原值；旧口径「run_number − 3」自本轮停用，总序由 139 跳至
  下一 run 的 run_number 原值，仍递增。旧锚（历史成功数、现存 artifact 最大号）停用。
- 重试：`run_attempt > 1` 复用首次 attempt 已定的号（从本运行已有产物名取得），
  不重新加号；首次 attempt 未产出产物时按正常流程计算。
- `created_at`、`run_number` 缺失或校验不通过，立即停止出包与上传（当日序号按
  上条退化处理，不停包）。
- 本地构建：非发行开发版，版本回落 `0.0.0.0.<fcmself.buildNumber>`，不参与发行
  序号；正式产物只认 CI 出包（主人已裁定）。
- 映射：versionCode = 总序号；文件名与 artifact 名统一 `Gself-<版本>`。
- 号源分界点（2026-10-14）：总序 144 出现过两个——`Gself-26.10.14.3.144` 是
  Android CI（run 38488429439）的正规出包；`Gself-26.10.14.1.144` 是遗留 Build
  工作流（run 38488343468）的旧口径产物，已由主人删除（2026-10-14）。当日序号 2 见下行。
- 版本分界点：2026-10-14 当日序号 2 曾重复（`Gself-26.10.14.2.147` 与
  `Gself-26.10.14.2.148`，后者计数时当日首个出包运行 run 143 的记录已被
  runs 清理删除、少计一位），该分界点之前的当日序号不可信；`.2.147` 按
  重复判据删（待出包工作流首次运行删除，预演已证名单）。
- 号源分界点补证：删除前名单（取值 2026-10-14T03:17Z，`gh api
  repos/Sumicya/Gself/actions/artifacts`）为 `Gself-26.10.14.1.144`
  （run 38488343468）、`Gself-26.10.14.3.144`（run 38488429439）、
  `Gself-26.10.14.4.145`（run 38488671063）、`Gself-26.10.14.2.147`
  （run 38497274451）；总序号 144 的两条来自两个不同 run id，现注册表
  只剩 `Android CI(android.yml)` 与 `PR check(pr-check.yml)` 两条序列。
- 已知风险（号源）：默认分支 `.github/workflows/android.yml` 仍含
  `pull_request` 触发，旧分支 `arena/01a10a49-gself`（ahead 25、PR #13
  已关闭未合并）若新开 PR，会按旧触发跑一次出包工作流、吃掉一个总序号；
  风险存续到本次交付进入默认分支为止。本会话不向非本会话的开放 PR 分支
  推送，也不做关闭 PR 的动作。

## 清理（本仓实现）

- 实现只有一份：`.github/scripts/cleanup.py`，靠 `CLEANUP_APPLY` 开关分两种
  调用。出包工作流（android.yml）上传成功后以 `actions: write` + `'true'`
  真删；PR 校验工作流的 `cleanup-preview` job 以 `actions: read` + `'false'`
  只打印将删名单与判据理由——会话侧的真机证据就是预演日志，真删仍只发生在
  出包工作流。权限 `contents: read` + 最小 `actions: write`，不给
  `contents: write`。
- 归属核验写死：artifact 逐个追 `workflow_run` 的 workflow 路径（须为本仓构建
  工作流 `.github/workflows/android.yml` 或遗留 `.github/workflows/build.yml` 的
  运行记录）确认属本项目，未核验对象不删、不计入保留窗口。
- 清理四判据（判定顺序：artifact 先算——口径→来源→重复序号→数量，run 后算
  ——承载→数量；逐对象打印判据结论与豁免原因，失败只报错、不回滚）：
  ① 口径：版本段数 ≠ android.yml 顶部 `SEGMENTS` 声明值（现 5）的删，不豁免当日；
  ② 来源：`workflow_run` 事件为 `pull_request` 的 artifact 一律删，不豁免当日
  （存量 `Gself-26.10.14.4.145` 属此列，待出包工作流首次运行删除）；
  ③ 重复当日序号：同日同序列出现相同当日序号，只留总序号最大的一个，其余删
  （存量 `.2.147` 属此列，待出包工作流首次运行删除；分界点见版本节）；
  ④ 数量：当日之外，每条工作流序列的 artifact 与 run 各保留最近 5 个（序列按
  工作流计，PR 校验的 run 由出包清理一并处理）；当日创建的对象当天不删，
  次日纳入滚动；承载保留产物的 run 不删、不计入 run 上限，上限按不承载
  保留产物的 run 计。排序一律按来源 run 的 `run_started_at`（UTC+8 日界），
  id 只作同刻 tiebreak。
- `retention-days: 5` 只作时间兜底，不代替滚动清理。
- 分支删除唯一判据：compare `ahead_by=0`（工作已继承进默认分支）。处置记录
  （2026-10-13）：`arena/01a10a49-gself` 上轮按旧规则在 PR #13 关闭后被本会话
  删除，本轮按新判据以 PR #13 头提交 `958ed97` 恢复，compare 为 diverged、
  ahead_by=25，保留并报主人；本会话分支 `arena/43c5efbf-gself` 挂开放 PR #14，不删。

## 必要限制

- 本轮改动提交并推送到当前会话分支（默认授权）；收尾工作树干净、无未推送提交。
- 交付走会话分支加 PR，不直推默认分支（清理类删除不在此限）；不执行未信任的
  fork PR 代码。
- 本沙箱无 JDK / Android SDK：编译验证只能由 CI 完成，汇报须如实区分本地实现、
  远端分支与实际运行状态。跨仓只读；动其它仓碰到 403 当轮报阻塞并附可粘贴命令。
