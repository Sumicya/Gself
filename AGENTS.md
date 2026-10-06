# Gself 项目规则

本文件承载本仓库常驻规则。全局工程规范以 [Sumicya/selfs 的 GLOBAL.md](https://github.com/Sumicya/selfs/blob/main/GLOBAL.md) 为唯一权威。

- 规范最新版：<https://github.com/Sumicya/selfs/blob/main/GLOBAL.md>
- 本仓库上次同步 = 第二十一版
- 仓库名：`Gself`（原 `fcmself` 已迁至此名；文档与 CI 对外一律写 Gself）

## 全局规范同步（2026-10-05，第二十一版）

以下规则继承自 Sumicya/selfs 的 GLOBAL.md，适用于本仓库：

- 任何本轮本地改动必须最终提交并推送到当前远端分支；任务结束时不得留下未提交或已提交但未推送的改动。
- CI 必须符合全局规范；规范自检由 agent 在会话中完成（读本文件与 `GLOBAL.md`、核指针、版本戳、权限与禁发版），**不设规范检查工作流**。一份职责只留一个工作流：本仓库唯一工作流是 `.github/workflows/build.yml`（构建 + 出包 + 清理）。
- CI 不得自动创建 Release、正式发行 tag 或正式 Release asset。正式发版必须先获得主人对项目、版本和触发条件的明确允许。
- 发版授权与清理分离：**滚动清理自规范第二十一版起默认启用，不需要逐仓批准**；发版（Release / 正式 tag / 正式 asset）仍须主人对项目、版本和触发条件的明确允许。
- 默认保留：Release 1 个、关联 tag 1 个、Actions artifact 5 个；artifact 另以 retention-days: 5 作为时间兜底。
- 自动清理必须完整分页、按时间排序并按项目名前缀筛选，不得误删手工或无关对象；积压清理是启用清理的一部分，当轮清到保留数以内。
- 权限默认只读（`contents: read`，查运行历史加 `actions: read`）；出包不需要写权限；写权限只出现在清理 job（`actions: write`），且不执行来自未信任 PR 的代码。
- 对外动作（正式发版、删除远程分支、删除标签等）不得由普通 CI 触发器隐式执行。
- 只做新包与不做降级：涉及平台新机制只实现新机制；改平台行为前先查上游并留证据；能明确失败则直接失败，删降级路径前确认最坏失败模式不是不可恢复。

### 本仓库的滚动清理（规范第二十一版：默认启用）

- 范围：本仓库自己工作流产出的对象，按项目名前缀筛选——`Gself-`（大小写不敏感）与改名前的遗留前缀
  `fcmself-` / `fcmfix-`；不碰其它项目、手工上传的对象、Release 与 tag。
- 保留数：**发行对象保留最近 5 个**（`KEEP_ARTIFACT: "5"`，对象 = `Gself-<五段版本>` 及遗留名），按创建时间倒序完整分页后计算；另以 `retention-days: 5` 作时间兜底。
  PR / 其它分支的非发行包（`Gself-dev-<构建数>`）**不占名额**，按 `retention-days: 5` 过期。
- 与规范字面的差异（按【权威与冲突】裁决后落地）：规范第二十一版写「Actions artifact 保留最近 5 个」，
  主人当轮指示为「只按发行包计数」；权威顺序是**主人当轮指示 > 规范最新版**，故按主人指示执行，并在 CHANGELOG 记账。
  这样下载入口永远能取到发行包，代价是非发行包最多多占几天、由 5 天过期兜底。
- 实现位置：`.github/workflows/build.yml` 的 `cleanup_artifacts` job + `.github/scripts/cleanup_artifacts.py`；
  `needs: build` 且 `concurrency` 串行，PR 事件不执行；删除前打印完整清单，本次产物不可见就整轮放弃。
- 为什么不让非发行包占名额：PR / 分支包比发行包新时，若按「最近 5 个」一视同仁，发行包会被挤出窗口、下载入口取不到包；
  只算发行对象后不存在这个场景，代价是非发行包最多多占几天（`retention-days: 5` 兜底）。
- 版本连续性：`build.yml` 由 `android.yml` 改名而来，`GITHUB_RUN_NUMBER` 归零，
  所以总序号不直接用 run 号，而是取「本仓库所有工作流最大 run 号 + 1」与「现存发行产物第五段 + 1」的较大者
  （查法写在工作流里，不写死现值），保证 `versionCode` 不回退。

### 权威冲突台账（每轮汇报必须同文列出，不许沉默落地）

规范第二十一版（sha `193300d`）行号如下；权威顺序按【权威与冲突】：**主人当轮指示 > 规范最新版 > 本文件 > 其它文档**。

| # | 规范怎么说 | 主人指示 / 项目事实 | 按谁执行 | 后果与解除条件 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 第 250 行：Actions artifact「保留最近 5 个」 | 主人（2026-10-05）：只按发行包计数 | 主人指示 | 现存 13 个 artifact 全是 `Gself-dev-*`：按字面该删 8 个、保留 5 个；按主人口径发行对象 0 个、该删 0 个 | **已执行，待主人一句话确认或改回** |
| 2 | 第 248 行：清理 job 必须在**默认分支**的构建工作流里，只留在分支或未合并 PR 上视为未实现 | 主人：PR #13 不合（先查消息滞留） | 主人指示 | `main` 仍是 `android.yml` + `ubuntu-latest` + `spec-check.yml`，**清理未实现**；解除条件 = 主人放行合并 PR #13，合并后在 main 跑一次出包让清理 job 生效 | **合规地未实现，待主人放行** |
| 3 | 第 270 行：积压清理是启用清理的一部分，当轮把积压清到保留数以内 | 主人口径：非发行包不占名额 | 主人指示 | 现在真在默认分支执行会删 0 个；按规范字面会删 8 个 `Gself-dev-*` | **待裁决**（要不要把 dev 积压也清到 5 个） |
| 4 | 第 19、23 行：「裁决后改错的那一处：规则错就改本文件并记一条 CHANGELOG.md」 | 规范文件在 `Sumicya/selfs`，不在本仓库 | — | 我只在本仓库 `CHANGELOG.md` 记账，未改 `selfs/GLOBAL.md` | **待主人裁决**要不要让我去改规范文件 |

本仓库若已有更严格的项目专属规则，以更严格者为准；若与全局规范冲突，以 Sumicya/selfs 的 GLOBAL.md 为准，并在修改时说明冲突。

## 本项目核对清单

- **版本**：五段 `yy.m.d.当日序号.总序号`（第四段当日序号、第五段总序号），由 Android CI 的「Compute release version」一处算定（日期取本次运行的 `created_at`），构建配置只读 `-PversionName` / `-PversionCode`，不自己算日期。
  - 总序号 = `versionCode` = max(仓库所有工作流最大 run 号 + 1, 现存发行产物第五段 + 1, 本工作流 run 号)：跨工作流改名也不回退；发行与非发行构建共用它，两种包互相覆盖安装都不被版本号拦。
  - 当日序号 = 当天 main 分支出包运行（push / workflow_dispatch）中 run 号不大于本次的个数，含本次、从 1 起；PR 与其它分支的运行不占号。
  - 非发行构建（PR 检查、手动构建其它分支、本地构建）写 `dev-<构建数>`，不伪造发行序号。
  - 查当前最大总序号（现值会过期，只记查法）：
    `gh api 'repos/Sumicya/Gself/actions/workflows/build.yml/runs?per_page=1' --jq '.workflow_runs[0].run_number'`
- **产物**：单一产物 `Gself-<版本>.apk`（debug 签名、可直装），由 CI 构建、经 Actions artifact 分发。**本仓库不发 Release、不打 tag，文档里不写 release 下载入口**；要么走 artifact，要么本地构建 + 本地签名（见 `docs/build-and-sign-termux.md`）。
- **构建 / 出包**：只在 CI 构建（Android SDK / JDK 不常备）；唯一工作流 `build.yml` 必须含 `actions/upload-artifact`（`name: Gself-<版本>`、`retention-days: 5`、`if-no-files-found: error`）。**CI 出包：有**（Actions artifact）；**CI 发版：未授权**（不发 Release、不打 tag）。本地构建产出非发行版本，不能当发行版引用。
- **下载与安装命令**：按 `GLOBAL.md`「下载与安装命令」一节，在每轮汇报里给可复制的命令块（run-id / artifact-id 在命令里自己算、产物按前缀过滤 `Gself-<五段版本>`、`su -c cp` 到 `/data/local/tmp` 再 `pm install`、末尾带本地清理）；本仓库不发 Release，不写 release 下载入口。
- **术语表**：`docs/glossary.md`；新增术语时同步。
- **文档同步点**：`module.prop` 的 `minApiVersion`、`scope.list` 的作用域、README 的功能清单与代码三方保持一致；平台要求（minSdk 36 / 只做新包）以 `app/build.gradle.kts` 为准，README 与 `module.prop` 同步。
- **内部包名**：历史可能仍为 `sumicya.fcmself`（改包名会破坏已装模块）；对外名一律 Gself。

规范指针：按 `GLOBAL.md` 最新版执行；本文件只保留本仓库专属条目，不复制全局规则。
