package sumicya.fcmself

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.WorkSource
import android.service.notification.NotificationListenerService

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 全部挂载点。五组，每组一个顶层扩展函数，都长在 [Hook] 上、由 [XposedMain] 的清单驱动。
 *
 * 共同点只有两条：介入判据统一是 [Push]（推送族 + 目标明确），参数一律**按类型/按值**
 * 在实参里认，不按 SDK_INT 硬编码下标。挂载点找不到就 `hook skip` 跳过，不影响其它组。
 */

// ---------- 1. 唤醒已停止的应用（核心） ----------

/**
 * 在系统唯一的广播出口补上 `FLAG_INCLUDE_STOPPED_PACKAGES`，解决
 * `Failed to broadcast to stopped app`：原生 AOSP 发往特定应用的推送广播本就带这个标志，
 * GMS 常常不带，ROM 又各自加了闸门，于是应用被划掉之后就再也收不到推送。
 */
fun Hook.wakeStoppedApps() {
    // Android 15+ 广播出口挪进了 BroadcastController，10–14 还在 AMS
    val broadcast = classIfExists(CONTROLLER) ?: classOf(AMS)
    val entry = find(broadcast, "broadcastIntentLocked")
    trace("hook target: ${entry.declaringClass.name}#${entry.name}(${entry.parameterCount})")

    hook(entry) { chain ->
        if (!booted) return@hook chain.proceed()
        // 签名里只有一个 Intent 参数，按类型取即可，ROM 在前后插参数都不影响
        val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
        if (intent != null && Push.isTargeted(intent) && !intent.wakesStoppedApps()) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val target = Push.targetOf(intent)
            trace("Add FLAG_INCLUDE_STOPPED_PACKAGES: $target")
            unfreeze(target)
        }
        chain.proceed()
    }
    // ponytail: 不再改 appOp（旧版把 OP_NONE 抬成 OP_POST_NOTIFICATION）。appOp 前面排着
    //             requestCode / userId / flags 三个 int，没有可靠的位置规则能认出它；而唤醒停止态
    //             应用靠的是上面这个 flag（AOSP 明文语义）。真机若仍被拦再加回来，
    //             用「appOp 是紧跟 Bundle bOptions 之前的那个 int」定位。
}

private fun Intent.wakesStoppedApps() = flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0

// ---------- 2. 自启动限制 ----------

/** 这些闸门返回 true = 允许自启动。 */
private val ALLOW_AUTOSTART = listOf(
    "com.android.server.am.BroadcastQueueInjector" to "checkApplicationAutoStart", // MIUI 12
    "com.android.server.am.BroadcastQueueImpl" to "checkApplicationAutoStart", // MIUI 13
    "com.android.server.am.BroadcastQueueModernStubImpl" to "checkApplicationAutoStart", // HyperOS
    "com.android.server.am.AutoStartManagerServiceStubImpl" to "isAllowStartService", // MIUI / HyperOS
)

/** 这些闸门返回 false = 不拦截。 */
private val NO_INTERCEPT = listOf(
    "com.android.server.am.BroadcastQueueModernStubImpl" to "checkReceiverIfRestricted", // HyperOS
    "com.android.server.am.SmartPowerService" to "shouldInterceptBroadcast", // MIUI / HyperOS
    "com.android.server.am.OplusAppStartupManager" to "shouldPreventSendReceiverReal", // ColorOS
)

/**
 * 解除 ROM 的自启动限制：推送广播不许被「应用没在运行」拦下来。
 *
 * 八个挂载点其实是两种形状（放行 / 不拦截），所以是两张表加一个循环，不是八段代码。
 */
fun Hook.autoStartFixes() {
    gate(ALLOW_AUTOSTART, allow = true)
    gate(NO_INTERCEPT, allow = false)
    // ponytail: MIUI 12/13 放行后不再补调 checkAbnormalBroadcastInQueueLocked（上游 fcmfix 用它
    //             留一条「异常广播」记录）。补调要按名字反射调一个签名未知的 ROM 私有方法，只为留痕；
    //             真机若发现 MIUI 因此惩罚应用再加回来。
    install("SmartPowerPolicyManager#shouldInterceptService") {
        val clazz = classOf("com.miui.server.smartpower.SmartPowerPolicyManager")
        hook(find(clazz, "shouldInterceptService")) { chain ->
            val result = chain.proceed() // 原判定照跑，留下 ROM 自己的统计
            val intent = Push.intentIn(chain.args) ?: return@hook result
            trace("Disable MIUI Intercept: ${Push.targetOf(intent)}")
            false
        }
    }
}

/** 一张表 = 一批同形状的闸门：实参里有推送就返回 [allow]，否则原样放行。 */
private fun Hook.gate(points: List<Pair<String, String>>, allow: Boolean) {
    for ((clazz, method) in points) install("$clazz#$method") {
        hook(find(classOf(clazz), method)) { chain ->
            val intent = Push.intentIn(chain.args) ?: return@hook chain.proceed()
            trace("${if (allow) "Allow Auto Start" else "No Intercept"}: ${Push.targetOf(intent)}")
            allow
        }
    }
}

// ---------- 3. 通知不被自动清除 ----------

/**
 * 原生 AOSP 不会因为「应用包发生变化」去取消它的通知；部分 ROM 会在应用没运行时借这个理由
 * 清掉推送通知。这里把这一类取消请求直接忽略（返回 null = 这次取消没有发生），
 * 对所有包生效、无白名单。
 */
fun Hook.notificationFixes() {
    install("cancelAllNotificationsInt") {
        val nms = classOf("com.android.server.notification.NotificationManagerService")
        hook(find(nms, "cancelAllNotificationsInt")) { chain ->
            // ponytail: reason 按值认，不按下标——ROM 签名里 pkg / reason 的位置都随版本漂移，
            //             而 reason 是唯一取值落在这三个数里的 int。代价是别的 int 参数恰好等于 8 时
            //             会误拦一次取消（只影响「通知没被清掉」，不影响投递）。真机若出现误拦，
            //             改成「第一个 String 之后、值为 8/10020/10021 的那个 int」。
            val blocked = chain.args.filterIsInstance<Int>().any { it in BLOCKED_REASONS }
            if (!booted || !blocked) return@hook chain.proceed()
            trace("Keep notification: ${chain.args.filterIsInstance<String>().firstOrNull()}")
            null
        }
    }

    install("MIUI 本地通知限制") {
        val clazz = classIfExists(NMS_INJECTOR) ?: classIfExists(NMS_IMPL)
            ?: return@install trace("MIUI 本地通知限制：$NMS_INJECTOR / $NMS_IMPL 都不存在")
        // isAllow* 改成恒允许、isDenied* 改成恒不拒绝，其余调用原样放行
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "isAllowLocalNotification" || it.name == "isDeniedLocalNotification"
        } ?: return@install trace("MIUI 本地通知限制：${clazz.name} 没有目标方法")
        val allowed = method.name == "isAllowLocalNotification"

        hook(method) { chain ->
            val result = chain.proceed()
            // 包名按值认：这两个方法的 userId 是 int，第一个 String 就是包名
            val pkg = chain.args.filterIsInstance<String>().firstOrNull()
            if (pkg.isNullOrEmpty()) result else allowed
        }
        trace("MIUI 本地通知限制：${clazz.name}#${method.name} → $allowed")
    }
}

// ---------- 4. MIUI 电源策略 ----------

/** MIUI PowerKeeper 对 GMS 的管控：Millet 名单 + `gms_control` 开关。 */
fun Hook.miuiFixes() {
    install("MilletConfig.isGlobal") {
        field(classOf("com.miui.powerkeeper.millet.MilletConfig"), "isGlobal").set(null, true)
        trace("MilletConfig.isGlobal = true")
    }

    install("PowerKeeper gms_control") {
        val misc = classOf("com.miui.powerkeeper.provider.SimpleSettings\$Misc")
        hook(find(misc, "getBoolean")) { chain ->
            val result = chain.proceed()
            if (GMS_CONTROL in chain.args) false else result
        }
        trace("PowerKeeper gms_control 已关掉")
    }

    install("MilletPolicy 名单") {
        val policy = classOf("com.miui.powerkeeper.millet.MilletPolicy")
        // 只挂第一个参数是 Context 的构造器（ROM 往尾部加过参数，取最宽的那个即可）
        val target = ctor(policy).takeIf { it.parameterTypes.firstOrNull() == Context::class.java }
            ?: return@install trace("MilletPolicy 没有 Context 构造器，已跳过")
        hook(target) { chain ->
            chain.proceed()
            val self = chain.thisObject
            if (self != null) {
                strings(self, "mSystemBlackList")?.remove(GMS)
                strings(self, "mDataWhiteList")?.let { if (GMS !in it) it.add(GMS) }
                trace("MilletPolicy 名单已调整")
            }
            null
        }
        // ponytail: 不再动 whiteApps（上游 fcmfix 时代连它一起移除 GMS + ext.services）。
        //             该列表语义始终未被证实：若它其实是「允许后台的白名单」，移除 GMS 是在**收紧**
        //             而不是放开——一条可能反向起作用的逻辑，不该带着。真机确认它是黑名单再加回来。
    }
}

private fun Hook.strings(holder: Any, name: String): MutableList<String>? =
    runCatching {
        @Suppress("UNCHECKED_CAST")
        field(holder.javaClass, name).get(holder) as? MutableList<String>
    }.getOrNull()

// ---------- 5. ColorOS 后台代理 ----------

/**
 * OPPO / OnePlus：代理广播、进程冻结、Hans 后台管理。
 *
 * `shouldProxy` 的 8 个参数里 caller / 目标包名 / action 都是散字符串、位置随版本漂移，
 * 所以按内容认（[Push.actionIn] / [Push.packageIn]），不按下标。
 */
fun Hook.oplusFixes() {
    install("OplusProxyWakeLock") {
        hook(ctor(classOf("com.android.server.power.OplusProxyWakeLock"))) { chain ->
            chain.proceed()
            val captured = wakelock.compareAndSet(null, chain.thisObject)
            trace(if (captured) "OplusProxyWakeLock instance captured" else "warn: OplusProxyWakeLock 被重复构造")
            null
        }
    }

    install("OplusProxyBroadcast.shouldProxy") {
        val proxy = classOf("com.android.server.am.OplusProxyBroadcast")
        val notInclude = field(classOf("com.android.server.am.OplusProxyBroadcast\$RESULT"), "NOT_INCLUDE").get(null)
        hook(find(proxy, "shouldProxy")) { chain ->
            val action = Push.actionIn(chain.args)
            val target = Push.packageIn(chain.args)
            // 不调 proceed()：这条广播不走代理检查
            if (action != null && target != null) {
                logBypass(target, action)
                notInclude
            } else {
                chain.proceed()
            }
        }
    }

    install("Hans GMS 限制") {
        val hans = classOf("com.android.server.hans.scene.OplusBgSceneManager")
        hook(find(hans, "registerGmsRestrictObserver")) { null }
        hook(find(hans, "updateGmsRestrict")) { null }
        trace("Hans GMS 限制已置空")
    }

    install("OplusStartupStrategy") {
        val strategy = classOf("com.android.server.am.OplusAppStartupManager\$OplusStartupStrategy")
        hook(find(strategy, "isGoogleRestricInfoOn", Int::class.javaPrimitiveType!!)) { false }
        trace("isGoogleRestricInfoOn → false")
    }
}

/**
 * 解冻目标应用（ColorOS）：调用之前捕获的 `OplusProxyWakeLock.unfreezeIfNeed`。
 * 由 [wakeStoppedApps] 在放行推送广播时调用；失败不影响广播放行。
 */
private fun unfreeze(target: String?) {
    val lock = wakelock.get() ?: return
    if (target == null) return
    val uid = uidOf(target) ?: return
    // 3 参与 4 参两代签名（ROM 在尾部加了 owner）：参数多的先试，试通就记住复用
    val known = knownUnfreeze
    val candidates = known?.let { listOf(it) } ?: lock.javaClass.declaredMethods
        .filter { it.name == "unfreezeIfNeed" && it.parameterCount in 3..4 }
        .sortedByDescending { it.parameterCount }
    for (method in candidates) {
        val args = if (method.parameterCount == 4) {
            arrayOf<Any?>(uid, WorkSource(), WAKELOCK_TAG, WAKELOCK_OWNER)
        } else {
            arrayOf<Any?>(uid, WorkSource(), WAKELOCK_TAG)
        }
        val ok = runCatching { method.apply { isAccessible = true }.invoke(lock, *args) }.isSuccess
        if (ok) {
            knownUnfreeze = method
            trace("unfreeze: $target uid=$uid（${method.parameterCount} 参签名）")
            return
        }
    }
}

// ponytail: 全局节流（旧版按包名分别节流）。代价是 60 秒内第二个应用的 bypass 日志被吞掉；
//             真需要按包名区分再换回 ConcurrentHashMap<String, LongArray>。
private val lastBypassLog = AtomicLong(-BYPASS_LOG_INTERVAL * 2)
private val suppressedBypass = AtomicInteger()

private fun logBypass(pkg: String, action: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastBypassLog.get() < BYPASS_LOG_INTERVAL) {
        suppressedBypass.incrementAndGet()
        return
    }
    lastBypassLog.set(now)
    val suppressed = suppressedBypass.getAndSet(0)
    val note = if (suppressed > 0) "（期间另有 $suppressed 条已抑制）" else ""
    trace("shouldProxy bypass: pkg=$pkg action=$action$note")
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
 * 被忽略的通知取消原因：AOSP 的 `REASON_PACKAGE_CHANGED`(8)，
 * 以及 ColorOS 15 / OxygenOS 15 自造的 10020 / 10021。
 */
private val BLOCKED_REASONS = setOf(NotificationListenerService.REASON_PACKAGE_CHANGED, 10020, 10021)

internal const val AMS = "com.android.server.am.ActivityManagerService"

private const val CONTROLLER = "com.android.server.am.BroadcastController"
private const val NMS_INJECTOR = "com.android.server.notification.NotificationManagerServiceInjector"
private const val NMS_IMPL = "com.android.server.notification.NotificationManagerServiceImpl"
private const val GMS_CONTROL = "gms_control"
private const val GMS = "com.google.android.gms"
private const val WAKELOCK_TAG = "FCMXX"
private const val WAKELOCK_OWNER = "FcmSelf"
private const val BYPASS_LOG_INTERVAL = 60_000L
