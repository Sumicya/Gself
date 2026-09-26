package sumicya.fcmself

import android.os.Build
import android.util.Log

import java.util.concurrent.atomic.AtomicBoolean

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 开机闸门：放行介入之前先等 [BOOT_DELAY_MS]。
 *
 * 开机早期 ROM 自己就在猛发广播、猛改通知，那时介入没有意义。这是 system_server 模块，
 * 宁可多等一分钟，也别在设备最脆弱的阶段添乱。
 */
@Volatile
var booted = false

private var xposedApi: XposedInterface? = null

private const val TAG = "FcmSelf"
private const val BOOT_DELAY_MS = 60_000L

/** 一行日志：logcat + LSPosed 框架日志。模块没有界面，日志是唯一的观测手段。 */
fun trace(text: String) {
    Log.d(TAG, text)
    runCatching { xposedApi?.log(Log.INFO, TAG, "[fcmself] $text") }
}

/**
 * fcmself 入口。
 *
 * 全部 Hook 都在 system_server 里，所以只有一个进程入口、一张挂载点清单 [FIXES]：
 * 新增一组 Hook 就是往清单里加一行。模块不读配置、不写文件、没有界面——
 * 装上、作用域勾 `system`、重启。
 */
class XposedMain : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val hooks = Hook(this, param.classLoader)
        xposedApi = hooks.api
        trace("fcmself 载入 system_server（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
        bootGate(hooks)
        for ((name, fixes) in FIXES) hooks.install(name, fixes)
    }

    private fun bootGate(hooks: Hook) {
        var installed = false
        hooks.install("开机闸门") {
            hook(find(classOf(AMS), "finishBooting")) { chain -> chain.proceed().also { bootLater() } }
            installed = true
        }
        // ponytail: 闸门挂不上（ROM 改了 finishBooting）就退化成「载入后 60 秒」。
        //             宁可早一点介入，也不能因为找不到一个方法就让整个模块永久不生效。
        if (!installed) bootLater()
    }

    /** finishBooting 在部分 ROM 上会命中多次（真机日志：两次，隔 2 秒），闸门只起一次表。 */
    private val bootScheduled = AtomicBoolean()

    private fun bootLater() {
        if (!bootScheduled.compareAndSet(false, true)) return
        Thread({
            Thread.sleep(BOOT_DELAY_MS)
            booted = true
            trace("Boot Complete")
        }, "fcmself-boot").apply { isDaemon = true }.start()
    }

    private companion object {
        /**
         * system_server 内安装的挂载点组，顺序即安装顺序。
         *
         * 写成 `{ wakeStoppedApps() }` 而不是 `::wakeStoppedApps`：顶层扩展函数的 callable
         * reference 类型是 `(Hook) -> Unit`，不会适配成 [Fixes] 的 `Hook.() -> Unit`
         * （lambda 会适配，引用不会，编译器报 receiver type mismatch）。
         */
        val FIXES = listOf<Pair<String, Fixes>>(
            "唤醒已停止的应用" to { wakeStoppedApps() },
            "自启动限制" to { autoStartFixes() },
            "通知不被自动清除" to { notificationFixes() },
            "MIUI 电源策略" to { miuiFixes() },
            "ColorOS 后台代理" to { oplusFixes() },
        )
    }
}
