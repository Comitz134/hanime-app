import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../api.dart';
import '../models.dart';
import 'player_screen.dart';

/// Playlists tab, split into two genuinely different sources.
///
/// **Public** — playlists anyone can open on hanime.tv. They are readable but
/// unlisted: no index page, no public list endpoint, and the sitemap contains
/// none, so the proxy discovers them by crawling the "Related Playlists" rail
/// on video pages and expanding creators into their channels. This tab is that
/// crawl, and it needs no account.
///
/// **Mine** — playlists on your own account. Those live inside the account
/// session payload, so this tab starts in a connect state until a cookie has
/// authenticated.
class PlaylistsScreen extends StatelessWidget {
  const PlaylistsScreen({super.key, required this.api});

  final Api api;

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        body: SafeArea(
          child: Column(
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('Playlists',
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
                ),
              ),
              TabBar(
                labelColor: const Color(0xFFFFE0C2),
                unselectedLabelColor: const Color(0xFFB4B4B4),
                indicatorColor: const Color(0xFFFFE0C2),
                indicatorSize: TabBarIndicatorSize.label,
                dividerColor: Colors.transparent,
                tabs: const [
                  Tab(text: 'Public'),
                  Tab(text: 'Mine'),
                ],
              ),
              Expanded(
                child: TabBarView(
                  children: [
                    _PublicTab(api: api),
                    _MineTab(api: api),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/* ------------------------------------------------------------------ public -- */

class _PublicTab extends StatefulWidget {
  const _PublicTab({required this.api});

  final Api api;

  @override
  State<_PublicTab> createState() => _PublicTabState();
}

class _PublicTabState extends State<_PublicTab> {
  final _searchController = TextEditingController();
  final _scrollController = ScrollController();

  List<PublicPlaylist> _playlists = const [];
  PublicPlaylistStats _stats = PublicPlaylistStats.empty;
  Timer? _debounce;
  bool _loading = true;
  bool _scanning = false;
  String? _error;
  String? _ownerFilter;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    if (!mounted) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.api.publicPlaylists(
        query: _searchController.text,
        owner: _ownerFilter ?? '',
      );
      if (!mounted) return;
      setState(() {
        _playlists = page.playlists;
        _stats = page.stats;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not load public playlists — $e';
      });
    }
  }

  void _onSearchChanged(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 260), _load);
  }

  /// Start a discovery pass and poll until the server reports it finished.
  /// The pass outlives any single request, so progress is read, not awaited.
  Future<void> _scanMore() async {
    setState(() => _scanning = true);
    try {
      await widget.api.startPublicCrawl();
      for (var i = 0; i < 120; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 1500));
        if (!mounted) return;
        final stats = await widget.api.publicCrawlStats();
        await _load();
        if (stats.discovered == _stats.discovered && i > 3) break;
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Scan failed — $e')),
      );
    } finally {
      if (mounted) setState(() => _scanning = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final q = _searchController.text.trim();

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '${_stats.playlists} crawled · ${formatCount(_stats.items)} titles'
                  ' · ${_stats.owners} creators'
                  '${_stats.discovered > _stats.playlists ? ' · ${_stats.discovered - _stats.playlists} not opened' : ''}',
                  style: TextStyle(fontSize: 11.5, color: theme.hintColor),
                ),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: _scanning ? null : _scanMore,
                child: _scanning
                    ? const SizedBox(
                        width: 14, height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Scan more', style: TextStyle(fontSize: 12.5)),
              ),
            ],
          ),
        ),
        if (_ownerFilter != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: Row(
              children: [
                Chip(
                  label: Text('creator: $_ownerFilter', style: const TextStyle(fontSize: 11.5)),
                  onDeleted: () {
                    setState(() => _ownerFilter = null);
                    _load();
                  },
                ),
              ],
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
          child: TextField(
            controller: _searchController,
            onChanged: _onSearchChanged,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: 'Search public playlists, creators and their titles…',
              prefixIcon: const Icon(Icons.search, size: 20),
              suffixIcon: q.isEmpty
                  ? null
                  : IconButton(
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: () {
                        _searchController.clear();
                        _load();
                      },
                    ),
            ),
          ),
        ),
        Expanded(child: _body(q)),
      ],
    );
  }

  Widget _body(String q) {
    final theme = Theme.of(context);
    if (_loading && _playlists.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null && _playlists.isEmpty) {
      return _centered(_error!, onRetry: _load);
    }
    if (_playlists.isEmpty) {
      return _centered(
        q.isEmpty
            ? 'The crawl index is empty. Tap Scan more to start discovering public playlists.'
            : 'Nothing matches “$q”. Title and creator matches come first, then playlists '
                'containing a matching title. Only crawled playlists are searchable by content.',
      );
    }

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        controller: _scrollController,
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 32),
        itemCount: _playlists.length,
        separatorBuilder: (_, __) => const SizedBox(height: 18),
        itemBuilder: (context, i) => _PublicPlaylistRow(
          playlist: _playlists[i],
          onOpen: () => _open(_playlists[i]),
          onOwner: (slug) {
            setState(() => _ownerFilter = slug);
            _load();
          },
        ),
      ),
    );
  }

  Widget _centered(String message, {VoidCallback? onRetry}) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message, textAlign: TextAlign.center),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              FilledButton(onPressed: onRetry, child: const Text('Retry')),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _open(PublicPlaylist p) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => PublicPlaylistDetailScreen(api: widget.api, summary: p),
      ),
    );
  }
}

class _PublicPlaylistRow extends StatelessWidget {
  const _PublicPlaylistRow({
    required this.playlist,
    required this.onOpen,
    required this.onOwner,
  });

  final PublicPlaylist playlist;
  final VoidCallback onOpen;
  final ValueChanged<String> onOwner;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final badge = switch (playlist.matchKind) {
      'title' => 'title match',
      'content' => '${playlist.matchCount} inside',
      _ => null,
    };

    return InkWell(
      onTap: onOpen,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.all(6),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 84,
              height: 84,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: playlist.cover != null
                    ? CachedNetworkImage(
                        imageUrl: playlist.cover!,
                        fit: BoxFit.cover,
                        errorWidget: (_, __, ___) => ColoredBox(
                          color: theme.cardColor,
                          child: Center(
                            child: Text('no cover',
                                style: TextStyle(fontSize: 10, color: theme.hintColor)),
                          ),
                        ),
                      )
                    : ColoredBox(
                        color: theme.cardColor,
                        child: Center(
                          child: Text('no cover',
                              style: TextStyle(fontSize: 10, color: theme.hintColor)),
                        ),
                      ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(playlist.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 5),
                  Row(
                    children: [
                      if (playlist.ownerAvatar != null)
                        Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: ClipOval(
                            child: CachedNetworkImage(
                              imageUrl: playlist.ownerAvatar!,
                              width: 16,
                              height: 16,
                              fit: BoxFit.cover,
                              errorWidget: (_, __, ___) => const SizedBox(width: 16, height: 16),
                            ),
                          ),
                        ),
                      Expanded(
                        child: GestureDetector(
                          onTap: playlist.ownerChannelSlug == null
                              ? null
                              : () => onOwner(playlist.ownerChannelSlug!),
                          child: Text(
                            playlist.ownerName ?? 'unknown creator',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 11.5, color: Color(0xFFFFE0C2)),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '${playlist.itemCount} ${playlist.itemCount == 1 ? 'title' : 'titles'}'
                    '${playlist.views != null ? ' · ${formatCount(playlist.views!)} views' : ''}'
                    '${badge != null ? ' · $badge' : ''}'
                    '${playlist.truncated ? ' · partial' : ''}'
                    '${!playlist.fetched ? ' · not opened yet' : ''}',
                    style: TextStyle(fontSize: 11, color: theme.hintColor),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: theme.hintColor),
          ],
        ),
      ),
    );
  }
}

/// One crawled playlist. Entries the local catalog cannot resolve render as a
/// dashed placeholder rather than being dropped — a catalogue gap should be
/// visible, not silent.
class PublicPlaylistDetailScreen extends StatefulWidget {
  const PublicPlaylistDetailScreen({super.key, required this.api, required this.summary});

  final Api api;
  final PublicPlaylist summary;

  @override
  State<PublicPlaylistDetailScreen> createState() => _PublicPlaylistDetailScreenState();
}

class _PublicPlaylistDetailScreenState extends State<PublicPlaylistDetailScreen> {
  PublicPlaylistDetail? _detail;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final d = await widget.api.publicPlaylist(widget.summary.slug);
      if (!mounted) return;
      setState(() => _detail = d);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final d = _detail;

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.summary.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      body: d == null
          ? Center(child: Text(_error ?? 'Loading…'))
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'by ${d.ownerName ?? 'unknown creator'} · ${d.entries.length} titles'
                      ' · ${d.playable} playable'
                      '${d.unresolved > 0 ? ' · ${d.unresolved} not in the local catalog' : ''}'
                      '${d.truncated ? ' · upstream truncated this list' : ''}',
                      style: TextStyle(fontSize: 12.5, color: theme.hintColor),
                    ),
                  ),
                ),
                Expanded(
                  child: GridView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 28),
                    gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                      maxCrossAxisExtent: 150,
                      mainAxisSpacing: 12,
                      crossAxisSpacing: 12,
                      childAspectRatio: .58,
                    ),
                    itemCount: d.entries.length,
                    itemBuilder: (context, i) {
                      final e = d.entries[i];
                      if (!e.resolved || e.slug == null) {
                        return DecoratedBox(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: Colors.white24),
                          ),
                          child: Center(
                            child: Padding(
                              padding: const EdgeInsets.all(8),
                              child: Text(e.title,
                                  textAlign: TextAlign.center,
                                  style: TextStyle(fontSize: 10, color: theme.hintColor)),
                            ),
                          ),
                        );
                      }
                      return InkWell(
                        onTap: () => _play(e),
                        borderRadius: BorderRadius.circular(10),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            AspectRatio(
                              aspectRatio: 2 / 3,
                              child: ClipRRect(
                                borderRadius: BorderRadius.circular(10),
                                child: e.cover != null
                                    ? CachedNetworkImage(
                                        imageUrl: e.cover!,
                                        fit: BoxFit.cover,
                                        errorWidget: (_, __, ___) =>
                                            const ColoredBox(color: Color(0xFF1C1C26)),
                                      )
                                    : const ColoredBox(color: Color(0xFF1C1C26)),
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(e.title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 11.5, height: 1.3)),
                            if (e.brand != null)
                              Text(e.brand!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(fontSize: 10, color: theme.hintColor)),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
    );
  }

  Future<void> _play(PublicPlaylistEntry entry) async {
    try {
      final video = await widget.api.video(entry.slug!);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => PlayerScreen(api: widget.api, video: video)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not open that title — $e')),
      );
    }
  }
}

/* -------------------------------------------------------------------- mine -- */

class _MineTab extends StatefulWidget {
  const _MineTab({required this.api});

  final Api api;

  @override
  State<_MineTab> createState() => _MineTabState();
}

class _MineTabState extends State<_MineTab> {
  final _searchController = TextEditingController();
  final _cookieController = TextEditingController();

  SessionInfo _session = SessionInfo.empty;
  List<Playlist> _playlists = const [];
  Timer? _debounce;
  bool _loading = true;
  bool _connecting = false;
  String? _error;
  String? _connectMessage;
  bool _connectFailed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _cookieController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final session = await widget.api.session();
      if (!mounted) return;
      setState(() {
        _session = session;
        _loading = false;
      });
      if (session.live) await _loadPlaylists();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not reach the server at ${widget.api.baseUrl}\n$e';
      });
    }
  }

  Future<void> _loadPlaylists() async {
    try {
      final page = await widget.api.playlists(query: _searchController.text);
      if (!mounted) return;
      setState(() {
        _playlists = page.playlists;
        _session = SessionInfo(
          configured: true,
          live: true,
          username: _session.username,
          reason: null,
          playlistCount: page.playlists.length,
        );
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = 'Could not load playlists — $e');
    }
  }

  Future<void> _connect() async {
    final cookie = _cookieController.text.trim();
    if (cookie.isEmpty) return;
    setState(() {
      _connecting = true;
      _connectMessage = null;
    });
    try {
      final session = await widget.api.connectSession(cookie);
      if (!mounted) return;
      _cookieController.clear();
      setState(() {
        _session = session;
        _connectMessage = 'Connected as ${session.username ?? 'account'}.';
        _connectFailed = false;
      });
      await _loadPlaylists();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _connectMessage = e.message;
        _connectFailed = true;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _connectMessage = '$e';
        _connectFailed = true;
      });
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  Future<void> _disconnect() async {
    await widget.api.disconnectSession();
    if (!mounted) return;
    setState(() {
      _session = SessionInfo.empty;
      _playlists = const [];
      _connectMessage = null;
    });
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 260), _loadPlaylists);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final connected = _session.live;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              Text(
                connected
                    ? 'Signed in as ${_session.username ?? 'account'}'
                    : 'No account connected',
                style: TextStyle(fontSize: 12.5, color: theme.hintColor),
              ),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: theme.cardColor,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 6,
                      height: 6,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: connected ? const Color(0xFFFFE0C2) : theme.hintColor,
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(_session.label, style: const TextStyle(fontSize: 11.5)),
                  ],
                ),
              ),
            ],
          ),
        ),
        if (connected)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: TextField(
              controller: _searchController,
              onChanged: _onSearchChanged,
              textInputAction: TextInputAction.search,
              decoration: const InputDecoration(
                hintText: 'Search your playlists and their contents…',
                prefixIcon: Icon(Icons.search, size: 20),
              ),
            ),
          ),
        Expanded(child: _body(connected)),
      ],
    );
  }

  Widget _body(bool connected) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null && _playlists.isEmpty) {
      return _centered(_error!, withRetry: true);
    }
    if (!connected) return _connectForm();
    if (_playlists.isEmpty) {
      final q = _searchController.text.trim();
      return _centered(q.isEmpty ? 'No playlists on this account.' : 'Nothing matches “$q”.');
    }

    return RefreshIndicator(
      onRefresh: _loadPlaylists,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _playlists.length,
        separatorBuilder: (_, __) => const SizedBox(height: 20),
        itemBuilder: (context, i) => _PlaylistRow(
          playlist: _playlists[i],
          onOpen: () => _open(_playlists[i]),
        ),
      ),
    );
  }

  Widget _centered(String message, {bool withRetry = false}) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message, textAlign: TextAlign.center),
            if (withRetry) ...[
              const SizedBox(height: 16),
              FilledButton(onPressed: _load, child: const Text('Retry')),
            ],
          ],
        ),
      ),
    );
  }

  Widget _connectForm() {
    final theme = Theme.of(context);
    final why = {
          'no_session': 'No account connected yet.',
          'expired': 'The stored cookie no longer authenticates — it has probably expired.',
        }[_session.reason] ??
        (_session.reason != null
            ? 'Session check failed: ${_session.reason}'
            : 'No account connected yet.');

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: theme.cardColor.withValues(alpha: .4),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.white10),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Connect an account to see your playlists',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              Text(
                'Public playlists are readable without an account — see the Public tab. '
                'Your own playlists are different: they arrive inside the account session '
                'payload, so the proxy needs a cookie from a browser you are already '
                'logged in with. $why',
                style: TextStyle(fontSize: 13, height: 1.6, color: theme.hintColor),
              ),
              const SizedBox(height: 14),
              Text(
                '1. Log in on hanime.tv\n'
                '2. DevTools → Network → any request to auth.hanime.tv\n'
                '3. Copy that request\'s Cookie header and paste it below',
                style: TextStyle(fontSize: 12.5, height: 1.65, color: theme.hintColor),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _cookieController,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                maxLines: 1,
                decoration: const InputDecoration(hintText: 'session=…'),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _connecting ? null : _connect,
                  child: _connecting
                      ? const SizedBox(
                          width: 16, height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Connect'),
                ),
              ),
              if (_connectMessage != null) ...[
                const SizedBox(height: 12),
                Text(
                  _connectMessage!,
                  style: TextStyle(
                    fontSize: 12.5,
                    color: _connectFailed ? const Color(0xFFFF8B9A) : const Color(0xFFFFE0C2),
                  ),
                ),
              ],
              if (_session.configured) ...[
                const SizedBox(height: 8),
                TextButton(onPressed: _disconnect, child: const Text('Disconnect')),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _open(Playlist p) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => PlaylistDetailScreen(api: widget.api, summary: p),
      ),
    );
  }
}

class _PlaylistRow extends StatelessWidget {
  const _PlaylistRow({required this.playlist, required this.onOpen});

  final Playlist playlist;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final covers = playlist.covers;

    return InkWell(
      onTap: onOpen,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.all(6),
        child: Row(
          children: [
            // Mosaic of up to four covers, matching the web client's tile.
            SizedBox(
              width: 84,
              height: 84,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: covers.isEmpty
                    ? ColoredBox(
                        color: theme.cardColor,
                        child: Center(
                          child: Text('no cover',
                              style: TextStyle(fontSize: 10, color: theme.hintColor)),
                        ),
                      )
                    : GridView.count(
                        physics: const NeverScrollableScrollPhysics(),
                        crossAxisCount: covers.length == 1 ? 1 : 2,
                        mainAxisSpacing: 1,
                        crossAxisSpacing: 1,
                        children: covers
                            .map((c) => CachedNetworkImage(
                                  imageUrl: c,
                                  fit: BoxFit.cover,
                                  errorWidget: (_, __, ___) =>
                                      const ColoredBox(color: Color(0xFF1C1C26)),
                                ))
                            .toList(),
                      ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(playlist.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  Text(
                    '${playlist.count} ${playlist.count == 1 ? 'title' : 'titles'}'
                    '${playlist.synthetic ? ' · derived' : ''}'
                    '${playlist.unresolved > 0 ? ' · ${playlist.unresolved} unresolved' : ''}'
                    '${playlist.match == 'item' ? ' · ${playlist.matchCount} match' : ''}',
                    style: TextStyle(fontSize: 11.5, color: theme.hintColor),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: theme.hintColor),
          ],
        ),
      ),
    );
  }
}

/// Contents of one account playlist. Items missing from the local catalog
/// render as a dashed placeholder rather than being dropped.
class PlaylistDetailScreen extends StatefulWidget {
  const PlaylistDetailScreen({super.key, required this.api, required this.summary});

  final Api api;
  final Playlist summary;

  @override
  State<PlaylistDetailScreen> createState() => _PlaylistDetailScreenState();
}

class _PlaylistDetailScreenState extends State<PlaylistDetailScreen> {
  Playlist? _playlist;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final p = await widget.api.playlist(widget.summary.slug);
      if (!mounted) return;
      setState(() => _playlist = p);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final p = _playlist;

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.summary.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      body: p == null
          ? Center(child: Text(_error ?? 'Loading…'))
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '${p.items.length} titles · ${p.resolved} playable'
                      '${p.unresolved > 0 ? ' · ${p.unresolved} not in the local catalog' : ''}',
                      style: TextStyle(fontSize: 12.5, color: theme.hintColor),
                    ),
                  ),
                ),
                Expanded(
                  child: GridView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 28),
                    gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                      maxCrossAxisExtent: 150,
                      mainAxisSpacing: 12,
                      crossAxisSpacing: 12,
                      childAspectRatio: .58,
                    ),
                    itemCount: p.items.length,
                    itemBuilder: (context, i) {
                      final item = p.items[i];
                      if (!item.resolved || item.slug == null) {
                        return DecoratedBox(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: Colors.white24),
                          ),
                          child: Center(
                            child: Padding(
                              padding: const EdgeInsets.all(8),
                              child: Text(item.title,
                                  textAlign: TextAlign.center,
                                  style: TextStyle(fontSize: 10, color: theme.hintColor)),
                            ),
                          ),
                        );
                      }
                      return InkWell(
                        onTap: () => _play(item),
                        borderRadius: BorderRadius.circular(10),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            AspectRatio(
                              aspectRatio: 2 / 3,
                              child: ClipRRect(
                                borderRadius: BorderRadius.circular(10),
                                child: item.cover != null
                                    ? CachedNetworkImage(
                                        imageUrl: item.cover!,
                                        fit: BoxFit.cover,
                                        errorWidget: (_, __, ___) =>
                                            const ColoredBox(color: Color(0xFF1C1C26)),
                                      )
                                    : const ColoredBox(color: Color(0xFF1C1C26)),
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(item.title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 11.5, height: 1.3)),
                            if (item.brand != null)
                              Text(item.brand!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(fontSize: 10, color: theme.hintColor)),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
    );
  }

  /// The player needs full metadata, so fetch the catalog record for the slug
  /// rather than playing from the thin row the playlist carries.
  Future<void> _play(PlaylistItem item) async {
    try {
      final video = await widget.api.video(item.slug!);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => PlayerScreen(api: widget.api, video: video)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not open that title — $e')),
      );
    }
  }
}
