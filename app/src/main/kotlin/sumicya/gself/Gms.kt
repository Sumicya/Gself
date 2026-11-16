package sumicya.gself

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

@Volatile
private var gmsInstalled = false

/**
 * CallingAppInfo 构造器里 origin 参数的下标（找不到返回 -1）：
 * 首参固定是 packageName: String；其余参数里若有且仅有一个 String，它就是 origin
 * （兼容 3 参 `(String, SigningInfo/List, String)` 下标 2 与 4 参 `(String, String, Compat, SigningInfo)` 下标 1）。
 */
internal fun originArgIndex(paramTypes: Array<Class<*>>): Int {
    if (paramTypes.isEmpty() || paramTypes[0] != String::class.java) return -1
    var idx = -1
    for (i in 1 until paramTypes.size) {
        if (paramTypes[i] == String::class.java) {
            if (idx != -1) return -1
            idx = i
        }
    }
    return idx
}

/**
 * 通行密钥解限（自 [yangFenTuoZi/GooglePasswordManagerUnlock] 移植，GPL-3.0，换用本仓库的 libxposed 基建）。
 *
 * 移除 Google 密码管理器对「特权浏览器允许列表」的检查：用 DexKit 的稳定字符串定位 GMS 的
 * 来源解析器，不依赖任何混淆后的名称。动态匹配器只有在找到**唯一**候选时才安装，否则故意
 * 不执行任何操作，避免误改其它凭据流程（这条保留，是它的安全边界）。
 */
fun Hook.passkeyUnlock(apkPath: String) {
    if (gmsInstalled) return
    val assertedOrigins = Collections.synchronizedMap(WeakHashMap<Any, String>())

    System.loadLibrary("dexkit")
    val resolveOrigin: Method
    DexKitBridge.create(apkPath).use { bridge ->
        fun match(vararg strings: String) = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .returnType(String::class.java)
                    .paramTypes(String::class.java)
                    .usingStrings(*strings),
            ),
        )
        val candidates = match("privilegedAllowlist", "apps", "cert_fingerprint_sha256")
            .ifEmpty { match("privilegedAllowlist", "privilegedAllowlist must be formatted properly") }
        check(candidates.size == 1) { "动态定位到 ${candidates.size} 个允许列表来源解析器候选项" }
        resolveOrigin = candidates.single().getMethodInstance(loader).apply { isAccessible = true }
    }

    val originCtors = resolveOrigin.declaringClass.declaredConstructors.mapNotNull { c ->
        val idx = originArgIndex(c.parameterTypes)
        if (idx > 0) c.apply { isAccessible = true } to idx else null
    }
    if (originCtors.isEmpty()) {
        throw NoSuchMethodException("动态定位的类中不存在来源构造函数")
    }

    // 解析器：有被断言的 origin 就直接返回它，否则原方法照跑
    hook(resolveOrigin) { chain -> assertedOrigins[chain.thisObject] ?: chain.proceed() }

    // 来源构造函数：构造完成后把 origin 记到实例上，供解析器改写
    for ((ctor, originIdx) in originCtors) {
        hook(ctor) { chain ->
            chain.proceed().also {
                val origin = chain.args.getOrNull(originIdx) as? String
                val holder = chain.thisObject
                if (!origin.isNullOrEmpty() && holder != null) assertedOrigins[holder] = origin
            }
        }
    }
    gmsInstalled = true
    trace("通行密钥解限 Hook 已安装（构造器 ${originCtors.size} 个）")
}
