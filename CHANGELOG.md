# 更新日志

> 本仓库由 fcmfix 迁移而来。0.9.0 及以前压缩为要点，完整历史见 `git log`。

## 26.10.5.1 —— 对齐 GLOBAL.md 第二十一版

> 规范自第十七版更新到第二十一版，本仓库按最新版收口：CI 少流程、禁旧、出包三要素、清理默认启用并当轮清积压。

### CI

- **删除 `.github/workflows/spec-check.yml`**：第二十版起规范自检由 agent 在会话中完成，不设规范检查工作流；
  「一份职责只留一个工作流」，本仓库唯一工作流是 `.github/workflows/build.yml`（构建 + 出包 + 清理）。
  此前我把它扩成 127 行静态检查属于逆着规范加码，已整文件删除。
- **禁旧**：`runs-on: ubuntu-latest` → `ubuntu-24.04`（`ubuntu-latest` 会打迁移告警，规范明令禁止）；
  Action 保持现行档（`checkout@v7`、`setup-java@v6`、`upload-artifact@v7`）。
- **出包三要素**：`actions/upload-artifact` 补 `if-no-files-found: error`（原有 `name: Gself-<版本>`、`retention-days: 5`）。
  构建 job 权限加 `actions: read`（要查 run 历史），出包本身不需要写权限。
- **总序号跨改名不回退**：工作流由 `android.yml` 改名 `build.yml` 后 `GITHUB_RUN_NUMBER` 归零（旧工作流停在 run#127），
  直接用它会让 `versionCode` 从 1 起、覆盖安装被降级拦截。改成取「本仓库所有工作流最大 run 号 + 1」与
  「现存发行产物第五段 + 1」的较大者（只用查法，不写死现值），发行与非发行构建共用它当 `versionCode`。
- **滚动清理按第二十一版口径**：默认启用、不需逐仓批准；范围按项目名前缀筛选（`Gself-`、遗留 `fcmself-` / `fcmfix-`），
  保留**最近 5 个**（PR 的非发行包同样计入）。清理由 `cleanup_artifacts` job 执行：`needs: build`、`concurrency` 串行、
  PR 事件不执行、权限只有 `actions: write` + `contents: read`、删除前打印完整清单、本次产物不可见就整轮放弃。
  已知代价：PR 包比发行包新时发行包会被清出窗口，用 `gh workflow run build.yml --ref main` 可在 main 上补一次出包。

### 文档

- `AGENTS.md`：版本戳 第十七版 → 第二十一版；删掉「只读规范检查工作流」条目，补上清理默认启用、前缀筛选、
  出包三要素与「CI 出包：有 / CI 发版：未授权」的汇报口径。
- `README.md`：CI 徽章指向改名后的 `Build` 工作流；构建段说明产物走 Actions artifact、不发 Release。
- `docs/glossary.md`：更新「滚动清理」「保留数」口径（最近 5 个，含非发行包）。

### 真机验证（2026-10-05 22:13，OnePlus/ColorOS，Android 16 / API 36）

- 装上分支产物（`Gself-dev-*`）重启后，system_server 三组挂载点全部命中：
  `hook target: BroadcastController#broadcastIntentLocked(25)`、`OplusAppStartup 自启动闸门已关`、
  `OplusProxyBroadcast 代理已全关`、`Hans GMS 限制已置空`、`OplusProxyWakeLock instance captured`。
- 运行期证据：`unfreeze 可用（4 参签名）` + `wake: com.zhiliaoapp.musically`（核心修复命中抖音的定向推送）；
  `keep notification: com.termux / mark.via / com.android.devicelockcontroller`（按值认原因的设计行为，见文档第 9 节）。
- 「只做新包」在真机成立：Android 16 上 `BroadcastController` 存在、ColorOS `unfreezeIfNeed` 4 参签名可用，无 `hook skip`。
- `通行密钥解限 Hook 已安装` 出现两次（GMS 进程重启过）；**`Gboard 剪贴板 Hook 已安装` 未见**——
  待确认 Gboard 是否为当前输入法（若不是，属预期；若是，按文档第 1 节的排查命令继续）。
- 参数个数实测 25（早期样机 19），文档样例行已改成「随 ROM 变化」的写法，不再写死一个数。

### 复查补正（真机日志之后，2026-10-05）

- `Fixes.kt` `notificationFixes`：日志带上命中的取消原因值——`keep notification: <包名>（reason=10020）`。
  真机上 `com.termux` / `mark.via` / `com.android.devicelockcontroller` 在 7 分钟里被拦了 6 次，
  只打包名无法判断拦对没拦对；带上原因值后可直接对照 8 / 10020 / 10021 的实际含义排查误拦。
- 滚动清理的保留窗口按【权威与冲突】**回到主人指示**：只按发行对象计数保留 5 个，`Gself-dev-<构建数>` 不占名额。
  规范第二十一版字面为「Actions artifact 保留最近 5 个」，两者冲突；权威顺序是主人当轮指示 > 规范最新版，
  故按主人指示执行并在此记账。对象范围仍按规范的项目名前缀筛选（`Gself-` 与遗留 `fcmself-` / `fcmfix-`）。
- `docs/verify-on-device.md` 新增「7.1 消息延迟 / 滞留：怎么抓日志」：LSPosed 日志的回溯起点查法、
  `logcat -f` 轮转长期抓取、按「缺哪一类行」判断卡在哪一段、按墙钟窗口截取；命令**未在真机跑过**，已标注。

### 真机证据（2026-10-05 23:41–23:50 洪水，2026-10-06 追记）

- 22:13 重启后约 1.5 小时，`fork.risin42.nagramx` 在 8.7 分钟内被 `wake:` 77 次（平均 6.9 秒一条，
  其中 12 组同秒双发），之后到次日 08:12 归于安静——**积压倒灌**的形状（消息集中在一个窗口投递）。
- 同窗内两次 `keep notification: fork.risin42.nagramx`（23:48:18 / 23:58:22）说明有整包取消被拦下；
  是否误拦要看 `reason=`（自本次提交起打印，对应产物 `Gself-dev-11` / `Gself-dev-12`）。
- 无法从模块日志判定「当时应用是否停止态」「消息是服务端晚发还是设备侧排队」：前者要当时抓
  `dumpsys package` / `ps` 快照，后者要拿发送时间减 `wake:` 时间量化延迟。两条方法已写进
  `docs/verify-on-device.md` 第 7.1 节。

### 待落地（需要主人）

- 清理 job 必须存在于**默认分支**的构建工作流里才算实现：本分支的 PR #13 合并进 `main` 后生效；
  合并前 main 上既没有清理 job，也仍是旧的 `android.yml` + `ubuntu-latest`。

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

### 复查补正（同日）

- **段位口径**（第十六版更正）：`docs/glossary.md` 里当日序号、总序号的段位写反了，改为「当日序号 = 第四段、总序号 = 第五段」。
- **平台声明取证**（第十七版「先查证再动手」）：`Fixes.kt` 原注「Android 15+ 广播出口挪进 `BroadcastController`」不准确。
  查 `aosp-mirror/platform_frameworks_base` 的 `services/core/java/com/android/server/am/`：`BroadcastController.java`
  首次出现在 `android-16.0.0_r1`，`android-14.0.0_r1` 与 `android-15.0.0_r1` 的 am 包下都没有这个文件。
  结论改为「Android 16+（API 36）在 `BroadcastController`，Android 10–15 在 AMS」，同一台设备只挂一处、不做新旧双写。
- **只做新包与不做降级**（第十七版，已落地）：推送唤醒**只实现** Android 16 起的 `BroadcastController`，
  删掉 Android 10–15 的 AMS 回退路径；`minSdk` 29 → 36，旧版本在安装时由系统明确拒绝，README 与 `module.prop`
  写明「Android 16+」，不在文档里假装支持。ColorOS 解冻同理：只认当前 4 参 `unfreezeIfNeed` 签名，
  不再做 3 参旧签名的逐档回退，签名对不上就打一条 `unfreeze 跳过：…`（功能不生效，不静默装作成功）。
  最坏失败模式：类/方法不存在时 `install()` 打 `hook skip 推送唤醒`，整组不生效，不损坏系统。
  与【Ponytail 与工程原则】「不擅自改变兼容范围」存在冲突，按权威顺序（主人当轮指示 = 规范高于一切）执行
  更新的第十七版；若主人要保留 Android 10–15 兼容，回退点是本提交里删掉的那两段。
- **滚动清理对象收窄**（主人批准）：保留名额只算发行对象（`Gself-<五段版本>` 与历史命名 `fcmself-*`），
  PR 的非发行构建 `Gself-dev-<构建数>` 不占名额、按 `retention-days: 5` 过期。原因是实测发现连续的 PR 运行
  会把最新发行产物挤出「最近 5 个」，下载入口会取不到包。
- **继承说明**：滚动清理移植自已关闭、未合并的 PR #12（其分支已删除），保留数由 1 改为 5，并补 PR 隔离与并发延后。
- **README 口径**：维持极简（主人选择），下载与安装命令按规范在每轮汇报里给出，不在 README 里重复。

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
