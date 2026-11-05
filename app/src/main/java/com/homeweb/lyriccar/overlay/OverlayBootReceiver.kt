package com.homeweb.lyriccar.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后如果悬浮歌词是开着的，把前台服务拉起来 —— 网易云那种「开了就一直有」的体验。
 *
 * 只做这一件事；没有权限 / 开关关着时 [LyricOverlayService.refresh] 会直接忽略。
 */
class OverlayBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        LyricOverlayService.refresh(context)
    }
}
