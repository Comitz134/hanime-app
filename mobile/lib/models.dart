/// Wire models. Field names mirror the proxy's JSON exactly so a server change
/// surfaces as a parse error rather than a silently empty field.

class Video {
  const Video({
    required this.id,
    required this.slug,
    required this.name,
    required this.cover,
    required this.poster,
    required this.brand,
    required this.tags,
    required this.views,
    required this.likes,
    required this.releasedAt,
    required this.description,
  });

  final int id;
  final String slug;
  final String name;
  final String? cover;
  final String? poster;
  final String? brand;
  final List<String> tags;
  final int views;
  final int likes;
  final String? releasedAt;
  final String? description;

  factory Video.fromJson(Map<String, dynamic> json) => Video(
        id: (json['id'] as num?)?.toInt() ?? 0,
        slug: json['slug'] as String? ?? '',
        name: json['name'] as String? ?? '(untitled)',
        cover: json['cover'] as String?,
        poster: json['poster'] as String?,
        brand: json['brand'] as String?,
        tags: (json['tags'] as List?)?.map((e) => e.toString()).toList() ?? const [],
        views: (json['views'] as num?)?.toInt() ?? 0,
        likes: (json['likes'] as num?)?.toInt() ?? 0,
        releasedAt: json['released_at'] as String?,
        description: json['description'] as String?,
      );

  /// Strip the `<p>` soup the catalog ships and collapse runs of blank lines.
  String get plainDescription => (description ?? '')
      .replaceAll(RegExp(r'<[^>]+>'), ' ')
      .replaceAll(RegExp(r'&nbsp;'), ' ')
      .replaceAll(RegExp(r'&amp;'), '&')
      .replaceAll(RegExp(r'\s*\n\s*'), '\n')
      .replaceAll(RegExp(r'\n{2,}'), '\n\n')
      .trim();
}

class VideoPage {
  const VideoPage({
    required this.page,
    required this.pages,
    required this.total,
    required this.data,
  });

  final int page;
  final int pages;
  final int total;
  final List<Video> data;

  factory VideoPage.fromJson(Map<String, dynamic> json) => VideoPage(
        page: (json['page'] as num?)?.toInt() ?? 0,
        pages: (json['pages'] as num?)?.toInt() ?? 1,
        total: (json['total'] as num?)?.toInt() ?? 0,
        data: (json['data'] as List? ?? const [])
            .map((e) => Video.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class StreamSource {
  const StreamSource({
    required this.label,
    required this.height,
    required this.url,
    required this.kind,
  });

  final String label;
  final int height;
  final String url;
  final String kind;

  factory StreamSource.fromJson(Map<String, dynamic> json) => StreamSource(
        label: json['label'] as String? ?? 'auto',
        height: (json['height'] as num?)?.toInt() ?? 0,
        url: json['url'] as String? ?? '',
        kind: json['kind'] as String? ?? 'normal',
      );
}

/// Account session state. *Your* playlists live inside the account payload
/// upstream, so nothing account-playlist-shaped exists until `connected` is
/// true. Public playlists are unrelated — see [PublicPlaylist], which needs no
/// session at all.
class SessionInfo {
  const SessionInfo({
    required this.configured,
    required this.live,
    required this.username,
    required this.reason,
    required this.playlistCount,
  });

  final bool configured;
  final bool live;
  final String? username;
  final String? reason;
  final int? playlistCount;

  static const empty = SessionInfo(
    configured: false,
    live: false,
    username: null,
    reason: 'no_session',
    playlistCount: null,
  );

  factory SessionInfo.fromJson(Map<String, dynamic> json) => SessionInfo(
        configured: json['configured'] == true,
        live: json['live'] == true,
        username: (json['user'] as Map?)?['username'] as String?,
        reason: json['reason'] as String?,
        playlistCount: (json['playlists'] as num?)?.toInt(),
      );

  String get label => live ? (username != null ? '@$username' : 'connected') : 'not connected';
}

class PlaylistPreview {
  const PlaylistPreview({required this.cover});
  final String? cover;

  factory PlaylistPreview.fromJson(Map<String, dynamic> json) =>
      PlaylistPreview(cover: json['cover'] as String?);
}

class Playlist {
  const Playlist({
    required this.slug,
    required this.title,
    required this.count,
    required this.resolved,
    required this.unresolved,
    required this.synthetic,
    required this.preview,
    this.match,
    this.matchCount,
    this.items = const [],
  });

  final String slug;
  final String title;
  final int count;
  final int resolved;
  final int unresolved;

  /// True for a bucket derived from membership rows that named no playlist.
  /// Shown rather than hidden so a shape change upstream is visible.
  final bool synthetic;

  final List<PlaylistPreview> preview;
  final String? match; // 'title' | 'item'
  final int? matchCount;
  final List<PlaylistItem> items;

  factory Playlist.fromJson(Map<String, dynamic> json) => Playlist(
        slug: json['slug'] as String? ?? '',
        title: json['title'] as String? ?? 'Untitled playlist',
        count: (json['count'] as num?)?.toInt() ?? 0,
        resolved: (json['resolved'] as num?)?.toInt() ?? 0,
        unresolved: (json['unresolved'] as num?)?.toInt() ?? 0,
        synthetic: json['synthetic'] == true,
        preview: (json['preview'] as List? ?? const [])
            .map((e) => PlaylistPreview.fromJson(e as Map<String, dynamic>))
            .toList(),
        match: json['match'] as String?,
        matchCount: (json['match_count'] as num?)?.toInt(),
        items: (json['items'] as List? ?? const [])
            .map((e) => PlaylistItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  List<String> get covers => preview.map((p) => p.cover).whereType<String>().take(4).toList();
}

class PlaylistItem {
  const PlaylistItem({
    required this.slug,
    required this.title,
    required this.cover,
    required this.brand,
    required this.resolved,
  });

  final String? slug;
  final String title;
  final String? cover;
  final String? brand;

  /// False means the row exists upstream but is not in the local catalog. The
  /// tile is still shown, greyed, instead of silently vanishing.
  final bool resolved;

  factory PlaylistItem.fromJson(Map<String, dynamic> json) => PlaylistItem(
        slug: json['slug'] as String?,
        title: json['title'] as String? ?? '(unknown title)',
        cover: json['cover'] as String?,
        brand: json['brand'] as String?,
        resolved: json['resolved'] == true,
      );
}

class PlaylistPage {
  const PlaylistPage({required this.configured, required this.reason, required this.playlists});

  final bool configured;
  final String? reason;
  final List<Playlist> playlists;

  factory PlaylistPage.fromJson(Map<String, dynamic> json) => PlaylistPage(
        configured: json['configured'] == true,
        reason: json['reason'] as String?,
        playlists: (json['playlists'] as List? ?? const [])
            .map((e) => Playlist.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// A public playlist discovered by the server's crawler.
///
/// Distinct from [Playlist], which is the account-scoped shape. Public
/// playlists are readable by anyone on hanime.tv but listed nowhere: no index
/// page, no public list endpoint, and zero entries in the sitemap. The proxy
/// finds them by crawling the "Related Playlists" rail on video pages and
/// expanding creators into their channels, then serves the result from
/// `GET /api/public/playlists`. No account involved.
class PublicPlaylist {
  const PublicPlaylist({
    required this.slug,
    required this.title,
    required this.ownerName,
    required this.ownerAvatar,
    required this.ownerChannelSlug,
    required this.cover,
    required this.itemCount,
    required this.views,
    required this.matchKind,
    required this.matchCount,
    required this.truncated,
    required this.fetched,
  });

  final String slug;
  final String title;
  final String? ownerName;
  final String? ownerAvatar;
  final String? ownerChannelSlug;
  final String? cover;
  final int itemCount;
  final int? views;

  /// 'title' | 'content' | 'browse' — how the query matched. Title matches rank
  /// first and carry the whole playlist; content matches carry only the hits.
  final String matchKind;
  final int matchCount;

  /// Upstream flagged this list as cut short. Never treated as complete.
  final bool truncated;

  /// False when only the discovery card is known — the playlist's own page has
  /// not been fetched, so its entries are not searchable yet.
  final bool fetched;

  factory PublicPlaylist.fromJson(Map<String, dynamic> json) => PublicPlaylist(
        slug: json['slug'] as String? ?? '',
        title: json['title'] as String? ?? '(untitled)',
        ownerName: json['owner_name'] as String?,
        ownerAvatar: json['owner_avatar_url'] as String?,
        ownerChannelSlug: json['owner_channel_slug'] as String?,
        cover: json['cover_url'] as String?,
        itemCount: (json['item_count'] as num?)?.toInt() ??
            (json['video_count'] as num?)?.toInt() ??
            0,
        views: (json['views'] as num?)?.toInt(),
        matchKind: json['match_kind'] as String? ?? 'browse',
        matchCount: (json['match_count'] as num?)?.toInt() ?? 0,
        truncated: json['truncated'] == true,
        fetched: json['fetched'] == true,
      );
}

class PublicPlaylistStats {
  const PublicPlaylistStats({
    required this.playlists,
    required this.discovered,
    required this.items,
    required this.owners,
  });

  final int playlists;
  final int discovered;
  final int items;
  final int owners;

  static const empty = PublicPlaylistStats(playlists: 0, discovered: 0, items: 0, owners: 0);

  factory PublicPlaylistStats.fromJson(Map<String, dynamic> json) => PublicPlaylistStats(
        playlists: (json['playlists'] as num?)?.toInt() ?? 0,
        discovered: (json['discovered'] as num?)?.toInt() ?? 0,
        items: (json['items'] as num?)?.toInt() ?? 0,
        owners: (json['owners'] as num?)?.toInt() ?? 0,
      );
}

class PublicPlaylistPage {
  const PublicPlaylistPage({
    required this.playlists,
    required this.total,
    required this.matched,
    required this.stats,
  });

  final List<PublicPlaylist> playlists;
  final int total;
  final int matched;
  final PublicPlaylistStats stats;

  factory PublicPlaylistPage.fromJson(Map<String, dynamic> json) => PublicPlaylistPage(
        playlists: (json['playlists'] as List? ?? const [])
            .map((e) => PublicPlaylist.fromJson(e as Map<String, dynamic>))
            .toList(),
        total: (json['total'] as num?)?.toInt() ?? 0,
        matched: (json['matched'] as num?)?.toInt() ?? 0,
        stats: PublicPlaylistStats.fromJson(
            (json['stats'] as Map?)?.cast<String, dynamic>() ?? const {}),
      );
}

/// One entry inside a crawled playlist. `resolved` is decided by the server
/// against the local catalog, so an unplayable row is never a dead button.
class PublicPlaylistEntry {
  const PublicPlaylistEntry({
    required this.slug,
    required this.title,
    required this.brand,
    required this.cover,
    required this.resolved,
  });

  final String? slug;
  final String title;
  final String? brand;
  final String? cover;
  final bool resolved;

  factory PublicPlaylistEntry.fromJson(Map<String, dynamic> json) => PublicPlaylistEntry(
        slug: json['slug'] as String?,
        title: json['name'] as String? ?? json['slug'] as String? ?? '(unknown)',
        brand: json['brand'] as String?,
        cover: json['cover_url'] as String? ?? json['poster_url'] as String?,
        resolved: json['resolved'] == true,
      );
}

class PublicPlaylistDetail {
  const PublicPlaylistDetail({
    required this.slug,
    required this.title,
    required this.ownerName,
    required this.ownerChannelSlug,
    required this.playable,
    required this.unresolved,
    required this.truncated,
    required this.entries,
  });

  final String slug;
  final String title;
  final String? ownerName;
  final String? ownerChannelSlug;
  final int playable;
  final int unresolved;
  final bool truncated;
  final List<PublicPlaylistEntry> entries;

  factory PublicPlaylistDetail.fromJson(Map<String, dynamic> json) => PublicPlaylistDetail(
        slug: json['slug'] as String? ?? '',
        title: json['title'] as String? ?? '(untitled)',
        ownerName: json['owner_name'] as String?,
        ownerChannelSlug: json['owner_channel_slug'] as String?,
        playable: (json['playable'] as num?)?.toInt() ?? 0,
        unresolved: (json['unresolved'] as num?)?.toInt() ?? 0,
        truncated: json['truncated'] == true,
        entries: (json['items'] as List? ?? const [])
            .map((e) => PublicPlaylistEntry.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class TagCount {
  const TagCount(this.name, this.count);
  final String name;
  final int count;

  factory TagCount.fromJson(Map<String, dynamic> json) =>
      TagCount(json['name'] as String? ?? '', (json['count'] as num?)?.toInt() ?? 0);
}

String formatCount(int n) {
  if (n >= 1000000) return '${(n / 1000000).toStringAsFixed(1)}M';
  if (n >= 1000) return '${(n / 1000).toStringAsFixed(1)}K';
  return '$n';
}
