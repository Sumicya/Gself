# 真机验证清单

模块没有界面，行为全在日志里。tag 固定 `FcmSelf`，每条同时写进 logcat 与 LSPosed 框架日志：

```bash
adb logcat -s FcmSelf                        # 或 Termux 里 su -c "logcat -s FcmSelf"
su -c "grep -h fcmself /data/adb/lspd/log/*.log | tail -80"   # 启动那几行会被挤出 logcat 环形缓冲
```

toybox grep **不支持 `\|` 交替**，多关键字用 `-E`：`grep -hE 'Boot Complete|instance captured'`。

## 0. 准备

1. 装 CI 产物 `fcmself-<版本>-debug-signed.apk`（或本地 `scripts/sign-apk.sh` 签一个）
2. LSPosed 启用模块，作用域勾 `system`（**只需这一个**，1.0.0 起不再进 GMS 进程）
3. 重启，立刻开始抓日志

## 1. 模块有没有被加载

```
[fcmself] fcmself 载入 system_server（Android 16 / API 36）
[fcmself] hook target: com.android.server.am.BroadcastController#broadcastIntentLocked(19)
[fcmself] OplusProxyWakeLock instance captured        ← 仅 ColorOS
[fcmself] Boot Complete                               ← 载入 + 60 秒
```

- 什么都没有 → LSPosed 没加载模块：查启用状态、作用域、是否重启过（改作用域必须重启）
- 只有第一行 + 一堆 `hook skip` → 模块加载了，但这台 ROM 上没有对应挂载点
- `hook skip 开机闸门: …` → `finishBooting` 没找到，模块会退化成「载入后 60 秒放行」（fail-open，不影响功能）

**必须没有的一行**（出现说明核心挂载点没找到，模块整体不工作）：

```
hook skip 唤醒已停止的应用: java.lang.NoSuchMethodError: …#broadcastIntentLocked
```

## 2. 核心：唤醒已停止的应用

先把目标应用弄成真正停止态：最近任务划掉，再 `adb shell su -c "am force-stop <包名>"`
（`dumpsys package <包名> | grep stopped=` 应为 `stopped=true`）。然后推一条 FCM 消息：

```
[fcmself] Add FLAG_INCLUDE_STOPPED_PACKAGES: <包名>
[fcmself] unfreeze: <包名> uid=10323（4 参签名）      ← 仅 ColorOS
```

出现这行且通知弹出 = 核心生效。（这是给正在发送的那条广播补 flag，不是额外发一条广播。）

> **冻结 ≠ 停止**：ColorOS 上从最近任务划掉通常只是**冻结**（`ps -A | grep <包名>` 见
> `do_freezer_trap`、`stopped=false`），这种状态广播本来就能投递，起作用的是
> `shouldProxy bypass` / `unfreeze`。只有 `stopped=true` 才走本节这条路。

## 3. 自启动闸门

1.0.0 起放行时**会打日志**（旧版静默，只能靠「没有失败行」反推）：

| 日志 | 挂载点 |
| --- | --- |
| `Allow Auto Start: <包名>` | MIUI 12/13 `checkApplicationAutoStart`、HyperOS 同名方法、`isAllowStartService` |
| `No Intercept: <包名>` | HyperOS `checkReceiverIfRestricted`、MIUI `shouldInterceptBroadcast`、ColorOS `shouldPreventSendReceiverReal` |
| `Disable MIUI Intercept: <包名>` | MIUI 13 `shouldInterceptService`（after 改写） |

非对应 ROM 上是 `hook skip <类名>#<方法>: sumicya.fcmself.Hook$Missing: <类名>`，**属正常**。

## 4. 通知不被自动清理

拦的是 `NotificationManagerService.cancelAllNotificationsInt`，只在取消原因为
`REASON_PACKAGE_CHANGED`(8) 或 ColorOS 的 10020 / 10021 时忽略这次取消：

```
[fcmself] Keep notification: <包名>
```

## 5. MIUI 电源

```
[fcmself] MilletConfig.isGlobal = true
[fcmself] PowerKeeper gms_control 已关掉
[fcmself] MilletPolicy 名单已调整
[fcmself] MIUI 本地通知限制：<类名>#isAllowLocalNotification → true
```

## 6. ColorOS

```
[fcmself] Hans GMS 限制已置空
[fcmself] isGoogleRestricInfoOn → false
[fcmself] shouldProxy bypass: pkg=<包名> action=com.google.android.c2dm.intent.RECEIVE（期间另有 N 条已抑制）
```

bypass 日志**全局**节流 60 秒（旧版按包名分别节流）：60 秒内第二个应用的 bypass 会被吞掉，
只留计数。要按包名区分得看 logcat 全量。

`unfreeze` 那行括号里是实际试通的签名参数个数：模块按 4 参 → 3 参依次试，成功后记住复用。
偶尔看到一条失败紧跟一条成功属正常探测。

## 7. 三段归因

| 段 | 证据 | 没有证据说明 |
| --- | --- | --- |
| ① 服务器 → GMS | `shouldProxy bypass: …action=…c2dm.intent.RECEIVE`（仅 ColorOS） | 推送没到 GMS：token 失效或网络问题，与本模块无关 |
| ② GMS → 应用 | `Add FLAG_INCLUDE_STOPPED_PACKAGES` + `unfreeze` | 模块没介入：不在 `Boot Complete` 之后，或这条广播本来就带了 flag |
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

## 8. 1.0.0 需要重点验的两处（都是刻意砍掉的角）

1. **appOp 不再改写**（旧版把 `OP_NONE` 抬成 `OP_POST_NOTIFICATION`）—— **已验通**：通知照常弹出并被 `Keep notification` 保住，force-stop 后推送照常唤醒应用。
   验法：`am force-stop` 后推送，看通知能否弹出。若 ② 有日志、③ 没结果，且
   `dumpsys notification` 显示通知被 post 但没显示 → 可能就是缺这一改，按 `Fixes.kt` 里
   `ponytail:` 注释的路径加回来。
2. **取消原因按值认，不按下标**。验法：正常用一段时间，看有没有「该被清掉的通知没被清掉」
   （别的 int 参数恰好等于 8 会误拦一次取消）。出现就按注释改成「第一个 String 之后」的定位。

## 9. 当前验证状态

| 项目 | 状态 |
| --- | --- |
| 1.0.0 冻结态链路 | **已验通**（OnePlus/ColorOS，build `20260926_1edb471`，详见第 11 节）：载入 / hook 装配 / 开机闸门 / `shouldProxy bypass` / `No Intercept` / `Add FLAG_INCLUDE_STOPPED_PACKAGES` / `unfreeze` / `Keep notification` 全命中，通知被保住 |
| 1.0.0 force-stop 唤醒 | **已验通**（2026-09-26，OnePlus/ColorOS，nagramx fork）：`stopped=true` 下推送以新 pid 拉起应用，证据见第 11 节 |
| 介入判据（`Push`） | 单测覆盖（`PushTest`）：action 分类 + 从散字符串实参里认包名 |
| 参数按类型/按值识别 | **未验证**：无 JVM 单测（要真 Intent / 真 ROM 类），只能靠第 1 节的 `hook target:` 行与真机日志 |
| release（R8）产物 | **未验证**：真机一直装 debug-signed，release 只过了 CI 的入口类 dex 检查 |
| GMS 重连修复 | 已删除，无需验证 |

## 10. 反馈问题时请附上

- 从重启开始的完整 `FcmSelf` 日志（尤其 `hook target:` 与所有 `hook skip` 行）
- ROM 名称与版本、GMS 版本号、LSPosed 版本、Android 版本
- 目标应用包名 + 「杀掉应用 → 推送」的复现步骤

## 11. 真机现状（2026-09-26，OnePlus/ColorOS，nagramx fork，build `20260926_1edb471`）

冻结态（`stopped=false`）与 force-stop 后（`stopped=true`）两条路都验通了。
`stopped=true` 的关键证据 —— 推送把应用以新 pid 拉起来，拉起它的正是 c2dm 广播的接收器：

```
12:29:41.314  FcmSelf: Add FLAG_INCLUDE_STOPPED_PACKAGES: fork.risin42.nagramx
12:29:41.316  FcmSelf: unfreeze: fork.risin42.nagramx uid=10323（4 参签名）
12:29:41.317  FcmSelf: No Intercept: fork.risin42.nagramx
12:29:41.322  ActivityManager: Start proc 24130:fork.risin42.nagramx/u0a323
              for broadcast {fork.risin42.nagramx/com.google.firebase.iid.FirebaseInstanceIdReceiver}
```

同时 `Keep notification` 也打出来了 —— 删掉 appOp 改写不影响通知。

**空日志不等于失败，推送有延迟**：本轮多个 30 秒窗口里 `grep -icE 'c2dm|firebase'` 全是 0，
但应用最后还是被拉起来了（`stopped=` 自己从 true 变回 false）—— force-stop 之后第一条推送迟到，
30 秒窗口抓不到它。广播 hook 是无条件打日志的（与 stopped 无关），所以「全空」只说明
这段时间广播没进 `broadcastIntentLocked`，不说明模块没生效。

测这条等 **2–5 分钟**，并且用下面这条自打标签的循环抓（每窗自带时间戳，粘贴不会错位）：

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

已修：`Boot Complete` 双打（`finishBooting` 在该机命中两次，隔 2 秒），见 `89eded0`。
