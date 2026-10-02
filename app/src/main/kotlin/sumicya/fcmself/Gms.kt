package sumicya.fcmself

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 通行密钥解限（自 [yangFenTuoZi/GooglePasswordManagerUnlock] 移植，GPL-3.0，换用本仓库的 libxposed 基建）。
 *
 * 移除 Google 密码管理器对「特权浏览器允许列表」的检查：用 DexKit 的稳定字符串定位 GMS 的
 * 来源解析器，不依赖任何混淆后的名称。动态匹配器只有在找到**唯一**候选时才安装，否则故意
 * 不执行任何操作，避免误改其它凭据流程（这条保留，是它的安全边界）。
 */
fun Hook.passkeyUnlock(apkPath: String) {
    val assertedOrigins = Collections.synchronizedMap(WeakHashMap<Any, String>())

    System.loadLibrary("dexkit")
    val resolveOrigin: Method
    DexKitBridge.create(apkPath).use { bridge ->
        val candidates = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .returnType(String::class.java)
                    .paramTypes(String::class.java)
                    .usingStrings("privilegedAllowlist", "apps", "cert_fingerprint_sha256"),
            ),
        )
        check(candidates.size == 1) { "动态定位到 ${candidates.size} 个允许列表来源解析器候选项" }
        resolveOrigin = candidates.single().getMethodInstance(loader).apply { isAccessible = true }
    }

    val originCtor: Constructor<*> = resolveOrigin.declaringClass.declaredConstructors.firstOrNull { c ->
        c.parameterTypes.let { it.size == 3 && it[0] == String::class.java && it[2] == String::class.java }
    }?.apply { isAccessible = true }
        ?: throw NoSuchMethodException("动态定位的类中不存在 (String, *, String) 来源构造函数")

    // 解析器：有被断言的 origin 就直接返回它，否则原方法照跑
    hook(resolveOrigin) { chain -> assertedOrigins[chain.thisObject] ?: chain.proceed() }

    // 来源构造函数：构造完成后把 args[2]（origin）记到实例上，供解析器改写
    hook(originCtor) { chain ->
        chain.proceed().also {
            val origin = chain.args.getOrNull(2) as? String
            val holder = chain.thisObject
            if (!origin.isNullOrEmpty() && holder != null) assertedOrigins[holder] = origin
        }
    }
    trace("通行密钥解限 Hook 已安装")
}
