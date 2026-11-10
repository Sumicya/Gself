package sumicya.gself

import android.content.Intent

/**
 * 「这条广播要不要补停止态放行」——推送唤醒唯一的判据，全是纯函数。
 *
 * 判据是**子串**而不是 action 全等表：`c2dm` / `firebase` 两个词覆盖整个推送族及其变体
 * （`…c2dm.intent.RECEIVE`、`…firebase.MESSAGING_EVENT`，以及 GMS 以后改名的任何 action），
 * 比列举字符串短，也不会因为名字变了就静默漏掉一整条推送。
 *
 * 这些函数在 system_server 里被高频调用（`isExcludingStopped` 每次 intent 解析问一次，AMS 每个
 * 广播入口每次投递问一次），所以不分配对象、不建列表、不遍历集合，只做位运算与 `String.contains`。
 * 判据留在本文件而不到挂载点里，是为了让 [PushTest] 在 JVM 上逐条钉住——判错了就是「对所有广播
 * 放行」或「一个都不放行」，两种都致命。
 */
object Push {

    /** `FLAG_EXCLUDE_STOPPED_PACKAGES`（AOSP `Intent.java:7192`）。 */
    private const val EXCLUDE = 0x10

    /** `FLAG_INCLUDE_STOPPED_PACKAGES`（AOSP `Intent.java:7203`）。 */
    private const val INCLUDE = 0x20

    /** 推送族：action 里含 `c2dm` 或 `firebase`。 */
    fun isPush(action: String?): Boolean =
        action != null && (action.contains("c2dm") || action.contains("firebase"))

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package；空串按没有目标算。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }.takeUnless { it.isNullOrEmpty() }

    /**
     * 原生 `Intent#isExcludingStopped()` 的答案，逐字对应 AOSP `Intent.java:10023`：
     * 只有「带 EXCLUDE 且不带 INCLUDE」才排除停止态应用。
     *
     * 语义层拿它做快路：原生答案本来就是 false 时，`proceed()` 与「返回 false」等价，
     * 绝大多数 intent 解析只花一次掩码比较，不进字符串判据。
     */
    fun excludesStopped(flags: Int): Boolean = (flags and (EXCLUDE or INCLUDE)) == EXCLUDE

    /**
     * 补标志的唯一条件：还没带 INCLUDE + 有明确目标 + 属于推送族。
     *
     * 顺序按代价排：位运算常数时间，目标包名一次字段读，`contains` 最后做。
     * 「已带标志就跳过」让同一条广播经过多个 AMS 重载时只补一次、只解冻一次。
     * 没有目标的推送不动：`wake:` 记不下对象，也无从解冻。
     */
    fun needsStoppedPatch(action: String?, target: String?, flags: Int): Boolean =
        (flags and INCLUDE) == 0 && !target.isNullOrEmpty() && isPush(action)

    /**
     * 语义层的判据：定向推送，不看 flags（标志层另有 [needsStoppedPatch]）。
     * 目标包名先查——系统每次解析 intent 都进这一层，而绝大多数 intent 没有目标包名。
     */
    fun isTargetedPush(target: String?, action: String?): Boolean =
        !target.isNullOrEmpty() && isPush(action)
}
