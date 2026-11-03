package com.homeweb.lyriccar.data

/** 局域网传输只需要歌词库的三件事；抽成接口后服务可以在纯 JVM 里验证。 */
interface LyricLibrary {
    val count: Int
    fun exportJson(): String
    fun importJson(text: String): ImportResult
}
