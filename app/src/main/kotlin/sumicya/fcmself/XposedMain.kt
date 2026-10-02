package sumicya.fcmself

import android.os.Build
import android.util.Log

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

private var xposedApi: XposedInterface? = null

private const val TAG = "FcmSelf"

internal const val GMS = "com.google.android.gms"
internal const val GBOARD = "com.google.android.inputmethod.latin"

/** 一行日志：logcat + LSPosed 框架日志。模块没有界面，日志是唯一的观测手段。 */
fun trace(text: String) {
    Log.d(TAG, text)
    runCatching { xposedApi?.log(Log.INFO, TAG, "[fcmself] $text") }
}

/**
 * fcmself 入口。全部 Hook 都在 system_server 里，所以只有一个进程入口。
 *
 * 没有开机闸门、没有配置、不写文件、没有界面：装上、作用域勾 `system`、重启。
 * 三组挂载点按顺序各自独立安装，任何一组挂不上只影响它自己。
 */
class XposedMain : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val hooks = Hook(this, param.classLoader)
        xposedApi = hooks.api
        trace("fcmself 载入 system_server（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
        hooks.install("推送唤醒") { wakeStoppedApps() }
        hooks.install("通知保留") { notificationFixes() }
        hooks.install("ColorOS 放行") { oplusFixes() }
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
