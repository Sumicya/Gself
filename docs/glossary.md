# 术语表

本仓库与全局规范里的术语，按出现顺序排列；新增术语时同步这里。

## 模块与 Hook

| 术语 | 含义 |
| --- | --- |
| 模块（module） | 本仓库的 LSPosed 模块 Gself；无界面、无配置、不写文件，装完勾作用域重启即生效 |
| 作用域（scope） | LSPosed 里允许模块代码注入的进程名单；本模块是 `system` + `com.google.android.gms` + Gboard |
| Hook | 在宿主进程里拦截某个方法，在调用前后插入自己的代码 |
| 挂载点 | 被 Hook 的具体方法（如 `BroadcastController#broadcastIntentLocked`） |
| system_server | Android 的系统服务进程；推送唤醒、通知保留、ColorOS 放行三组 Hook 都在这里 |
| GMS | `com.google.android.gms`，Google Play 服务；通行密钥解限这组 Hook 在它的进程里 |
| Gboard | `com.google.android.inputmethod.latin`，Google 输入法；剪贴板改写在这组 |
| libxposed API | LSPosed 的模块 API；本仓库按 102 编译，最低要求 101（102 是纯增量） |
| Protective 模式 | `module.prop` 的 `exceptionMode`：模块自身抛的异常被框架记录并按「没有 hook」处理，不拖垮 system_server |
| 入口类 | `sumicya.fcmself.XposedMain`，写在 `java_init.list` 里，必须原样保留（R8 改名会导致模块静默不加载） |
| R8 | Android 的压缩 / 混淆器；本仓库 debug 与 release 都开 `minify`，出口由 `proguard-rules.pro` 保住 |
| DexKit | 运行期按字符串搜 dex 的库；通行密钥解限与 Gboard 剪贴板用它定位混淆后的目标方法 |

## 推送链路

| 术语 | 含义 |
| --- | --- |
| 推送族（push family） | action 里含 `c2dm` 或 `firebase` 的广播，即 FCM 及其变体；本模块唯一的介入判据 |
| 定向推送 | 带明确目标包名的推送广播（显式 component 或 `package`），`Push.isTargeted` 的判据 |
| `FLAG_INCLUDE_STOPPED_PACKAGES` | Android 的广播标志：置位后可以唤醒处于停止态的应用；原生系统发推送会带，GMS 常常不带 |
| 停止态（stopped） | `am force-stop` 之后的 `stopped=true` 状态，不带上述标志的广播收不到 |
| 冻结（frozen） | ColorOS 把后台应用冻住（`do_freezer_trap`），`stopped=false`；从最近任务划掉通常是冻结而不是停止 |
| 三段归因 | 推送排查的三段：① 服务器 → GMS ② GMS → 应用 ③ 应用 → 通知栏；本模块只管第 ② 段 |

## 通行密钥（passkey）

| 术语 | 含义 |
| --- | --- |
| 通行密钥 | 用生物识别 / 屏幕锁替代密码的登录凭据，由 Google 密码管理器提供 |
| 特权浏览器允许列表 | GMS 只允许 Chrome 等「特权浏览器」调用通行密钥的检查；本模块去掉它，非 Chrome 浏览器也能用 |
| 来源解析器 | GMS 里解析调用方 origin 的方法；用稳定字符串（`privilegedAllowlist` 等）定位，只有唯一候选才 Hook |

## 版本与发布

| 术语 | 含义 |
| --- | --- |
| 只做新包 | GLOBAL.md 第十七版的规则：涉及平台新机制的只实现新机制，不做旧机制双写与逐档回退；不支持的环境在构建或安装时明确拒绝 |
| minSdk | 最低支持的 Android 版本；本模块 = 36（Android 16），旧版本由系统在安装时拒绝，文档不假装支持 |
| 五段版本 | 全局发行版本格式 `yy.m.d.当日序号.总序号`，例：`26.10.5.4.24` 只是格式示例 |
| 总序号 | 第五段，等于 `versionCode` 等于 `github.run_number`；本工作流第几次运行，单调递增 |
| 当日序号 | 第四段，当天 main 分支出包运行里 run 号不大于本次的个数，含本次、从 1 起 |
| 出包运行 | 占用当日序号的运行：main 的 `push` 与从 main 触发的 `workflow_dispatch` |
| 非发行版本 | 取不到发行序号时的版本名，形如 `dev-<构建数>`；PR 检查、其它分支与本地构建都用它 |
| 单一版本来源 | 版本只在 Android CI 的「Compute release version」算一次，产物名、artifact 名与构建元数据都从它生成 |
| artifact（Actions 产物） | GitHub Actions 的上传产物；本仓库只发 `Gself-<版本>.apk`，不发 Release |
| Release / tag | GitHub 的发行与标签；本仓库不发 Release、不打 tag |
| 滚动清理 | 出包成功后自动删除旧 artifact，只保留最近 N 个（本仓库 N = 5，由 `KEEP_ARTIFACT` 指定） |
| `retention-days` | artifact 的时间兜底过期天数（本仓库 5 天），不代替按数量的滚动清理 |
