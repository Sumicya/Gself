# fcmself 的 R8 规则（debug / release 都开 minify）

# LSPosed 按 META-INF/xposed/java_init.list 里的**类名字符串**加载入口类：
# 入口一旦被改名或裁剪，构建仍然成功，但模块会静默不加载，所以必须原样保留。
-keep class sumicya.fcmself.XposedMain { *; }

# libxposed API 是 compileOnly 依赖，运行期由框架提供，R8 在编译期看不到这些类。
-dontwarn io.github.libxposed.api.**

# `hook skip <组名>: <异常>` 是排查「这台设备有没有这个挂载点」的唯一线索，
# 异常类名被混淆成「q1:」就没意义了——只保留类名，不保留成员。
-keepnames class sumicya.fcmself.Hook$Missing
