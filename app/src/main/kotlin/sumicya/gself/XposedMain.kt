package sumicya.gself

import android.os.Build
import android.util.Log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

private const val TAG = "Gself"

internal const val GMS = "com.google.android.gms"
internal const val GBOARD = "com.google.android.inputmethod.latin"

/** 一行日志，只走 logcat：原先还调一次框架日志（落盘 + 跨进程），而这里每条命中推送都要跑一次。 */
fun trace(text: String) {
    Log.d(TAG, text)
}

/**
 * 载入期把这几行也写进 LSPosed 框架日志（`/data/adb/lspd/log/`）。
 *
 * 118/119 两版把框架日志整条删掉，那个目录里一行 Gself 都没有，真机验收无从下手。
 * 这里只补「载入判定」那几行——它们一共十来条，都在开机那一刻，不进热路径。
 * 热路径（`waken` / `keep notification`）仍然只走 logcat，省电的边界守在这里。
 */
private fun traceOnBoot(api: io.github.libxposed.api.XposedInterface, text: String) {
    Log.d(TAG, text)
    runCatching { api.log(Log.INFO, TAG, "[gself] $text") }
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
        // 这几行走两条路：logcat 与框架日志。探针读的是框架日志那个目录。
        traceOnBoot(hooks.api, "Gself 载入 system_server（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
        hooks.wakeStoppedApps()
        hooks.notificationFixes()
        hooks.oplusFixes()
    }

    /** app 进程入口：按包名分发 GMS / Gboard 两组。 */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val hooks = Hook(this, param.defaultClassLoader)
        when (param.packageName) {
            GMS -> hooks.install("通行密钥解限") { passkeyUnlock(param.applicationInfo.sourceDir) }
            GBOARD -> hooks.install("Gboard 剪贴板") { gboardFixes() }
        }
    }
}
