# 真机探针

模块没有界面，唯一的观测手段是日志与系统侧计数。本文件只列**手机端能直接跑**的命令，
不依赖电脑。全部命令都在 ColorOS 16 / Android 16 上用 `su -c` 跑过。

## 0. 你现在装的是哪一版

先认版本，再谈读数。两条判据互斥：

```bash
# 出现「语义层」= 117 或更早（语义层还在）
# 出现「AMS 广播入口已挂」= 118 及以后（语义层已删）
su -c "grep -hE '推送达标' /data/adb/lspd/log/*.log | sort -u | tail -3"
```

| 输出 | 版本 |
| --- | --- |
| `推送达标：语义层在｜标志层在` | ≤ `Gself-26.11.24.4.117` |
| `推送达标：AMS 广播入口已挂` | ≥ `Gself-26.11.26.1.118` |

## 1. 模块在不在（10 秒）

```bash
su -c "grep -hE '载入 system_server|推送达标|hook active|hook skip' /data/adb/lspd/log/*.log | sort -u"
```

期望至少两行：`Gself 载入 system_server（…）` 与 `推送达标：…`。
`hook active: AMS 广播入口 N 个重载` 里的 N 随 ROM 变，只看有没有，不看是几。

**必须没有**：`hook skip 推送入口（AMS）` —— 出现就是这台 ROM 上推送线整条不工作。

## 2. 推送通不通（3 分钟）

```bash
PKG=<你的包名>
su -c "am force-stop $PKG"
su -c "dumpsys package $PKG | grep -E 'stopped='"     # ① 要 stopped=true
su -c "pidof $PKG"                                    # ② 要空
su -c "logcat -c"
# —— 这里推一条推送 ——
# 等 60 秒
su -c "pidof $PKG"                                    # ③ 要有一个新 pid
su -c "logcat -d -s Gself | tail -20"                 # ④ 要含 wake: $PKG
su -c "logcat -b events -d | grep am_proc_start | grep -E 'c2dm|firebase'"   # ⑤ 平台旁证
```

③④⑤ 全中才算通。④ 是模块自述，⑤ 是平台 EventLog，两者分开看。
`wake:` 每进程只打前 5 条（118 起如此，117 是每条都打），所以 ④ 取 `tail` 不取 `head`。

## 3. 耗电（这是本轮真正要的读数）

模块自己报不了耗电，只能问系统。三条命令，**装新版前后各跑一次**才可比：

```bash
# ① 系统侧：哪个进程在耗
su -c "dumpsys batterystats --charged | grep -E 'Estimated power|system_server|uid ' | head -30"

# ② 唤醒锁：有没有人拿着锁不放
su -c "dumpsys power | grep -E 'Wake Locks|mWakefulness|mHoldingWakeLockSuspendBlocker'"

# ③ 待机：进 doze 用了多久
su -c "dumpsys deviceidle | grep -E 'mState|mLightState|mLastIdleEntryTime'"
```

读法：

- ① 里 `system_server` 的 estimated power 是模块代价的上界（模块跑在里面）。比值比绝对值有用：
  记下「system_server 占比 / 总电量」。
- ② 里如果 `FCMXX`（本模块的唤醒锁 tag）长时间挂在表里，说明解冻没释放，这才是真问题。
- ③ 里 `mState=IDLE` 占比低说明根本没进待机，那时量什么都在噪音里。

**对比口径**：同一台机、同一段时长（比如都放 6 小时）、同一块电量区间（比如都从 100% 到 80%），
换版本前后各跑一次 ①，看 `system_server` 那一行的变化。绝对值没有意义，只看同条件前后的差。

## 4. 一次跑完

```bash
{ echo "=== 版本"; su -c "grep -hE '推送达标' /data/adb/lspd/log/*.log | sort -u | tail -1"
  echo "=== 载入"; su -c "grep -hE '载入 system_server|hook active|hook skip' /data/adb/lspd/log/*.log | sort -u"
  echo "=== 唤醒锁"; su -c "dumpsys power | grep -E 'Wake Locks' -A12"
  echo "=== 待机"; su -c "dumpsys deviceidle | grep -E 'mState|mLightState'"
} > /storage/emulated/0/Download/gself-probe.txt 2>&1
```

产物落 `Download/gself-probe.txt`，直接贴回来。
