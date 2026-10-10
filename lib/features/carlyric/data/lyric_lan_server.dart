// ============================================================
// 局域网传输
//
// 车机上起一个极小的 HTTP 服务，手机连上来就能：
//   * 把车上的歌词库下载到手机（换车机 / 备份）；
//   * 把手机里的歌词库上传到车上（导入）。
//
// 只在「局域网传输」面板开着的时候跑（面板一关就 stop），传完即走，
// 不用一直监听端口。地址里的 IP 从网卡上取，只认私有网段。
// ============================================================
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'lyric_store.dart';
import 'lyric_transfer.dart';

/// 局域网传输只用到歌词库的三件事，抽成接口：测试里能用内存版替掉，
/// 也免得为了传个文件把整个 [LyricStore]（写文件那套）拖进依赖里。
abstract interface class LyricLibrary {
  int get count;
  String exportJson();
  ImportResult importJson(String text);
}

class LyricLanServer {
  LyricLanServer({required this.store});

  final LyricLibrary store;

  HttpServer? _server;
  int? _requestCount;

  bool get running => _server != null;

  /// 实际监听端口（界面上要拼进地址里）。
  int? get port => _server?.port;

  /// 已经处理过的请求数（界面上用来显示「手机连上来了」）。
  int get requestCount => _requestCount ?? 0;

  /// 局域网里能访问到的地址，比如 `http://192.168.1.23:8080`。
  List<String> get urls {
    final server = _server;
    if (server == null) return const [];
    return [
      for (final address in lanAddresses) 'http://${address.address}:${server.port}',
    ];
  }

  /// 车机的局域网 IP。只留「手机连得上的」那几个，见 [chooseAddresses]。
  static List<InternetAddress> get lanAddresses => _addresses;
  static List<InternetAddress> _addresses = const [];

  /// 读一次网卡列表（异步 API，start() 里读，之后同步用缓存）。
  static Future<List<InternetAddress>> refreshAddresses() async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLoopback: false,
      );
      _addresses = chooseAddresses([
        for (final interface in interfaces)
          for (final address in interface.addresses) (interface.name, address),
      ]);
    } catch (_) {
      // 网卡读不到就当没有，界面会提示「没找到局域网地址」
      _addresses = const [];
    }
    return _addresses;
  }

  /// 车机上的网卡名：Wi-Fi / 热点 / 有线。手机连的就是这几张。
  static const List<String> _wirelessPrefixes = ['wlan', 'ap', 'eth', 'wifi'];

  /// 蜂窝模块的内部网卡。它们也是 10.x 地址，**看着像内网但手机连不上**：
  /// 实测这台 BYD 车机同时挂着 wlan0(10.35.199.116/24) 和 ccmni0/1/2
  /// (10.210.x、12.167.x、10.41.x) —— 全列出来的话，用户多半会复制错那个。
  static const List<String> _cellularPrefixes = [
    'ccmni', 'rmnet', 'pdp', 'wwan', 'ppp', 'clat', 'tun', 'rmnet_data',
  ];

  /// 从网卡列表里挑出该显示的地址：排除蜂窝、排除回环和非私有网段，
  /// Wi-Fi 排前面。
  static List<InternetAddress> chooseAddresses(
    Iterable<(String, InternetAddress)> found,
  ) {
    final wireless = <InternetAddress>[];
    final others = <InternetAddress>[];
    for (final (name, address) in found) {
      final lower = name.toLowerCase();
      if (_cellularPrefixes.any(lower.startsWith)) continue;
      if (address.isLoopback) continue;
      if (!_isPrivate(address.address)) continue;
      if (_wirelessPrefixes.any(lower.startsWith)) {
        wireless.add(address);
      } else {
        others.add(address);
      }
    }
    return [...wireless, ...others];
  }

  static bool _isPrivate(String ip) {
    final parts = ip.split('.').map(int.tryParse).toList();
    if (parts.length != 4 || parts.any((p) => p == null)) return false;
    final a = parts[0]!, b = parts[1]!;
    if (a == 10) return true;
    if (a == 172 && b >= 16 && b <= 31) return true;
    if (a == 192 && b == 168) return true;
    return false;
  }

  /// 起服务。[preferredPort] 被别的程序占了就换一个系统分配的端口。
  Future<void> start({int preferredPort = 8080}) async {
    if (_server != null) return;
    await refreshAddresses();
    try {
      _server = await HttpServer.bind(InternetAddress.anyIPv4, preferredPort);
    } on SocketException {
      _server = await HttpServer.bind(InternetAddress.anyIPv4, 0);
    }
    _requestCount = 0;
    _server!.listen(_handle, onError: (_) {});
  }

  Future<void> stop() async {
    final server = _server;
    _server = null;
    await server?.close(force: true);
  }

  Future<void> _handle(HttpRequest request) async {
    _requestCount = (_requestCount ?? 0) + 1;
    try {
      final path = request.uri.path;
      if (request.method == 'GET' && (path == '/' || path == '/index.html')) {
        await _send(_page(), request);
      } else if (request.method == 'GET' && path == '/lyriccar.json') {
        await _sendJson(store.exportJson(), request);
      } else if (request.method == 'POST' && path == '/import') {
        await _import(request);
      } else {
        request.response.statusCode = HttpStatus.notFound;
        await _send(_page(message: '没有这个地址：$path'), request);
      }
    } catch (error) {
      try {
        request.response.statusCode = HttpStatus.internalServerError;
        await _send(_page(message: '出错了：$error'), request);
      } catch (_) {
        // 连接已经断了，不用再管
      }
    }
  }

  Future<void> _sendJson(String body, HttpRequest request) async {
    final bytes = utf8.encode(body);
    request.response.headers.contentType = ContentType.json;
    request.response.headers.set(
      'Content-Disposition',
      'attachment; filename="${lyricExportFileName()}"',
    );
    request.response.headers.contentLength = bytes.length;
    request.response.add(bytes);
    await request.response.close();
  }

  Future<void> _send(String html, HttpRequest request) async {
    final bytes = utf8.encode(html);
    request.response.headers.contentType = ContentType.html;
    request.response.headers.contentLength = bytes.length;
    request.response.add(bytes);
    await request.response.close();
  }

  Future<void> _import(HttpRequest request) async {
    final body = await _readBody(request);
    final contentType = request.headers.contentType?.toString() ?? '';
    final text = contentType.contains('multipart/form-data')
        ? (extractMultipartFile(body, contentType) ?? '')
        : utf8.decode(body, allowMalformed: true);

    if (text.trim().isEmpty) {
      await _send(_page(message: '没读到文件内容，再选一次试试'), request);
      return;
    }

    final result = store.importJson(text);
    if (result.error != null) {
      await _send(_page(message: '导入失败：${result.error}'), request);
      return;
    }
    await _send(
      _page(
        message: '导入完成：新增 ${result.added} 首，更新 ${result.updated} 首，'
            '跳过 ${result.skipped} 首。现在这台车上有 ${store.count} 首歌词。',
      ),
      request,
    );
  }

  static Future<List<int>> _readBody(HttpRequest request) async {
    final builder = BytesBuilder(copy: false);
    await for (final chunk in request) {
      builder.add(chunk);
    }
    return builder.takeBytes();
  }

  // ----------------------------------------------------------
  // multipart 表单里把文件内容抠出来
  // ----------------------------------------------------------
  /// 从 `multipart/form-data` 请求体里取出文件部分的内容（原样 UTF-8 解码）。
  ///
  /// 没有引入解析库：这里只需要处理浏览器 `<input type="file">` 发出来的那一份
  /// 表单，格式固定 —— 按 boundary 切段，取带 filename 的那段正文。
  static String? extractMultipartFile(List<int> body, String contentType) {
    final boundary = _boundaryOf(contentType);
    if (boundary == null) return null;

    final delimiter = utf8.encode('--$boundary');
    final starts = _findAll(body, delimiter);
    if (starts.length < 2) return null;

    for (var i = 0; i < starts.length - 1; i++) {
      final partStart = starts[i] + delimiter.length;
      final partEnd = starts[i + 1];
      if (partStart >= partEnd) continue;

      final split = _indexOf(body, utf8.encode('\r\n\r\n'), partStart, partEnd);
      if (split < 0) continue;

      final headers = utf8.decode(
        body.sublist(partStart, split),
        allowMalformed: true,
      );
      if (!headers.contains('filename=')) continue;

      // 正文的结尾是「\r\n」+ boundary，把那个换行去掉
      var contentEnd = partEnd;
      if (contentEnd >= 2 &&
          body[contentEnd - 2] == 13 &&
          body[contentEnd - 1] == 10) {
        contentEnd -= 2;
      }
      return utf8.decode(body.sublist(split + 4, contentEnd), allowMalformed: true);
    }
    return null;
  }

  static String? _boundaryOf(String contentType) {
    final match = RegExp(
      r'boundary=(?:"([^"]+)"|([^;]+))',
      caseSensitive: false,
    ).firstMatch(contentType);
    final value = (match?.group(1) ?? match?.group(2))?.trim();
    return (value == null || value.isEmpty) ? null : value;
  }

  static List<int> _findAll(List<int> haystack, List<int> needle) {
    final found = <int>[];
    var from = 0;
    while (true) {
      final at = _indexOf(haystack, needle, from, haystack.length);
      if (at < 0) break;
      found.add(at);
      from = at + 1;
    }
    return found;
  }

  static int _indexOf(List<int> haystack, List<int> needle, int from, int to) {
    if (needle.isEmpty) return -1;
    for (var i = from; i <= to - needle.length; i++) {
      var ok = true;
      for (var j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          ok = false;
          break;
        }
      }
      if (ok) return i;
    }
    return -1;
  }

  // ----------------------------------------------------------
  // 页面
  // ----------------------------------------------------------
  String _page({String? message}) {
    final songs = store.count;
    final banner = message == null
        ? ''
        : '<p class="msg">${_escape(message)}</p>';
    final address = urls.isEmpty ? '' : urls.first;

    return '''
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
  <p class="sub">这台车机上有 <b>$songs</b> 首歌的歌词${address.isEmpty ? '' : '（$address）'}。</p>
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

  <p class="tip">传输期间别退出车上的 App（页面就是它在提供）。传完可以关掉这个页面。</p>
</body>
</html>
''';
  }

  static String _escape(String text) => text
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;');
}
