package com.homeweb.lyriccar.lan

import com.homeweb.lyriccar.data.LyricLibrary
import com.homeweb.lyriccar.transfer.lyricExportFileName
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网传输：车上起一个极小的 HTTP 服务，手机连上来就能下载 / 上传歌词库。
 * 面板关闭即停止监听，不长期暴露端口。
 */
class LyricLanServer(
    private val store: LyricLibrary,
    private val logProvider: () -> String,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lyriccar-lan-request").apply { isDaemon = true }
    }
    private var addresses: List<InetAddress> = emptyList()
    private val handled = AtomicInteger(0)
    @Volatile
    private var running = false

    val port: Int get() = serverSocket?.localPort ?: 0
    val requestCount: Int get() = handled.get()

    val urls: List<String>
        get() {
            val currentPort = port
            if (currentPort <= 0) return emptyList()
            return addresses.map { "http://${it.hostAddress}:$currentPort" }
        }

    /** 起服务；首选端口被占就换一个系统分配的端口。 */
    fun start(preferredPort: Int = 8080) {
        if (serverSocket != null) return
        addresses = refreshAddresses()
        serverSocket = try {
            ServerSocket(preferredPort)
        } catch (_: Exception) {
            ServerSocket(0)
        }
        running = true
        acceptThread = Thread({
            while (running) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (_: Exception) {
                    break
                }
                executor.execute { handle(socket) }
            }
        }, "lyriccar-lan-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // 已经关了
        }
        serverSocket = null
        executor.shutdownNow()
    }

    // ----------------------------------------------------------
    // 网卡
    // ----------------------------------------------------------
    private fun refreshAddresses(): List<InetAddress> {
        return try {
            val found = mutableListOf<Pair<String, InetAddress>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nic in interfaces) {
                if (!nic.isUp) continue
                for (address in nic.inetAddresses) {
                    if (address is Inet4Address) found.add(nic.name to address)
                }
            }
            chooseAddresses(found)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun handle(socket: Socket) {
        handled.incrementAndGet()
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val headerBytes = readHeader(input)
            if (headerBytes.isEmpty()) return
            val headerText = String(headerBytes, StandardCharsets.UTF_8)
            val headerLines = headerText.split("\r\n")
            val requestLine = headerLines.firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase(Locale.US)
            val target = requestLine[1]
            val path = target.substringBefore('?')

            val headers = mutableMapOf<String, String>()
            for (line in headerLines.drop(1)) {
                val index = line.indexOf(':')
                if (index <= 0) continue
                headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    flush()
                }
            }
            val body = readBody(input, contentLength)

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    send(socket, 200, "text/html; charset=utf-8", page())
                }
                method == "GET" && path == "/lyriccar.json" -> {
                    send(
                        socket,
                        200,
                        "application/json; charset=utf-8",
                        store.exportJson(),
                        lyricExportFileName(),
                    )
                }
                method == "GET" && path == "/diagnostics.txt" -> {
                    send(
                        socket,
                        200,
                        "text/plain; charset=utf-8",
                        logProvider(),
                        "lyriccar-log.txt",
                    )
                }
                method == "POST" && path == "/import" -> {
                    importFile(socket, headers["content-type"].orEmpty(), body)
                }
                else -> {
                    send(socket, 404, "text/html; charset=utf-8", page("没有这个地址：$path"))
                }
            }
        } catch (error: Exception) {
            try {
                send(socket, 500, "text/html; charset=utf-8", page("出错了：${error.message}"))
            } catch (_: Exception) {
                // 连接已经断了
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    private fun importFile(socket: Socket, contentType: String, body: ByteArray) {
        val text = if (contentType.contains("multipart/form-data", ignoreCase = true)) {
            extractMultipartFile(body, contentType).orEmpty()
        } else {
            String(body, StandardCharsets.UTF_8)
        }
        if (text.trim().isEmpty()) {
            send(socket, 200, "text/html; charset=utf-8", page("没读到文件内容，再选一次试试"))
            return
        }
        val result = store.importJson(text)
        if (result.error != null) {
            send(socket, 200, "text/html; charset=utf-8", page("导入失败：${result.error}"))
            return
        }
        send(
            socket,
            200,
            "text/html; charset=utf-8",
            page(
                "导入完成：新增 ${result.added} 首，更新 ${result.updated} 首，" +
                    "跳过 ${result.skipped} 首。现在这台车上有 ${store.count} 首歌词。",
            ),
        )
    }

    // ----------------------------------------------------------
    // HTTP 读写
    // ----------------------------------------------------------
    private fun readHeader(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (out.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = if (value.toByte() == terminator[matched]) matched + 1 else {
                if (value.toByte() == terminator[0]) 1 else 0
            }
            if (matched == terminator.size) break
        }
        return out.toByteArray()
    }

    private fun readBody(input: InputStream, contentLength: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (contentLength > 0) {
            val buffer = ByteArray(8192)
            var remaining = contentLength
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
                if (out.size() > MAX_BODY_BYTES) error("上传文件太大")
            }
        }
        return out.toByteArray()
    }

    private fun send(
        socket: Socket,
        status: Int,
        contentType: String,
        body: String,
        attachmentName: String? = null,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = StringBuilder()
        header.append("HTTP/1.1 $status ").append(statusText(status)).append("\r\n")
        header.append("Content-Type: ").append(contentType).append("\r\n")
        header.append("Content-Length: ").append(bytes.size).append("\r\n")
        header.append("Connection: close\r\n")
        header.append("Cache-Control: no-store\r\n")
        if (attachmentName != null) {
            header.append("Content-Disposition: attachment; filename=\"")
                .append(attachmentName).append("\"\r\n")
        }
        header.append("\r\n")
        val output: OutputStream = socket.getOutputStream()
        output.write(header.toString().toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    // ----------------------------------------------------------
    // multipart
    // ----------------------------------------------------------
    private fun extractMultipartFile(body: ByteArray, contentType: String): String? {
        val boundary = boundaryOf(contentType) ?: return null
        val delimiter = "--$boundary".toByteArray(StandardCharsets.UTF_8)
        val starts = findAll(body, delimiter)
        if (starts.size < 2) return null

        for (i in 0 until starts.size - 1) {
            val partStart = starts[i] + delimiter.size
            val partEnd = starts[i + 1]
            if (partStart >= partEnd) continue
            val split = indexOf(body, CRLF_CRLF, partStart, partEnd)
            if (split < 0) continue
            val headers = String(body, partStart, split - partStart, StandardCharsets.UTF_8)
            if (!headers.contains("filename=")) continue
            var contentEnd = partEnd
            if (contentEnd >= 2 && body[contentEnd - 2] == 13.toByte() &&
                body[contentEnd - 1] == 10.toByte()
            ) {
                contentEnd -= 2
            }
            return String(body, split + 4, contentEnd - (split + 4), StandardCharsets.UTF_8)
        }
        return null
    }

    private fun boundaryOf(contentType: String): String? {
        val match = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
            .find(contentType) ?: return null
        val value = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
        return value.ifEmpty { null }
    }

    private fun findAll(haystack: ByteArray, needle: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = indexOf(haystack, needle, from, haystack.size)
            if (at < 0) break
            found.add(at)
            from = at + 1
        }
        return found
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return -1
        for (i in from..(to - needle.size)) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return -1
    }

    // ----------------------------------------------------------
    // 页面
    // ----------------------------------------------------------
    private fun page(message: String? = null): String {
        val songs = store.count
        val banner = message?.let { "<p class=\"msg\">${escape(it)}</p>" }.orEmpty()
        val address = urls.firstOrNull().orEmpty()
        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LyricCar 歌词传输</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 24px 18px 48px; background: #0a0e13; color: #eceff1;
         font: 16px/1.7 -apple-system, "PingFang SC", "Noto Sans CJK SC", sans-serif; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  h2 { font-size: 17px; margin: 28px 0 8px; color: #57c7ff; }
  .sub { color: #8b959f; margin: 0 0 8px; }
  a.btn, button { display: inline-block; margin-top: 6px; padding: 13px 20px;
    border: 0; border-radius: 12px; background: #0f6fa8; color: #fff;
    font-size: 16px; text-decoration: none; }
  button:active, a.btn:active { background: #57c7ff; color: #06121b; }
  input[type=file] { width: 100%; margin-top: 10px; padding: 12px;
    background: #182230; border: 1px solid #2b3a4b; border-radius: 12px; color: inherit; }
  .msg { background: #123043; border-left: 3px solid #57c7ff; padding: 12px 14px;
    border-radius: 8px; margin: 18px 0 0; }
  .tip { color: #8b959f; font-size: 14px; margin-top: 28px; }
  code { background: #182230; padding: 2px 6px; border-radius: 6px; }
</style>
</head>
<body>
  <h1>LyricCar 歌词</h1>
  <p class="sub">这台车机上有 <b>$songs</b> 首歌的歌词${if (address.isEmpty()) "" else "（$address）"}。</p>
  $banner
  <h2>① 把车上的歌词存到手机</h2>
  <p class="sub">下载得到的 <code>.json</code> 发给别的车机就是一份完整歌词库。</p>
  <a class="btn" href="/lyriccar.json" download>下载歌词库（${lyricExportFileName()}）</a>

  <h2>② 把手机的歌词传到这台车</h2>
  <p class="sub">选一份之前导出的 <code>.json</code> 上传即可。已采齐的稿子不会被更差的覆盖。</p>
  <form method="post" action="/import" enctype="multipart/form-data">
    <input type="file" name="file" accept=".json,application/json" required>
    <button type="submit">上传并导入</button>
  </form>

  <h2>③ 把诊断日志发给开发者</h2>
  <p class="sub">换了车机发现不显示歌词时，下载这个 <code>.txt</code> 发过来就行 ——
  里面有系统版本、通知使用权、以及这台车机上每个播放器暴露的元数据。</p>
  <a class="btn" href="/diagnostics.txt" download>下载诊断日志（lyriccar-log.txt）</a>

  <p class="tip">传输期间别退出车上的 App（页面就是它在提供）。传完可以关掉这个页面。</p>
</body>
</html>
""".trimIndent()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        private val CRLF_CRLF = byteArrayOf(13, 10, 13, 10)

        private val WIRELESS_PREFIXES = listOf("wlan", "ap", "eth", "wifi")
        private val CELLULAR_PREFIXES = listOf(
            "ccmni", "rmnet", "pdp", "wwan", "ppp", "clat", "tun", "rmnet_data",
        )

        /** 排除蜂窝、回环、非私有网段；Wi-Fi / 热点 / 有线排前面。 */
        fun chooseAddresses(found: Iterable<Pair<String, InetAddress>>): List<InetAddress> {
            val wireless = mutableListOf<InetAddress>()
            val others = mutableListOf<InetAddress>()
            for ((name, address) in found) {
                val lower = name.lowercase(Locale.US)
                if (CELLULAR_PREFIXES.any { lower.startsWith(it) }) continue
                if (address.isLoopbackAddress) continue
                if (!isPrivate(address.hostAddress.orEmpty())) continue
                if (WIRELESS_PREFIXES.any { lower.startsWith(it) }) {
                    wireless.add(address)
                } else {
                    others.add(address)
                }
            }
            return wireless + others
        }

        private fun isPrivate(ip: String): Boolean {
            val parts = ip.split('.').map { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it == null }) return false
            val a = parts[0] ?: return false
            val b = parts[1] ?: return false
            if (a == 10) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            return false
        }
    }
}
