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
        // CI 固化 yy.m.d.当日序号.总序号；本地构建使用非发行开发版本。
        val buildNumber = providers.gradleProperty("fcmself.buildNumber").getOrElse("1").toInt()
        val releaseName = providers.gradleProperty("versionName").orNull
        val releaseCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        versionName = releaseName ?: "0.0.0.0.$buildNumber"
        versionCode = releaseCode ?: buildNumber
    }

    buildTypes {
        // debug / release 同一套裁剪（入口类由 proguard-rules.pro 保住，LSPosed 按类名字符串加载）。
        // 唯一差别是签名：debug 用默认 debug 证书可直接装，release 产出未签名包、本地签。
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
