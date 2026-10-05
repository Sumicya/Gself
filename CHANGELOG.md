# 更新日志

> 本仓库由 fcmfix 迁移而来。0.9.0 及以前压缩为要点，完整历史见 `git log`。

## 26.10.5 —— 版本单一来源 + CI 滚动清理 + 文档同步

> 这一轮按 Sumicya/selfs 的 GLOBAL.md 第十五版对齐：版本只留一个来源、CI 加滚动清理与静态规范检查、
> 文档回到 Gself 的实际产物与安装方式。三组 Hook 的行为没改。

### 版本（五段，单一来源）

- 发行版本由 Android CI 的「Compute release version」一处算定：`yy.m.d.当日序号.总序号`。
  构建配置只读 `-PversionName` / `-PversionCode`，删掉 `gradle.properties` 里的 `fcmself.buildNumber`
  与 `build.gradle.kts` 里第二套回落版本。
- 总序号（`versionCode`）改用 `github.run_number`。旧写法取「android.yml 历史 push 运行总数」，
  于是 artifact `Gself-26.10.5.4.24` 的 versionCode 是 24，比设备上已装的 26.10.1.100+
  （它们的 versionCode = 构建数）更小，普通升级会被降级拦住。
- 当日序号改用「当天 main 出包运行中 run 号不大于本次的个数」。旧写法取当天 push 运行总数，
  并发运行会算出同一个号（2026-10-05 的 run 108 与 109 都得到 26.10.5.4.x）；现在分别是 3、4。
- 修复当天起点：`date -u -d "$local_day 00:00:00"` 里 TZ 不参与输入解析，窗口实际从北京时间 08:00 起，
  会漏算北京时间 0–8 点的运行；改成显式 `+08:00` 偏移。
- 非发行构建（PR 检查、手动构建其它分支、本地构建）写 `dev-<构建数>`，不伪造发行序号。

### CI

- 新增 `.github/scripts/cleanup_artifacts.py` 与 `cleanup_artifacts` job（移植自已关闭、未合并的 PR #12，保留数由 1 改为 5，并加上 PR 隔离、并发延后与「本次产物不可见就整轮放弃」）：main 出包成功后只保留最近 5 个
  artifact（首次运行清掉既有积压）。范围按该 workflow 的 run id 界定、完整分页、按创建时间倒序，
  并发更新的对象延迟处理；本次运行的 artifact 不可见就整轮放弃；权限只有 `actions: write` + `contents: read`，
  与同仓库其它清理串行；PR 检查不执行清理。
- `android.yml` 增 `workflow_dispatch`，且只有 ref 是 main 时才算发行 / 清理；产物名与上传路径不变。
- `spec-check.yml` 扩成静态检查：AGENTS.md 指针与版本戳（执行的规范版本写在检查里，与 AGENTS.md 必须一致）、
  常驻规则条目、版本单一来源、禁止自动发版（工作流 + `.github/scripts`）、写权限最小化
  （只允许 `android.yml` 的清理 job 拿 `actions: write`）、滚动保留策略落地。规范检查保持 `contents: read`。
- 清理脚本先跑 `--dry-run` 验证：当前 89 个 artifact 里 87 个属于本工作流，保留 5 个、计划删除 82 个。

### 文档

- README 增补「下载与安装」：从 Actions artifact 取 `Gself-<版本>.apk`（id 在命令里自己算、产物按前缀过滤），
  `su -c cp` 到 `/data/local/tmp` 再 `pm install`，末尾带本地清理；并补「相关文档」一节。
- `docs/verify-on-device.md`：安装步骤与作用域改成 Gself 的实际值（`Gself-<版本>.apk`，
  作用域 system + GMS + Gboard），验证状态表按本轮证据重写。
- `docs/build-and-sign-termux.md` 重写为「本地构建（非发行版本）+ 自备密钥签名」，
  删掉早已不存在的 release 未签名产物描述。
- `scripts/sign-apk.sh`：环境变量改 `GSELF_KEYSTORE` / `GSELF_KEY_ALIAS`，安装提示改用 `/data/local/tmp`。
- 新增 `docs/glossary.md` 术语表；`AGENTS.md` 补齐本项目的版本口径、查法与滚动清理授权范围。

### 未验证

- 真机：本轮没上设备，`docs/verify-on-device.md` 里的 26.10.1 全链路仍是「未验证」。
- 滚动清理的实删：只在本机跑过 `--dry-run`；实删要等本分支合并进 main 后的下一次出包。

## 26.10.1 —— Gself：三合一 + 换 GPL-3.0

> 把三个项目合成一个 libxposed 模块：fcmself（推送/通知/ColorOS，system_server）、
> GooglePasswordManagerUnlock（通行密钥解限，GMS 进程）、GboardHook（剪贴板，Gboard 进程）。
> 对外改名 Gself（applicationId `sumicya.gself`），内部包名与入口类 `sumicya.fcmself.XposedMain`
> 不变，CI 的 R8 入口校验照旧。整仓改用 **GPL-3.0-or-later**（并入的 GboardHook 是 GPL-3.0，合并作品须同证）。

### 新增

- `onPackageLoaded` 按包名分发：`com.google.android.gms` → 通行密钥解限；Gboard → 剪贴板。
- `Gms.kt` 移植通行密钥解限：DexKit 稳定字符串定位来源解析器，**唯一候选才 Hook**（保留其安全边界），
  来源构造函数记录 origin、解析器据其改写。加 `org.luckypray:dexkit:2.2.0` 依赖。
- `Gboard.kt` 移植剪贴板：改写 `ClipboardContentProvider#query` 的时间下限与 `limit 5`（默认 10 条、过期时间放宽到实际不限），
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
