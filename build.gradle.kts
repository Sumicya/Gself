// 根工程只负责钉住插件版本；依赖与仓库见 settings.gradle.kts 与 gradle/libs.versions.toml。
// 没有根级任务——clean 由 app 模块的 base 插件自带（./gradlew :app:clean）。
plugins {
    alias(libs.plugins.android.application) apply false
}
