package sumicya.fcmself

import android.content.Context
import android.content.Intent
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
    // Android 15+ 广播出口挪进了 BroadcastController，10–14 还在 AMS
    val entry = find(classIfExists(CONTROLLER) ?: classOf(AMS), "broadcastIntentLocked")
    trace("hook target: ${entry.declaringClass.name}#${entry.name}(${entry.parameterCount})")

    hook(entry) { chain ->
        // 签名里只有一个 Intent 参数，按类型取即可，ROM 在前后插参数都不影响
        val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
        if (intent != null && Push.isTargeted(intent) && !intent.wakesStoppedApps()) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val target = Push.targetOf(intent)
            unfreeze(target)
            trace("wake: $target")
        }
        chain.proceed()
    }
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
            //             会误拦一次取消（只影响「通知没被清掉」，不影响投递）。真机若出现误拦，
            //             改成「第一个 String 之后、值为 8/10020/10021 的那个 int」。
            if (chain.args.filterIsInstance<Int>().none { it in BLOCKED_REASONS }) return@hook chain.proceed()
            trace("keep notification: ${chain.args.filterIsInstance<String>().firstOrNull()}")
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
        if (runCatching { method.apply { isAccessible = true }.invoke(lock, *args) }.isSuccess) {
            // 只记「找到了可用签名」这一次，不每条推送打一行
            if (knownUnfreeze == null) trace("unfreeze 可用（${method.parameterCount} 参签名）")
            knownUnfreeze = method
            return
        }
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
 * 被忽略的通知取消原因：AOSP 的 `REASON_PACKAGE_CHANGED`(8)，
 * 以及 ColorOS 15 / OxygenOS 15 自造的 10020 / 10021。
 */
private val BLOCKED_REASONS = setOf(NotificationListenerService.REASON_PACKAGE_CHANGED, 10020, 10021)

internal const val AMS = "com.android.server.am.ActivityManagerService"

private const val CONTROLLER = "com.android.server.am.BroadcastController"
private const val STARTUP = "com.android.server.am.OplusAppStartupManager"
private const val WAKELOCK_TAG = "FCMXX"
private const val WAKELOCK_OWNER = "FcmSelf"
