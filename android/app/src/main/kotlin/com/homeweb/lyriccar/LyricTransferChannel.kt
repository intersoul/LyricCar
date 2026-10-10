package com.homeweb.lyriccar

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * 把歌词库的 JSON 存进**系统「下载」目录**，以及从那里读回来。
 *
 * 为什么不用系统的「另存为」对话框（SAF / ACTION_CREATE_DOCUMENT）：
 * 这台 BYD 车机（Android 9）根本没装能处理它的 Activity，实测
 * `cmd package resolve-activity -a android.intent.action.CREATE_DOCUMENT`
 * 返回 "No activity found"，所以那条路在这台车上是**必然失败**的。
 * 直接写「下载」目录反而在文件管理器里一眼就能看到，也方便拷到别的车机。
 *
 * 存的位置：
 *   * Android 9 及以下：`下载/LyricCar/xxx.json`（公共目录，要
 *     WRITE_EXTERNAL_STORAGE 权限，运行时申请）；
 *   * Android 10 及以上：走 MediaStore 插进「下载」，不需要权限。
 */
class LyricTransferChannel(
    private val activity: Activity,
    private val channel: MethodChannel,
) {

    /** 下载目录下的子目录名，导出/导入都只认这里和下载根目录的 .json。 */
    private val folderName = "LyricCar"

    /** 等用户点完权限对话框。一次只可能有一个在等。 */
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val legacyStorage: Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    fun attach() {
        channel.setMethodCallHandler(::onCall)
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        pendingPermission = null
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_STORAGE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingPermission?.invoke(granted)
        pendingPermission = null
    }

    private fun onCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "saveToDownloads" -> {
                val name = call.argument<String>("name").orEmpty()
                val content = call.argument<String>("content").orEmpty()
                if (name.isEmpty()) {
                    result.error("bad_args", "缺少文件名", null)
                    return
                }
                withStoragePermission(result) { save(name, content, result) }
            }

            "listDownloads" -> withStoragePermission(result) {
                runCatching { result.success(if (legacyStorage) listLegacy() else listMediaStore()) }
                    .onFailure { result.error("list_failed", it.message, null) }
            }

            "readDownload" -> withStoragePermission(result) {
                val path = call.argument<String>("path").orEmpty()
                runCatching {
                    val text = if (legacyStorage) {
                        File(path).readText()
                    } else {
                        activity.contentResolver.openInputStream(Uri.parse(path))!!
                            .bufferedReader().use { it.readText() }
                    }
                    result.success(text)
                }.onFailure { result.error("read_failed", it.message, null) }
            }

            "deleteDownload" -> withStoragePermission(result) {
                val path = call.argument<String>("path").orEmpty()
                runCatching {
                    val ok = if (legacyStorage) File(path).delete()
                    else activity.contentResolver.delete(Uri.parse(path), null, null) > 0
                    result.success(ok)
                }.onFailure { result.error("delete_failed", it.message, null) }
            }

            else -> result.notImplemented()
        }
    }

    // ----------------------------------------------------------
    // 权限
    // ----------------------------------------------------------
    /**
     * Android 9 及以下写公共「下载」目录要 WRITE_EXTERNAL_STORAGE，是危险权限，
     * 得运行时问一次。Android 10 起用 MediaStore，不需要权限。
     */
    private fun withStoragePermission(result: MethodChannel.Result, action: () -> Unit) {
        if (!legacyStorage) {
            action()
            return
        }
        val granted = activity.checkSelfPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
            return
        }
        if (pendingPermission != null) {
            result.error("busy", "上一个权限申请还没结束", null)
            return
        }
        pendingPermission = { ok ->
            if (ok) action() else result.error("no_permission", "没有存储权限，写不进「下载」目录", null)
        }
        activity.requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE,
        )
    }

    // ----------------------------------------------------------
    // Android 9 及以下：直接写公共目录
    // ----------------------------------------------------------
    private fun legacyFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folderName)

    private fun save(name: String, content: String, result: MethodChannel.Result) {
        runCatching {
            if (legacyStorage) {
                val dir = legacyFolder()
                if (!dir.exists() && !dir.mkdirs()) error("创建目录失败：${dir.path}")
                val file = File(dir, name)
                file.writeText(content)
                result.success(file.absolutePath)
            } else {
                result.success(saveViaMediaStore(name, content))
            }
        }.onFailure { result.error("save_failed", it.message, null) }
    }

    private fun listLegacy(): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        // 下载根目录和 LyricCar 子目录都看一眼：别的车机拷过来的文件常常直接丢在下载目录
        for (dir in listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            legacyFolder(),
        )) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) } ?: continue
            for (file in files) {
                out += mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to file.length(),
                    "modifiedMs" to file.lastModified(),
                )
            }
        }
        return out.sortedByDescending { it["modifiedMs"] as Long }
    }

    // ----------------------------------------------------------
    // Android 10 及以上：MediaStore
    // ----------------------------------------------------------
    private fun saveViaMediaStore(name: String, content: String): String {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    private fun listMediaStore(): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        activity.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("%.json"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn).toString(),
                )
                out += mapOf(
                    "name" to cursor.getString(nameColumn),
                    "path" to uri.toString(),
                    "size" to cursor.getLong(sizeColumn),
                    // MediaStore 的修改时间是秒
                    "modifiedMs" to cursor.getLong(dateColumn) * 1000,
                )
            }
        }
        return out
    }

    companion object {
        const val CHANNEL = "carlyric/transfer"
        private const val REQUEST_STORAGE = 4711
    }
}
