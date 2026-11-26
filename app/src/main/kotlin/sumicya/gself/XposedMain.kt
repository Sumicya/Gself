package sumicya.gself

import android.os.Build
import android.util.Log

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

private const val TAG = "Gself"

internal const val GMS = "com.google.android.gms"
internal const val GBOARD = "com.google.android.inputmethod.latin"

/** 框架日志句柄：只在写前几十条时用，之后为 null 也不影响 logcat。 */
private var frameworkApi: XposedInterface? = null

/**
 * 一行日志：logcat 每次写，LSPosed 框架日志只写前 [FRAMEWORK_LOG_BUDGET] 条。
 *
 * 上一轮把框架日志整条删掉是个错误：`/data/adb/lspd/log/*.log` 里就再也读不到 Gself 的任何一行，
 * 而真机验收全指着那个目录。省电的边界不该越过「还能不能验证」。改成限量：
 * 启动判定行、几条 hook active 都在前几十条里，全进得来；之后的高频 `wake:` 不再落盘。
 */
private const val FRAMEWORK_LOG_BUDGET = 40

/** 已写进框架日志的条数。只用原子自增，不做二次判断——多个线程同时进来时账不会算错。 */
private val frameworkLogs = java.util.concurrent.atomic.AtomicInteger()

fun trace(text: String) {
    Log.d(TAG, text)
    // 超出配额直接返回：这里在热路径上，连 runCatching 都不该进。
    if (frameworkLogs.get() >= FRAMEWORK_LOG_BUDGET) return
    if (frameworkLogs.incrementAndGet() <= FRAMEWORK_LOG_BUDGET) {
        runCatching { frameworkApi?.log(Log.INFO, TAG, "[gself] $text") }
    }
}

/**
 * Gself 入口（包名 sumicya.gself，入口类名由 java_init.list 与 proguard keep 同时锁死）。全部 Hook 都在 system_server 里，所以只有一个进程入口。
 *
 * 没有开机闸门、没有配置、不写文件、没有界面：装上、作用域勾 `system`、重启。
 * 三组挂载点按顺序各自独立安装，任何一组挂不上只影响它自己。
 */
class XposedMain : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val hooks = Hook(this, param.classLoader)
        frameworkApi = hooks.api
        trace("Gself 载入 system_server（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
        hooks.wakeStoppedApps()
        hooks.notificationFixes()
        hooks.oplusFixes()
    }

    /** app 进程入口：按包名分发 GMS / Gboard 两组。 */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val hooks = Hook(this, param.defaultClassLoader)
        frameworkApi = hooks.api
        when (param.packageName) {
            GMS -> hooks.install("通行密钥解限") { passkeyUnlock(param.applicationInfo.sourceDir) }
            GBOARD -> hooks.install("Gboard 剪贴板") { gboardFixes() }
        }
    }
}
