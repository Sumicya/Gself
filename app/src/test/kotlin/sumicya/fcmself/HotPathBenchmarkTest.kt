package sumicya.fcmself

import java.io.File
import java.lang.management.ManagementFactory

import org.junit.jupiter.api.Test

/**
 * 热路径基线：把「推送判据有多贵」变成一条命令的能量出来的数，而不是形容词。
 *
 * 为什么放在单测里而不是引 JMH：JMH 要新依赖、要独立源码集，CI 得多跑一整个模块；
 * 这里的三个函数是纯函数（位运算 + `String.contains`），量的是**相对量级**和**每次分配字节**，
 * 用 `System.nanoTime` + 多批取中位数已经够分辨「纳秒级」和「微秒级」。
 *
 * 口径（写死，换机器可比）：
 *   · 每个函数先预热 20 万次（让 C1/C2 编完），再跑 5 批 × 100 万次；
 *   · ns/op 取 5 批的**中位数**除以次数，除法在最后做，避免批间抖动放大；
 *   · 分配量用 `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`，同样是 5 批取中位数；
 *   · 结果是宿主 JVM 的数（CI 是 Linux x86_64），**不是真机**；真机只有 logcat 的毫秒差可看。
 *
 * 不测 `Push.targetOf`：它吃 `android.content.Intent`，JVM 单测里拿到的是桩对象，
 * 量出来的是桩的耗时，与 system_server 无关——那种数比不测更糟。
 */
class HotPathBenchmarkTest {

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Volatile
    private var sink = 0

    private data class Row(val name: String, val nsPerOp: Double, val bytesPerOp: Double)

    private fun measure(name: String, body: () -> Boolean): Row {
        val warmup = 200_000
        val iterations = 1_000_000
        val batches = 5
        repeat(warmup) { if (body()) sink++ }
        val times = LongArray(batches)
        val allocs = LongArray(batches)
        for (b in 0 until batches) {
            val before = if (bean.isThreadAllocatedMemorySupported) bean.currentThreadAllocatedBytes else 0L
            val t0 = System.nanoTime()
            var hits = 0
            for (i in 0 until iterations) if (body()) hits++
            val dt = System.nanoTime() - t0
            sink += hits
            allocs[b] = if (bean.isThreadAllocatedMemorySupported)
                bean.currentThreadAllocatedBytes - before else 0L
            times[b] = dt
        }
        times.sort()
        allocs.sort()
        return Row(name, times[batches / 2].toDouble() / iterations,
                   allocs[batches / 2].toDouble() / iterations)
    }

    @Test
    fun reportHotPathBaseline() {
        val pushAction = "com.google.android.c2dm.intent.RECEIVE"
        val otherAction = "android.intent.action.SCREEN_ON"
        val target = "com.example.app"
        val plain = 0x10000000
        val include = 0x20

        val rows = listOf(
            measure("isPush(hit)") { Push.isPush(pushAction) },
            measure("isPush(miss)") { Push.isPush(otherAction) },
            measure("excludesStopped(false)") { Push.excludesStopped(plain) },
            measure("excludesStopped(true)") { Push.excludesStopped(0x10 or plain) },
            measure("needsStoppedPatch(hit)") { Push.needsStoppedPatch(pushAction, target, plain) },
            measure("needsStoppedPatch(带 INCLUDE，快路)") { Push.needsStoppedPatch(pushAction, target, include) },
            measure("needsStoppedPatch(非推送)") { Push.needsStoppedPatch(otherAction, target, plain) },
        )

        val cpu = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }
                ?.substringAfter(':')?.trim()
        }.getOrNull() ?: "unknown"
        val head = "# JVM ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}) " +
            "/ ${System.getProperty("os.name")} ${System.getProperty("os.arch")} / CPU: $cpu"

        val lines = rows.map {
            java.lang.String.format(java.util.Locale.ROOT, "%-34s %8.2f ns/op %6.1f B/op",
                it.name, it.nsPerOp, it.bytesPerOp)
        }
        val text = (listOf(head) + lines).joinToString("\n")
        println(text)
        File(System.getProperty("user.dir"), "build").mkdirs()
        File(System.getProperty("user.dir"), "build/bench-hooks.txt").writeText(text + "\n")
    }
}
