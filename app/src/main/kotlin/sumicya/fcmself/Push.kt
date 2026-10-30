package sumicya.fcmself

import android.content.Intent

/**
 * 「这条广播要不要补停止态放行」—— 推送唤醒唯一的判据，全是纯函数。
 *
 * 判据是**子串**而不是 action 全等表：`c2dm` / `firebase` 两个词就覆盖了整个推送族及其变体
 * （`…c2dm.intent.RECEIVE`、`…firebase.MESSAGING_EVENT`、以及 GMS 未来改名的任何 action），
 * 比列举五个字符串更短，也不会因为名字变了就静默漏掉一整条推送。
 *
 * 这里的每个函数都可能在 system_server 里被高频调用（`isExcludingStopped` 每次 intent 解析都要问
 * 一次，AMS 每个广播入口每次投递都要问一次），所以刻意保持：不分配对象、不建列表、不遍历集合，
 * 只做位运算和 `String.contains`。判据留在这里而不是散进挂载点，是为了让下面这张表能被 JVM 单测
 * 逐条钉住——判错了就是「对所有广播放行」或「一个都不放行」，两种都致命。
 */
object Push {

    /** `FLAG_EXCLUDE_STOPPED_PACKAGES | FLAG_INCLUDE_STOPPED_PACKAGES`（AOSP `Intent.java:7192`、`:7203`）。 */
    private val STOPPED_MASK =
        Intent.FLAG_EXCLUDE_STOPPED_PACKAGES or Intent.FLAG_INCLUDE_STOPPED_PACKAGES

    /** 推送族：action 里含 `c2dm` 或 `firebase`。展开成两次 `contains`，不建集合、不走 lambda。 */
    fun isPush(action: String?): Boolean =
        action != null && (action.contains("c2dm") || action.contains("firebase"))

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }?.takeIf(String::isNotEmpty)

    /**
     * 原生 `Intent#isExcludingStopped()` 的答案，逐字对应 AOSP `Intent.java:10023`：
     * 只有「带 EXCLUDE 且不带 INCLUDE」才排除停止态应用。
     *
     * 语义层用它做快路：原生答案本来就是 false 时，`proceed()` 的返回值与「返回 false」完全相同，
     * 于是绝大多数 intent 解析只花一次掩码比较，不进字符串判据。
     */
    fun excludesStopped(flags: Int): Boolean =
        (flags and STOPPED_MASK) == Intent.FLAG_EXCLUDE_STOPPED_PACKAGES

    /**
     * 补标志的唯一条件：还没带 `FLAG_INCLUDE_STOPPED_PACKAGES` + 属于推送族 + 有明确目标。
     *
     * 顺序按代价排：位运算是常数时间，`contains` 只读 action 前缀，目标包名最后查。
     * 「已带标志就跳过」让同一条广播经过多个 AMS 重载时只补一次、只解冻一次。
     * 没有目标包名的推送不动：`wake:` 记不下对象，也无从解冻。
     */
    fun needsStoppedPatch(action: String?, target: String?, flags: Int): Boolean =
        (flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 &&
            isPush(action) &&
            !target.isNullOrEmpty()

    fun needsStoppedPatch(intent: Intent?): Boolean =
        intent != null && needsStoppedPatch(intent.action, targetOf(intent), intent.flags)

    /**
     * 定向推送：停止态判定要放行的对象；不看 flags（语义层用它，标志层另有 [needsStoppedPatch]）。
     *
     * 先看目标包名：语义层挂在 `Intent#isExcludingStopped()` 上，系统每次解析 intent 都会进来一次，
     * 而绝大多数 intent 没有目标包名，一次字段读就退出去，比做两次子串匹配便宜。
     */
    fun isTargetedPush(intent: Intent?): Boolean =
        intent != null && targetOf(intent) != null && isPush(intent.action)
}
