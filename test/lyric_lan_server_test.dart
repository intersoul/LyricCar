// ============================================================
// 局域网传输的测试
//
// 这里真的起一个 HTTP 服务、真的发请求 —— 这套东西的坑全在「手机浏览器发过来的
// 那份 multipart 到底长什么样」上，只有真发一遍才算测到。
// ============================================================
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/data/lyric_lan_server.dart';
import 'package:lyriccar/features/carlyric/data/lyric_store.dart';
import 'package:lyriccar/features/carlyric/data/lyric_transfer.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';

/// 只存在于内存里的歌词库（LyricStore 是写文件的）。
class _MemoryStore implements LyricLibrary {
  final Map<String, StoredSong> songs = {};

  @override
  int get count => songs.length;

  @override
  String exportJson() => jsonEncode({
    'format': 'lyriccar',
    'songs': [for (final song in songs.values) song.toJson()],
  });

  @override
  ImportResult importJson(String text) {
    // 行为要跟真 LyricStore.importJson 一致：坏内容返回 error 而不是抛异常
    final Object? decoded;
    try {
      decoded = jsonDecode(text);
    } catch (error) {
      return ImportResult(error: '不是合法的 JSON（$error）');
    }
    if (decoded is! Map) return const ImportResult(error: '格式不对');
    final raw = decoded['songs'];
    if (raw is! List) return const ImportResult(error: '没有 songs');

    var added = 0;
    for (final item in raw) {
      final song = StoredSong.fromJson(item);
      if (song == null) continue;
      songs[song.key] = song;
      added++;
    }
    return ImportResult(added: added, updated: 0, skipped: 0);
  }

}

StoredSong _song(String title, int seconds, List<int> lines) => StoredSong(
  key: songKey(title: title, duration: Duration(seconds: seconds)),
  title: title,
  duration: Duration(seconds: seconds),
  complete: true,
  fromStart: true,
  lines: [for (final at in lines) LyricLine(timeMs: at * 1000, text: '第 $at 秒')],
);

void main() {
  group('multipart 解析', () {
    /// 照着浏览器 <input type="file"> 发出来的那份表单拼一个。
    List<int> multipartBody(String boundary, String fileName, String content) {
      final body = '--$boundary\r\n'
          'Content-Disposition: form-data; name="file"; filename="$fileName"\r\n'
          'Content-Type: application/json\r\n'
          '\r\n'
          '$content\r\n'
          '--$boundary--\r\n';
      return utf8.encode(body);
    }

    test('能把文件内容抠出来', () {
      const boundary = '----WebKitFormBoundaryABC123';
      final body = multipartBody(boundary, 'lyriccar-2026.json', '{"songs":[]}');
      final content = LyricLanServer.extractMultipartFile(
        body,
        'multipart/form-data; boundary=$boundary',
      );
      expect(content, '{"songs":[]}');
    });

    test('中文内容不会被边界判断切坏', () {
      const boundary = '---------1234';
      const content = '{"songs":[{"title":"夜曲 - 周杰伦","lines":["一句中文歌词"]}]}';
      final body = multipartBody(boundary, '歌词库.json', content);
      expect(
        LyricLanServer.extractMultipartFile(body, 'multipart/form-data; boundary=$boundary'),
        content,
      );
    });

    test('boundary 带引号也认得', () {
      final body = multipartBody('xyz', 'a.json', 'hello');
      expect(
        LyricLanServer.extractMultipartFile(
          body,
          'multipart/form-data; boundary="xyz"',
        ),
        'hello',
      );
    });

    test('没有文件字段就返回 null（不是文件上传）', () {
      const boundary = 'b1';
      final body = utf8.encode(
        '--$boundary\r\n'
        'Content-Disposition: form-data; name="note"\r\n'
        '\r\n'
        'hi\r\n'
        '--$boundary--\r\n',
      );
      expect(
        LyricLanServer.extractMultipartFile(body, 'multipart/form-data; boundary=$boundary'),
        isNull,
      );
    });
  });

  group('真的起一个服务', () {
    late _MemoryStore store;
    late LyricLanServer server;
    late HttpClient client;
    late String base;

    setUp(() async {
      store = _MemoryStore();
      store.songs['a'] = _song('夜曲', 180, [5, 30, 60]);
      server = LyricLanServer(store: store);
      await server.start();
      client = HttpClient();
      base = 'http://127.0.0.1:${server.port}';
    });

    tearDown(() async {
      client.close(force: true);
      await server.stop();
    });

    test('首页能打开，并且写着有几首歌', () async {
      final response = await _get(client, '$base/');
      expect(response.status, 200);
      expect(response.body, contains('LyricCar 歌词'));
      expect(response.body, contains('<b>1</b>'));
      expect(response.body, contains('/lyriccar.json'), reason: '要有下载入口');
      expect(response.body, contains('action="/import"'), reason: '要有上传入口');
    });

    test('下载歌词库：是带附件的 JSON', () async {
      final response = await _get(client, '$base/lyriccar.json');
      expect(response.status, 200);
      expect(response.disposition, contains('attachment'));
      expect(response.disposition, contains('.json'));

      final decoded = jsonDecode(response.body) as Map;
      expect((decoded['songs'] as List).length, 1);
      expect(response.body, contains('夜曲'));
    });

    test('上传一份歌词库：合并进库里，并回报结果', () async {
      final incoming = _MemoryStore()..songs['b'] = _song('发如雪', 240, [10, 50]);
      final boundary = '----WebKitFormBoundaryZZZ';
      final body = utf8.encode(
        '--$boundary\r\n'
        'Content-Disposition: form-data; name="file"; filename="lyriccar.json"\r\n'
        'Content-Type: application/json\r\n'
        '\r\n'
        '${incoming.exportJson()}\r\n'
        '--$boundary--\r\n',
      );

      final response = await _post(client, '$base/import', body,
          'multipart/form-data; boundary=$boundary');
      expect(response.status, 200);
      expect(response.body, contains('新增 1 首'));
      expect(store.count, 2, reason: '原来那首还在，新的并进来');
      expect(store.songs.containsKey(songKey(title: '发如雪', duration: const Duration(seconds: 240))), isTrue);
    });

    test('直接 POST 一段 JSON 也收（方便用 curl 传）', () async {
      final incoming = _MemoryStore()..songs['c'] = _song('晴天', 260, [3]);
      final response = await _post(
        client,
        '$base/import',
        utf8.encode(incoming.exportJson()),
        'application/json',
      );
      expect(response.status, 200);
      expect(store.count, 2);
    });

    test('传上去的不是歌词文件 → 给一句人话，不是崩掉', () async {
      final response = await _post(
        client,
        '$base/import',
        utf8.encode('这不是 JSON'),
        'application/json',
      );
      expect(response.status, 200);
      expect(response.body, contains('导入失败'));
      expect(store.count, 1, reason: '原来的库不能被动过');
    });

    test('关掉之后就真的不监听端口了', () async {
      final port = server.port!;
      await server.stop();
      await expectLater(
        Socket.connect('127.0.0.1', port, timeout: const Duration(seconds: 2)),
        throwsA(isA<SocketException>()),
      );
      // tearDown 里再 stop 一次也不能炸
      await server.stop();
    });
  });

  group('该显示哪个地址', () {
    // 真机（BYD 车机）上的网卡：wlan0 是手机真正连得上的那张，ccmni0/1/2 是
    // 4G 模块的内部地址 —— 它们也是 10.x，列出来只会让人复制错。
    test('排除蜂窝网卡，Wi-Fi 排最前', () {
      final picked = LyricLanServer.chooseAddresses([
        ('lo', InternetAddress('127.0.0.1')),
        ('ccmni0', InternetAddress('10.210.164.175')),
        ('ccmni1', InternetAddress('12.167.62.6')),
        ('ccmni2', InternetAddress('10.41.75.199')),
        ('wlan0', InternetAddress('10.35.199.116')),
      ]);
      expect(picked.map((a) => a.address), ['10.35.199.116']);
    });

    test('没有 Wi-Fi 时，其他私有网卡也算数（总比没有强）', () {
      final picked = LyricLanServer.chooseAddresses([
        ('rndis0', InternetAddress('192.168.42.129')),
      ]);
      expect(picked.map((a) => a.address), ['192.168.42.129']);
    });

    test('公网地址不显示（连上去也进不来）', () {
      final picked = LyricLanServer.chooseAddresses([
        ('wlan0', InternetAddress('203.0.113.7')),
      ]);
      expect(picked, isEmpty);
    });
  });

  group('导出文件名', () {
    test('带时间戳、以 .json 结尾（下载目录和浏览器用的是同一个名字）', () {
      expect(
        lyricExportFileName(DateTime(2026, 10, 3, 9, 5)),
        'lyriccar-20261003-0905.json',
      );
    });
  });
}

Future<({int status, String body, String disposition})> _get(
  HttpClient client,
  String url,
) async {
  final request = await client.getUrl(Uri.parse(url));
  final response = await request.close();
  final body = await response.transform(utf8.decoder).join();
  return (
    status: response.statusCode,
    body: body,
    disposition: response.headers.value('content-disposition') ?? '',
  );
}

Future<({int status, String body, String disposition})> _post(
  HttpClient client,
  String url,
  List<int> body,
  String contentType,
) async {
  final request = await client.postUrl(Uri.parse(url));
  request.headers.set('Content-Type', contentType);
  request.headers.contentLength = body.length;
  request.add(body);
  final response = await request.close();
  final text = await response.transform(utf8.decoder).join();
  return (
    status: response.statusCode,
    body: text,
    disposition: response.headers.value('content-disposition') ?? '',
  );
}
