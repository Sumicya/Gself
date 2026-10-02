package sumicya.fcmself

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Push.isPush] 的唯一一份检查：介入判据是全模块的单一支点，判错了就是「对所有广播放行」
 * 或「一个都不放行」，两种都致命。
 *
 * `isTargeted` 要真 `android.content.Intent`，JVM 单测拿不到，由 docs/verify-on-device.md 覆盖。
 */
class PushTest {

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
}
