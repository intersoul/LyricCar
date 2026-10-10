package com.homeweb.lyriccar

import android.os.Bundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 宿主 Activity。
 *
 * 只干三件事：
 *   1. `stools/screen`  —— 屏幕常亮（看歌词时不自动熄屏）；
 *   2. 装上 [MediaSessionBridge]，把系统媒体会话（正在播哪首歌、歌词写到哪一行）
 *      实时喂给 Flutter；
 *   3. 装上 [LyricTransferChannel]，把歌词库导出到系统「下载」目录（以及从那里
 *      读回来）—— 这台车机没有处理 SAF「另存为」的 Activity，只能自己写。
 *
 * 这里**没有**任何音频相关的代码 —— 本 App 不接管蓝牙音频，声音始终由
 * 车机自带的蓝牙播放器出，我们只是个旁观的歌词显示器。
 */
class MainActivity : FlutterActivity() {

    private val screenChannelName = "stools/screen"

    private var bridge: MediaSessionBridge? = null

    private var transfer: LyricTransferChannel? = null

    /** Android 9 及以下写公共「下载」目录要运行时申请存储权限，回调转给通道。 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        transfer?.onPermissionResult(requestCode, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 车机是横屏的：允许左右横屏（传感器控制），但绝不竖过来
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, screenChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // 只是 window.addFlags(FLAG_KEEP_SCREEN_ON)：
                    // 让系统别自动灭屏，不是 WakeLock，不需要权限也不额外耗电
                    "setKeepScreenOn" -> {
                        setKeepScreenOn(call.argument<Boolean>("enabled") ?: false)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        bridge = MediaSessionBridge(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
            .also { it.attach() }

        transfer = LyricTransferChannel(
            this,
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, LyricTransferChannel.CHANNEL),
        ).also { it.attach() }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridge?.detach()
        bridge = null
        transfer?.detach()
        transfer = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        runOnUiThread {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
