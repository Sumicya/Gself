# 真机验证清单

模块没有界面，行为全在日志里。tag 固定 `FcmSelf`，每条同时写进 logcat 与 LSPosed 框架日志：

```bash
su -c 'logcat -d -s FcmSelf'
su -c 'grep -h fcmself /data/adb/lspd/log/*.log | tail -80'   # 启动那几行会被挤出 logcat 环形缓冲
```

toybox grep **不支持 `\|` 交替**，多关键字用 `-E`：`grep -hE 'wake|keep notification'`。

## 0. 准备

1. 装 CI 产物 `fcmself-<版本>-debug-signed.apk`（或本地 `scripts/sign-apk.sh` 签一个）
2. LSPosed 启用模块，作用域勾 `system`（**只需这一个**）
3. 重启，立刻开始抓日志

## 1. 模块有没有被加载

```
[fcmself] fcmself 载入 system_server（Android 16 / API 36）
[fcmself] hook target: com.android.server.am.BroadcastController#broadcastIntentLocked(19)
[fcmself] OplusProxyWakeLock instance captured        ← 仅 ColorOS
[fcmself] unfreeze 可用（4 参签名）                    ← 仅 ColorOS，只打一次
```

- 什么都没有 → LSPosed 没加载模块：查启用状态、作用域、是否重启过（改作用域必须重启）
- 只有第一行 + 一堆 `hook skip` → 模块加载了，但这台 ROM 上没有对应挂载点
- 26.10.1 起**没有开机闸门**：`Boot Complete` 那行不会再出现，载入即介入

**必须没有的一行**（出现说明核心挂载点没找到，模块整体不工作）：

```
hook skip 推送唤醒: java.lang.NoSuchMethodError: …#broadcastIntentLocked
```

## 2. 核心：唤醒已停止的应用

先把目标应用弄成真正停止态：最近任务划掉，再 `su -c "am force-stop <包名>"`
（`dumpsys package <包名> | grep stopped=` 应为 `stopped=true`）。然后推一条 FCM 消息：

```
[fcmself] wake: <包名>
```

出现这行且通知弹出 = 核心生效。（这是给正在发送的那条广播补 `FLAG_INCLUDE_STOPPED_PACKAGES`，
不是额外发一条广播。）ColorOS 上同一时刻还会走一次 `unfreezeIfNeed`，成功只在**第一次**打
`unfreeze 可用（N 参签名）`，之后静默。

> **冻结 ≠ 停止**：ColorOS 上从最近任务划掉通常只是**冻结**（`ps -A | grep <包名>` 见
> `do_freezer_trap`、`stopped=false`），这种状态广播本来就能投递。只有 `stopped=true` 才走本节这条路。

## 3. ColorOS 放行

26.10.1 起 ColorOS 的四个闸门都是**无条件**放开，不再逐条判「实参里有没有推送」，所以
**没有**逐次日志（1.0.0 的 `No Intercept` / `shouldProxy bypass` / `Allow Auto Start` 已删）。
判据只剩「载入时有没有挂上」：

| 挂载点 | 挂上了的表现 |
| --- | --- |
| `OplusProxyBroadcast#shouldProxy` → `NOT_INCLUDE` | 没有 `hook skip OplusProxyBroadcast` |
| `OplusAppStartupManager#shouldPreventSendReceiverReal` → false | 没有 `hook skip OplusAppStartupManager` |
| `OplusStartupStrategy#isGoogleRestricInfoOn` → false | 同上（同一组） |
| `OplusBgSceneManager` 两个 GMS 限制方法置空 | 没有 `hook skip Hans GMS 限制` |

非 ColorOS 机器上这四处是 `hook skip <组名>: sumicya.fcmself.Hook$Missing: <类名>`，**属正常**。

## 4. 通知不被自动清理

拦的是 `NotificationManagerService.cancelAllNotificationsInt`，只在取消原因为
`REASON_PACKAGE_CHANGED`(8) 或 ColorOS 的 10020 / 10021 时忽略这次取消：

```
[fcmself] keep notification: <包名>
```

## 5. 三段归因

| 段 | 证据 | 没有证据说明 |
| --- | --- | --- |
| ① 服务器 → GMS | 推送到了设备（GMS 自己的日志 / 应用最终被拉起） | token 失效或网络问题，与本模块无关 |
| ② GMS → 应用 | `wake: <包名>` | 模块没介入，或这条广播本来就带了 flag |
| ③ 应用 → 通知栏 | 通知真的弹出来 | 应用没起来 / 起来就被杀 / 通知被拦——这段本模块管不到 |

②有日志、③没结果时依次查：

```bash
su -c "dumpsys package <包名> | grep -iE 'stopped|enabled='"   # a. 当时是不是真 stopped
su -c "ps -A | grep <包名>"                                    # b. 推送后进程有没有起来
su -c "dumpsys notification --noredact | grep -i <包名>"        # c. 通知有没有被 post
```

「进程起来了」≠「通知弹出来了」：进程起来之后是应用自己解析消息、调 `NotificationManager.notify()`，
再由系统查通知权限/渠道才显示，这一段模块没有任何 hook。

想看系统有没有拒绝投递：

```bash
su -c "logcat -c"    # 清空后推一条消息，等 10 秒
su -c "logcat -d | grep -iE '<包名>|c2dm|Background execution|not delivering|stopped'"
```

## 6. 26.10.1 需要重点验的三处（都是这一轮砍掉的角）

1. **没有开机闸门**：开机后立刻推一条，看 `wake:` 有没有打出来、系统稳不稳。
   若开机阶段出现异常，把 1.0.0 的 `finishBooting` 闸门从 git 历史里取回来。
2. **`shouldProxy` 一律 `NOT_INCLUDE`**（旧版只对推送放行）。验法是**待机耗电**：
   ```bash
   su -c 'dumpsys batterystats --reset'; sleep 3600   # 或正常使用一晚
   su -c 'dumpsys batterystats | grep -iE "wake lock|Estimated battery"'
   ```
   耗电明显上升就按 `Fixes.kt` 里 `ponytail:` 注释的路径把推送判据加回来。
3. **取消原因按值认，不按下标**。验法：正常用一段时间，看有没有「该被清掉的通知没被清掉」
   （别的 int 参数恰好等于 8 会误拦一次取消）。出现就按注释改成「第一个 String 之后」的定位。

## 7. 当前验证状态

| 项目 | 状态 |
| --- | --- |
| 编译 + `PushTest` | **已验证**（PR #11 的 Android CI，run 36788287654）：`./gradlew test assembleRelease assembleDebug` 全绿，R8 入口类 dex 校验通过。沙箱本身没有 JDK / Android SDK / 外网，跑不了 |
| 26.10.1 真机全链路 | **未验证**：还没装到设备上 |
| 1.0.0 冻结态链路 | 已验通（OnePlus/ColorOS，build `20260926_1edb471`）：载入 / hook 装配 / `shouldProxy bypass` / `No Intercept` / flag / `unfreeze` / `Keep notification` 全命中 |
| 1.0.0 force-stop 唤醒 | 已验通（2026-09-26，OnePlus/ColorOS，nagramx fork）：`stopped=true` 下推送以新 pid 拉起应用 |
| 介入判据（`Push.isPush`） | 单测覆盖（`PushTest`）；`isTargeted` 要真 Intent，只能靠真机日志 |
| 参数按类型/按值识别 | **未验证**：无 JVM 单测（要真 Intent / 真 ROM 类） |
| release（R8）产物 | **未验证**：真机一直装 debug-signed，release 只过了 CI 的入口类 dex 检查 |

## 8. 反馈问题时请附上

- 从重启开始的完整 `FcmSelf` 日志（尤其 `hook target:` 与所有 `hook skip` 行）
- ROM 名称与版本、GMS 版本号、LSPosed 版本、Android 版本
- 目标应用包名 + 「杀掉应用 → 推送」的复现步骤

## 9. 真机现状参考（2026-09-26 / 2026-10-01，OnePlus/ColorOS，nagramx fork）

1.0.0 在这台机器上 3 小时的日志里，`Add FLAG_INCLUDE_STOPPED_PACKAGES` / `unfreeze` /
`No Intercept` 每条推送各打一行，共几百行 —— 26.10.1 把 `unfreeze` 收成一次性、删掉后两条，
就是冲着这个来的。`stopped=true` 下的关键证据（推送把应用以新 pid 拉起来）：

```
12:29:41.314  FcmSelf: Add FLAG_INCLUDE_STOPPED_PACKAGES: fork.risin42.nagramx
12:29:41.316  FcmSelf: unfreeze: fork.risin42.nagramx uid=10323（4 参签名）
12:29:41.317  FcmSelf: No Intercept: fork.risin42.nagramx
12:29:41.322  ActivityManager: Start proc 24130:fork.risin42.nagramx/u0a323
              for broadcast {fork.risin42.nagramx/com.google.firebase.iid.FirebaseInstanceIdReceiver}
```

**空日志不等于失败，推送有延迟**：多个 30 秒窗口里 `grep -icE 'c2dm|firebase'` 全是 0，
但应用最后还是被拉起来了（`stopped=` 自己从 true 变回 false）—— force-stop 之后第一条推送迟到，
30 秒窗口抓不到它。测这条等 **2–5 分钟**，用下面这条自打标签的循环抓：

```bash
su -c 'logcat -c; for i in $(seq 10); do sleep 30; echo "=== 第 $i 个 30 秒 $(date +%H:%M:%S) ===";
  echo -n "c2dm/firebase 计数: "; logcat -d | grep -icE "c2dm|firebase|MESSAGING_EVENT";
  logcat -d -s FcmSelf | tail -3; dumpsys package <目标包名> | grep -m1 -o "stopped=[a-z]*";
  logcat -c; done'
```

计数为 0 只表示「这会儿还没到」；判失败要看 5 分钟后 `stopped=` 是否仍为 true、
且 `FcmSelf` 一行都没有。

备忘：若将来某台机器确实需要写 appOp，取值用「紧跟 `Bundle bOptions` 之前的那个 int」，
不要用 0.9.0 的硬编码下标 13（`intent@3` 之后依次是 requestCode@7 / userId@11 /
flags@12 / appOp@13，换 ROM 会漂）。本机不需要。
