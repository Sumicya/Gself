## ColorOS 首次解锁后 GMS 断网保护（2026-10-18）

- 对应 FCM Diagnostics 的 `ERR_CLOSE_BY_USER_UNLOCKED` / `Close err:30`：在 system_server 中清除 Oplus 的 Google 限制广播，并在 Battery 声明 `IgnoreGmsUserSet=true` 时阻止对 GMS、GSF、Play 商店和 ConfigUpdater 下发拒绝联网规则。放行规则和非 Google UID 不受影响；用户仍可控制联网的 ROM 不覆盖其设置。
- 新增纯 JVM 策略测试与真机验证说明。当前沙箱没有 JDK，未能本地运行 Gradle；新挂点及 FCM 恢复效果仍待目标 ColorOS 设备验证。

## 规范同步 —— 时区固定偏移与 README 按 S1 调整（2026-10-18）

- 依据 selfs 最新规范提交 `01887e42d21ccb56357b0ebd1bfeba54e6704167`。
- `build.yml` 取号命令由 `TZ=Asia/Shanghai date` 改为 `date -u -d '+8 hours'`（V1）。
- README 删除第 3 行 Build 徽章；`## 功能（按进程分发）` 改名为 `## 功能` 并移到 `## 要求` 之后（S1）。
- 同一提交更新本变更记录（S4）。

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
