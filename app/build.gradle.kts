plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sumicya.fcmself"
    compileSdk = 36

    defaultConfig {
        // 对外身份是 Gself；内部包名（namespace / 入口类）不变，CI 的 R8 入口校验仍查 sumicya/fcmself/XposedMain
        applicationId = "sumicya.gself"
        // 只做新包（项目约定）：广播出口只实现 Android 16+ 的 AMS 广播入口，
        // 旧版本不做兼容，安装时由 minSdk 明确拒绝。
        minSdk = 36
        targetSdk = 36
        // 发行版本（五段 yy.m.d.当日序号.总序号）由 CI 以 build.yml 的 run_number 一处算定并经
        // -PversionName / -PversionCode 传入，这里只读，不自己算日期、不取 run 号。
        // 取不到 = 非发行本地构建：名字带 dev- 前缀，绝不伪装成发行版本；
        // 要接着 CI 的总序号走就传 -PversionCode=<最近一次 CI 产物的第五段>。
        val buildNumber = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "dev-$buildNumber"
        versionCode = buildNumber

        // LSPosed 模块只在本机跑，不需要全 ABI 包：Android 16（API 36）设备全部是 64 位。
        // 依赖（DexKit）自带 armeabi-v7a / arm64-v8a / x86 / x86_64 四份 .so，只留本机这一份。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        // debug / release 同一套裁剪（入口类由 proguard-rules.pro 保住，LSPosed 按类名字符串加载）。
        // CI 只出 debug 包：它用默认 debug 证书签名，可直接安装测试；
        // release 变体保留给本地按需构建（未签名，用 tools/sign-apk.sh 签）。
        configureEach {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 打包时剔掉运行期用不到的随包资源：Kotlin 的 .kotlin_builtins / .kotlin_module 只服务编译器与
    // 反射库（本模块不用 kotlin-reflect），协程调试探针、依赖的 build 元数据同理。
    // 注意别动 META-INF/xposed/**：那是 LSPosed 读的 module.prop / java_init.list / scope.list。
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

    // .so 默认按 store 存（AGP 4.2 起 useLegacyPackaging=false，为了安装后能直接 mmap），
    // 原生库的原始字节一分不少地计入 APK。改成安装时解压，APK 内按 deflate 存。
    // 这一项是量出来的，不是猜的：同一份代码只增删这三行，APK 由 452,315 变成 651,327 字节，
    // 其中 `lib/arm64-v8a/libdexkit.so` 由 190,746（deflate）变 381,024（store），
    // 剩下的 8,734 字节是对齐与额外字段。删掉这三行，包体大 44%。
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
