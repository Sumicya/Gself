# Gself 项目规则

本文件承载本仓库常驻规则。全局工程规范以 [Sumicya/selfs 的 GLOBAL.md](https://github.com/Sumicya/selfs/blob/main/GLOBAL.md) 为唯一权威。

- 规范最新版：<https://github.com/Sumicya/selfs/blob/main/GLOBAL.md>
- 本仓库上次同步 = 第十七版
- 仓库名：`Gself`（原 `fcmself` 已迁至此名；文档与 CI 对外一律写 Gself）

## 全局规范同步（2026-10-05，第十七版）

以下规则继承自 Sumicya/selfs 的 GLOBAL.md，适用于本仓库：

- 任何本轮本地改动必须最终提交并推送到当前远端分支；任务结束时不得留下未提交或已提交但未推送的改动。
- CI 必须符合全局规范；本仓库的只读规范检查是 `.github/workflows/spec-check.yml`，它只做静态核对，不得拥有 contents: write、actions: write 或其它发布写权限。
- CI 不得自动创建 Release、正式发行 tag 或正式 Release asset。正式发版必须先获得主人对项目、版本和触发条件的明确允许。
- 发版授权与清理授权分离。获准人工发版完成后，CI 可以自动清理旧 Release、关联 tag 和 Actions artifact。
- 默认保留：Release 1 个、关联 tag 1 个、Actions artifact 5 个；artifact 另以 retention-days: 5 作为时间兜底。
- 自动清理必须完整分页、按时间排序并限定在本项目明确的发行对象范围内，不得误删手工或无关对象。
- 发布/清理 job 只授予所需最小写权限；PR 检查与规范检查保持只读，不把写权限暴露给未信任 PR 代码。
- 对外动作（正式发版、删除远程分支、删除标签等）不得由普通 CI 触发器隐式执行。
- 只做新包与不做降级：涉及平台新机制只实现新机制；改平台行为前先查上游并留证据；能明确失败则直接失败，删降级路径前确认最坏失败模式不是不可恢复。

### 本仓库的滚动清理授权（2026-10-05 主人已批准）

- 只清理 Android CI（`.github/workflows/android.yml`）自己产生的 Actions artifact，保留最近 5 个**发行对象**（`Gself-<五段版本>`，以及历史一代命名 `fcmself-*`）。
- PR 检查 / 手动构建其它分支产生的非发行构建 `Gself-dev-<构建数>` **不占保留名额**：它们由 `retention-days: 5` 自然过期。理由：混在一起计数时，连续的 PR 运行会把最新发行产物挤出窗口，下载入口就取不到包。
- 清理由 `android.yml` 的 `cleanup_artifacts` job 执行：只在 main 出包成功后运行，权限只有 `actions: write` + `contents: read`，与同仓库其它清理串行。
- PR 检查、规范检查与其它分支的运行都不执行清理，也不获得写权限。
- 本仓库没有 Release 与正式发行 tag，清理范围不扩展到其它工作流、手工上传的对象或任何 Release/tag。
- 改保留数、改清理范围都需要主人重新授权，并同步本文件与 `spec-check.yml` 的检查项。

本仓库若已有更严格的项目专属规则，以更严格者为准；若与全局规范冲突，以 Sumicya/selfs 的 GLOBAL.md 为准，并在修改时说明冲突。

## 本项目核对清单

- **版本**：五段 `yy.m.d.当日序号.总序号`（第四段当日序号、第五段总序号），由 Android CI 的「Compute release version」一处算定（日期取本次运行的 `created_at`），构建配置只读 `-PversionName` / `-PversionCode`，不自己算日期。
  - 总序号 = `versionCode` = `github.run_number`（本工作流第几次运行，单调递增，且天然大于设备上旧版的 versionCode）。
  - 当日序号 = 当天 main 分支出包运行（push / workflow_dispatch）中 run 号不大于本次的个数，含本次、从 1 起；PR 与其它分支的运行不占号。
  - 非发行构建（PR 检查、手动构建其它分支、本地构建）写 `dev-<构建数>`，不伪造发行序号。
  - 查当前最大总序号（现值会过期，只记查法）：
    `gh api 'repos/Sumicya/Gself/actions/workflows/android.yml/runs?per_page=1' --jq '.workflow_runs[0].run_number'`
- **产物**：单一产物 `Gself-<版本>.apk`（debug 签名、可直装），由 CI 构建、经 Actions artifact 分发。**本仓库不发 Release、不打 tag，文档里不写 release 下载入口**；要么走 artifact，要么本地构建 + 本地签名（见 `docs/build-and-sign-termux.md`）。
- **构建**：只在 CI 构建（Android SDK / JDK 不常备）；本地构建产出非发行版本，不能当发行版引用。
- **下载与安装命令**：按 `GLOBAL.md`「下载与安装命令」一节，在每轮汇报里给可复制的命令块（run-id / artifact-id 在命令里自己算、产物按前缀过滤 `Gself-<五段版本>`、`su -c cp` 到 `/data/local/tmp` 再 `pm install`、末尾带本地清理）；本仓库不发 Release，不写 release 下载入口。
- **术语表**：`docs/glossary.md`；新增术语时同步。
- **文档同步点**：`module.prop` 的 `minApiVersion`、`scope.list` 的作用域、README 的功能清单与代码三方保持一致。
- **内部包名**：历史可能仍为 `sumicya.fcmself`（改包名会破坏已装模块）；对外名一律 Gself。

规范指针：按 `GLOBAL.md` 最新版执行；本文件只保留本仓库专属条目，不复制全局规则。
