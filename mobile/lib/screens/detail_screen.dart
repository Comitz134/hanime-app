import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../api.dart';
import '../models.dart';
import 'player_screen.dart';
import 'playlists_screen.dart';

class DetailScreen extends StatefulWidget {
  const DetailScreen({super.key, required this.api, required this.slug, this.preview});

  final Api api;
  final String slug;

  /// The grid already holds everything except the description, so render
  /// immediately from it and refine once the full record lands.
  final Video? preview;

  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  Video? _video;
  String? _error;

  /// Public playlists carrying this title. Loaded separately from the video
  /// record so a slow or failing lookup never holds up the detail page.
  List<PublicPlaylist> _inPlaylists = const [];

  @override
  void initState() {
    super.initState();
    _video = widget.preview;
    _load();
    _loadPlaylists();
  }

  Future<void> _load() async {
    try {
      final video = await widget.api.video(widget.slug);
      if (!mounted) return;
      setState(() => _video = video);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  Future<void> _loadPlaylists() async {
    try {
      final lists = await widget.api.playlistsForVideo(widget.slug);
      if (!mounted) return;
      setState(() => _inPlaylists = lists);
    } catch (_) {
      // The crawl index is optional context; a miss should stay invisible.
    }
  }

  Future<void> _openPublicPlaylist(PublicPlaylist p) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => PublicPlaylistDetailScreen(api: widget.api, summary: p),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final video = _video;

    if (video == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(child: Text(_error ?? 'Loading…')),
      );
    }

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          SliverAppBar(
            expandedHeight: 300,
            pinned: true,
            backgroundColor: Theme.of(context).scaffoldBackgroundColor,
            flexibleSpace: FlexibleSpaceBar(
              background: video.poster != null
                  ? CachedNetworkImage(
                      imageUrl: video.poster!,
                      fit: BoxFit.cover,
                      placeholder: (_, __) => const ColoredBox(color: Color(0xFF1C1C26)),
                      errorWidget: (_, __, ___) => const ColoredBox(color: Color(0xFF1C1C26)),
                    )
                  : const ColoredBox(color: Color(0xFF1C1C26)),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 40),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    video.name,
                    style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700, height: 1.25),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    [
                      if (video.brand != null && video.brand!.isNotEmpty) video.brand!,
                      '${formatCount(video.views)} views',
                      '${formatCount(video.likes)} likes',
                    ].join(' · '),
                    style: TextStyle(fontSize: 13, color: theme.hintColor),
                  ),
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => PlayerScreen(api: widget.api, video: video),
                        ),
                      ),
                      icon: const Icon(Icons.play_arrow),
                      label: const Text('Play'),
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                  if (video.tags.isNotEmpty) ...[
                    const SizedBox(height: 18),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        for (final tag in video.tags)
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: theme.cardColor,
                              borderRadius: BorderRadius.circular(999),
                              border: Border.all(color: Colors.white10),
                            ),
                            child: Text(
                              tag,
                              style: TextStyle(fontSize: 12, color: theme.hintColor),
                            ),
                          ),
                      ],
                    ),
                  ],
                  if (_inPlaylists.isNotEmpty) ...[
                    const SizedBox(height: 22),
                    Text(
                      'In ${_inPlaylists.length} public '
                      '${_inPlaylists.length == 1 ? 'playlist' : 'playlists'}',
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final p in _inPlaylists)
                          ActionChip(
                            onPressed: () => _openPublicPlaylist(p),
                            label: Text(
                              '${p.title}${p.itemCount > 0 ? ' · ${p.itemCount}' : ''}',
                              style: const TextStyle(fontSize: 12),
                            ),
                          ),
                      ],
                    ),
                  ],
                  if (video.plainDescription.isNotEmpty) ...[
                    const SizedBox(height: 20),
                    Text(
                      video.plainDescription,
                      style: const TextStyle(fontSize: 14.5, height: 1.6, color: Color(0xFFC9C9D8)),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
