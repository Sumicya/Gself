## 推送唤醒补标志挂到 AMS 入口，并保留诊断行（2026-10-30）

- 推送唤醒补 `FLAG_INCLUDE_STOPPED_PACKAGES` 的主挂点改为 `ActivityManagerService#broadcastIntentWithFeature`：真机日志显示推送广播经此进入，`BroadcastController#broadcastIntentLocked` 在本机从未命中。`BroadcastController` 出口保留同一判据，任一处先到即补，后到者直接跳过。
- 新增诊断行 `push seen@AMS`，`wake:` 行追加挂点名（AMS 或 BroadcastController）；定位完成后删除诊断行。
- `build.yml` 的 `push` 补上规范 C2 要求的 `paths-ignore` 名单（`**.md`、`docs/**`、`bad/**`、`LICENSE`、`.gitignore`）：纯文档推送不再占用 `run_number`、不出包。
- 未验证：本次只经过 CI 编译，未在真机运行补标志后的投递结果。

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
