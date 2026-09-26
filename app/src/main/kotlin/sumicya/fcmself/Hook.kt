package sumicya.fcmself

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain

/** 一组挂载点：拿到 [Hook] 往里登记。 */
typealias Fixes = Hook.() -> Unit

/**
 * 全部基建就这一个类。
 *
 * Hook 目标都是各家 ROM 的私有类，签名编译期不可知，所以只能按名字运行期查找——
 * 这是 ROM 逼出来的复杂度，留着。其余一切抽象都删了：模块基类、静态实例表、
 * 就绪握手、SDK_INT 版本候选表、签名解析器、before/after 两套拦截原语。
 *
 * 拦截只有一个原语 [hook]：要不要调 `chain.proceed()`、调完之后改不改返回值，
 * 都由 lambda 自己决定。要「原方法照跑再改结果」就自己先 `proceed()`：
 * `hook(m) { chain -> val r = chain.proceed(); if (推送) false else r }`
 *
 * 两条规矩：
 * - 类/方法查不到 = 这台设备没这个 ROM 特性，[install] 记一条 `hook skip` 就跳过；
 * - 拦截器自己抛的异常不吞，交给框架（module.prop 的 protective 模式）。
 */
class Hook(val api: XposedInterface, val loader: ClassLoader) {

    /** 登记一组挂载点；组内任何一处失败只影响这一组。 */
    fun install(name: String, fixes: Fixes) {
        try {
            fixes()
        } catch (t: Throwable) {
            trace("hook skip $name: $t")
        }
    }

    /** 加载类；不存在抛 [Missing]，由 [install] 降级成一条日志。 */
    fun classOf(name: String): Class<*> = try {
        Class.forName(name, false, loader)
    } catch (e: ClassNotFoundException) {
        throw Missing(name, e)
    }

    /** 加载类；不存在返回 null（「A 类不在就试 B 类」用）。 */
    fun classIfExists(name: String): Class<*>? = runCatching { classOf(name) }.getOrNull()

    /** 按名字找方法；省略 [params] 表示取参数最多的重载（ROM 逐版本往尾部加参数）。 */
    fun find(clazz: Class<*>, name: String, vararg params: Class<*>): Method =
        clazz.declaredMethods
            .filter { it.name == name && (params.isEmpty() || it.parameterTypes.contentEquals(params)) }
            .maxByOrNull { it.parameterCount }
            ?.also { it.isAccessible = true }
            ?: throw NoSuchMethodError("${clazz.name}#$name")

    /** 参数最多的构造器。 */
    fun ctor(clazz: Class<*>): Constructor<*> =
        clazz.declaredConstructors.maxByOrNull { it.parameterCount }
            ?.also { it.isAccessible = true }
            ?: throw NoSuchMethodError("${clazz.name}#<init>")

    /** 找字段，沿继承链向上。 */
    fun field(clazz: Class<*>, name: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchFieldError("${clazz.name}#$name")
    }

    /** 唯一的拦截原语。 */
    fun hook(target: Executable, body: (Chain) -> Any?): Executable =
        target.also { api.hook(it).intercept { chain -> body(chain) } }

    /** 目标类在这台 ROM 上不存在。继承 [Error]：只 catch 它，不吞别的异常。 */
    class Missing(name: String, cause: Throwable) : Error(name, cause)
}
