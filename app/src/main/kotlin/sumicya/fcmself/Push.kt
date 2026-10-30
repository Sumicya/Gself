package sumicya.fcmself

import android.content.Intent

/**
 * 「这条广播要不要拦下来、补停止态放行」—— 推送唤醒唯一的判据。
 *
 * 判据是**子串**而不是 action 全等表：`c2dm` / `firebase` 两个词就覆盖了整个推送族及其变体
 * （`…c2dm.intent.RECEIVE`、`…firebase.MESSAGING_EVENT`、以及 GMS 未来改名的任何 action），
 * 比列举五个字符串更短，也不会因为名字变了就静默漏掉一整条推送。
 *
 * 决策本身是纯函数（[needsStoppedPatch]），只吃 action / 目标包名 / flags 三个值：两个挂载点
 * 只负责把 `Intent` 拆成这三个值，判据表由 JVM 单测直接覆盖。
 */
object Push {

    private val MARKERS = listOf("c2dm", "firebase")

    fun isPush(action: String?): Boolean = action != null && MARKERS.any { action.contains(it) }

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }?.takeIf(String::isNotEmpty)

    /**
     * 补标志的唯一条件：推送族 + 有明确目标 + 还没带 `FLAG_INCLUDE_STOPPED_PACKAGES`。
     *
     * 没有目标包名的推送不动：`wake:` 记不下对象，也无从解冻；这条边界写在一处，不散在挂载点里。
     * 「已带标志就跳过」让同一条广播经过多个 AMS 重载时只补一次、只解冻一次。
     */
    fun needsStoppedPatch(action: String?, target: String?, flags: Int): Boolean =
        isPush(action) && !target.isNullOrEmpty() && flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES == 0

    fun needsStoppedPatch(intent: Intent?): Boolean =
        intent != null && needsStoppedPatch(intent.action, targetOf(intent), intent.flags)

    /**
     * 定向推送：停止态判定要放行的对象；不看 flags（语义层要用它，标志层另有 [needsStoppedPatch]）。
     *
     * 先看目标包名：语义层挂在 `Intent#isExcludingStopped()` 上，系统每次解析 intent 都会进来一次，
     * 而绝大多数 intent 没有目标包名，一次字段读就退出去，比先做两次子串匹配便宜。
     */
    fun isTargetedPush(intent: Intent?): Boolean =
        intent != null && targetOf(intent) != null && isPush(intent.action)
}
