package com.homeweb.lyriccar.data

/** 导入结果，用来给用户一句人话的反馈。 */
data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    val summary: String
        get() = when {
            error != null -> "导入失败：$error"
            added == 0 && updated == 0 -> "文件里没有新的歌词（跳过 $skipped 首）"
            else -> "导入完成：新增 $added 首，更新 $updated 首，跳过 $skipped 首"
        }
}
