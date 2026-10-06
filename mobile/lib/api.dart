import 'dart:convert';

import 'package:http/http.dart' as http;

import 'models.dart';

/// Talks to the self-hosted proxy. Nothing here knows about hanime.tv — the
/// server owns upstream auth, signing, and playlist rewriting.
class Api {
  Api({String? baseUrl, String? token})
      : baseUrl = (baseUrl ?? defaultBaseUrl).replaceAll(RegExp(r'/+$'), ''),
        token = token ?? const String.fromEnvironment('API_TOKEN');

  /// Point this at the machine running `server/`.
  ///
  ///   Android emulator  ->  http://10.0.2.2:8787
  ///   iOS simulator     ->  http://127.0.0.1:8787
  ///   physical device   ->  http://<your-lan-ip>:8787
  ///
  /// Override at build time: flutter run --dart-define=API_BASE=http://192.168.1.20:8787
  static const defaultBaseUrl = String.fromEnvironment('API_BASE', defaultValue: 'http://10.0.2.2:8787');

  final String baseUrl;
  final String token;

  static const _timeout = Duration(seconds: 30);

  Map<String, String> get _headers => {
        'accept': 'application/json',
        if (token.isNotEmpty) 'authorization': 'Bearer $token',
      };

  Uri _uri(String path, [Map<String, String>? query]) =>
      Uri.parse('$baseUrl$path').replace(queryParameters: query);

  Future<dynamic> _get(String path, [Map<String, String>? query]) async {
    final res = await http.get(_uri(path, query), headers: _headers).timeout(_timeout);
    if (res.statusCode != 200) {
      throw ApiException('$path failed (${res.statusCode})', res.statusCode);
    }
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  Future<VideoPage> videos({
    int page = 0,
    int perPage = 30,
    String query = '',
    List<String> tags = const [],
    String orderBy = 'released_at_unix',
    String ordering = 'desc',
  }) async {
    final json = await _get('/api/videos', {
      'page': '$page',
      'per_page': '$perPage',
      'order_by': orderBy,
      'ordering': ordering,
      if (query.trim().isNotEmpty) 'q': query.trim(),
      if (tags.isNotEmpty) 'tags': tags.join(','),
    });
    return VideoPage.fromJson(json as Map<String, dynamic>);
  }

  Future<Video> video(String slug) async {
    final json = await _get('/api/videos/${Uri.encodeComponent(slug)}');
    return Video.fromJson(json as Map<String, dynamic>);
  }

  Future<List<StreamSource>> sources(String slug) async {
    final json = await _get('/api/videos/${Uri.encodeComponent(slug)}/sources')
        as Map<String, dynamic>;
    return (json['sources'] as List? ?? const [])
        .map((e) => StreamSource.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<List<TagCount>> tags() async {
    final json = await _get('/api/tags') as Map<String, dynamic>;
    return (json['data'] as List? ?? const [])
        .map((e) => TagCount.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // ---- account session ---------------------------------------------------

  /// *Your* playlists live inside the account payload upstream, so the client
  /// needs a cookie the user already holds. This never sends a password.
  /// Public playlists are separate and need none of this — see
  /// [publicPlaylists].
  Future<SessionInfo> session() async {
    final json = await _get('/api/session') as Map<String, dynamic>;
    return SessionInfo.fromJson(json);
  }

  /// Submits a raw `Cookie:` header value. Throws [ApiException] with the
  /// server's reason when the cookie does not authenticate.
  Future<SessionInfo> connectSession(String cookie) async {
    final res = await http
        .post(_uri('/api/session'),
            headers: {..._headers, 'content-type': 'application/json'},
            body: jsonEncode({'cookie': cookie}))
        .timeout(_timeout);
    final body = res.bodyBytes.isEmpty ? null : jsonDecode(utf8.decode(res.bodyBytes));
    if (res.statusCode != 200) {
      final hint = (body as Map?)?['hint'] ?? (body as Map?)?['reason'] ?? 'Rejected.';
      throw ApiException(hint.toString(), res.statusCode);
    }
    return session();
  }

  Future<void> disconnectSession() async {
    await http.delete(_uri('/api/session'), headers: _headers).timeout(_timeout);
  }

  // ---- playlists ---------------------------------------------------------

  /// Account-scoped: searches *your* playlist titles and their contents. A
  /// title match returns the whole playlist; a content match returns only the
  /// matching items. Requires a connected session.
  Future<PlaylistPage> playlists({String query = ''}) async {
    final json = await _get('/api/playlists', {
      if (query.trim().isNotEmpty) 'q': query.trim(),
    }) as Map<String, dynamic>;
    return PlaylistPage.fromJson(json);
  }

  Future<Playlist> playlist(String slug) async {
    final json = await _get('/api/playlists/${Uri.encodeComponent(slug)}') as Map<String, dynamic>;
    return Playlist.fromJson(json);
  }

  // ---- public playlists --------------------------------------------------
  //
  // Served from the proxy's crawl, not from an account. These work without any
  // session, which is the point: the site has no playlist index of its own.

  Future<PublicPlaylistPage> publicPlaylists({
    String query = '',
    String owner = '',
    int limit = 60,
  }) async {
    final json = await _get('/api/public/playlists', {
      'limit': '$limit',
      if (query.trim().isNotEmpty) 'q': query.trim(),
      if (owner.isNotEmpty) 'owner': owner,
    }) as Map<String, dynamic>;
    return PublicPlaylistPage.fromJson(json);
  }

  Future<PublicPlaylistDetail> publicPlaylist(String slug) async {
    final json = await _get('/api/public/playlists/${Uri.encodeComponent(slug)}')
        as Map<String, dynamic>;
    return PublicPlaylistDetail.fromJson(json);
  }

  Future<PublicPlaylistStats> publicCrawlStats() async {
    final json = await _get('/api/public/crawl') as Map<String, dynamic>;
    return PublicPlaylistStats.fromJson(
        (json['stats'] as Map?)?.cast<String, dynamic>() ?? const {});
  }

  /// Kicks off a discovery pass. Returns immediately — the pass writes the
  /// index on the server and the next [publicPlaylists] call sees the result.
  Future<bool> startPublicCrawl({int count = 60, int maxPlaylists = 400}) async {
    final res = await http
        .post(_uri('/api/public/crawl'),
            headers: {..._headers, 'content-type': 'application/json'},
            body: jsonEncode({'count': count, 'max_playlists': maxPlaylists}))
        .timeout(_timeout);
    return res.statusCode == 202 || res.statusCode == 200;
  }

  /// Which crawled public playlists carry this title.
  Future<List<PublicPlaylist>> playlistsForVideo(String slug) async {
    final json = await _get('/api/public/videos/${Uri.encodeComponent(slug)}/playlists')
        as Map<String, dynamic>;
    return (json['playlists'] as List? ?? const [])
        .map((e) => PublicPlaylist.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<bool> health() async {
    try {
      final json = await _get('/api/health') as Map<String, dynamic>;
      return json['ok'] == true;
    } catch (_) {
      return false;
    }
  }
}

class ApiException implements Exception {
  ApiException(this.message, this.status);
  final String message;
  final int status;

  @override
  String toString() => message;
}
