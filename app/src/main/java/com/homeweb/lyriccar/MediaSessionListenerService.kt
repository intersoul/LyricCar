package com.homeweb.lyriccar

import android.service.notification.NotificationListenerService

/**
 * 只为拿「钥匙」而存在：被启用为通知监听器后，
 * MediaSessionManager.getActiveSessions() 才能读到别的 App 的媒体会话。
 *
 * 类名与旧 Flutter 版保持一致（com.homeweb.lyriccar.MediaSessionListenerService），
 * 这样覆盖安装后系统里已经授予的「通知使用权」仍然指向同一个组件，不用重新授权。
 */
class MediaSessionListenerService : NotificationListenerService()
