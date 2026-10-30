package sumicya.fcmself

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 推送判据表的唯一一份检查。介入判据是全模块的单一支点：判错了就是「对所有广播放行」或
 * 「一个都不放行」，两种都致命。
 *
 * [Push.needsStoppedPatch] 是纯函数（action / 目标包名 / flags 三个值，不碰 Android 对象），
 * 所以整张表都能在 JVM 里直接验；挂载点只负责把 `Intent` 拆成这三个值。
 */
class PushTest {

    private val noFlags = 0x10000000

    @Test
    fun acceptsTheWholePushFamily() {
        assertTrue(Push.isPush("com.google.android.c2dm.intent.RECEIVE"))
        assertTrue(Push.isPush("com.google.android.c2dm.intent.REGISTRATION"))
        assertTrue(Push.isPush("com.google.firebase.MESSAGING_EVENT"))
        assertTrue(Push.isPush("com.google.firebase.INSTANCE_ID_EVENT"))
        assertTrue(Push.isPush("com.google.firebase.NEW_TOKEN"))
    }

    /** 放宽成子串的全部意义：GMS / ROM 改了 action 名，判据不会跟着失效。 */
    @Test
    fun acceptsVariantsTheOldExactTableMissed() {
        assertTrue(Push.isPush("com.google.android.c2dm.intent.RECEIVE2"))
        assertTrue(Push.isPush("com.google.firebase.messaging.DIRECT_BOOT_MESSAGE_RECEIVED"))
        assertTrue(Push.isPush("com.google.android.c2dm.RECEIVE"))
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
        assertTrue(
            Push.needsStoppedPatch(
                "com.google.android.c2dm.intent.RECEIVE",
                "fork.risin42.nagramx",
                noFlags,
            ),
        )
    }

    /** 已带 0x20 就不再补：同一条广播经过多个 AMS 重载时只补一次、只解冻一次。 */
    @Test
    fun neverPatchesTwice() {
        assertFalse(
            Push.needsStoppedPatch(
                "com.google.android.c2dm.intent.RECEIVE",
                "fork.risin42.nagramx",
                noFlags or 0x20,
            ),
        )
    }

    /** 没有目标包名就不动：记不下对象，也无从解冻。 */
    @Test
    fun leavesUntargetedBroadcastsAlone() {
        assertFalse(Push.needsStoppedPatch("com.google.android.c2dm.intent.RECEIVE", null, noFlags))
        assertFalse(Push.needsStoppedPatch("com.google.android.c2dm.intent.RECEIVE", "", noFlags))
    }

    /** 非推送族一律零介入，哪怕带目标、哪怕没带标志。 */
    @Test
    fun ignoresNonPushBroadcasts() {
        assertFalse(Push.needsStoppedPatch("android.intent.action.BOOT_COMPLETED", "pkg", noFlags))
        assertFalse(Push.needsStoppedPatch(null, "pkg", noFlags))
    }
}
