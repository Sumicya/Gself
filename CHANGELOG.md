## 推送唤醒改为「语义层 + 标志层」两层，判据抽成纯函数（2026-10-30）

- 新增语义层：Hook `Intent#isExcludingStopped()`，对定向推送答 false。这是 AOSP 判定「要不要排除停止态应用」的唯一枢轴（`IntentResolver.java:729` 取值、`:749` 据此跳过过滤器；`BroadcastSkipPolicy` 无第二处停止态判断），因此不依赖哪个 AMS 重载进来，也不受 ROM 改签名影响。
- 保留标志层：在 `ActivityManagerService` 所有带 `Intent` 参数的 `broadcastIntent` / `broadcastIntentWithFeature` / `broadcastIntentLocked` 重载上补 `FLAG_INCLUDE_STOPPED_PACKAGES`。补标志必须赶在 `BroadcastController#broadcastIntentLocked` 复制 intent（`:896`）之前，因为系统随后给副本补 `FLAG_EXCLUDE_STOPPED_PACKAGES`（`:922`，原文「By default broadcasts do not go to stopped apps」）；这正是此前挂在 BC 从不命中的同一处机制。
- 判据抽成纯函数 `Push.needsStoppedPatch(action, target, flags)`：两个挂载点只拆 `Intent`，判据表（推送族、定向、已带 `0x20` 不重复补）由 JVM 单测覆盖，补了「已带标志不再补」「无目标不动」「非推送零介入」三条。
- 新增两层自证日志：`决策入口兜底：<包名>（… 未带 0x20，第 N 次）` 表示标志层漏了这条、语义层兜住，同时是「还有没挂上的 AMS 入口」的信号；`停止态决策未被调用：3 条推送已补标志…` 表示枢轴没被调用（可能被内联）。前 3 次逐条、之后每 100 次一条，不刷屏。
- 删除诊断行 `push seen@AMS`（定位已完成），`wake:` 保留挂点名；`XposedMain` 去掉与组内 `install` 重复的外层包裹；广播重载查找抽成 `intentOverloads` 供两层与 ColorOS 受限广播共用；`unfreeze` 去掉候选列表循环。
- 真机验收（2026-10-30 12:39，nagramx，上一版挂点）：`am force-stop` 后由 GMS 的 c2dm 广播以新 pid 拉起，`wake:` 命中，GMS 侧 `CANCELLED` 计数 0，通知入队。
- 真机对照（shell 广播，仅有无 `--include-stopped-packages`）：不带 `0x20` 不投递，带 `0x20` 投递成功。
- 文档纠错：通知分组原写「原生 AOSP 不会因包变化取消通知」不成立——`NotificationManagerService#mPackageIntentReceiver` 在包移除 / 禁用时以 `REASON_PACKAGE_CHANGED`(5) 取消（`:2199`、`:2281`）；本模块拦它是故意偏离，代价（卸载 / 禁用后通知可能滞留）与「不在此处回调 PackageManager 以免锁序反转」的理由已写进代码注释与 `docs/verify-on-device.md` 第 4 节。同时修正 `app/build.gradle.kts`、`gradle.properties` 的号源注释（run_number），`docs/glossary.md` 6 行过期口径，`docs/verify-on-device.md` 第 1 / 2 / 7 / 8 节的挂点与日志示例，`cleanup.py` 注释里的 `qself-cleanup`。
- `build.yml` 的 `push` 补上规范 C2 要求的 `paths-ignore` 名单：纯文档推送不再占用 `run_number`、不出包。
- 未验证：语义层挂点是本轮新增，只经 CI 编译与 JVM 单测，未在真机确认命中；两层在真机上的分工要按 `docs/verify-on-device.md` 第 2 节的三条日志判读。

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
