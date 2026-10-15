package sumicya.fcmself

import android.net.Uri

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Gboard 剪贴板（自 [chenyue404/GboardHook] 移植，GPL-3.0）：改剪切板显示个数与过期时间。 */
private const val CLIP_NUM = 10
// 上游默认 3 天；这里按「实际不限时长」处理：时间下限直接给 long 最小值，等价于关掉过期过滤。
// 不用「100 年」魔数：当前毫秒数减 100 年已是负 epoch，哨兵值比魔数干净。
private const val CLIP_TIME_FLOOR = Long.MIN_VALUE

fun Hook.gboardFixes() {
    System.loadLibrary("dexkit")

    // 1) ClipboardContentProvider#query：改写时间下限与写死的 limit 5。
    //    与下面的 readConfig 是两个独立功能，用 runCatching 隔开：Gboard 改版导致 query 类名/签名
    //    变了只跳过这一个，不能连累 readConfig（否则整组中断，另一个功能也白丢）。
    runCatching {
        val provider = classOf("com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider")
        val query = find(
            provider, "query",
            Uri::class.java, Array<String>::class.java, String::class.java,
            Array<String>::class.java, String::class.java,
        )
        hook(query) { chain ->
            val selection = chain.args[2] as? String
            val selectionArgs = chain.args[3] as? Array<String>
            val idx = selection?.indexOf("timestamp >= ?") ?: -1
            if (idx != -1 && selection != null && selectionArgs != null) {
                var placeholders = 0
                for (i in 0 until idx) if (selection[i] == '?') placeholders++
                selectionArgs[placeholders] = CLIP_TIME_FLOOR.toString()
            }
            if (chain.args[4] == "timestamp DESC limit 5") chain.args[4] = "timestamp DESC limit $CLIP_NUM"
            chain.proceed()
        }
    }.onFailure { trace("Gboard query hook 跳过：$it") }

    // 2) 把两个剪贴板特性开关写死成 false（不然 limit 永远是 100，查出来却只有 5 个）
    DexKitBridge.create(loader, true).use { bridge ->
        val readConfig = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create().usingStrings("Invalid flag: ").returnType(Any::class.java),
            ),
        ).singleOrNull()?.getMethodInstance(loader)
        if (readConfig != null) {
            hook(readConfig) { chain ->
                val self = chain.thisObject
                val name = if (self == null) "" else runCatching {
                    field(self.javaClass, "a").get(self).toString()
                }.getOrDefault("")
                if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") {
                    false
                } else {
                    chain.proceed()
                }
            }
            trace("Gboard 剪贴板 Hook 已安装")
        } else {
            trace("Gboard 剪贴板：未定位到 readConfig，跳过")
        }
    }
}
