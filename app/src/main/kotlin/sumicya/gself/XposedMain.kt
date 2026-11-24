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
 * Gself 入口（包名 sumicya.gself，入口类名由 java_init.list 与 proguard keep 同时锁死）。全部 Hook 都在 system_server 里，所以只有一个进程入口。
 *
 * 没有开机闸门、没有配置、不写文件、没有界面：装上、作用域勾 `system`、重启。
 * 三组挂载点按顺序各自独立安装，任何一组挂不上只影响它自己。
 */
class XposedMain : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val hooks = Hook(this, param.classLoader)
        trace("Gself 载入 system_server（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
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
