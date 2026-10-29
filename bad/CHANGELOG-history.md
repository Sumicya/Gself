# CHANGELOG 历史

按规范 S4 从 CHANGELOG.md 移出的较早条目，原文未改。

## 规范同步 —— 按 selfs 最新规范整理 README 与变更记录（2026-10-16）

- 依据 selfs `GLOBAL.md` 最新规范提交 `662e72d8256728d08f4cefde672b27bf58b0428b`（按「仓库类型」表属有产物仓）。
- README 末行补规范指针（S1）。
- CHANGELOG 只留最新 5 条（S4），较早 31 条原样移入 `bad/CHANGELOG-history.md`。
- 源码与文档里「GLOBAL.md 第十七版」的注释和术语表条目改为「项目约定」：规范已不带版次，「只做新包」本身是本项目的约定，内容不变；`build.yml` 头注释改指现行条文名。均为注释与文档，未改功能代码，未改清理脚本与工作流逻辑。
- 未验证：本次没有在 GitHub Actions 上运行；注释改动不影响编译，但没有本地构建。

## 规范同步 —— selfs 工具清单与外部项目审查规则（2026-10-15）

- 依据 selfs `GLOBAL.md` 最新规范提交 `d7700aee263300cbf2b66f27c03e790ab6949ec3`（[提交链接](https://github.com/Sumicya/selfs/commit/d7700aee263300cbf2b66f27c03e790ab6949ec3)）核对本仓适用范围。
- Gself 是 Android Hook 模块，DexSQL 仅在需要对 APK/DEX 做静态检索或排查入口、调用关系时作为可选分析工具；当前 CI 已有 R8 入口类检查，因此本次不新增依赖、不改构建或 Hook 代码，也不表示已安装或运行 DexSQL。
- 外部项目与 `https://t.me/xinyuanai` 只作为发现线索，不视为安全或质量背书。候选项目须独立核对上游、维护状态、许可证、权限及数据流向后，才可按具体需求引入；未验证内容不得记为已验证。
- Gself 没有单独复制全局规范，继续以 selfs 最新 `GLOBAL.md` 为准；本条记录项目侧适用性与本次不改代码的决定。
## 并入 main 并行线并给图标加 Monet 单色层（2026-10-15）

- 按主人指示将 main 的 8 个提交（所有者并行落地：删 AGENTS/spec-
  check/android.yml、自建 build/pr-check）整体并入本分支（合并提交
  9596c51，共同祖先 d2bf70a）。冲突只在 build.yml、pr-check.yml 两
  文件，按现行规范（selfs 12b0bff）取本侧：单脚本 cleanup.py、全仓
  artifact 最新 5、每工作流 run 硬上限 5；main 侧的每序列 KEEP 口径
  与内联清理被取代，其取号 TZ=UTC-8 与本侧 +08:00 等价。其余文件自
  动合并，无内容丢失。
- 图标加 Monet：ic_launcher 与 ic_launcher_round 的 adaptive-icon 各
  加一层 monochrome（复用现有 foreground 矢量），Android 13+ 主题图
  标随壁纸取色；原图标仍是模板机器人图，本次只加单色层不换图。

## Monocles Chat 重启不恢复：源码侧定位（2026-10-15）

- docs/verify-on-device.md 新增第 11 节：按 Experimental14-v2.3.1
  （f7803c2）源码核到候选机制——开机广播经 Compatibility.startService
  以 startForegroundService 拉起 XmppConnectionService，升前台时按
  权限选 MICROPHONE/CAMERA 类型，命中 Android 15「BOOT_COMPLETED 不
  得拉起受限前台服务类型」，异常被 startForegroundOrCatch 吞掉只记
  日志，服务随后被系统收掉。附设备判定命令与白名单绕法；设备日志
  签名未取，坐实程度标未验证。

## 全文逐条核对 —— 清理失败文案补端点（2026-10-15）

- 按现行全文（selfs main `12b0bff972ec08dd42d534a9c21a8c7c1ec5e3c4`，
  https://github.com/Sumicya/selfs/commit/12b0bff972ec08dd42d534a9c21a8c7c1ec5e3c4）
  逐条核本仓实现，唯一不符：清理失败的 403 warning 只写了对象与返回码，
  没写端点。`cleanup.py` 的 delete 与 cancel 两处文案补齐「端点
  `DELETE|POST repos/<repo>/actions/...`、返回码、对象」三项；非 403 的
  error 行同样带端点。build.yml 清理步名去掉已过时的 per-sequence 措辞。
- 其余各条核对通过（两工作流与触发、SEGMENTS/CLEANUP_APPLY 同值、
  fetch-depth: 0、提交数取号、五判据与硬上限、分支判据），证据见当轮
  汇报。

## 规范同步 —— 运行记录改硬上限、上限优先（selfs 4f748b5 / 12b0bff）

- selfs main 定版：每个工作流最多保留最新 5 条运行记录，含当天、不设
  例外，任何关联关系不豁免；运行记录上限优先于 artifact 保留——保留
  某 artifact 会让其工作流留第 6 条 run 的，删该 artifact 并同步校正
  留存清单。本仓上一版「承载保留产物不占上限」就此撤销（当时 Build
  序列 6 条里第 6 条即此例）。
- `cleanup.py` 重构判定顺序：先算每工作流 run 保留集（最新 5 条），
  再算 artifact 五判据与全仓 5 个，最后按 run 上限校正 artifact 留存；
  超限已完成 run 删除（逐个列出其产物，超限产物已先删），超限未完成
  先取消较旧者，本次运行不取消不删。
- 收尾自检改按 selfs 新清单（7 项）汇报。

## 规范同步 —— 清理上限重设（selfs f4091e3 / 49a99e3）

- selfs main「重设全局规范」（GLOBAL.md 9288B）。本仓清理两处改：
  artifact 上限由每工作流序列 5 个改为**全仓最新 5 个**（按 created_at
  从新到旧）；run 仍每工作流 5 条，处置改按新条文：承载保留产物的 run
  不删、超限已完成的删（其不在保留范围的产物已先删、删前逐个列出对象
  及产物）、超限未完成的先取消较旧者（新增 cancel 调用，失败同 delete
  分 403 与非 403 两路）、本次运行不取消不删。
- `cleanup.py` 相应重写数量与 run 两段，启动行与摘要同步。判据其余四条
  （命名口径、来源、重复当日序号、号源口径）不变。

## 规范同步 —— 当日豁免撤销、每序列各 5 个（selfs 790bf82 / eb676eb）

- selfs main 新增两提交（`790bf82` 撤当日豁免、`eb676eb` 重写四原则），
  GLOBAL.md 7364B。本仓唯一实现差异：数量判据的当日豁免。
- `cleanup.py`：artifact 与 run 的数量判据不再分当日与否，每条工作流
  序列保留最近 5 个（五条判据均不豁免）；run 的「当日豁免」分支与话术
  删除，承载保留产物、未完成、当前运行三项保护不变；无用函数 `d8`
  删除。号源已是提交数，删当日 run 不再抽计数基准，豁免失去依据。
- 本次推送后清理按新规收口：artifact 由 6 件压到 5 件（删最旧的
  `Gself-26.10.15.1.325` 一件，名单以运行实测为准）。

## AGENTS.md 删除（2026-10-15，主人提交 5afaa06）

- 主人在本会话分支提交 `5afaa06` 删除 AGENTS.md（与 selfs `716e864`
  删自家 AGENTS.md 同一决定）。本文件与各工作流不再指向 AGENTS.md；
  项目口径以 selfs GLOBAL.md 与本 CHANGELOG 的真数字记录为准。
- 上条「规范载体变更」里记的 AGENTS 依据行同步已被该删除覆盖。

## 规范载体变更 —— selfs PR #6 已合，指针回默认分支（2026-10-15）

- 取证：selfs PR #6 已合入 main（merge `aedf88d`），会话分支
  `arena/2d001a47-selfs` 已删；main 的 GLOBAL.md（11654B）与提交
  `2f8c419` 版逐字一致——条文无新增，本仓实现无需改。
- AGENTS 依据行同步：原指向已删会话分支，改为记「已随 PR #6 合入
  selfs 默认分支 main」。selfs 同轮删了它自己的 AGENTS.md，那是无产物
  仓的本地决定；本仓 AGENTS.md 记项目事实与依据行，按规范保留。
- 同期事实（GitHub 侧，取值 2026-10-15T01:24Z 起）：默认分支 main 被
  直接推入 6 个提交（head `00f685c`），文件树已含 build.yml 与
  pr-check.yml，并在 main 上出包 `Gself-26.10.15.4.299`（run 38664400309）、
  `Gself-26.10.15.5.300`（run 38665267835）；另有旧 Android CI 口径产物
  `Gself-26.10.15.1.2`（run 38664141929，main，sha 5d515e1）。本会话分支
  与 main 为 diverged（ahead 33、behind 6），未被继承，按判据保留；
  PR #14 仍 OPEN。

## 规范同步 —— 五判据定名、依据改引 2f8c419（selfs 129a292 / 2f8c419）

- 判据正式定为五条：命名口径、来源、重复当日序号、号源口径、数量。
  本仓清理实现上轮已含号源核验，本轮把 AGENTS、工作流步骤名、脚本
  docstring 与摘要标题的「四判据」统一改「五判据」；`cleanup.py` 启动行
  补打印号源核验的两条 git 命令原文。
- AGENTS 依据行同步到 selfs 提交 `2f8c419f63ca6d2e540184b4f29161ff7e8c293b`；
  「号源分界点」「版本分界点」两个标题改成带日期的事实记录（规范禁以
  「基线」「分界点」代称数字，记录里的 run 号、artifact 名与 id 不变）。

## 规范同步 —— 号源改 git 提交数、工作流统一改名（selfs 240a214 / c5ac12d）

> 号源挂在 run 上时 PR 运行吃号、改文件名号从 1 回头、双触发与重试靠
> 条文层层堵；号源挂到提交上一起消失，同一提交重跑同号（天然幂等）。

- 取号改 git 提交数：总序号 `git rev-list --count HEAD`、当日序号
  `git rev-list --count --since=<当日0点 UTC+8> HEAD`；`build.yml` 的
  checkout 加 `fetch-depth: 0`，取号启动行打印 head sha、两个数与命令
  原文，取不到即失败退出，不再退化四段；重试复用逻辑删除（同提交同号）。
  `-Pfcmself.buildNumber` 改传 VERSION_CODE，全仓不再用 run_number。
- 工作流统一改名：`android.yml` → `build.yml`、`name: Android CI` → `Build`
  （属基线跳变，AGENTS 记一行事实）；`pr-check.yml` 的 `pull_request`
  去掉 branches 过滤；其 cleanup job 补 `contents: read` 与
  `fetch-depth: 0`；build.yml 顶部补 `env: CLEANUP_APPLY: 'true'`。
- `cleanup.py` 口径判据加号源核验：名义序号与来源 run 的 head sha 提交数
  不符的按旧口径删；无一合规时暂留最新一个旧口径产物，等新口径包出成
  再删（不许出现无包可装的状态）。重复当日序号改按 `run_started_at`
  删旧留新（不按总序号大小）；启动行补打印 `CLEANUP_APPLY`；跑完打印
  各序列剩余数。本次推送先出新口径包，旧口径 `.3.149`、`.2.148` 在
  新包出成后由清理删，真数字记入 AGENTS。
- 实测（2026-10-15T00:16Z）：Build run 38658709163 出新口径首包
  `Gself-26.10.15.1.325`（id 11937379087；head `ab96706`，API 核
  `commits?sha` 总数 325、当日 1，与产物名逐字一致）；清理已删旧口径
  `.3.149`、`.2.148`，artifact 只剩新口径首包，无无包窗口。

## 规范同步 —— 出包不限分支、清理失败分级（selfs a8787a8 / 358120a）

> 撤回口径：会话分支不出包、默认分支 ref 守卫一类禁令砍掉了唯一的验证
> 手段（PR 校验不上传产物，拿不出可装可跑的包），本轮按新口径改。

- `android.yml` 去掉 `push` 的 `branches: [ "main" ]` 限定：会话分支推送
  同样触发出包用于验证，与默认分支共用同一命名口径、同一条 `run_number`
  序列；仍不接 `pull_request`，不加 `github.ref` 守卫。当日序号计数把
  会话分支运行一并计入（脚本原已按事件过滤、不分分支，无需改）。
- `cleanup.py` 删除失败分级：403 权限类 `::warning::` 降级不红 job；403
  以外 `::error::` 点名（对象与返回码）并让 job 当轮红。AGENTS 清理节与
  工作流口径同步（PR 校验原写「不清理」一并修正）。
- AGENTS 头部去掉 head 短哈希戳，改为依据 selfs 提交
  `a8787a8b4b6becf2be142732593a105a0a2672c2`（附链接）。

## 真删首次落地 —— PR 校验 cleanup job 删除两件存量（2026-10-14）

- [PR check run 38564494224](https://github.com/Sumicya/Gself/actions/runs/38564494224)
  的 cleanup job（`actions: write` + `CLEANUP_APPLY: 'true'`）删除
  `Gself-26.10.14.4.145`（来源 pull_request）与 `Gself-26.10.14.2.147`
  （重复当日序号）；验收命令实测 artifact 只剩 `Gself-26.10.14.2.148`。
- 首次真删前一跑（run 38563957488）cleanup 红：`delete()` 定义被同文件
  并行编辑丢掉，NameError。修复提交 b8b3a57 补回定义并本地复现降级路径
  （沙箱 403 → `::warning::` 降级、退出码 0）后推送，次跑即删成。

## 规范同步 —— 真删改挂 PR 校验工作流（selfs b0594d3：清理不再永久挂起）

> 根因：真删原先只挂出包工作流，而出包只在默认分支 push 与 workflow_dispatch
> 触发，会话两侧都够不着——「待出包工作流首次运行」在会话侧永远不会来。

- `pr-check.yml` 的清理 job 由只读预演改为真删：`cleanup-preview`→`cleanup`，
  `actions: read`→`actions: write`，`CLEANUP_APPLY: 'false'`→`'true'`，步骤名
  改「按四条判据清理（真删）」；与 android.yml 的 build job 同挂
  `concurrency` group `gself-cleanup` 串行。PR 校验工作流文件头注释同步
  （原写「不清理」已与实现矛盾）。
- `cleanup.py` 的 DELETE 改降级式：失败（fork PR 只读令牌 403 等）打
  `::warning::` 本次降级为预演并继续，不红 job、不再 SystemExit；只读预演
  降为备用形态，预演名单不作清理已做的证据。
- 存量待删状态改记「待下一次 PR 校验运行」：`.4.145`（来源）、`.2.147`
  （重复当日序号）。本提交的 PR 校验运行即首次真删，验收看 artifact 名单。

## 规范同步 —— 启动参数打印与号源已知风险（selfs 4e9effb 复查清单）

- `cleanup.py` 启动行补 `PREFIX=Gself-`：预演日志可独立核对脚本读到的参数
  （SEGMENTS、CLEANUP_APPLY、KEEP、日期、前缀）。
- AGENTS.md 号源分界点后加「已知风险（号源）」一行：默认分支 android.yml
  仍含 pull_request 触发，旧分支 arena/01a10a49-gself 若新开 PR 会吃一个
  总序号；风险存续到本次交付进入默认分支为止，期间不向非本会话的开放
  PR 分支推送、不做关闭 PR 的动作。

## 存量补证 —— 分界点删除前名单（selfs 补洞：分界点只引可核对象）

- AGENTS.md 号源分界点补一行：删除前名单原文（取值 2026-10-14T03:17Z）四条
  产物各注来源 run id——已删的 `.1.144`、`.3.144` 不再光引名字，改为可核的
  run 号与删除前名单；现注册表只剩 android.yml 与 pr-check.yml 两条序列。

## 规范同步 —— selfs 条文补洞（清理单脚本预演、重复当日序号判据、run 承载耦合）

> 条文实测撞墙点：清理只挂出包工作流，而出包只在默认分支 push 与
> workflow_dispatch 触发、沙箱令牌 actions 写 403——清理写好了跑不到真删。
> 本轮按补洞条文落地到本仓。

### 改的

- 清理逻辑抽成单脚本 `.github/scripts/cleanup.py`，`CLEANUP_APPLY` 开关：
  出包工作流 `'true'` 真删，`pr-check.yml` 新增 `cleanup-preview` job
  （`actions: read` + `'false'`）只打印将删名单与判据理由。真删仍只一处
  实现，逻辑不许两份；会话侧真机证据取预演日志。
- 新增第四判据「重复当日序号」：同日同序列相同当日序号只留总序号最大者。
  本地预演干跑（只读）结果：`Gself-26.10.14.4.145` 命中来源判据、
  `Gself-26.10.14.2.147` 命中重复判据、`.2.148` 保留，run 0 条将删——
  两件存量违规均记「待出包工作流首次运行」由清理 job 删除。
- run 上限与 artifact 承载关系写死：承载保留产物的 run 不删、不计入上限；
  判定顺序固定 artifact（口径→来源→重复→数量）先、run（承载→数量）后；
  排序按来源 run 的 `run_started_at`，id 只作 tiebreak；日志逐对象打印
  判据结论与豁免原因。
- AGENTS.md 版本节补分界点一行：2026-10-14 当日序号 2 曾重复，分界点之前
  的当日序号不可信，`.2.147` 按重复判据删。

# 更新日志

> 本仓库由 fcmfix 迁移而来。0.9.0 及以前压缩为要点，完整历史见 `git log`。

## 规范同步 —— selfs 规范更新（清理三判据、责任固定、交接三件）

> 实测依据：沙箱令牌在**本仓**删 artifact、触发 rerun 同样 403——能力按权限类
> 分（actions 写无、contents/refs 写有），不按仓库分。本轮按新条文落地到本仓。

### 改的

- 清理 job 改三判据：① 口径——版本段数 ≠ 工作流顶部 `SEGMENTS` 声明值（新增
  `env: SEGMENTS: "5"`）的删；② 来源——`workflow_run` 事件为 `pull_request`
  的 artifact 一律删；③ 数量——当日之外每条工作流序列 artifact/run 各留 5。
  口径与来源不豁免当日；数量判据当日不删（防当日序号计数基准被抽走）。
  本地干跑验证：存量 `Gself-26.10.14.4.145`（PR 运行来源）命中来源判据，
  `.2.147`/`.2.148` 保留，分类无误（实删待下个出包运行）。
- runs 清理改按工作流分组计数（Android CI 与 PR check 各一条序列）；清理仍
  只挂出包工作流，PR 校验的超量 run 由它一并删。
- 当日序号排序改按 `run_started_at` 升序取位次（原按 run id 近似）。
- 文档按禁句式核对：AGENTS.md、CHANGELOG、本文件、工作流注释中责任外推类
  责任外推句式 grep 为 0 处；阻塞改按三件报（端点与返回码、完整命令、验证
  命令与预期输出）。

## 规范同步补正 —— 当日 run 清理保护（防当日序号被清理吃掉）

> 实测：`Gself-26.10.14.2.148` 当日序号为 2 而非 3——当日首个出包运行
> （run 143，push main）的记录已在上一次出包运行的 runs 清理中作为最旧记录
> 被删，后续运行数当日出包运行少计一位，与 `.2.147` 当日序号重复。

### 改的

- runs 清理加当日保护：当日（UTC+8）创建的 run 不删，留到明日再收；当日序号
  依赖当日运行记录完整，盘中 run 数可超 5，次日出包运行压回 5。
- AGENTS.md 分界点补记 `.2.147` 与 `.2.148` 当日序号重复的成因；Build 旧口径
  产物 `.1.144` 已由主人删除，分界点转为历史记录。

## 规范同步 —— selfs 规范更新（拆分出包与 PR 校验工作流）

> 实测根因：`run_number` 在运行创建时按工作流分配，步骤级 `if` 只挡上传、
> 挡不住 PR 运行取号；且两个注册工作流各有一条 run_number 序列，本仓已出现
> 总序同为 144 的两次不同运行。本轮按新规拆分，模块代码零改动。

### 改的

- `android.yml` 改为纯出包工作流：触发只留 push(main) 与 `workflow_dispatch`，
  删掉 `pull_request` 触发、版本步内的 PR 占位回落与三处事件 `if`（工作流级
  隔离后不再需要）；当日序号改数本工作流当日运行（全部为出包运行）。
- 新增 `pr-check.yml`：只接 `pull_request`，只跑测试、编译、R8 入口校验；
  独立 run_number 序列永不参与版本号，不取号、不上传、不清理。
- AGENTS.md 记双工作流口径与号源分界点：总序 144 的两个产物中只有
  `Gself-26.10.14.3.144`（Android CI）是正规出包；Build 的
  `Gself-26.10.14.1.144` 为旧口径产物待删（沙箱令牌删产物 403，命令已交接）。

## 规范同步 —— selfs 规范更新（堵版本号刷号漏洞）

> 背景实测：双触发（push + pull_request）让同一提交吃两个总序号，Gself 同仓
> 并存总序 1 与 144/145 两套号。本轮按新规堵漏，模块代码零改动。

### 改的

- 出包运行只认 push（main）与 `workflow_dispatch`：`pull_request` 运行只跑校验
  （测试、编译、R8 入口校验），版本步内回落占位版本 `0.0.0.0.pr`，不取号；
  Rename、Upload、Cleanup 三步加 `if: github.event_name != 'pull_request'`。
- 当日序号改为当日该仓出包运行的序号（android.yml 的 push / workflow_dispatch
  按开始时间递增取位次，PR 不计入）；禁止按 head 提交位次取数（并发竞态，
  实测当日序号重复、乱序）。取不到仍退化四段并 `::warning` 写明。
- 号源单一化：本仓出包只走 android.yml；遗留 Build 工作流（仅存在于
  `arena/01a10a49-gself` 分支）的注册禁用被接口 403 挡（沙箱令牌无 Actions
  管理权），处置命令已在当轮汇报交接。
- 总序号维持 run_number 原值（上一轮已改，本轮核对无残留减基线表述）。

## 规范同步 —— selfs 规范更新（取消版本号与同步戳）

> 规范按主人指示取自 Sumicya/selfs 会话分支 `arena/2d001a47-selfs`（PR #6，
> head a9fc529，尚未合入默认分支）。规范本体取消版本号，本仓文档同步去掉
> 版本号与同步戳。模块代码零改动。

### 改的

- 总序号改为本次 run 的 `run_number` 原值：本仓 workflow 未重建、未记录基线，
  旧口径「run_number − 3」停用；总序由 139 跳至下一 run 的 run_number 原值，仍递增。
- 当日序号取不到时退化四段 `yy.m.d.总序号`，CI 日志以 `::warning` 写明原因；
  `created_at`、`run_number` 缺失仍立即停包。
- `AGENTS.md` 整份精简：只记项目事实、必要限制与规范指针，不复制全局规则、
  不带版本号或同步戳；计数与清理两节只留本仓实现口径。
- 分支删除唯一判据改为 compare `ahead_by=0`：上轮按旧规则删掉的
  `arena/01a10a49-gself`（PR #13 已关闭未合并）已以其头提交 `958ed97` 恢复，
  compare diverged、ahead_by=25，按新规保留并报主人。
- 核对无误、无需改动：action 钉主版本（`@v7`/`@v6`）、具体镜像
  `ubuntu-24.04`、无 `@main`/`@latest` 浮动标签；本仓无孤立 tag、无 Release。

## 规范同步 —— GLOBAL.md 第0版修订

> 对齐 Sumicya/selfs 会话分支 `arena/7a7f3552-selfs`@1984b03a 的第0版修订
> （2026-10-13，尚未合入 selfs 默认分支，按主人指示先行同步）。模块代码零改动。

### 改的

- 计数改锚（修订核心）：当日序号 = 本次 run 的 head 提交在当日（UTC+8）提交
  序列中的位次（本仓实现：数 head 祖先链当日提交数，含 head）；总序号 = 本次
  run 的 `run_number` − 基线 **3**（定死：run 38407093039 的 run_number 140 ↔
  总序 137）。旧锚（历史成功数、现存 artifact 最大号）停用；run 记录删除后
  run_number 不复用，删 run 不再影响序号。
- runs 清理随之去掉「今日 run 延到明日再收」的保护（计数已不依赖 run 历史），
  严格保留最近 5 条；当前 run 与未完成 run 不删。
- 新增分支保留 **0 个**：PR 合并或关闭后删对应会话分支，分支清理只由会话
  执行、不进 CI。本轮已删 `arena/01a10a49-gself`（PR #13 关闭后收尾）。
- `AGENTS.md` 版本戳更新为第0版修订，并注明规范来源分支。

## 规范同步 —— GLOBAL.md 第0版

> 对齐 Sumicya/selfs/GLOBAL.md 第0版（2026-10-13，全文重写为简版）。模块代码零改动。

### 改的

- 新增运行记录保留：workflow runs 只留最近 **5 条**。清理在 artifact 清理之后
  执行，只删已完成、非今日（UTC+8）的旧 run；今日 run 留到明日再收（防当日序号
  位次被删坏），当前 run 不删；删前打印保留 + 删除清单，失败只报错。
- 总序号改锚：运行记录只留 5 条后，历史成功 run 数不可再作计数锚。总序号 =
  现存 artifact 最大总序 + 1，下限基数 136（切换时最新总序，见 AGENTS.md 衔接）；
  失败运行不上传、不占总序；attempt > 1 复用首定版本不变。当日序号位次法不变。
- `AGENTS.md` 版本戳更新为第0版；清理权限表述同步（`actions: write` 只删本项目
  artifact 与 run，禁 `contents: write`）。

### 核对结果（无需改动项）

- 五段格式、文件名 `<项目名>-<五段版本>`、取数锚定本次 run、缺失即失败、
  Release 1 个 / artifact 5 个、CI 出包 ≠ 发版、runner 具体镜像：均与现行实现一致。

## 规范同步 —— GLOBAL.md 第二十五版修订

> 对齐 Sumicya/selfs/GLOBAL.md 第二十五版修订（2026-10-13）。修订点只有清理权限
> 一处，其余条款经核对不变。模块代码零改动。

### 改的

- 清理权限按修订口径重做：取消独立 cleanup job 和「仅默认分支 push 才清理」；
  清理并入构建 job，在本 run 上传成功后立即执行（任意触发事件，含 PR）。权限只给
  最小 `actions: write`（仅删本项目 artifact），禁止 `contents: write`；构建与检查
  保持 `contents: read`。
- 清理脚本写死归属核验：名称前缀仅作初筛，逐个 artifact 追其 `workflow_run` 的
  workflow 路径（须为 `.github/workflows/android.yml`）确认属于本项目；未核验对象
  不计入保留窗口、不删，只列出报告。删前打印完整保留 + 删除清单；删除失败只报错，
  不回滚新产物、不删保留窗口内对象，清理后复核已核验数量 ≤ 5。
- `AGENTS.md` 版本戳更新为第二十五版修订，数量清理与 CI 权限原则两节同步新口径。

### 影响

- 自本次提交的 PR 运行起，清理在分支上即时生效：新产物上传后总数超 5 时，当次运行
  即删最旧的已核验 artifact，不再等合并后 main 运行。
- 实测记录：首跑（run 38402503506）清理步骤失败——新脚本 DELETE 调用漏了一个
  `]`，Python 编译期即挂，清理实际未执行（产物涨到 6 个）；补诊断定位、修复后
  run 38403199408 成功，清理打印「保留 5 删除 4 未核验 0」，台账压回 5 个。
  诊断脚手架（分阶段标记）已在验证后撤除，只保留 DELETE 失败的 `::error::` 输出。

## 规范同步 —— GLOBAL.md 第二十五版

> 对齐 Sumicya/selfs/GLOBAL.md 第二十五版（2026-10-12）。模块代码零改动。版本计数
> 口径经核对不变：当日序号 = 当日全部已触发运行（含失败）按触发先后排号（位次法，
> 并发不抢号）；总序号 = 历史成功出包构建数 + 衔接常数 22；attempt > 1 复用首定版本。

### 改的

- `android.yml`、`AGENTS.md`：清理表述同步第二十五版——「清理默认启用，不再逐仓
  询问」「已有积压属于落实范围，不能只写代码留待以后」；PR 出包不是放弃数量清理
  的理由，也不是给 PR 写权限的理由；积压当场清，main 运行只做常态数量清理。
- 本条目与第二十四版条目移至 Gboard 条目之上，恢复新在前的时序。

### 第二十五版核对结果（无需改动项）

- `runs-on: ubuntu-24.04`（未用 ubuntu-latest）；`actions/checkout@v7`、
  `actions/setup-java@v6`、`actions/upload-artifact@v7`，均非弃用档。
- `retention-days: 5`（时间兜底）、`if-no-files-found: error`、
  `overwrite: true`（仅重试复用同版本名时生效，不覆盖不同内容）。

### 当场清账（2026-10-13，不等合并）

- 关闭 PR #13（另一会话的第十五版旧口径同步，与本 PR 改同一批文件，已被本 PR 取代）。
- 冗余工作流处置：`spec-check` 随本 PR 合并删除文件即消亡；#13 分支遗留的 `Build`
  随该分支处置消亡。当场禁用尝试对接口 403（沙箱 token 无工作流管理权限），如实
  报告；可行路径：仓库 Actions 页面手动禁用。
- 改名前遗留 artifact（空名 `Gself-`、`Gself-dev-26.10.6.5.127`、
  `Gself-dev-26.10.6.1.128`）经核对已不在仓库；现存 4 个 artifact 经 workflow_run
  归属核验均属本项目，未超保留上限 5；仓库无 Release，无清理对象。

## 规范同步 —— GLOBAL.md 第二十四版

> 对齐 Sumicya/selfs/GLOBAL.md 第二十四版（2026-10-10）。模块代码零改动。

### 换上的

- 版本取数改按第二十四版通用步骤：输入锚定本次运行的 created_at / run_id /
  run_attempt；当日序号改为「本次运行在当日全部已触发运行（含失败）中的位次」
  （按 run id 排位，并发不抢号）；总序号只计历史成功出包构建数，失败运行不增加总序号。
- 衔接规则：总序号 = 历史成功数 + 22（切换前历史最大总序号 131 − 成功数 110 + 1），
  自 2026-10-10 本次提交起生效，保证计数不回退。
- run_attempt > 1 时复用首次 attempt 已确定的版本（从本运行产物名取得），不重新加号。
- 清理 job 写权限收窄到默认分支构建运行（`contents: write` + `actions: write`），
  同仓 PR 不再执行清理，积压由合入后的 main 运行收。
- AGENTS.md 版本戳更新为第二十四版。

## Gboard 剪贴板：过期过滤改「无下限」

> 消除代码与文档的口径不一致：代码一直是「实际不限时长」，README 却写「3 天」。

### 换上的

- `Gboard.kt`：`timestamp >= ?` 的实参从「当前时间减 100 年」（算出来已是负 epoch 的魔数）
  改为 `Long.MIN_VALUE` 哨兵值，行为不变（全部保留）。改法不变：仍替换实参，不改写
  selection 字符串（不动占位符结构，最小侵入）。
- README：剪贴板过期描述由「3 天」更正为「实际不限时长」。

## 规范同步 —— GLOBAL.md 第二十二版

> 对齐 Sumicya/selfs/GLOBAL.md 第二十二版（2026-10-06）。模块代码零改动，只动
> `AGENTS.md` 与 CI。

### 删掉的

- `spec-check.yml` 整条工作流：第二十二版简单性要求 CI 围绕实际构建与清理安排，
  不设规范检查工作流，规范由 agent 在会话中核对。AGENTS.md 第十五版「应有只读规范
  检查」的条目与之冲突，按全局规范优先处理。

### 换上的

- `AGENTS.md` 按第二十二版格式重写：只记项目事实、版本计数口径、数量清理与必要限制，
  版本戳更新为「上次同步 = 第二十二版」。
- runner `ubuntu-latest` → `ubuntu-24.04`（第二十二版：用具体、受支持的镜像）。
- 版本计数统一五段 `yy.m.d.当日序号.总序号`：push 与 PR 同一序号池（此前 PR 固定
  `yy.m.d.0.0`、versionCode=1）；序号来自 android.yml 的真实运行记录并锚定本次运行，
  失败运行同样计数，同次重试复用版本。取数失败或校验不过立即停止出包与上传
  （此前有版本为空的运行照样上传了 `Gself-` 空名产物）。
- `upload-artifact` 补 `if-no-files-found: error`。

### 新增

- `cleanup` job：上传成功后保留最近 **5 个** artifact（跨分支、跨触发事件，含改名前
  遗留名），完整分页、按创建时间排序，删除前在 job summary 列出保留与删除清单；
  最小写权限 `actions: write`；fork PR 无写权限不执行，由后续 push 运行补清。
  当前 23 个积压产物会在下一次出包运行收到尾。

## Gself —— 三合一 + 换 GPL-3.0

> 把三个项目合成一个 libxposed 模块：fcmself（推送/通知/ColorOS，system_server）、
> GooglePasswordManagerUnlock（通行密钥解限，GMS 进程）、GboardHook（剪贴板，Gboard 进程）。
> 对外改名 Gself（applicationId `sumicya.gself`），内部包名与入口类 `sumicya.fcmself.XposedMain`
> 不变，CI 的 R8 入口校验照旧。整仓改用 **GPL-3.0-or-later**（并入的 GboardHook 是 GPL-3.0，合并作品须同证）。

### 新增

- `onPackageLoaded` 按包名分发：`com.google.android.gms` → 通行密钥解限；Gboard → 剪贴板。
- `Gms.kt` 移植通行密钥解限：DexKit 稳定字符串定位来源解析器，**唯一候选才 Hook**（保留其安全边界），
  来源构造函数记录 origin、解析器据其改写。加 `org.luckypray:dexkit:2.2.0` 依赖。
- `Gboard.kt` 移植剪贴板：改写 `ClipboardContentProvider#query` 的时间下限与 `limit 5`（默认 10 条 / 3 天），
  并写死 `enable_clipboard_entity_extraction` / `enable_clipboard_query_refactoring` 两个开关。
- `scope.list` 加 GMS 与 Gboard 两个包；`module.prop` 注释同步。

### 真机验证（ColorOS / OnePlus，Android 16 / API 36）

- 三组 Hook 全部确认挂载：`wake: <pkg>` + `unfreeze 可用（4 参签名）`（推送）、
  `keep notification: …`（通知）、`通行密钥解限 Hook 已安装`（GMS）、`Gboard 剪贴板 Hook 已安装`（Gboard）。
- 编译仍由 CI 校验（本环境无 JDK/SDK）。

## 26.10.1 —— 按真机证据再砍一半

> 1.0.0 的 553 行 → **303 行主代码**（4 文件 1 包不变）。依据是真机两组事实：
> `service list` 里没有 `powerkeeper` / `millet` / `smartpower`（MIUI 那套反射目标从未命中），
> `dumpsys deviceidle whitelist` 里 `com.google.android.gms` 已在 `system-excidle`
> （电池白名单原生已覆盖，不需要 hook）。规范仍是 ponytail ultra。

### 删掉的

- **开机闸门整组**（`bootGate` / `bootLater` / `finishBooting` hook / `AtomicBoolean` / 后台线程 /
  `booted` 标志，约 30 行）：代价是开机头 60 秒不介入，而刚开机正是积压推送集中到达的时候。
  现在载入即介入
- **MIUI / HyperOS / PowerKeeper 全部挂载点**（约 110 行）：`miuiFixes`（MilletConfig /
  `gms_control` / MilletPolicy 名单）、MIUI 本地通知限制、`shouldInterceptService`、
  `ALLOW_AUTOSTART` / `NO_INTERCEPT` 两张表与 `gate()` 循环。这台设备上全是 `hook skip`
- **实参启发式扫描**：`Push.intentIn` / `intentFieldOf` / `actionIn` / `packageIn` 与包名正则
  （约 40 行）。`shouldProxy` 改成一律 `NOT_INCLUDE` 之后，不再需要从散字符串实参里认 action 和包名
- **bypass 日志全局节流**（`lastBypassLog` / `suppressedBypass` / `BYPASS_LOG_INTERVAL`，约 26 行）
- **`FIXES` 清单表**：只剩三组，直接三次 `install()` 调用
- **日志噪声**：真机 3 小时刷出几百行，把粘贴都挤爆了。`unfreeze` 改为只记「找到可用签名」那一次，
  `No Intercept` 与 `shouldProxy bypass` 两条逐次日志删掉（信息与 `wake:` 重复），
  `Add FLAG_INCLUDE_STOPPED_PACKAGES: <pkg>` 缩成 `wake: <pkg>`

### 换上的

- **判据放宽成子串**：`c2dm` / `firebase` 两个词取代五个 action 的全等表。更短，且 GMS / ROM
  改 action 名不再静默漏掉整条推送（`PushTest` 钉住这一点）
- **版本号改成 `26.10.1.<构建数>`，`versionCode` = 构建数**：构建数 = CI 的 `github.run_number`
  （第几次 CI 构建），由 workflow 用 `-Pfcmself.buildNumber` 注入，`build.gradle.kts` 拿它同时当
  versionCode 与版本名第四段；本地构建回落到 `gradle.properties` 的默认值。这样 vc 跟着 CI 单调
  递增、不会漂，也天然大于设备上旧版的 versionCode，普通升级即可。CI 不再注入「日期_短SHA」，
  并删掉 workflow 里设了没人用的 `VERSION_CODE` 一步

### 刻意砍掉的角（新增）

| 砍掉的角 | 位置 | 一句话代价 |
| --- | --- | --- |
| `shouldProxy` 一律 `NOT_INCLUDE` | `Fixes.kt` `oplusFixes` | 所有广播都不再走 ColorOS 代理合批，功耗回到系统自己身上；待机耗电明显上升就把判据加回来 |

沿用 1.0.0 的四条（不改 appOp、不补调 `checkAbnormalBroadcastInQueueLocked`、取消原因按值认、
`intent` 字段不缓存）——其中 `intent` 字段那条随 `intentFieldOf` 一起消失了。

## 1.0.0 —— 推倒重写

> 上一轮（0.9.0）的产物整体删除：`git rm` 掉 2501 行 Kotlin（含 559 行测试）后从零重写。
> 新实现 **548 行主代码 + 56 行测试，4 个文件，1 个包**（原来 17 个文件、4 层包）。
> 规范：[ponytail](https://github.com/DietrichGebert/ponytail) ultra——
> 最好的代码是没写的代码，删除优先于新增，刻意砍掉的角用 `ponytail:` 注释标明代价与加回条件。
> 本轮允许行为调整，不保证与 0.9.0 逐点一致。

### 删掉的（2501 → 548 行）

- **`ReconnectManagerFix`（275 行）**：GMS 心跳/重连倒计时修复，全仓库最复杂的一块
  （自动发现 timer 类、反射改倒计时、诊断日志转发），也是唯一跑在 GMS 进程里的 Hook 组。
  删掉后模块**只 hook system_server**，作用域从 `system` + `com.google.android.gms` 收窄为 `system`
- **`hook/Reflect`(243) + `Signatures`(69) + `MethodArgs`(59) + `Hooks`(82)** 四层反射基建
  → 一个 `Hook.kt`（80 行）：`classOf` / `classIfExists` / `find` / `ctor` / `field` / `hook` / `install`
- **before/after 两套拦截原语** → 一个 `hook(target) { chain -> … }`：要不要 `proceed()`、
  跑完改不改返回值，全由 lambda 自己决定，`AfterHook` / `hookAfter` / `hookMethodAfter` 全删
- **`ProcessEnv`(240)**：静态实例表、`onReady` 回调排队与去重、两次握手、
  `ContextWrapper.attachBaseContext` 守株待兔、USER_UNLOCKED 接收器、自卸载监听、
  旧通知渠道清理、诊断日志广播 sink → 一个 `@Volatile var booted` + 一个 `fun trace()`。
  system Context 改为用时 `ActivityThread.currentActivityThread().getSystemContext()` 懒取（零 Hook）
- **`FcmselfModule` 基类 + `mods/PushArgs`** → `Hook.install("组名") { … }` 与 `Push.intentIn`
- **`core/hook/mods` 三层包** → 单包 `sumicya.fcmself`，模块内零 import
- **按 SDK_INT 铺开的参数下标候选表**（`amsCandidates`、`resolveBroadcastArgs`、
  `resolveNotificationArgs`、参数名兜底）→ 参数按类型/按值在实参里认，见下
- **559 行测试 → 56 行**：`ReflectTest` / `MethodArgsTest` / `SignaturesTest` 随被测代码一起删，
  只留介入判据一份 `PushTest`（判据是全模块单一支点，判错了就是全放行或全不放行）
- 构建不再需要 `-parameters` / `javaParameters`（参数名兜底已删）

> CI workflow 两处收尾（改 `.github/workflows/` 要 `workflows` 权限，GitHub App 推不动，
> v1.0.0 之后由人工落地）：① 删掉「构建失败往 PR 贴日志评论」那一步与随之而来的
> `pull-requests: write` 权限（日志已经在 job summary 里，不值得为它扩大仓库权限）；
> ② 步骤注释里的「参数下标解析的单元测试」改为介入判据（`PushTest`）。

### 换上的

- **表驱动**：七个自启动/拦截闸门其实是两种形状（返回 true 放行 / 返回 false 不拦截），
  写成两张 `List<Pair<类名, 方法名>>` 加一个 `gate()` 循环，不再是七段近似重复的代码
- **入口清单化**：`XposedMain.FIXES: List<Pair<String, Fixes>>`，新增一组 Hook = 加一行
- **开机闸门 fail-open**：`AMS.finishBooting` 挂不上时退化为「载入后 60 秒」，
  不会因为找不到一个方法就让整个模块永久不生效（旧版没有这条退路）
- **参数按类型/按值识别**，与参数位置彻底解耦：
  - Intent：签名里只有一个，`args.filterIsInstance<Intent>().firstOrNull()`
  - 目标包名 / 推送 action：`Push.packageIn`（包名形态正则）/ `Push.actionIn`（推送族匹配），
    取代 `shouldProxy` 的 `args[3] / args[5] / args[6]` 与 MIUI 本地通知的 `args[3]`
  - 通知取消原因：按值认（取值落在 8 / 10020 / 10021 的那个 int）
- **日志**：`Keep notification`、七个自启动闸门现在**成功时也打日志**
  （0.9.0 的验证清单里这两处标着「无法直接观测」）
- 版本 0.9.0 → 1.0.0，versionCode 56 → 60，入口类仍是 `XposedMain`（CI 的 dex 校验查的就是这个字符串，改名要动 workflow 权限，不值得）

### 刻意砍掉的角

代价与加回条件都写在代码里的 `ponytail:` 注释旁边，这里只列清单，不复述理由
（复述就是把复杂度当散文再塞回来一遍）：

| 砍掉的角 | 位置 | 一句话代价 |
| --- | --- | --- |
| 不再改写 appOp | `Fixes.kt` `wakeStoppedApps` | 「intent 之后第一个 int」实际命中 `requestCode@7` 而非 `appOp@13`，会静默改错参数；真机已验通——通知照弹、force-stop 后推送照样唤醒 |
| 不再补调 `checkAbnormalBroadcastInQueueLocked` | `Fixes.kt` `autoStartFixes` | MIUI 少一条「异常广播」留痕；换来三个点同形状、能进同一张表 |
| 通知取消原因按值认 | `Fixes.kt` `notificationFixes` | 别的 int 参数恰好等于 8 时误拦一次取消（只影响「通知没被清掉」） |
| bypass 日志全局节流 | `Fixes.kt` `logBypass` | 60 秒内第二个应用的 bypass 日志被吞掉，只留计数 |
| `intent` 字段不做反射缓存 | `Push.kt` `intentFieldOf` | 没有该字段的宿主类每次调用付一次 `NoSuchFieldException` |
| **不再动 `whiteApps`** | `Fixes.kt` `miuiFixes` | 上游 fcmfix 连它一起移除 GMS + `ext.services`，但该列表语义从未被证实：若它其实是「允许后台的白名单」，移除是在**收紧**而不是放开。一条可能反向起作用的逻辑不该带着 |

### 保留

- 全部 system_server 侧修复的介入判据仍是同一套「推送族 + 目标明确」（`Push.isTargeted`）
- 开机后延迟 60 秒才介入；非对应 ROM 的挂载点独立跳过（`hook skip <组名>: <原因>`）
- CI 结构（零 secrets、test → assembleRelease → assembleDebug → lint → 入口类 dex 校验 → 上传产物）、
  `scripts/sign-apk.sh`、`docs/build-and-sign-termux.md`

## 0.9.0（已被 1.0.0 覆盖）

签名自适应（版本候选表 + 类型校验 + 参数名兜底）、`core/hook/mods` 分层、
`ProcessEnv` 收敛进程状态、Groovy → Kotlin DSL + version catalog、JUnit 4 → 5、
`src/main/java` → `src/main/kotlin`、CI 用 `-Pfcmself.versionName` 注入版本。
真机验证：OnePlus PLC110 / ColorOS（Android 16, API 36）/ GMS 26.33.32 全链路通过。

## 0.8.0 及更早（历史）

- 去配置化：删掉 SharedPreferences 配置缓存、通知开关、FCM Diagnostics 的 RECONNECT 按钮
- 恢复 MIUI / HyperOS 自启动修复，新增 MIUI 本地通知与 PowerKeeper 修复
- 身份迁移：`com.kooritea.fcmfix` → `sumicya.fcmself`，移除设置界面与白名单
- 环境升级：AGP 9.4 / Gradle 9.6 / libxposed 102 / compileSdk 36 / built-in Kotlin
