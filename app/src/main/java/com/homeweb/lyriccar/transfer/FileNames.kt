package com.homeweb.lyriccar.transfer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun lyricExportFileName(now: Date = Date()): String {
    val format = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    return "lyriccar-${format.format(now)}.json"
}

fun diagnosticsFileName(now: Date = Date()): String {
    val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    return "lyriccar-log-${format.format(now)}.txt"
}
