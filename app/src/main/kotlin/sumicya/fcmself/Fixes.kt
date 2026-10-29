package sumicya.fcmself

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.WorkSource
import android.service.notification.NotificationListenerService

import java.lang.reflect.Method
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
 * 在系统唯一的广播出口补上 `FLAG_INCLUDE_STOPPED_PACKAGES`，解决
 * `Failed to broadcast to stopped app`：原生 AOSP 发往特定应用的推送广播本就带这个标志，
 * GMS 常常不带，ROM 又各自加了闸门，于是应用被划掉之后就再也收不到推送。
 */
fun Hook.wakeStoppedApps() {
    // 只做新包（项目约定）：广播出口只实现 Android 16+（API 36）的 BroadcastController，
    // 不写 Android 10–15 的旧出口、不做双路径回退。上游证据（aosp-mirror/platform_frameworks_base
    // services/core/java/com/android/server/am/）：BroadcastController.java 首次出现于 android-16.0.0_r1，
    // android-14.0.0_r1 与 android-15.0.0_r1 的 am 包下都没有这个文件。
    // 不支持的环境由 minSdk = 36 在安装时明确拒绝；类/方法缺失时 [install] 打一条 `hook skip 推送唤醒`
    // 后整组不生效（功能不生效，不会损坏系统）。
    val entry = find(classOf(CONTROLLER), "broadcastIntentLocked")
    trace("hook target: ${entry.declaringClass.name}#${entry.name}(${entry.parameterCount})")

    // 入口一：AMS#broadcastIntentWithFeature。真机日志证明推送广播必经此处（push seen@AMS），
    // 而 BroadcastController 的重载在本机从未命中，所以补标志的主挂点放在这里。
    install("推送入口（AMS）") {
        hook(find(classOf("com.android.server.am.ActivityManagerService"), "broadcastIntentWithFeature")) { chain ->
            // 签名里只有一个 Intent 参数，按类型取即可，ROM 在前后插参数都不影响
            val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
            // 诊断（定位后删除）：进入 AMS 的推送广播一律记一行，用来确认命中。
            if (intent != null && Push.isPush(intent.action)) {
                trace("push seen@AMS: ${intent.action} flags=0x${Integer.toHexString(intent.flags)} target=${Push.targetOf(intent)}")
            }
            wakeIfPush("AMS", intent)
            chain.proceed()
        }
    }

    hook(entry) { chain ->
        // 签名里只有一个 Intent 参数，按类型取即可，ROM 在前后插参数都不影响
        val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
        // 诊断（定位后删除）：进入本出口的推送广播一律记一行，不论目标与标志，用来判断 Hook 是否被命中。
        if (intent != null && Push.isPush(intent.action)) {
            trace("push seen: ${intent.action} flags=0x${Integer.toHexString(intent.flags)} target=${Push.targetOf(intent)}")
        }
        wakeIfPush("BroadcastController", intent)
        chain.proceed()
    }
}

/**
 * 推送广播补 `FLAG_INCLUDE_STOPPED_PACKAGES` 并解冻目标。AMS 入口与 BroadcastController 出口共用同一判据：
 * 任一处先到即补，后到者看到标志已带，直接跳过，不会重复解冻。
 */
private fun Hook.wakeIfPush(where: String, intent: Intent?) {
    if (intent == null || !Push.isTargeted(intent) || intent.wakesStoppedApps()) return
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    val target = Push.targetOf(intent)
    unfreeze(target)
    trace("wake: $target（$where）")
}

private fun Intent.wakesStoppedApps() = flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0

// ---------- 2. 通知不被自动清除 ----------

/**
 * 原生 AOSP 不会因为「应用包发生变化」去取消它的通知；ColorOS 会，还自造了 10020 / 10021
 * 两个原因码。这里把这一类取消请求直接忽略（返回 null = 这次取消没有发生），对所有包生效、无白名单。
 */
fun Hook.notificationFixes() {
    install("cancelAllNotificationsInt") {
        val nms = classOf("com.android.server.notification.NotificationManagerService")
        hook(find(nms, "cancelAllNotificationsInt")) { chain ->
            // ponytail: reason 按值认，不按下标——ROM 签名里 pkg / reason 的位置都随版本漂移，
            //             而 reason 是唯一取值落在这三个数里的 int。代价是别的 int 参数恰好等于 8 时
            //             会误拦一次取消（只影响「通知没被清掉」，不影响投递）。
            //             日志里带上命中的 reason 值：真机日志按值判定误拦（例如出现 reason=8 却并非包变化）。
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
        if (!batteryIgnoresGoogleNetworkSetting(context)) return@hook chain.proceed()
        val shouldDrop = shouldDropGoogleFirewallReject(
            uid = uid,
            rule = rule,
            googleAppIds = googleNetworkAppIds(context),
            batteryIgnoresGmsUserSet = true,
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
    // This action is Oplus-specific; avoid installing a broad broadcast hook on AOSP ROMs.
    classOf(STARTUP)

    val classes = listOf(
        "com.android.server.am.ActivityManagerService",
        CONTROLLER,
    )
    var hooked = false
    for (className in classes) {
        val clazz = classIfExists(className) ?: continue
        val targets = clazz.declaredMethods.filter { method ->
            method.name == "broadcastIntentWithFeature" &&
                method.parameterTypes.count { it == Intent::class.java } == 1
        }
        if (targets.isEmpty()) continue

        targets.forEach { target ->
            hook(target.apply { isAccessible = true }) { chain ->
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
        hooked = true
        break // AMS delegates to BroadcastController; the outermost matching boundary is enough.
    }
    check(hooked) { "broadcastIntentWithFeature(Intent) not found" }
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
private fun unfreeze(target: String?) {
    val lock = wakelock.get() ?: return
    if (target == null) return
    val uid = uidOf(target) ?: return
    // 只做新包（项目约定）：只认当前 ColorOS 的 4 参签名（尾部带 owner），
    // 不做 3 参旧签名的逐档回退；签名对不上就明确跳过，不静默装作解冻过。
    val known = knownUnfreeze
    val candidates = known?.let { listOf(it) } ?: lock.javaClass.declaredMethods
        .filter { it.name == "unfreezeIfNeed" && it.parameterCount == 4 }
    if (known == null && candidates.isEmpty()) {
        trace("unfreeze 跳过：没有 4 参 unfreezeIfNeed 签名")
        return
    }
    for (method in candidates) {
        val args = arrayOf<Any?>(uid, WorkSource(), WAKELOCK_TAG, WAKELOCK_OWNER)
        if (runCatching { method.apply { isAccessible = true }.invoke(lock, *args) }.isSuccess) {
            // 只记「找到了可用签名」这一次，不每条推送打一行
            if (knownUnfreeze == null) trace("unfreeze 可用（4 参签名）")
            knownUnfreeze = method
            return
        }
    }
    if (known == null) trace("unfreeze 跳过：4 参签名调用失败")
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
