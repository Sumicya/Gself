package sumicya.fcmself

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
 * 只剩 AOSP 与 ColorOS/Oplus 两条线。真机 `service list` 里没有 powerkeeper / millet /
 * smartpower，MIUI 那套挂载点在这台设备上从未命中过一次，删掉；非 ColorOS 的机器上
 * 第三组会整组 `hook skip`，不影响前两组。
 */

// ---------- 1. 推送唤醒（核心） ----------

/**
 * 让推送广播绕过「停止态不得投递」：两层挂载，一层管语义，一层管标志。
 *
 * 语义层（`停止态决策`）：AOSP 判定「要不要排除停止态应用」只有一处枢轴——`Intent.isExcludingStopped()`
 * （android-16.0.0_r1 `core/java/android/content/Intent.java:10023`）。`IntentResolver#buildResolveList`
 * 在 `IntentResolver.java:729` 取值，`:749` 据此跳过停止态过滤器；`BroadcastSkipPolicy` 全文没有第二处
 * 停止态判断。在这一层对定向推送答「不排除」，不依赖哪个 AMS 重载进来，也不怕 ROM 改签名。
 *
 * 标志层（`推送入口（AMS）`）：在 `ActivityManagerService` 所有带 `Intent` 参数的广播重载上补
 * `FLAG_INCLUDE_STOPPED_PACKAGES`——原生系统发 boot_completed 就是加这个标志
 * （`ActivityManagerService.java:5144`），真机 shell 对照实验也证明只有带它的广播才能投递到停止态应用。
 * 补标志必须赶在 `BroadcastController#broadcastIntentLocked` 复制 intent（`:896`）之前：复制之后
 * 系统会给**副本**补 `FLAG_EXCLUDE_STOPPED_PACKAGES`（`:922`，注释原文「By default broadcasts do not
 * go to stopped apps」），那时改原件已经晚了。这也是 2026-10-30 前挂 BC 从不命中的同一处机制。
 *
 * 两层的分工是可观测的：标志层命中时语义层静默（标志已让枢轴返回 false），标志层漏掉的推送由语义层
 * 兜底并打一行 `决策入口兜底`——这行同时是「还有没挂上的 AMS 入口」的客观信号。
 */
fun Hook.wakeStoppedApps() {
    install("停止态决策") { stoppedFilterDecision() }
    install("推送入口（AMS）") { broadcastEntryFlags() }
}

/** 已补标志的推送条数：只看「哪一层在干活」，不参与判据。 */
private val patchedPushes = AtomicInteger()

/** 语义层命中条数；0 而标志层一直在补，说明枢轴没被调用（被内联或被 ROM 改写）。 */
private val decisionHits = AtomicInteger()

/**
 * 语义层：把 `Intent.isExcludingStopped()` 对定向推送改答 false。
 *
 * 这个方法是 AOSP 的公共方法（每次 intent 解析问一次），ROM 一般不改名；它只有三行、理论上可能被
 * ART 内联，所以标志层保留——两层任何一层活着，推送都不会因为停止态被丢。
 */
private fun Hook.stoppedFilterDecision() {
    hook(find(Intent::class.java, "isExcludingStopped")) { chain ->
        val intent = chain.thisObject as? Intent ?: return@hook chain.proceed()
        if (!Push.isTargetedPush(intent)) return@hook chain.proceed()
        val hits = decisionHits.incrementAndGet()
        // 只在标志层漏了这条时出声：前 3 次逐条记，之后每 100 次记一次，避免洪水
        if (intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES == 0 && (hits <= 3 || hits % 100 == 0)) {
            trace("决策入口兜底：${Push.targetOf(intent)}（${intent.action} 未带 0x20，第 $hits 次）")
        }
        false
    }
    trace("停止态决策已接管：Intent#isExcludingStopped")
}

/** 标志层：AMS 每个带 `Intent` 参数的广播重载都挂，按名字取全部重载，ROM 增删参数不影响命中。 */
private fun Hook.broadcastEntryFlags() {
    val ams = classOf("com.android.server.am.ActivityManagerService")
    var hooked = 0
    for (name in BROADCAST_ENTRIES) {
        for (m in intentOverloads(ams, name)) {
            hook(m) { chain ->
                // 签名里只有一个 Intent 参数，按类型认，ROM 在前后插参数都不影响
                chain.args.filterIsInstance<Intent>().firstOrNull()?.let { waken(it, "AMS#$name") }
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

/** 补 `FLAG_INCLUDE_STOPPED_PACKAGES` 并解冻目标；判据全在 [Push.needsStoppedPatch]，这里只执行。 */
private fun waken(intent: Intent, where: String) {
    if (!Push.needsStoppedPatch(intent)) return
    val target = Push.targetOf(intent) ?: return
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    if (patchedPushes.incrementAndGet() == 3 && decisionHits.get() == 0) {
        trace("停止态决策未被调用：3 条推送已补标志，Intent#isExcludingStopped 一次未进（可能被内联）")
    }
    unfreeze(target)
    trace("wake: $target（$where）")
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
            val reason = chain.args.filterIsInstance<Int>().firstOrNull { it in BLOCKED_REASONS }
                ?: return@hook chain.proceed()
            val pkg = chain.args.filterIsInstance<String>().firstOrNull()
            trace("keep notification: $pkg（reason=$reason）")
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

    // 代理广播整个关掉：AOSP 没有这个东西，广播一律由系统自己投递，不代跑。
    // ponytail: 不再逐条判「实参里有没有推送 action」，而是一律返回 NOT_INCLUDE——省掉了从散字符串
    //             实参里认 action / 包名的整套启发式（旧 Push.actionIn / packageIn + 包名正则）。
    //             代价是所有广播都不再走 ColorOS 的代理合批，这部分功耗回到系统自己身上。
    //             真机若发现待机耗电明显上升，把「实参里有推送 action」的判据加回来。
    install("OplusProxyBroadcast") {
        val notInclude = field(classOf("com.android.server.am.OplusProxyBroadcast\$RESULT"), "NOT_INCLUDE").get(null)
        hook(find(classOf("com.android.server.am.OplusProxyBroadcast"), "shouldProxy")) { notInclude }
        trace("OplusProxyBroadcast 代理已全关")
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
            trace("Oplus Google network reject dropped: uid=$uid, type=$networkType")
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
            hook(target) { chain ->
                val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
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
    if (runCatching { method.invoke(lock, uid, WorkSource(), WAKELOCK_TAG, WAKELOCK_OWNER) }.isFailure) {
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

private fun uidOf(pkg: String): Int? = systemContext
    ?.let { ctx -> runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull() }
    ?.takeIf { it >= 0 }

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
private const val WAKELOCK_OWNER = "FcmSelf"
