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

- 当日序号：本次 run 的 head 提交在当日（UTC+8）该仓提交序列中的位次；实现为数
  head 祖先链中的当日提交数（含 head 自身，head 即末位）。取不到当日序号时退化
  四段 `yy.m.d.总序号`，CI 日志以 `::warning` 写明退化原因。
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

## 清理（本仓实现）

- 位置：构建 job 内，本 run 上传成功后立即执行（任意触发事件，含 PR）；权限
  `contents: read` + 最小 `actions: write`（只删本项目 artifact 与 run），不给
  `contents: write`。
- 归属核验写死：artifact 逐个追 `workflow_run` 的 workflow 路径（须为本仓构建
  工作流 `.github/workflows/android.yml` 或遗留 `.github/workflows/build.yml` 的
  运行记录）确认属本项目，未核验对象不删、不计入保留窗口；
  删前打印保留 + 删除清单，失败只报错、不回滚。`retention-days: 5` 只作时间兜底。
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
