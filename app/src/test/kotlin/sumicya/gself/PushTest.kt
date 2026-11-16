package sumicya.gself

import android.content.Intent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 推送判据表的唯一一份检查。判据是全模块的单一支点：判错了就是「对所有广播放行」或
 * 「一个都不放行」，两种都致命。
 *
 * 被测函数都只吃 String / Int，不碰 Android 对象，所以整张表能在 JVM 里直接验；
 * 挂载点只负责把 `Intent` 拆成这几个值。
 */
class PushTest {

    private val receive = "com.google.android.c2dm.intent.RECEIVE"
    private val target = "fork.risin42.nagramx"

    /** 正常运行期广播的 flags：既没带 0x10 也没带 0x20。 */
    private val plain = 0x10000000

    @Test
    fun acceptsTheWholePushFamily() {
        assertTrue(Push.isPush("com.google.android.c2dm.intent.RECEIVE"))
        assertTrue(Push.isPush("com.google.android.c2dm.intent.REGISTRATION"))
        assertTrue(Push.isPush("com.google.firebase.MESSAGING_EVENT"))
        assertTrue(Push.isPush("com.google.firebase.INSTANCE_ID_EVENT"))
        // 子串的全部意义：GMS / ROM 改名后判据不会跟着失效
        assertTrue(Push.isPush("com.google.android.c2dm.intent.RECEIVE2"))
        assertTrue(Push.isPush("com.google.firebase.messaging.DIRECT_BOOT_MESSAGE_RECEIVED"))
    }

    @Test
    fun rejectsEverythingElse() {
        assertFalse(Push.isPush(null))
        assertFalse(Push.isPush(""))
        assertFalse(Push.isPush("android.intent.action.BOOT_COMPLETED"))
        assertFalse(Push.isPush("com.example.intent.RECEIVE"))
        assertFalse(Push.isPush("com.google.android.gms.action.RECONNECT"))
    }

    /** 唯一的介入条件：推送族 + 有目标包名 + 还没带 0x20。 */
    @Test
    fun patchesOnlyTargetedPushesWithoutTheFlag() {
        assertTrue(Push.needsStoppedPatch(receive, target, plain))
        assertFalse(Push.needsStoppedPatch(receive, target, plain or 0x20))
        assertFalse(Push.needsStoppedPatch(receive, null, plain))
        assertFalse(Push.needsStoppedPatch(receive, "", plain))
        assertFalse(Push.needsStoppedPatch("android.intent.action.BOOT_COMPLETED", target, plain))
        assertFalse(Push.needsStoppedPatch(null, target, plain))
    }

    /**
     * 真值表来自 AOSP `Intent.java:10023`——`(flags & (0x10 | 0x20)) == 0x10`。
     * 语义层快路早退时返回 `proceed()`，与原方法逐位相同，所以不改变任何非推送 intent 的结果。
     */
    @Test
    fun matchesAospExclusionTruthTable() {
        assertFalse(Push.excludesStopped(0))
        assertTrue(Push.excludesStopped(0x10))
        assertFalse(Push.excludesStopped(0x20))
        assertFalse(Push.excludesStopped(0x30))
        assertTrue(Push.excludesStopped(0x10 or plain))
        assertFalse(Push.excludesStopped(0x20 or plain))
    }

    /**
     * 判据里写死的 0x10 / 0x20 必须与 `android.content.Intent` 的常量一致：
     * 挂载点用常量补标志，判据用字面量算，两边漂了就是「补了却判不出」。
     */
    @Test
    fun literalFlagsMatchIntentConstants() {
        assertEquals(0x10, Intent.FLAG_EXCLUDE_STOPPED_PACKAGES)
        assertEquals(0x20, Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        assertTrue(Push.excludesStopped(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES))
        assertFalse(Push.excludesStopped(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        assertFalse(Push.needsStoppedPatch(receive, target, Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
    }

    /** 语义层只放行「有目标包名 + 属于推送族」，与 flags 无关。 */
    @Test
    fun semanticLayerNeedsBothTargetAndPushAction() {
        assertTrue(Push.isTargetedPush(target, receive))
        assertFalse(Push.isTargetedPush(null, receive))
        assertFalse(Push.isTargetedPush("", receive))
        assertFalse(Push.isTargetedPush(target, "android.intent.action.SCREEN_ON"))
        assertFalse(Push.isTargetedPush(target, null))
    }

    /**
     * `waken` 的提前返回依赖「已带 0x20 必不需要补」这个合取项；这里把 action / target 的
     * 组合都过一遍钉住，谁删掉那个合取项这条就红，而不是让提前返回静默改语义。
     */
    @Test
    fun includeFlagNeverNeedsPatch() {
        for (action in listOf(receive, "com.google.firebase.MESSAGING_EVENT",
                              "android.intent.action.SCREEN_ON", null)) {
            for (pkg in listOf(target, null, "")) {
                for (flags in listOf(0x20, 0x20 or plain, 0x30)) {
                    assertFalse(Push.needsStoppedPatch(action, pkg, flags),
                        "带 INCLUDE 时不该需要补标志：action=$action target=$pkg flags=$flags")
                }
            }
        }
    }

    /**
     * `CallingAppInfo` 在 Jetpack Credentials 1.2–1.6 间的构造器签名变迁：
     * 3 参 `(String, SigningInfo, String)` 与 `(String, List, String)` 的 origin 在下标 2；
     * 4 参主构造器 `(String, String, Compat, SigningInfo)` 的 origin 在下标 1；
     * 2 参无 origin、或 3 个 String（歧义）必须返回 -1。
     */
    @Test
    fun findsOriginArgIndexAcrossCallingAppInfoConstructors() {
        assertEquals(2, originArgIndex(arrayOf(String::class.java, Any::class.java, String::class.java)))
        assertEquals(2, originArgIndex(arrayOf(String::class.java, List::class.java, String::class.java)))
        assertEquals(1, originArgIndex(arrayOf(String::class.java, String::class.java,
            Any::class.java, Any::class.java)))
        assertEquals(-1, originArgIndex(arrayOf(String::class.java, Any::class.java)))
        assertEquals(-1, originArgIndex(arrayOf(String::class.java, String::class.java, String::class.java)))
        assertEquals(-1, originArgIndex(emptyArray()))
    }
}
