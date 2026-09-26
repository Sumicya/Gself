package sumicya.fcmself

import android.content.Intent

/**
 * 「是不是一条 FCM 推送」—— 全部挂载点共用的唯一介入判据。
 *
 * 设计基准是**还原原生 AOSP 的推送行为**：原生系统对 GMS 推送链路上的广播
 * （c2dm RECEIVE / REGISTRATION、Firebase MESSAGING_EVENT / INSTANCE_ID_EVENT / NEW_TOKEN）
 * 不加任何自启动、后台与通知限制。本模块做的是把 ROM 丢掉的这部分语义补回来，
 * 不是绕过安全：判据只认「推送族 action + 目标包名可定位」，其余调用零介入。
 */
object Push {

    /** c2dm 家族按后缀匹配：历史上有 `com.google.android.c2dm` 等前缀变体。 */
    private val SUFFIXES = listOf(".android.c2dm.intent.RECEIVE", ".android.c2dm.intent.REGISTRATION")

    private val ACTIONS = setOf(
        "com.google.firebase.MESSAGING_EVENT",
        "com.google.firebase.INSTANCE_ID_EVENT",
        "com.google.firebase.NEW_TOKEN",
    )

    /** 包名形态：小写、带点、不含类名分隔符 `$`。 */
    private val PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")

    fun isAction(action: String?): Boolean =
        action != null && (action in ACTIONS || SUFFIXES.any { action.endsWith(it) })

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }?.takeIf(String::isNotEmpty)

    /** 「发往明确目标的推送」—— 绝大多数挂载点的介入条件。 */
    fun isTargeted(intent: Intent?): Boolean =
        intent != null && isAction(intent.action) && targetOf(intent) != null

    /**
     * 实参里第一条「目标明确的推送」Intent。
     *
     * ROM 往拦截点插参数是常态，所以**不按固定下标取**。携带推送 Intent 的载体只有两种形态：
     * 参数本身就是 Intent（`isAllowStartService`），或参数是广播记录类对象、其 `intent`
     * 字段是 Intent（BroadcastRecord、SmartPower 的包装类）。按形态扫一遍就与参数位置解耦。
     */
    fun intentIn(args: List<Any?>): Intent? {
        for (arg in args) {
            // 基本类型与字符串不可能是载体，先排掉，省掉一次反射
            if (arg == null || arg is String || arg is Number || arg is Boolean || arg is Char) continue
            val intent = if (arg is Intent) arg else intentFieldOf(arg)
            if (intent != null && isTargeted(intent)) return intent
        }
        return null
    }

    /** 实参里的推送 action 字符串（参数表里只有散字符串、没有 Intent 的挂载点用）。 */
    fun actionIn(args: List<Any?>): String? = args.filterIsInstance<String>().firstOrNull { isAction(it) }

    /** 实参里第一个长得像包名的字符串。 */
    fun packageIn(args: List<Any?>): String? =
        args.filterIsInstance<String>().firstOrNull { PACKAGE.matches(it) }

    // ponytail: intent 字段不做缓存，没有该字段的宿主类每次调用付一次 NoSuchFieldException。
    //             这些挂载点是 ROM 的自启动闸门、不在最热路径上；真测出热点再加 ConcurrentHashMap 缓存。
    private fun intentFieldOf(holder: Any): Intent? = runCatching {
        holder.javaClass.getDeclaredField("intent").apply { isAccessible = true }
    }.getOrNull()
        ?.takeIf { Intent::class.java.isAssignableFrom(it.type) }
        ?.let { runCatching { it.get(holder) as? Intent }.getOrNull() }
}
