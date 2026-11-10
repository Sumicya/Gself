plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sumicya.gself"
    compileSdk = 36

    defaultConfig {
        // 对外身份与代码身份都是 sumicya.gself：CI 的 dex 断言查 Lsumicya/gself/XposedMain;，
        // 改名必须与 java_init.list、proguard keep 同一提交。
        applicationId = "sumicya.gself"
        // 只做新包：广播出口只实现 Android 16+ 的 AMS 入口，低版本由 minSdk 在安装时拒绝。
        minSdk = 36
        targetSdk = 36
        // 版本由 CI 的 tools/version.py 一处算定，经 -PversionName / -PversionCode 传入；
        // 本地不传就是非发行版（versionName 带 dev-、versionCode=1），绝不伪装成发行版本。
        val buildNumber = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "dev-$buildNumber"
        versionCode = buildNumber

        // Android 16 设备全是 64 位；DexKit 自带四份 .so，只留本机这一份。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        configureEach {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 剔掉运行期用不到的随包资源（编译器与反射库才有用）；别动 META-INF/xposed/**。
    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/com/**",
                "DebugProbesKt.bin",
            )
        }
    }

    // LspModuleClassLoader 从 base.apk!/lib/arm64-v8a 直接 mmap .so，Bionic 要求它们按 STORED 存；
    // 2026-10-30 设过 useLegacyPackaging = true，压过的 libdexkit.so 在真机上抛 UnsatisfiedLinkError，
    // 所以保持 AGP 默认（STORED），并由 tools/apkcheck.py 断言。
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

// AGP 9 起 Kotlin 内置，不再单独 apply kotlin-android；jvmTarget 与 compileOptions 对齐。
// 不用 -parameters：参数下标全部按类型在运行期探测，不依赖参数名。
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
