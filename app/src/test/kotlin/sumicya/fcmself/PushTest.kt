package sumicya.fcmself

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Push] 的唯一一份检查：介入判据是全模块的单一支点，判错了就是「对所有广播放行」或
 * 「一个都不放行」，两种都致命。
 *
 * 只覆盖不需要真 `android.content.Intent` 的部分（action 分类、从散字符串实参里认包名）；
 * `isTargeted` / `intentIn` 要真 Intent，由 docs/verify-on-device.md 的真机清单覆盖。
 */
class PushTest {

    @Test
    fun acceptsTheWholePushFamily() {
        assertTrue(Push.isAction("com.google.android.c2dm.intent.RECEIVE"))
        assertTrue(Push.isAction("com.google.android.c2dm.intent.REGISTRATION"))
        assertTrue(Push.isAction("com.google.firebase.MESSAGING_EVENT"))
        assertTrue(Push.isAction("com.google.firebase.INSTANCE_ID_EVENT"))
        assertTrue(Push.isAction("com.google.firebase.NEW_TOKEN"))
    }

    @Test
    fun rejectsEverythingElse() {
        assertFalse(Push.isAction(null))
        assertFalse(Push.isAction(""))
        assertFalse(Push.isAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(Push.isAction("com.google.android.c2dm.intent.RECEIVE.extra")) // 后缀不完整
        assertFalse(Push.isAction("com.example.intent.RECEIVE")) // 前缀不对
    }

    @Test
    fun findsThePushActionAmongLooseArgs() {
        val args = listOf<Any?>("com.google.android.gms", null, 42, "com.google.android.c2dm.intent.RECEIVE")
        assertEquals("com.google.android.c2dm.intent.RECEIVE", Push.actionIn(args))
        assertNull(Push.actionIn(listOf<Any?>("android.intent.action.BOOT_COMPLETED", 1)))
    }

    @Test
    fun recognisesPackageNamesButNotActionsClassNamesOrTags() {
        val args = listOf<Any?>(
            "com.google.android.gms",
            "com.google.android.c2dm.intent.RECEIVE", // action 含大写
            "com.android.server.am.BroadcastRecord\$1", // 类名含 $
            "FCMXX", // wakelock tag 没有点
            "com.example.app",
        )
        assertEquals("com.google.android.gms", Push.packageIn(args))
        assertNull(Push.packageIn(listOf<Any?>("com.google.firebase.MESSAGING_EVENT")))
        assertNull(Push.packageIn(listOf<Any?>(null, 7, true)))
    }
}
