package sumicya.gself

import android.content.Intent

/**
 * 推送判据，全是纯函数，集中在 [PushTest] 里逐条钉住——判错了就是「全放行」或「全不放行」。
 * 跑在 system_server 的高频路径上，所以不分配对象、不建列表，只做位运算与 `String.contains`。
 */
object Push {

    /** `FLAG_INCLUDE_STOPPED_PACKAGES`（AOSP `Intent.java:7203`）。 */
    private const val INCLUDE = 0x20

    /** 推送族：action 里含 `c2dm` 或 `firebase`。 */
    fun isPush(action: String?): Boolean =
        action != null && (action.contains("c2dm") || action.contains("firebase"))

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package；空串按没有目标算。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }.takeUnless { it.isNullOrEmpty() }

    /**
     * 补标志的条件：还没带 INCLUDE + 属于推送族。
     *
     * 目标包名**不**在这里判：取包名要问 component / package，比两次 `contains` 贵，而全系统每条广播
     * 都进这个函数、绝大多数不是推送。先在这里挡掉非推送，调用方拿到 true 之后再去取包名。
     */
    fun needsFlag(action: String?, flags: Int): Boolean = (flags and INCLUDE) == 0 && isPush(action)

    /** `shouldProxy` 的 action 是散字符串，`Chain#args` 是 `List<Any?>`；`any{}`/`indices` 每次新建对象，故用 while。 */
    fun anyPushAction(args: List<Any?>): Boolean {
        var i = 0
        while (i < args.size) if (isPush(args[i++] as? String)) return true
        return false
    }
}
