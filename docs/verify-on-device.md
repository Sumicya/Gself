# 真机验证清单

模块没有界面，行为全在日志里。日志 tag 固定 `FcmSelf`，每条同时写进 logcat 与 LSPosed 框架日志：

```bash
su -c 'logcat -d -s FcmSelf'
su -c 'grep -h fcmself /data/adb/lspd/log/*.log | tail -80'   # 启动那几行会被挤出 logcat 环形缓冲
```

toybox grep **不支持 `\|` 交替**，多关键字用 `-E`：`grep -hE 'wake|keep notification'`。

## 0. 准备

1. 装 CI 产物 `Gself-<版本>.apk`（debug 签名，可直接安装；取法见会话汇报里的下载命令块，
   artifact 按前缀 `Gself-` 过滤），或用 `tools/sign-apk.sh` 给自己的包签名后安装。
   平台要求 **Android 16+（API 36）**：低版本会被系统在安装时拒绝
2. LSPosed 启用模块，作用域勾 `system` + `com.google.android.gms` + Gboard（`scope.list` 已预选）
3. 重启设备，立刻开始抓日志（system_server 里的 Hook 只能靠重启生效）

## 1. 模块有没有被加载

```
[fcmself] fcmself 载入 system_server（Android 16 / API 36）
[fcmself] hook target: com.android.server.am.BroadcastController#broadcastIntentLocked(25)
                                          ↑ 括号里是参数个数，随 ROM / 版本不同（本机 ColorOS + Android 16 实测 25，早期样机是 19）；
                                            代码按「参数最多」的重载取，不依赖具体数字
[fcmself] OplusProxyWakeLock instance captured        ← 仅 ColorOS
[fcmself] unfreeze 可用（4 参签名）                    ← 仅 ColorOS，只打一次
```

- 什么都没有 → LSPosed 没加载模块：查启用状态、作用域、是否重启过（改作用域必须重启）
- 只有第一行 + 一堆 `hook skip` → 模块加载了，但这台 ROM 上没有对应挂载点
- 26.10.1 起**没有开机闸门**：`Boot Complete` 那行不会再出现，载入即介入
- 三组进程各有一条安装日志：system 侧是上面的行，GMS 侧是
  `通行密钥解限 Hook 已安装`，Gboard 侧是 `Gboard 剪贴板 Hook 已安装`（后两条在各自应用的进程里，
  用 `su -c 'logcat -d -s FcmSelf'` 同样能看到）。**Gboard 那条只有在 Gboard 作为当前输入法被加载过之后才会出现**；
  一次都没看到就先确认当前输入法：`su -c "settings get secure default_input_method"`
- `hook target:` 那行只在 system_server 载入时打一次；`keep notification` / `wake` 是运行期日志，按需出现

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

> **诊断行（2026-10-29 诊断版起，定位后删除）**：每条推送广播进入出口都先打一行
> `push seen: <action> flags=0x… target=<包名>`，不论是否命中。判读：
> - 有 `push seen`、没有 `wake:`：广播到了出口，但判据没放行——看 `target=` 是否为空，或 `flags` 是否已带 `0x20`（INCLUDE_STOPPED）。
> - 没有 `push seen`，但有 `push seen@AMS`：推送经过了 AMS 入口，却没经过 `BroadcastController#broadcastIntentLocked`，ROM 绕过了该重载。
> - 两条都没有：推送没有进入 AMS 的 `broadcastIntentWithFeature`。

> **冻结 ≠ 停止**：ColorOS 上从最近任务划掉通常只是**冻结**（`ps -A | grep <包名>` 见
> `do_freezer_trap`、`stopped=false`），这种状态广播本来就能投递。只有 `stopped=true` 才走本节这条路。

## 3. ColorOS 放行

26.10.1 起原有的四个 ColorOS 后台闸门都是**无条件**放开，不再逐条判「实参里有没有推送」，所以
**没有**逐次日志（1.0.0 的 `No Intercept` / `shouldProxy bypass` / `Allow Auto Start` 已删）。
判据只剩「载入时有没有挂上」：

| 挂载点 | 挂上了的表现 |
| --- | --- |
| `OplusProxyBroadcast#shouldProxy` → `NOT_INCLUDE` | 没有 `hook skip OplusProxyBroadcast` |
| `OplusAppStartupManager#shouldPreventSendReceiverReal` → false | 没有 `hook skip OplusAppStartupManager` |
| `OplusStartupStrategy#isGoogleRestricInfoOn` → false | 同上（同一组） |
| `OplusBgSceneManager` 两个 GMS 限制方法置空 | 没有 `hook skip Hans GMS 限制` |

非 ColorOS 机器上这四处是 `hook skip <组名>: sumicya.fcmself.Hook$Missing: <类名>`，**属正常**。

### 首次解锁后 GMS 一直未连接

若 FCM Diagnostics 在首次解锁附近出现 `Close err:30` / `ERR_CLOSE_BY_USER_UNLOCKED`，随后反复 `Failed connection err:25`，
ColorOS 电池组件的 Google 连通性探测可能已对 GMS 等核心包下发禁网规则。新增的 system_server 保护只在 Battery 清单声明
`IgnoreGmsUserSet=true`（即系统不提供可由用户切换的 Google 联网开关）时丢弃这些 Google UID 的 reject 规则；不会拦截放行规则或其他应用。

重启后检查：

```
[fcmself] Oplus Google network firewall hook 已安装
[fcmself] Oplus Google restrict broadcast hook active: ...
```

实际拦下拒绝规则和清除限制广播时分别会有：

```
[fcmself] Oplus Google network reject dropped: uid=..., type=...
[fcmself] Oplus Google restrict broadcast cleared in system_server
```

`hook active` 只代表挂点存在；没有 `reject dropped` 可能是 ROM 未声明上述元数据、该规则没有发生，或问题在网络路由。尤其 VPN 下若日志显示
`wasVpnBypassed=true`，可临时关闭 VPN 或确认 GMS 被 VPN 正确接管后再测；这不是强制重连计时器，FCMFix 不会替 GMS 修复 DNS、代理或被封锁的 5228–5230 端口。
Play Integrity 的 `Labels: []` 与这两个 Hook 是否加载没有直接关系。

## 4. 通知不被自动清理

拦的是 `NotificationManagerService.cancelAllNotificationsInt`，只在取消原因为
`REASON_PACKAGE_CHANGED`(5) 或 ColorOS 的 10020 / 10021 时忽略这次取消：

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

## 6. 26.10.1 那轮砍掉的角（装上后重点验这三处）

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
| 编译 + 单测 | **已验证**（CI 持续实跑：`./gradlew test assembleDebug` 全绿，R8 入口类 dex 校验通过）。包含 `PushTest` 与纯策略单测；本轮 ColorOS 新挂点未真机验证。沙箱没有 JDK / Android SDK，编译只能由 CI 完成 |
| 五段版本（提交数口径）与产物名 | **已验证**（CI 实跑）：Build 按 git 提交数持续产出 `Gself-26.10.15.当日序号.总序号` 并上传同名 artifact |
| 清理（五判据、每工作流 run 最新 5 条、全仓 artifact 最新 5 个） | **已验证**（CI 实跑）：build.yml 与 pr-check.yml 的 cleanup 步持续执行并输出逐项结论 |
| system_server 三组 Hook（推送 / 通知 / ColorOS） | **已验证**（2026-10-05 22:13，Android 16 / API 36，ColorOS）：载入、`hook target: BroadcastController#broadcastIntentLocked(25)`、`OplusAppStartup 自启动闸门已关`、`OplusProxyBroadcast 代理已全关`、`Hans GMS 限制已置空`、`OplusProxyWakeLock instance captured`、`keep notification`、`unfreeze 可用（4 参签名）`、`wake: com.zhiliaoapp.musically` 全部出现（日志见第 9 节） |
| ColorOS 首次解锁后的 Google 联网保护 | **代码新增，待真机验证**：按 `IgnoreGmsUserSet` 元数据及 Google appId 窄范围处理拒绝规则，并清除 Oplus 限制广播；新增纯 JVM 策略测试。新挂点是否匹配用户固件、FCM 是否恢复仍须在设备验证 |
| 只做新包（minSdk 36 / 只挂 BroadcastController / 4 参 unfreeze） | **已验证**（同一份日志）：Android 16 上 `BroadcastController` 存在并挂上，ColorOS 的 `unfreezeIfNeed` 4 参签名可用；无 `hook skip`、无 AMS 回退痕迹 |
| GMS 通行密钥解限 | **仅装点已验证**：`通行密钥解限 Hook 已安装` 出现两次（GMS 进程重启过）；「非 Chrome 浏览器能用通行密钥」要实际调一次才知道 |
| Gboard 剪贴板 | **未见日志**（2026-10-05 时点）：日志里没有 `Gboard 剪贴板 Hook 已安装`，也没有 `hook skip`——先确认 Gboard 是不是当前输入法（见第 1 节），是的话这条要查 |
| Gself 真机链路 | **已验证**（2026-10-06，OnePlus/ColorOS，`26.10.6.1.128`）：推送唤醒 `wake:` 批量命中（含「长连接断流后重启恢复」场景，见第 10 节）、通知保留 `keep notification` 命中；三组挂载点已在三合一时验过 |
| 1.0.0 冻结态链路 | 已验通（OnePlus/ColorOS，build `20260926_1edb471`）：载入 / hook 装配 / `shouldProxy bypass` / `No Intercept` / flag / `unfreeze` / `Keep notification` 全命中 |
| 1.0.0 force-stop 唤醒 | 已验通（2026-09-26，OnePlus/ColorOS，nagramx fork）：`stopped=true` 下推送以新 pid 拉起应用 |
| 介入判据（`Push.isPush`） | 单测覆盖（`PushTest`）；`isTargeted` 要真 Intent，只能靠真机日志 |
| 参数按类型/按值识别 | **未验证**：无 JVM 单测（要真 Intent / 真 ROM 类） |
| release（R8）产物 | **未验证**：CI 只出 debug 包，release 变体只在本地产出、没人装上验过 |

## 7.1 消息延迟 / 滞留：怎么抓日志

症状是「消息晚了 / 卡住，事后才补到」。分两步：先确认**还能回溯到什么时候**，再决定补抓历史还是转长期抓取。

**第一步：现有日志能回溯多远**（模块自己的日志写在 LSPosed 日志文件里，不受 logcat 环形缓冲影响）

```bash
su -c 'ls -l /data/adb/lspd/log/'
su -c 'grep -h fcmself /data/adb/lspd/log/*.log | head -3'    # 最早一行 = 可回溯起点
su -c 'grep -h fcmself /data/adb/lspd/log/*.log | tail -80'
```

**第二步：系统侧转长期抓取**（logcat 自带轮转，写到文件，不会因为缓冲被冲掉）

```bash
su -c 'logcat -c'                     # 清一次，窗口从此刻开始
su -c 'nohup logcat -v threadtime -b all -f /data/local/tmp/gself.log -r 8192 -n 6 >/dev/null 2>&1 &'
su -c 'ls -l /data/local/tmp/gself.log*'   # 确认在写；6 个文件各 8 MB，通常够 1–2 天
```

复现后取回（先筛关键行，再决定要不要整段）：

```bash
su -c 'grep -hE "FcmSelf|c2dm|firebase|MESSAGING_EVENT|NotificationManager|BroadcastQueue|ActivityManager" \
  /data/local/tmp/gself.log*' | tail -200
su -c 'cp /data/local/tmp/gself.log* /sdcard/Download/'   # 整段带回来
```

**把窗口缩到某个包**（`<包名>` 换成出问题的应用；nagramx 是 `fork.risin42.nagramx`）

```bash
su -c 'grep -hE "<包名>|FcmSelf|c2dm|firebase|MESSAGING_EVENT|Start proc|DeviceIdle|doze" /data/local/tmp/gself.log*' | tail -300
```

**复现时抓这些状态快照**（模块日志只能证明「广播从 AMS 发出去了」，不能证明当时应用是不是停止态——快照负责补这一块）

```bash
su -c "dumpsys package <包名> | grep -m1 -o 'stopped=[a-z]*'"       # 停止态（true = 只有带 flag 的广播能唤醒）
su -c "ps -A | grep <包名>"                                          # 进程在不在、有没有 do_freezer_trap（冻结）
su -c 'dumpsys deviceidle | head -20'                                # 设备是不是在 doze / 白名单状态
su -c "dumpsys notification --noredact | grep -i -A3 <包名> | head -40"   # 通知有没有被 post（第三段）
su -c 'dumpsys alarm | grep -iE "gms|com.google.android.c2dm" | head -20'  # GMS 心跳/重连闹钟有没有被推迟
```

**量化延迟**（判断「滞留」到底发生在哪一段，这是唯一能定性的量）

1. 在另一台设备 / 桌面客户端看那条消息的**发送时间**（Telegram 等应用会显示）。
2. 在本机日志里找它对应的 `wake: <包名>` 时间（同一秒可能有两条，取最近的）。
3. 两者相减：秒级 = 正常；分钟级 = 设备侧排队（doze / 冻结 / 被停止）；小时级 = 服务器或 GMS 侧没推到设备，模块看不到那一段。

**判读：消息卡在哪一段**（对着第 5 节的三段归因看）

| 缺哪一类行 | 说明卡在 |
| --- | --- |
| 完全没有 `c2dm` / `firebase` / `MESSAGING_EVENT` 广播行 | 到达系统之前（服务器 / 网络 / GMS 心跳），模块不介入 |
| 有广播行、没有 `wake:` | 广播没带目标包名（非定向），或没判上——后者属模块 |
| 有 `wake: <包名>`、之后没有 `Start proc` | 广播发出去了但进程没起来（ROM 拦截 / 冻结 / 应用自杀） |
| 有 `Start proc`、没有通知入队行 | 应用侧问题（没解析消息 / 没调 `notify()` / 渠道与权限被拦） |

**按墙钟窗口截取**（比定时循环靠谱）：把「服务端发出时间」和「通知出现时间」各记一个，然后按时间戳 grep
（`threadtime` 格式是 `10-06 12:03:41.123`）：

```bash
su -c 'grep -nE "^10-06 (1[12]):" /data/local/tmp/gself.log*' | head -100
```

**重启也要抓**：重启前最后一步 `su -c 'logcat -c'`，重启后马上起第二条的长抓取；
LSPosed 日志本身会在模块载入时自动记一行，两条路互相兜底。

> 这一节的命令按 toybox / Android `logcat` 的标准写法给，**未在真机上跑过**（我没有设备）；
> 哪条报错把原文贴回来，我按实际改。

## 8. 反馈问题时请附上

- 从重启开始的完整 `FcmSelf` 日志（尤其 `hook target:` 与所有 `hook skip` 行）
- 产物版本号（artifact 名 `Gself-<版本>`）、ROM 名称与版本、GMS 版本号、LSPosed 版本、Android 版本
- 目标应用包名 + 「杀掉应用 → 推送」的复现步骤

## 9. 真机现状参考（2026-09-26 / 2026-10-01 / 2026-10-05，OnePlus/ColorOS）

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

### 2026-10-05 22:13（Android 16 / API 36，ColorOS，分支产物 `Gself-dev-*`）

模块载入与三组挂载点（重启后立刻抓）：

```
22:13:34.026 [fcmself] fcmself 载入 system_server（Android 16 / API 36）
22:13:34.028 [fcmself] hook target: com.android.server.am.BroadcastController#broadcastIntentLocked(25)
22:13:34.033 [fcmself] OplusAppStartup 自启动闸门已关
22:13:34.034 [fcmself] OplusProxyBroadcast 代理已全关
22:13:34.036 [fcmself] Hans GMS 限制已置空
22:13:34.222 [fcmself] OplusProxyWakeLock instance captured
```

运行期（7 分钟内）：

```
22:13:50.129 [fcmself] 通行密钥解限 Hook 已安装        ← GMS 进程
22:14:12.658 [fcmself] 通行密钥解限 Hook 已安装        ← GMS 又重启了一次
22:15:03.882 [fcmself] keep notification: com.termux
22:16:14.331 [fcmself] keep notification: com.android.devicelockcontroller
22:20:03.971 [fcmself] keep notification: com.termux
22:20:30.716 [fcmself] keep notification: mark.via
22:20:31.053 [fcmself] keep notification: com.termux
22:20:44.214 [fcmself] unfreeze 可用（4 参签名）        ← ColorOS 解冻，只打一次
22:20:44.215 [fcmself] wake: com.zhiliaoapp.musically   ← 核心修复命中（抖音收到定向推送）
```

判读：

- `wake:` + `unfreeze 可用（4 参签名）` 说明**核心链路在 Android 16 / ColorOS 上成立**，
  且「只做新包」的挂载点选择正确（`BroadcastController` 在这台机器上存在）。
- `keep notification` 打给 `com.termux` / `mark.via` / `devicelockcontroller` 属**设计行为**：
  拦的是「取消原因为 5 / 10020 / 10021 的整包取消」，对所有包生效、没有白名单；
  自 26.10.5.1 起日志带原因值（`keep notification: <包名>（reason=10020）`），据此判断是不是误拦——
  这些多是 ColorOS 空闲清理或包变化触发的取消。要判断有没有误拦，按第 6 节第 3 条验。
- 没有任何 `hook skip`，也没有 `Gboard 剪贴板 Hook 已安装`：Gboard 那组只在 Gboard 被加载过之后才打日志，
  先确认当前输入法（见第 1 节）；若 Gboard 就是当前输入法却仍无日志，把
  `su -c 'logcat -d | grep -iE "gboard|clipboard"' | head -40` 的结果贴回来。

### 2026-10-05 23:41–23:50 的「wake 洪水」（同一台机，`fork.risin42.nagramx`）

设备重启（22:13）后约 1.5 小时，`wake:` 突然密集出现，随后归于安静：

```
统计：23:41:57.789 → 23:50:42.178 共 77 行 wake:，跨度 524 秒（8.7 分钟），平均 6.9 秒一条；
      其中 12 组是同一秒内两条（多为间隔 10–20 毫秒）；之后到次日 08:12 只有两条 keep notification
```

判读：

- 这是**积压倒灌**的典型形状：短时间、同一包、高密度，之后长时间安静。说明消息不是「一直推不进来」，
  而是**集中在一个窗口被投递给应用**（设备刚脱离 doze / 冻结 / 应用刚变成可投递状态）。
- 它**不能**证明「当时应用是停止态」：模块只看到广播经过 AMS 并补了 flag（`wake:` 只在我们补 flag 时打印），
  应用是运行、冻结还是停止，要按第 7.1 节的状态快照在**当时**抓。
- 它**也不能**证明「消息是服务端晚发的」：模块只覆盖「GMS → 应用」这一段；服务器/GMS 侧的排队要
  用第 7.1 节的「量化延迟」法（拿发送时间减 `wake:` 时间）才能区分。
- 同一窗内两条 `keep notification: fork.risin42.nagramx`（23:48:18 / 23:58:22）说明有整包取消被拦下；
  是设计行为还是误拦，看 `reason=`（自含该改动的构建起打印，分支产物 `Gself-dev-11` 起）。
- 副作用：77 次唤醒本身有电量代价（每次都要拉起或唤醒一次目标应用）。反复出现这种洪水时按第 6 节第 2 条做待机耗电验证。

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

## 10. 真机案例参考（2026-10-06）：消息一夜之间突然滞留，模块是清白的

**症状**：nagramx 消息在前一晚 23:58 前后彻底停更，次日全天无新消息；模块日志里
`keep notification` 正常触发（钩子活着），但**全天 `wake:` 为零**——没有任何一条符合
推送判据的广播经过 system_server。

**排除顺序**（都实测过）：

1. 应用没更新过（`dumpsys package <包名> | grep lastUpdateTime` 是三周前）→ 排除
   「更新后令牌未重注册」；
2. `stopped=false`、通知权限正常、免打扰关 → 排除停止态与通知层；
3. 活体窗口 `grep -cE 'c2dm|firebase|MESSAGING_EVENT'` 为 0 → 排除判据漏放广播
   （有广播就会被 `wake:` 抓到）；
4. `timeout 8 bash -c 'exec 3<>/dev/tcp/mtalk.google.com/443 && echo OK'` 通 →
   排除网络层（Termux 的 curl 可能坏，用这条替代）。

**结论**：ColorOS 夜间清理把 GMS 的 FCM 长连接清断，且 GMS 自己接不回来。断点在
第①段（服务器 → GMS），模块管不到——这正是 1.0.0 删掉上游 GMS 心跳/重连修复
（`ReconnectManagerFix`）的已知代价边界：连接活着时无模块的事，连接断了模块也帮不上。

**恢复**：重启有效（重启后 2 分钟，积压推送批量到达，`wake:` 全命中）；更便宜的先手是
`su -c 'am force-stop com.google.android.gms'` 等 5 分钟（本机当时未恢复，但比重启便宜，
值得先试）。**若此形态频繁复发，就是重新考虑捡回重连修复的依据。**

**判读捷径**：消息突然停更，先 `grep -h fcmself /data/adb/lspd/log/*.log | grep 'wake:'`
——全天为零时别先怀疑钩子，按本节顺序把第①段排掉。

## 11. Monocles Chat 重启后不恢复：候选已定位（2026-10-15，源码侧）

对象版本：monocles chat `Experimental14-v2.3.1`（tag f7803c2，包名
de.monocles.chat，targetSdk 37、compileSdk 37，见该 tag 的 build.gradle）。
结论先行：**最可能卡在应用自己的前台服务类型选择上，与本模块的钩子无
关**；ColorOS 自启动闸门是第二候选。两者用重启后的 logcat 就能分辨。

### 机制（已读 tag 源码核实的部分）

- 清单声明 `.receiver.SystemEventReceiver`（exported=false）收
  BOOT_COMPLETED 与 MY_PACKAGE_REPLACED；`onReceive` 在有启用账户时调
  `Compatibility.startService`，targetSdk≥26 一律走
  `startForegroundService` 并带 `EXTRA_NEEDS_FOREGROUND_SERVICE=true`。
- 服务 `onStartCommand` 见此 extra 即升前台：`startForegroundOrCatch`
  按序选类型——通话中 MICROPHONE（可叠 LOCATION）→ 实时位置 LOCATION
  → 电池白名单内 SYSTEM_EXEMPTED → **授过录音权限 MICROPHONE → 授过
  相机权限 CAMERA** → 兜底 SPECIAL_USE，然后
  `startForeground(id, notification, type)`。
- 平台规则（Android 开发者文档《Changes to foreground service types
  for Android 15》）：targetSdk≥15 的应用不得从 BOOT_COMPLETED 接收器
  拉起 dataSync/camera/mediaPlayback/phoneCall/mediaProjection 类型
  （microphone 自 targetSdk 14 起），违者系统抛
  ForegroundServiceStartNotAllowedException。
- 该异常是 IllegalStateException 子类，正好被 `startForegroundOrCatch`
  的 `catch (IllegalStateException | SecurityException)` 吞掉，只记一行
  `Could not start foreground service`，**没有换 SPECIAL_USE 重试**。
  结果：服务以 startForegroundService 方式启动却没升上前台，随后被
  系统收掉，XMPP 不重连，直到手动打开应用。
- 触发条件很常见：给过录音权限（发语音消息基本都给）+ 不在电池优化
  白名单 + 开机当时无通话无实时位置。反之，在白名单里走
  SYSTEM_EXEMPTED（不受限），没给录音/相机权限走 SPECIAL_USE（不受
  限），都能正常恢复——这解释了「有的机器好、有的机器坏」。

引用精度：清单、SystemEventReceiver、Compatibility、build.gradle 均按
tag 原文核读；`startForegroundOrCatch` 函数段引自 Codeberg 代码索引
（master 4656db0c，tag 之后 3 个提交），tag 逐行一致未核，列未验证。

### 设备上一次分清（重启后先不要打开 Monocles）

```
adb shell dumpsys deviceidle whitelist | grep de.monocles.chat
adb shell dumpsys package de.monocles.chat | grep -E "RECORD_AUDIO|CAMERA" -A1
adb logcat -d | grep -E "Could not start foreground service|ForegroundServiceStartNotAllowed|SystemEventReceiver"
adb shell dumpsys activity services de.monocles.chat
```

- 出现 `Could not start foreground service` 且堆栈含
  ForegroundServiceStartNotAllowedException / microphone ⇒ 上面的应用
  侧机制坐实。临时绕法：把 de.monocles.chat 加入电池优化白名单
  （开机改走 SYSTEM_EXEMPTED），或重启后手动开一次。根治在上游：
  开机来源启动时先用 SPECIAL_USE 升前台，通话开始再换 MICROPHONE。
- logcat 里连 SystemEventReceiver 的动静都没有 ⇒ 广播没送达，查
  ColorOS 自启动闸门（本模块 system 侧的 OplusAppStartup 挂点管这一
  层，按第 9 节日志核对挂点是否命中）。
- 服务在跑但不连 ⇒ 与开机启动无关，转查网络与 XMPP 层。
