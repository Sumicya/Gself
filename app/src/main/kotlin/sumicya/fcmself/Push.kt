package sumicya.fcmself

import android.content.Intent

/**
 * 「是不是一条 FCM 推送」—— 全模块唯一的介入判据。
 *
 * 判据是**子串**而不是 action 全等表：`c2dm` / `firebase` 两个词就覆盖了整个推送族及其变体
 * （`…c2dm.intent.RECEIVE`、`…firebase.MESSAGING_EVENT`、以及 GMS 未来改名的任何 action），
 * 比列举五个字符串更短，也不会因为名字变了就静默漏掉一整条推送。
 *
 * 设计基准仍是还原原生 AOSP：原生系统对推送广播不加任何自启动、后台与通知限制。
 * 这里只把 ROM 丢掉的那部分语义补回来，判据之外的调用零介入。
 */
object Push {

    private val MARKERS = listOf("c2dm", "firebase")

    fun isPush(action: String?): Boolean = action != null && MARKERS.any { action.contains(it) }

    /** 定向 Intent 的目标包名：显式 component 优先，其次 package。 */
    fun targetOf(intent: Intent?): String? =
        intent?.let { it.component?.packageName ?: it.getPackage() }?.takeIf(String::isNotEmpty)

    /** 「发往明确目标的推送」—— 唯一的介入条件。 */
    fun isTargeted(intent: Intent?): Boolean =
        intent != null && isPush(intent.action) && targetOf(intent) != null
}
