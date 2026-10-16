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

## 继承 arena/01a10a49-gself 全部 26 个提交后删除该分支（2026-10-15）

- 按主人指示整体合并该分支（2026-10-05～10-06 的 26 个提交全部进入本
  分支历史），合并后其 compare ahead_by=0，分支删除。
- 并入的现行内容：只做新包（minSdk 29→36、推送唤醒只挂 Android 16 的
  BroadcastController、ColorOS 解冻只认 4 参签名，依据提交 f22c988）、
  通知保留日志带 reason 值（472734b）、2026-10-05 真机日志回填与
  「消息延迟 / 滞留怎么查」（81326d1、dddbab9）、验证清单校正（e7aecf0）、
  签名脚本环境变量改 GSELF_*、docs/glossary.md、.gitignore 补 *.pyc。
- 未并入的旧口径：run 号取号与旧清理脚本 cleanup_artifacts.py、旧单
  工作流 build.yml、AGENTS.md 引用与旧徽章；build.yml、pr-check.yml、
  cleanup.py、gradle.properties、build.gradle.kts 的版本注入保持现行
  提交数口径，仅取 minSdk 36 与 dev- 本地回落命名。
