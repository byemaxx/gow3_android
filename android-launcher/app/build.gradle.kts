plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Steam sign-in (SteamDllDialog.java) needs JavaSteam and its dependencies, fetched by
// tools/android/inputs/fetch_steam_libs.sh into .local-deps/android/steam-libs. When the
// libraries are absent (e.g. a plain Android Studio build before the fetcher has run) the
// src/steamStub/java fallback is compiled instead and GET FROM STEAM explains the build lacks
// Steam downloads. Once jars.txt exists the real implementation in src/steam/java is used and
// the jars and zstd JNI are packaged into the APK.
val steamLibs = rootProject.file("../.local-deps/android/steam-libs")
val steamAvailable = steamLibs.resolve("jars.txt").isFile

android {
    namespace = "com.gow3.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gow3.android.bionic"
        minSdk = 28
        targetSdk = 28
        versionCode = 4
        versionName = "2.1.7-bionic-v1-vibration-audio"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk 28 是本移植的硬性运行约束（Android 不变量）；它是
        // 侧载的游戏 APK，而非 Play Store 提交。
        disable += "ExpiredTargetSdkVersion"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets {
        getByName("main") {
            // 页面与运行时共用按 ID 的 XML 目录，不维护第二份补丁名单。
            assets.srcDir("../../patches")
            jniLibs.srcDir("../../tools/android/bridges")
            // Steam real implementation vs. no-Steam fallback, gated on the fetched libraries.
            java.srcDir(if (steamAvailable) "src/steam/java" else "src/steamStub/java")
            if (steamAvailable) {
                jniLibs.srcDir(steamLibs.resolve("jni"))
            }
        }
        getByName("debug") {
            assets.srcDir("../../out/android-debug/assets")
        }
        getByName("release") {
            assets.srcDir("../../out/android-release/assets")
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("gow3.patchCatalogDir", rootProject.file("../patches").absolutePath)
}

// 调试运行时 payload 不是源文件：它由仓库工具生成。
// 在任何 debug 构建之前（幂等地）暂存它，以便 Android Studio 一键构建携带
// 匹配的无游戏运行时，与 tools/android/debug/build_debug_apk.sh 的做法一致。
val stageDebugRuntime by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", "tools/android/debug/stage_debug_runtime.sh")
    // 没有可靠的 up-to-date 输入：总是重新执行（运行时缓存新鲜时很快）。
    outputs.upToDateWhen { false }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(stageDebugRuntime)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // 本地单测（无 Robolectric）需要真实的 org.json 实现：android.jar 里的 org.json
    // 是会抛 "Stub!" 的桩。仅测试 classpath 使用，不进 APK。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JavaSteam and its dependencies when the Steam libraries are present; see steamAvailable above.
    if (steamAvailable) {
        // Drop the jars the Kotlin/AGP plugins already put on the compile/runtime classpath: D8's
        // duplicate-class check would otherwise fail. The platforms' kotlin-stdlib, kotlinx-coroutines
        // and kotlinx-serialization are binary-compatible with the versions JavaSteam was built
        // against. kotlin-stdlib-jdk7/jdk8 stay: nothing else provides them.
        implementation(fileTree(steamLibs.resolve("jars")) {
            include("*.jar")
            exclude("annotations-*.jar")
            exclude {
                it.name.startsWith("kotlinx-coroutines-core-jvm-") ||
                    it.name.startsWith("kotlinx-serialization-core-jvm-") ||
                    (it.name.startsWith("kotlin-stdlib-") && !it.name.startsWith("kotlin-stdlib-jdk"))
            }
        })
    }
}
