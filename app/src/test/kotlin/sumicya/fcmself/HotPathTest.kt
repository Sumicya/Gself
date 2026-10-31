package sumicya.fcmself

import java.lang.management.ManagementFactory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「热路径不分配对象」这句注释，在 JVM 上是可以量的，而不是只能靠读代码相信。
 *
 * `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` 给出当前线程累计分配的字节数
 * （HotSpot 的 TLAB 计数，JDK 14 起在 `com.sun.management` 里公开）。先预热让 JIT 编完，再跑固定次数，
 * 两次读数的差值就是这批调用的分配量：只要每次调用哪怕多分配 1 个字节，20 万次就是 20 万字节，
 * 阈值 8 KB 兜不住。
 *
 * 为什么值得单独一个测试：这几个函数跑在 system_server 里，`isExcludingStopped` 每次 intent 解析都被问一次，
 * AMS 每个广播入口每次投递都被问一次；它们分配的对象不会立刻消失，而是变成系统进程里持续的 GC 压力。
 * 上一轮把这些调用改成「位运算 + 两次 `contains`」，本测试就是那句改动的可执行凭据。
 */
class HotPathTest {

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(): Long = bean.getCurrentThreadAllocatedBytes()

    @Test
    fun hotPathDoesNotAllocate() {
        // 不写成 assumeTrue 跳过：跳过在 CI 里看不出来，等于把「量过了」变成「可能没量」。
        // CI 用 Temurin 21（HotSpot），这项计数默认可用；真不可用就红，让人去看，而不是悄悄放行。
        assertTrue(bean.isThreadAllocatedMemorySupported, "JVM 不支持线程分配计数，无法验证零分配")
        assertTrue(bean.isThreadAllocatedMemoryEnabled, "线程分配计数被关闭，无法验证零分配")

        val pushAction = "com.google.android.c2dm.intent.RECEIVE"
        val otherAction = "android.intent.action.SCREEN_ON"
        val target = "com.example.app"
        val flags = 0x10000000 // 正常运行期广播的 flags，都没带 0x10 / 0x20

        fun batch(times: Int) {
            var sink = 0
            for (i in 0 until times) {
                // 语义层快路：原生答案本来就是 false 的那一支
                if (Push.excludesStopped(flags)) sink++
                // 标志层判据：不取 action、只做位运算就退出
                if (Push.needsStoppedPatch(pushAction, target, flags)) sink++
                if (Push.needsStoppedPatch(otherAction, target, flags)) sink++
                // 子串判据本体
                if (Push.isPush(pushAction)) sink++
                if (Push.isPush(otherAction)) sink++
            }
            // 每轮只该命中两处：推送判据为真、子串判据为真；顺带让 JIT 不能把整批调用当死代码删掉
            assertEquals(times * 2, sink)
        }

        batch(20_000) // 预热：解释执行与本机编译后各来一轮

        val before = allocatedBytes()
        batch(40_000) // 20 万次函数调用（每次批 5 个函数 × 40000）
        val used = allocatedBytes() - before

        // 阳性对照：同一个计量器、同一台机器，故意分配 2 万个对象，必须被量出来。
        // 没有这一段，上面的「0 字节」可能只是计量器没读数——一个从来没报过错的检查等于没加。
        val beforeControl = allocatedBytes()
        // 全部留着不放：对象一旦逃不掉，JIT 的逃逸分析就没法把它们抹成标量，计量器必然读得到
        val keepAll = ArrayList<Any>(20_000)
        for (i in 0 until 20_000) {
            keepAll.add(ArrayList<String>(1).also { it.add("probe-$i") })
        }
        val control = allocatedBytes() - beforeControl
        assertEquals(20_000, keepAll.size, "对照组的对象必须真的被创建并留住")
        assertTrue(
            control > 500_000,
            "阳性对照只量到 $control 字节，2 万个 ArrayList + 字符串至少该有几十万字节：计量器没在工作，零分配结论不算数",
        )

        assertTrue(
            used <= 8192,
            "热路径 20 万次调用分配了 $used 字节；预期 ≤ 8192（即每次 < 0.05 字节，等于不分配）",
        )
    }
}
