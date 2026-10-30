## 推送唤醒补标志挂到 AMS 全部广播入口（2026-10-30）

- 推送唤醒补 `FLAG_INCLUDE_STOPPED_PACKAGES` 的挂点改为 `ActivityManagerService` 上所有带 `Intent` 参数的 `broadcastIntent` / `broadcastIntentWithFeature` / `broadcastIntentLocked` 重载，按名字全部挂上，不再只挂参数最多的一个。
- 移除 `BroadcastController#broadcastIntentLocked` 挂点：真机 `push seen` 从未命中（推断为 ColorOS 上被内联，未证实）；它原在 2026-10-05 的日志里出现过 `wake:`。
- 真机验收（2026-10-30 12:39，nagramx）：`am force-stop` 后由 GMS 的 c2dm 广播以新 pid 拉起，`wake:` 两处命中，GMS 侧 `CANCELLED` 计数 0，通知入队；推送广播从 `AMS#broadcastIntentWithFeature` 进入。
- 真机对照（shell 广播，仅有无 `--include-stopped-packages`）：不带 `0x20` 不投递（进程未启动），带 `0x20` 投递成功（进程启动）。
- ColorOS services.jar 反汇编（标准库解析 dex，`/tmp`，未入库）：AMS 广播入口与 `BroadcastController` 的调用关系已核对；停止应用过滤读取 `Intent.isExcludingStopped()`，ColorOS 另有 `IStaticExt.onIsFilterStopped` 扩展（只对系统包且带 `ForbidStop` 元数据生效，不构成第三方应用的闸门）。
- 定位完成后删除诊断行 `push seen@AMS`；`wake:` 行保留挂点名（如 `AMS#broadcastIntentWithFeature`），仍能定位命中的入口。
- 精简与重构：广播入口重载查找抽成 `intentOverloads`，推送唤醒与 ColorOS 受限广播两组共用；`unfreeze` 去掉候选列表循环，改为「缓存签名 → 调用 → 首次成功记一行」；`googleRestrictionBroadcastFix` 去掉 `hooked` 标志与 `check`；`XposedMain` 去掉与组内 `install` 重复的外层包裹。
- 修正过期口径：`app/build.gradle.kts` 与 `gradle.properties` 的号源注释由「git 提交数」改为 `run_number`，`docs/glossary.md` 的挂载点示例、总序号、当日序号、非发行版本、滚动清理各行按现行实现重写，`docs/verify-on-device.md` 第 7 节的版本口径、第 2 节的诊断行说明、第 1 节与第 8 节的挂点与日志示例同步；`cleanup.py` 注释里的 `concurrency` group 由 `qself-cleanup` 改为 `gself-cleanup`。通知取消原因注释里的「恰好等于 8」改为现行的 5 / 10020 / 10021。
- `build.yml` 的 `push` 补上规范 C2 要求的 `paths-ignore` 名单（`**.md`、`docs/**`、`bad/**`、`LICENSE`、`.gitignore`）：纯文档推送不再占用 `run_number`、不出包。
- 未验证：本轮精简与诊断行删除只经过 CI 编译，未在真机复测；`unfreeze` 重构后的行为与重构前一致（同一 4 参签名、同一日志时机）属代码推断。

## 推送唤醒加入命中诊断日志，修正通知取消原因值（2026-10-29）

- 推送唤醒出口入口新增诊断行 `push seen`（action、flags、目标包名），并在 `ActivityManagerService#broadcastIntentWithFeature` 上加同样的诊断行 `push seen@AMS`，用于判断推送广播是否经过 `BroadcastController#broadcastIntentLocked`；定位完成后删除。
- 文档与注释里 `REASON_PACKAGE_CHANGED` 的值由 8 改为 5（AOSP `NotificationListenerService` 源码为 5，8 是 `REASON_APP_CANCEL`）。代码引用的是常量，行为不变。
- 未验证：本次只经过 CI 编译，未在真机运行。

## 号源改 run_number 原值并对齐双工作流清理结构（2026-10-28）

- 号源从 git rev-list 提交数改为 github.run_number 原值，当日序号按差值锚定（当日现存运行最小 run_number 与现存合规产物反推值取小），取号打印 HEAD SHA、run_id、run_number、R_first、当日序号与最终版本，取不到即退出。
- 清理从 build job 内步骤拆为 cleanup-artifacts（needs: build + if: always()）与 cleanup-releases（再加默认分支条件）两个独立 job，build job 收回 actions: write；pr-check 的清理 job 改名 cleanup-artifacts 且不写 needs；cleanup.py 换全仓统一接口（GH_TOKEN/REPO/RUN_ID/SEGMENTS/CLEANUP_APPLY/CLEANUP_SCOPE）。
- checkout 去掉 fetch-depth:0（浅克隆即可）；步骤名统一中文短语；删除 GITHUB_STEP_SUMMARY 日志尾巴步骤；scripts/sign-apk.sh 移入 tools/。
- cleanup.py 项目前缀改为由 REPO 推导：Qself 版写死 `Qself-`，导致 Gself 首个新口径产物 Gself-26.10.28.1.57 上传后被清理 job 以前缀不符删除（#57 三 job 全绿但零产物），修补后 #58 起按 `Gself-` 前缀保留；脚本内 Qself 专属措辞与过期注释同步清理。

## ColorOS 首次解锁后 GMS 断网保护（2026-10-18）

- 对应 FCM Diagnostics 的 `ERR_CLOSE_BY_USER_UNLOCKED` / `Close err:30`：在 system_server 中清除 Oplus 的 Google 限制广播，并在 Battery 声明 `IgnoreGmsUserSet=true` 时阻止对 GMS、GSF、Play 商店和 ConfigUpdater 下发拒绝联网规则。放行规则和非 Google UID 不受影响；用户仍可控制联网的 ROM 不覆盖其设置。
- 新增纯 JVM 策略测试与真机验证说明。当前沙箱没有 JDK，未能本地运行 Gradle；新挂点及 FCM 恢复效果仍待目标 ColorOS 设备验证。

## 规范同步 —— 时区固定偏移与 README 按 S1 调整（2026-10-18）

- 依据 selfs 最新规范提交 `01887e42d21ccb56357b0ebd1bfeba54e6704167`。
- `build.yml` 取号命令由 `TZ=Asia/Shanghai date` 改为 `date -u -d '+8 hours'`（V1）。
- README 删除第 3 行 Build 徽章；`## 功能（按进程分发）` 改名为 `## 功能` 并移到 `## 要求` 之后（S1）。
- 同一提交更新本变更记录（S4）。
