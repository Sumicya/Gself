plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sumicya.fcmself"
    compileSdk = 36

    defaultConfig {
        applicationId = "sumicya.fcmself"
        minSdk = 29
        targetSdk = 36
        versionCode = 60
        // CI 通过 -Pfcmself.versionName 注入「日期_短SHA」版本；本地构建回落到语义版本
        versionName = providers.gradleProperty("fcmself.versionName").getOrElse("1.0.0")
    }

    buildTypes {
        debug {
            // debug 也走 R8：把没用到的那 90%+ kotlin-stdlib 裁掉，
            // 同时保持默认 debug 签名（可直接安装），不再需要手动 apksigner。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            // 正式发布用：R8 裁剪 + 未签名。
            // 入口类由 proguard-rules.pro 保留（LSPosed 按类名字符串加载）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 起 Kotlin 由 AGP 内置（built-in Kotlin），不再单独 apply kotlin-android。
// jvmTarget 与 compileOptions 的 17 保持一致。
// 不需要 javaParameters / -parameters：参数下标全部按类型运行期探测，不依赖参数名。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
