package com.homeweb.lyriccar

import android.service.notification.NotificationListenerService

/**
 * 只为拿「钥匙」而存在的服务。
 *
 * 系统里每个正在播歌的 App 都会把自己的播放状态和歌曲信息写进 **MediaSession**
 * （`MediaMetadata` 里有 TITLE / ARTIST / ALBUM / DURATION / 封面）。
 * 普通 App 想读**别人**的这些数据，Android 只开了一个口子：
 * `MediaSessionManager.getActiveSessions()` 仅对「已启用的 NotificationListenerService」
 * （或持有 MEDIA_CONTENT_CONTROL 的系统应用）返回数据。
 *
 * 所以本服务里一行处理通知的代码都不写 —— 它的全部意义就是被用户在
 * 「设置 → 通知使用权」里勾选上，从而让 [MediaSessionBridge] 能拿到那把钥匙。
 */
class MediaSessionListenerService : NotificationListenerService()
