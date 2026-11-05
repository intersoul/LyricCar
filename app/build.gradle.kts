import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 发布签名 ─────────────────────────────────────────────
// 口令不写在构建脚本里，而是放在 app/key.properties（已被 .gitignore 排除）。
// 模板见 app/key.properties.example。
//
// applicationId 与签名保持不变，所以这个原生版本可以**直接覆盖安装**在旧版
// Flutter 版之上，车机上已经采集的歌词库（<files>/carlyric/lyrics.json）也会继续可用。
val keystoreProperties = Properties()
val keystorePropertiesFile = project.file("key.properties")
val hasReleaseKey = keystorePropertiesFile.exists()
if (hasReleaseKey) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

// ── 版本号 ─────────────────────────────────────────────
// 只在这里定义一次：APK 文件名（LyricCar v2.4.0.apk）和「关于」面板都读它。
val appVersionCode = 14
val appVersionName = "2.6.2"

android {
    namespace = "com.homeweb.lyriccar"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.homeweb.lyriccar"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        buildConfig = true
    }

    // 车机开发环境经常离线（Gradle 缓存里不一定有 lint-gradle）。
    // 发布包不跑 lintVital，避免 `assembleRelease` 被网络卡住。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // 没配 key.properties 时退回 debug key，只够本地跑通构建。
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// APK 文件名按模板 `LyricCar v{版本号}.apk`：
//   release → LyricCar v2.4.0.apk
//   debug   → LyricCar v2.4.0-debug.apk
// 文件名里的版本号和「关于」面板读的是同一个 appVersionName。
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val suffix = if (variant.name == "release") "" else "-${variant.name}"
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName
                .set("${rootProject.name} v$appVersionName$suffix.apk")
        }
    }
}

// 没有第三方依赖：媒体会话、JSON、HTTP、UI 全部使用 Android 平台自带 API。
