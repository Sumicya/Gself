plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sumicya.fcmself"
    compileSdk = 36

    defaultConfig {
        // 对外身份是 Gself；内部包名（namespace / 入口类）不变，CI 的 R8 入口校验仍查 sumicya/fcmself/XposedMain
        applicationId = "sumicya.gself"
        minSdk = 29
        targetSdk = 36
        // 发行版本（五段 yy.m.d.当日序号.总序号）由 CI 一处算定，这里只读，不自己算日期。
        // 取不到 = 非发行构建：名字带 dev- 前缀，绝不伪装成发行版本。
        // versionCode 仍按构建数单调递增，本地可传 -PversionCode=<CI run 号> 保持升级路径。
        val buildNumber = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "dev-$buildNumber"
        versionCode = buildNumber
    }

    buildTypes {
        // debug / release 同一套裁剪（入口类由 proguard-rules.pro 保住，LSPosed 按类名字符串加载）。
        // CI 只出 debug 包：它用默认 debug 证书签名，可直接安装测试；
        // release 变体保留给本地按需构建（未签名，用 scripts/sign-apk.sh 签）。
        configureEach {
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
    implementation(libs.dexkit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
