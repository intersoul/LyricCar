allprojects {
    repositories {
        // 国内镜像（阿里云）优先，加速依赖下载
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // Flutter 引擎组件（io.flutter:*_debug 等）兜底仓库。
        // 说明：~/.zshrc 设置了 FLUTTER_STORAGE_BASE_URL=清华镜像，但 TUNA 镜像
        // 不同步最新引擎版本（download.flutter.io 404），需官方仓库兜底才能解析。
        maven("https://storage.googleapis.com/download.flutter.io")
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// ---------------------------------------------------------------------------
// 统一子工程的 compileSdk
//
// 背景：file_picker 8.3.7 仍写死 `compileSdk 34`，而它依赖的
// flutter_plugin_android_lifecycle 2.0.35 的 AAR 元数据要求使用方
// minCompileSdk >= 36，导致构建报错：
//   ':file_picker:checkReleaseAarMetadata' ... compile against version 36 or later
//
// 这里把所有 Android 子工程的 compileSdk 提升到 36（与 app 的
// flutter.compileSdkVersion 保持一致），无需改动插件版本或业务代码。
// 用反射调用以兼容 AGP 各版本的 API 差异。
// ---------------------------------------------------------------------------
subprojects {
    val applyCompileSdk: () -> Unit = {
        val androidExt = extensions.findByName("android")
        if (androidExt != null) {
            val targetCompileSdk = 36
            val applied = listOf("compileSdkVersion", "setCompileSdkVersion", "setCompileSdk")
                .any { methodName ->
                    runCatching {
                        androidExt.javaClass
                            .getMethod(methodName, Int::class.javaPrimitiveType)
                            .invoke(androidExt, targetCompileSdk)
                    }.isSuccess
                }
            logger.lifecycle(
                if (applied) {
                    "compileSdk -> $targetCompileSdk for ${project.name}"
                } else {
                    "警告：无法为 ${project.name} 设置 compileSdk（AGP API 不匹配）"
                }
            )
        }
    }
    // 上面的 evaluationDependsOn(":app") 会让 :app 提前完成评估，
    // 此时再注册 afterEvaluate 会抛异常，需直接应用。
    try {
        afterEvaluate { applyCompileSdk() }
    } catch (e: Exception) {
        logger.lifecycle("compileSdk override: ${project.name} 已完成评估，立即应用")
        applyCompileSdk()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
