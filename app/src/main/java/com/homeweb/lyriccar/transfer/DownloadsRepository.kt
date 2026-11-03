package com.homeweb.lyriccar.transfer

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.annotation.TargetApi
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

data class DownloadedFile(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedMs: Long,
)

/**
 * 把歌词库 / 诊断日志写进系统「下载」目录，以及从那里读回来。
 *
 * Android 9 及以下直接写公共目录（运行时申请 WRITE_EXTERNAL_STORAGE）；
 * Android 10 起走 MediaStore，不需要权限。
 */
class DownloadsRepository(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyriccar-downloads").apply { isDaemon = true }
    }

    private val legacyStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val folderName = "LyricCar"

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_STORAGE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingPermission?.invoke(granted)
        pendingPermission = null
    }

    fun save(name: String, content: String, callback: (path: String?, error: String?) -> Unit) {
        withStoragePermission(
            callback = { ok ->
                if (!ok) {
                    callback(null, "没有存储权限，写不进「下载」目录")
                    return@withStoragePermission
                }
                io.execute {
                    try {
                        val path = if (legacyStorage) saveLegacy(name, content) else saveViaMediaStore(name, content)
                        mainHandler.post { callback(path, null) }
                    } catch (error: Exception) {
                        mainHandler.post { callback(null, "写入「下载」目录失败：${error.message}") }
                    }
                }
            },
            onBusy = { callback(null, "权限对话框还开着，稍等一下再试") },
        )
    }

    fun list(callback: (files: List<DownloadedFile>?, error: String?) -> Unit) {
        withStoragePermission(
            callback = { ok ->
                if (!ok) {
                    callback(null, "没有存储权限，读不了「下载」目录")
                    return@withStoragePermission
                }
                io.execute {
                    try {
                        val files = if (legacyStorage) listLegacy() else listMediaStore()
                        mainHandler.post { callback(files, null) }
                    } catch (error: Exception) {
                        mainHandler.post { callback(null, "列举「下载」目录失败：${error.message}") }
                    }
                }
            },
            onBusy = { callback(null, "权限对话框还开着，稍等一下再试") },
        )
    }

    fun read(file: DownloadedFile, callback: (text: String?, error: String?) -> Unit) {
        io.execute {
            try {
                val text = if (legacyStorage) {
                    File(file.path).readText()
                } else {
                    activity.contentResolver.openInputStream(Uri.parse(file.path))!!
                        .bufferedReader().use { it.readText() }
                }
                mainHandler.post { callback(text, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, "读文件失败：${error.message}") }
            }
        }
    }

    fun delete(file: DownloadedFile, callback: (ok: Boolean, error: String?) -> Unit) {
        withStoragePermission(
            callback = { granted ->
                if (!granted) {
                    callback(false, "没有存储权限，改不了「下载」目录")
                    return@withStoragePermission
                }
                io.execute {
                    try {
                        val ok = if (legacyStorage) {
                            File(file.path).delete()
                        } else {
                            activity.contentResolver.delete(Uri.parse(file.path), null, null) > 0
                        }
                        mainHandler.post { callback(ok, null) }
                    } catch (error: Exception) {
                        mainHandler.post { callback(false, "删除失败：${error.message}") }
                    }
                }
            },
            onBusy = { callback(false, "权限对话框还开着，稍等一下再试") },
        )
    }

    private fun withStoragePermission(
        callback: (Boolean) -> Unit,
        onBusy: () -> Unit,
    ) {
        if (!legacyStorage) {
            callback(true)
            return
        }
        val granted = activity.checkSelfPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback(true)
            return
        }
        if (pendingPermission != null) {
            onBusy()
            return
        }
        pendingPermission = callback
        activity.requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE,
        )
    }

    private fun legacyFolder(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        folderName,
    )

    private fun saveLegacy(name: String, content: String): String {
        val dir = legacyFolder()
        if (!dir.exists() && !dir.mkdirs()) error("创建目录失败：${dir.path}")
        val file = File(dir, name)
        file.writeText(content)
        return file.absolutePath
    }

    private fun listLegacy(): List<DownloadedFile> {
        val out = mutableListOf<DownloadedFile>()
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            legacyFolder(),
        )
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) } ?: continue
            for (file in files) {
                out.add(
                    DownloadedFile(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        modifiedMs = file.lastModified(),
                    ),
                )
            }
        }
        return out.sortedByDescending { it.modifiedMs }
    }

    @TargetApi(Build.VERSION_CODES.Q)
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
        try {
            resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return uri.toString()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun listMediaStore(): List<DownloadedFile> {
        val out = mutableListOf<DownloadedFile>()
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
                out.add(
                    DownloadedFile(
                        name = cursor.getString(nameColumn),
                        path = uri.toString(),
                        size = cursor.getLong(sizeColumn),
                        modifiedMs = cursor.getLong(dateColumn) * 1000,
                    ),
                )
            }
        }
        return out
    }

    companion object {
        private const val REQUEST_STORAGE = 4711
    }
}
