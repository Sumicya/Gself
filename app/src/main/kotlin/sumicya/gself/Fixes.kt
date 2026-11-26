package sumicya.gself

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.WorkSource
import android.service.notification.NotificationListenerService

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 全部挂载点：三组，每组一个顶层扩展函数，由 [XposedMain] 直接调用。
 *
 * 只剩 AOSP 与 ColorOS/Oplus 两条线：真机 `service list` 里没有 powerkeeper / millet /
 * smartpower，MIUI 那套挂载点一次都没命中过，已删。非 ColorOS 机器上第三组整组 `hook skip`，
 * 不影响前两组。
 */

// ---------- 1. 推送唤醒（核心） ----------

/**
 * 让推送广播绕过「停止态不得投递」，只挂一层：AMS 的广播入口，补 `FLAG_INCLUDE_STOPPED_PACKAGES`。
 *
 * 为什么只这一层（2026-11-24 按第一性原理砍掉的另一层）：
 * - AOSP 判定「要不要排除停止态应用」的唯一枢轴是 `Intent.isExcludingStopped()`（`Intent.java:10023`），
 *   它的实现就一行：`(mFlags & (0x10|0x20)) == 0x10`。**只要 0x20 在位，它必然返回 false**，
 *   `Intent.java:7200` 原文「If both of these flags are set, this one wins」。
 * - 全系统给广播补 `0x10` 的地方只有一处：`BroadcastController.java:922`
 *   （「By default broadcasts do not go to stopped apps」），在 `broadcastIntentLocked` 里、补在**入参那个
 *   intent 上**。标志层恰好挂在同一入口之前，先补 0x20 再让原方法跑，于是 922 补的 0x10 与它并存，
 *   而 0x20 胜出。
 * - 被砍掉的语义层当时挂 `Intent#isExcludingStopped`，那是个在 `IntentResolver#buildResolveList` 里
 *   每个 intent 解析都要问一次的方法（`IntentResolver.java:729`），广播/活动/服务/provider 四个域共用。
 *   它的收益只剩「标志层漏掉的推送由它兜底」，而真机上一次 `决策入口兜底` 都没出现过。
 *   代价是全系统每次解析都进一次 Xposed 桥，收益是零，因此删除。
 */
fun Hook.wakeStoppedApps() {
    if (install("推送入口（AMS）") { broadcastEntryFlags() }) {
        trace("推送达标：AMS 广播入口已挂")
    } else {
        trace("推送达标：AMS 广播入口没挂上——这台 ROM 上推送唤醒不工作")
    }
}

/** 逐条日志的出声配额：热路径上的东西只在前几条出声，之后静默。 */
private const val WAKE_LOG_LIMIT = 5
private val wakeLogged = AtomicInteger()
private val keptNotifications = AtomicInteger()
private val droppedRejects = AtomicInteger()

/** 标志层：AMS 每个带 `Intent` 参数的广播重载都挂，按名字取全部重载，ROM 增删参数不影响命中。 */
private fun Hook.broadcastEntryFlags() {
    val ams = classOf("com.android.server.am.ActivityManagerService")
    var hooked = 0
    for (name in BROADCAST_ENTRIES) {
        for (m in intentOverloads(ams, name)) {
            // Intent 的下标装挂载点时定下来（按类型算，ROM 前后插参数都不影响），运行期直接取：
            // filterIsInstance 每次调用都新建 ArrayList，而这里每次系统广播都进来一次。
            val intentIndex = m.parameterTypes.indexOfFirst { it == Intent::class.java }
            hook(m) { chain ->
                (chain.args.getOrNull(intentIndex) as? Intent)?.let { waken(it) }
                chain.proceed()
            }
            hooked++
        }
    }
    trace("hook active: AMS 广播入口 $hooked 个重载")
}

private val BROADCAST_ENTRIES = listOf("broadcastIntent", "broadcastIntentWithFeature", "broadcastIntentLocked")

/** 某个广播入口名字下所有带 `Intent` 参数的重载（ROM 逐版本增删参数，只按名字与类型认）。 */
private fun intentOverloads(clazz: Class<*>, name: String): List<Method> =
    clazz.declaredMethods
        .filter { it.name == name && it.parameterTypes.any { p -> p == Intent::class.java } }
        .onEach { it.isAccessible = true }

/** 补 `FLAG_INCLUDE_STOPPED_PACKAGES` 并解冻目标；判据在 [Push.needsFlag]，这里只执行。 */
private fun waken(intent: Intent) {
    // 全系统每条广播都进来，所以顺序是代价顺序：先一次位运算挡掉已补过的，再两次 contains 挡掉非推送，
    // 最后才取目标包名（要问 component / package）。绝大多数广播在前两步就退出。
    // 同一条广播会依次穿过 broadcastIntent / WithFeature / Locked，第 2、3 次进来时标志已补好。
    if (!Push.needsFlag(intent.action, intent.flags)) return
    val target = Push.targetOf(intent) ?: return
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    // 出声前 5 条：这行是「推送到底有没有被放行」的唯一真机信号，不能删；
    // 但每条推送一行是常驻开销，所以只在前几条打。
    if (wakeLogged.incrementAndGet() <= WAKE_LOG_LIMIT) trace("wake: $target（${intent.action}）")
    unfreeze(target)
}

// ---------- 2. 通知不被自动清除 ----------

/**
 * 忽略「包发生变化」这一类整包取消。
 *
 * AOSP 自己就会这么取消：`NotificationManagerService` 的 `mPackageIntentReceiver` 把 reason 定为
 * `REASON_PACKAGE_CHANGED`（`NotificationManagerService.java:2199`），包被移除、或被禁用时调用
 * `cancelAllNotificationsInt(..., reason)`（`:2281`）。所以这条 Hook 是**故意偏离原生**的取舍：
 * 换来的是 ROM 的「包变化」清理不再抹掉推送通知，代价是应用被卸载 / 禁用后它的通知可能留在栏里
 * （要等别的取消路径清掉，例如用户手动清除、渠道删除、用户切换）。
 *
 * 不在拦截时去查包是否还装着来区分「该清」与「不该清」：这个 Hook 跑在可能已持有通知锁的调用方线程上，
 * 在里面回调 PackageManager 有造成 system_server 锁序反转的风险，代价大于收益。日志里带 reason 值，
 * 先用真机数据确认 reason=5 到底会不会落在「包已删除」上，再决定要不要收窄。
 */
fun Hook.notificationFixes() {
    install("cancelAllNotificationsInt") {
        val nms = classOf("com.android.server.notification.NotificationManagerService")
        hook(find(nms, "cancelAllNotificationsInt")) { chain ->
            // ponytail: reason 按值认，不按下标——ROM 签名里 pkg / reason 的位置都随版本漂移，
            //             而 reason 是唯一取值落在这三个数里的 int。代价是别的 int 参数恰好等于 5 / 10020 / 10021 时
            //             会误拦一次取消（只影响「通知没被清掉」，不影响投递）。
            //             日志里带上命中的 reason 值：真机日志按值判定误拦（例如出现 reason=5 却并非包变化）。
            // 一次遍历取「第一个落在拦截集合里的 int」与「第一个 String」，不建列表：
            // 这条 Hook 每次整包取消都会进来，而 `filterIsInstance` 每次都要新建 ArrayList。
            var reason: Int? = null
            var pkg: String? = null
            for (i in chain.args.indices) {
                when (val arg = chain.args[i]) {
                    is Int -> if (reason == null && arg in BLOCKED_REASONS) reason = arg
                    is String -> if (pkg == null) pkg = arg
                }
            }
            val hit = reason ?: return@hook chain.proceed()
            // 只在头几次出声：整包取消在装包/更新时是成批来的，逐条记就是洪水。
            if (keptNotifications.incrementAndGet() <= 5) trace("keep notification: $pkg（reason=$hit）")
            null
        }
    }
}

// ---------- 3. ColorOS / Oplus ----------

/** OPPO / OnePlus：自启动闸门、代理广播、进程冻结、Hans 后台管理，一律按 AOSP 语义放开。 */
fun Hook.oplusFixes() {
    install("OplusProxyWakeLock") {
        hook(ctor(classOf("com.android.server.power.OplusProxyWakeLock"))) { chain ->
            chain.proceed()
            if (wakelock.compareAndSet(null, chain.thisObject)) trace("OplusProxyWakeLock instance captured")
            null
        }
    }

    install("OplusAppStartupManager") {
        hook(find(classOf(STARTUP), "shouldPreventSendReceiverReal")) { false }
        val strategy = classOf("$STARTUP\$OplusStartupStrategy")
        hook(find(strategy, "isGoogleRestricInfoOn", Int::class.javaPrimitiveType!!)) { false }
        trace("OplusAppStartup 自启动闸门已关")
    }

    // 只把推送挡在代理之外：一律 NOT_INCLUDE 会让全系统广播都不合批（2026-11-24 真机耗电）。认错只会「不进代理」。
    install("OplusProxyBroadcast") {
        val notInclude = field(classOf("com.android.server.am.OplusProxyBroadcast\$RESULT"), "NOT_INCLUDE").get(null)
        hook(find(classOf("com.android.server.am.OplusProxyBroadcast"), "shouldProxy")) { chain ->
            if (Push.anyPushAction(chain.args)) notInclude else chain.proceed()
        }
    }

    install("Hans GMS 限制") {
        val hans = classOf("com.android.server.hans.scene.OplusBgSceneManager")
        hook(find(hans, "registerGmsRestrictObserver")) { null }
        hook(find(hans, "updateGmsRestrict")) { null }
        trace("Hans GMS 限制已置空")
    }

    // ColorOS may revoke Google-core network access after the first unlock when its connectivity
    // probe runs before the user's VPN/proxy is ready. These are separate hooks so a ROM-specific
    // mismatch in one path does not disable the other Oplus fixes.
    install("Google 联网防火墙") { googleNetworkFirewallFix() }
    install("Google 受限广播") { googleRestrictionBroadcastFix() }
}

/**
 * At first unlock, ColorOS Battery may treat a failed Google connectivity probe as a reason to
 * write a reject-all firewall rule for Google core packages. Only override this on builds that
 * declare IgnoreGmsUserSet, where the user-facing Google network switches are not available.
 */
private fun Hook.googleNetworkFirewallFix() {
    val service = classOf(OPLUS_NETWORK_MANAGEMENT_SERVICE)
    val intType = Int::class.javaPrimitiveType!!
    val target = find(service, "setFirewallUidRuleForNetworkType", intType, intType, intType)
    check(target.returnType == java.lang.Void.TYPE) {
        "Unexpected return type for ${target.declaringClass.name}#${target.name}: ${target.returnType.name}"
    }

    hook(target) { chain ->
        val networkType = chain.args.getOrNull(0) as? Int ?: return@hook chain.proceed()
        val uid = chain.args.getOrNull(1) as? Int ?: return@hook chain.proceed()
        val rule = chain.args.getOrNull(2) as? Int ?: return@hook chain.proceed()
        if (rule != FIREWALL_RULE_REJECT) return@hook chain.proceed()

        val context = systemContext ?: return@hook chain.proceed()
        val shouldDrop = shouldDropGoogleFirewallReject(
            uid = uid,
            rule = rule,
            googleAppIds = googleNetworkAppIds(context),
            batteryIgnoresGmsUserSet = batteryIgnoresGoogleNetworkSetting(context),
        )

        if (shouldDrop) {
            if (droppedRejects.incrementAndGet() <= 5) {
                trace("Oplus Google network reject dropped: uid=$uid, type=$networkType")
            }
            null
        } else {
            chain.proceed()
        }
    }
    trace("Oplus Google network firewall hook 已安装")
}

/**
 * ColorOS broadcasts this after the same failed probe; system_server listeners use it to
 * downgrade Google wakeup alarms and standby state. Clear only the restrictive transition.
 */
private fun Hook.googleRestrictionBroadcastFix() {
    // 只在 ColorOS 上装：OplusAppStartupManager 不存在就抛 Missing，由 install 记一条 hook skip，
    // 不给 AOSP 机器挂这个额外的广播入口。
    classOf(STARTUP)

    // AMS 与 BroadcastController 都可能是最外层入口，挂上第一个能用的就走（AMS 会转到 BC，不必两边都挂）。
    for (className in listOf("com.android.server.am.ActivityManagerService", CONTROLLER)) {
        val clazz = classIfExists(className) ?: continue
        val targets = intentOverloads(clazz, "broadcastIntentWithFeature")
        if (targets.isEmpty()) continue

        for (target in targets) {
            val intentIndex = target.parameterTypes.indexOfFirst { it == Intent::class.java }
            hook(target) { chain ->
                val intent = chain.args.getOrNull(intentIndex) as? Intent
                if (intent?.action == GOOGLE_RESTRICT_CHANGE &&
                    intent.getBooleanExtra(EXTRA_RESTRICT_ENABLE, false)
                ) {
                    intent.putExtra(EXTRA_RESTRICT_ENABLE, false)
                    trace("Oplus Google restrict broadcast cleared in system_server")
                }
                chain.proceed()
            }
        }
        trace("Oplus Google restrict broadcast hook active: ${clazz.name}")
        return
    }
    throw NoSuchMethodError("broadcastIntentWithFeature(Intent)")
}

private fun batteryIgnoresGoogleNetworkSetting(context: Context): Boolean = runCatching {
    val battery = context.packageManager.getApplicationInfo(
        BATTERY_PACKAGE,
        PackageManager.GET_META_DATA,
    )
    battery.metaData?.get(IGNORE_GMS_USER_SET)?.toString() == "true"
}.getOrDefault(false)

private fun googleNetworkAppIds(context: Context): Set<Int> =
    GOOGLE_NETWORK_PACKAGES.mapNotNull { packageName ->
        runCatching {
            context.packageManager.getPackageUid(packageName, 0) % ANDROID_PER_USER_RANGE
        }.getOrNull()
    }.toSet()

/** Pure policy predicate, kept separate so the user-setting and multi-user guards are testable. */
internal fun shouldDropGoogleFirewallReject(
    uid: Int,
    rule: Int,
    googleAppIds: Set<Int>,
    batteryIgnoresGmsUserSet: Boolean,
): Boolean =
    batteryIgnoresGmsUserSet &&
        rule == FIREWALL_RULE_REJECT &&
        uid >= 0 &&
        uid % ANDROID_PER_USER_RANGE in googleAppIds

/** 空 WorkSource 复用一份：`unfreezeIfNeed` 只读它，既不持有也不改。 */
private val EMPTY_WORK_SOURCE = WorkSource()

/**
 * 解冻目标应用（ColorOS）：调用之前捕获的 `OplusProxyWakeLock.unfreezeIfNeed`。
 * 由 [wakeStoppedApps] 在放行推送广播时调用；失败不影响广播放行。
 */
private fun unfreeze(target: String) {
    val lock = wakelock.get() ?: return
    val uid = uidOf(target) ?: return
    // 只做新包（项目约定）：只认当前 ColorOS 的 4 参签名（尾部带 owner），
    // 不做 3 参旧签名的逐档回退；签名对不上就明确跳过，不静默装作解冻过。
    val method = knownUnfreeze ?: lock.javaClass.declaredMethods
        .firstOrNull { it.name == "unfreezeIfNeed" && it.parameterCount == 4 }
        ?.apply { isAccessible = true }
        ?: run {
            trace("unfreeze 跳过：没有 4 参 unfreezeIfNeed 签名")
            return
        }
    // 每条放行的推送都会进来，所以两件事都不做：不新建 WorkSource（复用一份空的），
    // 也不在失败时逐条打日志——失败会持续失败，那是每推送一行 logcat。
    val failed = runCatching { method.invoke(lock, uid, EMPTY_WORK_SOURCE, WAKELOCK_TAG, WAKELOCK_OWNER) }.isFailure
    if (failed && knownUnfreeze == null) {
        trace("unfreeze 跳过：4 参签名调用失败")
        return
    }
    // 「找到了可用签名」只记这一次，之后每条推送静默调用
    if (knownUnfreeze == null) {
        knownUnfreeze = method
        trace("unfreeze 可用（4 参签名）")
    }
}

/** system Context 只为给包名换 uid（ColorOS 解冻用），入口跑在 system_server 里，直接取现成的。 */
private val systemContext: Context? by lazy {
    runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(current) as Context
    }.getOrNull()
}

/** 包名 → uid，带缓存：`getPackageUid` 是 Binder 调用，而这里每条放行的推送都进来。 */
private val uidCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

private fun uidOf(pkg: String): Int? = uidCache[pkg] ?: (systemContext
    ?.let { ctx -> runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull() }
    ?.takeIf { it >= 0 && uidCache.size < 64 }
    ?.also { uidCache[pkg] = it })

private val wakelock = AtomicReference<Any?>()

@Volatile
private var knownUnfreeze: Method? = null

/**
 * 被忽略的通知取消原因：AOSP 的 `REASON_PACKAGE_CHANGED`(5；8 是 REASON_APP_CANCEL)，
 * 以及 ColorOS 15 / OxygenOS 15 自造的 10020 / 10021。
 */
private val BLOCKED_REASONS = setOf(NotificationListenerService.REASON_PACKAGE_CHANGED, 10020, 10021)

private const val CONTROLLER = "com.android.server.am.BroadcastController"
private const val STARTUP = "com.android.server.am.OplusAppStartupManager"
private const val OPLUS_NETWORK_MANAGEMENT_SERVICE = "com.android.server.net.OplusNetworkManagementService"
private const val BATTERY_PACKAGE = "com.oplus.battery"
private const val IGNORE_GMS_USER_SET = "IgnoreGmsUserSet"
private const val GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change"
private const val EXTRA_RESTRICT_ENABLE = "restrict_enable"
private const val FIREWALL_RULE_REJECT = 2
private const val ANDROID_PER_USER_RANGE = 100_000
private val GOOGLE_NETWORK_PACKAGES = listOf(
    GMS,
    "com.google.android.gsf",
    "com.android.vending",
    "com.google.android.configupdater",
)
private const val WAKELOCK_TAG = "FCMXX"
private const val WAKELOCK_OWNER = "Gself"
