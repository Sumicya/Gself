## 推送热路径零分配、打包去白装并加体积预算闸门（2026-10-31）

- 语义层快路：`Intent#isExcludingStopped` 每次 intent 解析都会进来，先只做一次掩码比较——只有原生答案本来是 true（带 `0x10` 且不带 `0x20`）才继续走字符串判据，其余直接 `proceed()`。返回值与原方法逐位相同（AOSP `Intent.java:10023`），单测用真值表钉住。
- 挂载点去分配：AMS 广播入口与 ColorOS 受限广播两处，`Intent` 的实参下标在装挂载点时按类型算好（`ActivityManagerService.java:14391`/`:14402`、`BroadcastController.java:710` 的单 `Intent` 参数签名），运行期 `chain.args[idx]` 直取；`filterIsInstance` 每次调用都会新建一个 ArrayList，而这里每次系统广播都要进来一次。通知 Hook 原来两次 `filterIsInstance` 也改成一次索引遍历。
- 判据去分配：`Push.isPush` 从「列表 + `any` + lambda」展开成两次 `String.contains`；`waken` 只取一次目标包名（原来判据与日志各取一次）；`Push.needsStoppedPatch` 按代价排序（位运算 → `contains` → 目标包名）。
- 「热路径不分配对象」从注释升级成可执行的断言：新增 `HotPathTest`，用 `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` 量当前线程累计分配字节——预热 2 万次后跑 20 万次调用，分配量必须 ≤ 8 KB（等于每次 < 0.05 字节），谁在哪天给判据里加了一个 `filterIsInstance`、一个 `listOf` 或一个装箱，CI 当场红。不用 `assumeTrue` 跳过：CI 用 Temurin 21，计数不可用就直接红——跳过在 CI 里看不出来，等于把「量过了」变成「可能没量」。
- 上面这条测试带**阳性对照**：同一个计量器同一台机器上故意分配 2 万个小对象，必须量到 500,000 字节以上，否则判「计量器没在工作」，零分配结论作废。按 `prove-it` 的标准（把错放回去、检查必须报错）补的：一个从来没报过错的检查等于没加。
- 规范 2026-10-31（`selfs` 分支 `arena/d037db35-selfs` head `278bf9b1`）对齐：版本号改为整数拼接 `f"{年%100}.{月}.{日}"`，不用 `%m/%d`（会补零）也不用 `%-m/%-d`（非 glibc 平台不生效），并在取号脚本里按规范给的正则 `^\d{2}\.(1[0-2]|[1-9])\.(3[01]|[12][0-9]|[1-9])\.([1-9]\d*)\.([1-9]\d*)$` 断言，不匹配即退出；`cleanup.py` 的五条判据里，「命名口径」加一条：出现 `26.11.01` 这类补零段按不合规产物删除并打印。
- 删掉两处 `trap … ERR`：运行 74 已实测它在这个步骤外壳里不触发，改成 `if ! ./gradlew … ; then echo "::error::…"; exit 1; fi` 与取号步的显式 `if ! python3 …`，注解由分支自己打，不再留一个不会响的警报器。
- 单测新增两条：`excludesStopped` 的真值表（4 种 flags 组合 + 带高位标志），以及 `Intent.FLAG_EXCLUDE_STOPPED_PACKAGES == 0x10` / `FLAG_INCLUDE_STOPPED_PACKAGES == 0x20` 的常量守卫（值漂了单元测试当场红，而不是真机静默失效）。
- 体积：`packaging.resources.excludes` 剔掉 `kotlin/**`、`META-INF/*.kotlin_module`、`META-INF/*.version`、`META-INF/com/**`、`DebugProbesKt.bin`（运行期不需要：模块不用 kotlin-reflect）；`proguard-rules.pro` 加 `-allowaccessmodification`（本模块没有对外 API，唯一的对外名字已由 `-keepnames` 保住）。`.so` 压缩开关的量法纠正（一次自我打脸）：曾据「`libdexkit.so` 的 `compress_size` 等于 `file_size`」判定 `useLegacyPackaging = true` 没生效并把它删掉，下一轮 CI 直接推翻——同一份代码只差这三行，APK 由 452,315 涨到 651,327 字节（运行记录 41107064782），`libdexkit.so` 由 190,746 变 381,024。真相是 190,746 本来就是**压缩后**的字节（deflate 对 stripped 的 .so 约省 50%），开关一直是有效的，删掉它等于白白多背 190,278 字节（+46%）。已恢复该项配置，并把「增删这两处的实测差值」写进注释，防止下一个人（含我自己）再凭一次读数把它删掉。教训是：读一次 `compress_size` 推断「是否生效」不成立，只有增删变量重跑一遍、看 APK 文件大小的差值才算证据。
- CI 体积闸门拆成两步：`报告产物体积构成` 打印 APK 文件字节、条目压缩后/解压后合计、dex 合计、`lib/` 下 ABI 与前 8 大条目，并用一行 `::notice::` 把结论带出（Actions 的 job 日志要另开页面，注解在 `check-runs/<id>/annotations` 一取就有）；`校验体积预算` 放到 `上传产物` 之后，超预算照样红，但红的是指标、不连产物一起扣掉。校验不止看总数：报告步把 dex、原生库、ABI 写进 `apk-parts.txt`，校验步对 APK ≤ 500,000、dex ≤ 250,000、原生库 ≤ 200,000 三个上限分别报错，并要求 ABI 精确等于 `arm64-v8a`——哪一部分长大了，看注解就知道，不用再从总数里猜。
- 体积口径纠正：预算原先按 artifact 的 `size_in_bytes`（APK 再压一遍得到的 305–320 KB 的 zip）定成 360,000，而闸门量的是 APK，于是同一条构建既「通过」又「超标」。现在统一为 **APK 文件自身的字节数**，预算按实测重定为 500,000。实测（`Gself-26.10.31.8.78`，运行记录 41107375745）：APK 452,315 字节 = 条目压缩后合计 446,265 + 6,050 字节 zip 头与中央目录；其中 `classes.dex` 236,792（52.3%）、`lib/arm64-v8a/libdexkit.so` 190,746（42.2%，deflate 后）、`assets/LICENSE` 14,203（3.1%）、其余合计 10,574，ABI 只有 `arm64-v8a` 一份——dex 加 DexKit 原生库占 94.5%，我们自己那 683 行代码不在体积里。顺带记一笔：上一轮那个「446,265」是条目合计、不是文件大小，正是同一类口径混淆。
- 载入期汇总：`install` 改为返回这一组是否整组装上，`wakeStoppedApps` 收尾打一行 `推送达标：语义层在｜标志层在`（任一层缺、两层全缺各有措辞），排查时不用在两条日志里拼。
- 出包健壮性（含两次自伤与修复）：取号步的两个 `gh api` 调用加了 5 次重试，失败时把 `gh` 原始输出写成 `::error::` 注解。改 heredoc 时把终止符 `PY` 写在第 0 列，整份工作流变成非法 YAML（运行记录 72 零 job 秒失败），已按 10 列缩进修正并用 `bash -n` 逐块校验全部 `run:` 块；接着想用 `trap on_err ERR` 把构建失败的尾巴写成注解，运行记录 74 证明它在本仓库的步骤外壳里不触发（只报 `Process completed with exit code 1`），改成在 python 里 `except BaseException` 捕获并自己 `::error::`，运行记录 75 才第一次读到真实的超标字节数。——2026-10-30 18:00 UTC+8 前后 Actions 接口连续抖动，第 68、69 两轮都卡在 `repos/…/artifacts` 那行 502 直接红掉，与代码无关；重试不改变取号结果。
- README 补上原本缺失的「安装与使用」章（规范章节顺序里的这一节此前一直省略）：下载 artifact、装完在 LSPosed 勾作用域后重启、按 `docs/verify-on-device.md` 第 1、2 节验收、停用也要重启一次。纯文档提交命中 `paths-ignore`，不占号。
- 未验证：真机运行期耗时没有仪器可测（沙箱无设备），本轮用「每次调用是否分配对象」与「快路指令数」作为可核对的替代指标；体积数字取自 APK 文件本身（不再用 artifact 的 `size_in_bytes`），真机未复测。

## 许可证换 GPL-3 全文、补项目限制与随包许可（2026-10-30）

- `LICENSE` 由自写的 569 字节说明换成 GPL-3.0 全文（35149 字节，与 Qself 同源）；GitHub 的许可识别只看默认分支，合并进 `main` 前接口仍返回 `NOASSERTION`（2026-10-30 21:00 UTC+8 实测），合并后才会识别为 GPL-3.0。「为什么用 GPL-3」与三个上游归属写进 README「许可证」章，不写进 `LICENSE`。
- APK 随包附同一份全文（`app/src/main/assets/LICENSE`），对应 GPL-3 §4「二进制发布随附许可全文」；本模块没有界面，§5(d) 的「可点的关于页」无法提供，以 README 许可证章 + 随包 `LICENSE` 呈现，写明若将来加界面再补入口。
- README 新增「项目限制」：不支持锁 bootloader 的 User Product、只在 Android 16 + ColorOS 真机验证、无界面无配置、DexKit 两组功能可能因宿主改版失效。构建章补「产物 ↔ 对应源码」的查法（artifact 第五段 = `run_number`）。
- `README` 功能章按两层挂载改写推送一条，原「补 `FLAG_INCLUDE_STOPPED_PACKAGES`」不再完整。
- 未验证：`assets/LICENSE` 是否被完整打进 APK 由 CI 的「打印产物体积构成」步骤体现，本轮未装机核对。

## 模块安装包瘦身：只留 arm64 与压缩 .so（2026-10-30）

- 起因：用户只要收消息，却看到一个近 1 MB 的模块。资源目录只有 17 KB 矢量图，业务代码不足 30 KB，体积不在我们写的代码上。
- 定位：`app/build.gradle.kts` 既没有 `abiFilters` 也没有 `packaging` 配置，于是依赖（DexKit）自带的 `armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64` 四份 `libdexkit.so` 全部打进包；同时 AGP 4.2 起 `useLegacyPackaging` 默认 false，`.so` 在 APK 里按 store 存（不压缩、4 KB 对齐），原生库字节一分不少地计入体积。
- 改动：`defaultConfig.ndk.abiFilters` 只留 `arm64-v8a`（Android 16 设备全部 64 位，模块只在本机跑）；`packaging.jniLibs.useLegacyPackaging = true`，`.so` 改为安装时解压、APK 内 deflate 存储。两项都不删功能，推送唤醒路径无原生依赖。
- `build.yml` 新增「打印产物体积构成」步骤：打印压缩后/解压后总字节与体积最大的 15 个条目，体积构成在 Actions 日志里可直接核对，不靠自述。
- 第二刀（dex 侧）：`app/proguard-rules.pro` 里手写的 `-keep class org.luckypray.dexkit.** { *; }` 把整个 DexKit API 面钉进 dex；DexKit 2.2.0 自己的 AAR 已用 `consumerProguardFiles` 声明了只保 native 方法的窄规则（源码 `dexkit-android/build.gradle:77` → `dexkit-android/proguard-rules.pro`），AGP 会自动应用，故删除手写宽规则；DexKit 不反射自身（全仓唯一 `Class.forName` 在 `DexKitBridge.kt:653`，查的是平台类 `dalvik.system.BaseDexClassLoader`）。
- 实测（GitHub API 的 artifact `size_in_bytes`，同一套构建）：`26.10.30.4.64` 974,533 字节 → `26.10.30.5.65` 460,962 字节（只留一个 ABI + 压缩 `.so`）→ `26.10.30.6.66` 305,649 字节（再删 DexKit 宽 keep），合计 −68.6%。
- 未验证：`useLegacyPackaging` 下 `System.loadLibrary("dexkit")` 由安装时解压出的库加载；R8 缩小 DexKit 后，通行密钥解限与 Gboard 剪贴板两组仍需真机复测（若 JNI 保名规则未生效，会在各自进程打一条 `hook skip`，不影响推送）。

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
