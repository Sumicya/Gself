# Gself 项目规则

本文件只记项目事实、计数口径与必要限制。全局工程规范以 Sumicya/selfs 默认分支的
GLOBAL.md 为准，**上次同步 = 第二十五版修订（2026-10-13）**。与全局规范冲突时以 GLOBAL.md 为准。

## 项目事实

- Gself：LSPosed 纯 Hook 模块，applicationId `sumicya.gself`；内部包名与入口类
  `sumicya.fcmself.XposedMain` 不变，CI 的 R8 入口校验查这个类名。
- 构建：`./gradlew test assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- CI（`.github/workflows/android.yml`）：零 secrets，push 与 PR 都出包；跑单测 → 编译 debug →
  校验 R8 入口类 → 上传 `Gself-<五段版本>.apk`。CI 只出包不发版：不创建 Release、正式 tag
  或 Release asset；正式发版须主人事先对项目、版本和触发条件明确授权，获准人工发版后
  按数量规则清理。
- 本沙箱无 JDK / Android SDK：编译验证只能由 CI 完成，汇报须如实区分本地与远端状态。

## 版本计数口径（五段：yy.m.d.当日序号.总序号，日界 UTC+8）

- 来源：android.yml 的真实运行记录（GitHub API 可查、可追溯），锚定本次运行的
  created_at、run_id、run_attempt，不取查询时的最新运行冒充本次。
- 当日序号：本次运行在本仓库当日全部已触发运行（含失败）中的位次（按 run id 升序
  排位，并发运行不抢号）；失败运行消耗当日序号。
- 总序号：只计本仓库历史成功出包的构建数；失败运行不增加总序号。
  衔接（2026-10-10 起）：总序号 = 历史成功数 + 22，常数 22 = 切换前历史最大总序号
  131 − 当时成功数 110 + 1，保证计数不回退、重试不重复加号。
- 重试：run_attempt > 1 时直接复用首次 attempt 已确定的版本（从本运行已有产物名取得），
  不重新加号；首次 attempt 未产出产物时按正常流程计算。
- 本地构建：非发行开发版，版本回落 `0.0.0.0.<fcmself.buildNumber>`，不参与发行序号；
  正式产物只认 CI 出包（主人已裁定）。
- 映射：versionCode = 总序号；产物文件名与 artifact 名统一 `Gself-<五段版本>`。
- 任一必要输入缺失、取数失败或校验不通过，立即停止出包与上传。

## 数量清理

- Actions artifact 全项目合计保留最近 **5 个**（跨分支、跨触发事件，含改名前遗留名）：
  清理在构建 job 内、本 run 上传成功后立即执行（任意触发事件，含 PR）；权限只给最小
  `actions: write`（仅删本项目 artifact），禁止 `contents: write`。完整分页、按创建时间
  降序稳定排序；脚本写死只删归属核验通过的对象（名称前缀初筛 + workflow 路径与运行
  记录核验），未核验对象不计入保留窗口、不删；删前打印保留 + 删除清单。失败只报错：
  不回滚新产物、不删除保留窗口内对象。
- 清理默认启用，不逐仓询问；**已有积压属于落实范围，当场清，不写代码留待以后**——
  2026-10-13 已当场清账（遗留 artifact 确认不在、#13 已关；spec-check / Build 工作流
  禁用因沙箱 token 403 受阻，路径：网页端 Actions 页禁用，或随本分支合并删
  spec-check.yml、#13 分支处置后 Build 消亡）。
- 另设 `retention-days: 5` 作时间兜底，不代替数量清理。
- Release 只保留最近 **1 个**（含关联 tag，仍被保留对象引用的 tag 不删）：本仓库当前
  没有 Release，无清理对象；日后获准人工发版后的清理按 GLOBAL.md 执行。

## 必要限制

- 任何本轮本地改动必须提交并推送到当前远端分支；任务结束不留未提交、不留已提交未推送。
- CI 权限原则：构建与检查只给 `contents: read`；清理只加最小 `actions: write`
  （仅删归属核验通过的本项目 artifact），不给 `contents: write`；不执行未信任的
  fork PR 代码。
- 对外动作（正式发版、删除远程分支、删除标签等）不得由普通 CI 触发器隐式执行。
