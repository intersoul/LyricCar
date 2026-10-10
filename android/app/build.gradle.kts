import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// ── 发布签名 ─────────────────────────────────────────────
// 口令不写在这个文件里（它是要进版本库的），而是放 android/key.properties，
// 那个文件被 .gitignore 排除。字段说明见 android/key.properties.example。
//
// 「装上去会不会覆盖别的 App」由两件事决定，这两件事在这里都定死了：
//   * applicationId —— 不同 = 系统当成两个完全独立的 App，绝不会互相覆盖；
//   * 签名          —— 同一个 applicationId 下，签名不同则装不上去（只能先卸载）。
// LyricCar 用的是自己的 lyriccar-release.jks，和之前的项目没有任何关系。
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
val hasReleaseKey = keystorePropertiesFile.exists()
if (hasReleaseKey) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.homeweb.lyriccar"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.homeweb.lyriccar"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
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
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // 没配 key.properties 时退回 debug key，只为了本地
                // `flutter build apk --release` 还能跑通。
                // ⚠️ 这样的包不能当正式包用：debug key 是公开的，而且换机器就变了。
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

// 没有额外依赖：媒体会话读取用的是平台自带的
// MediaSessionManager / MediaController（android.media.session），
// 封面解码用 android.graphics，全是系统 API，不需要引第三方库。

flutter {
    source = "../.."
}
