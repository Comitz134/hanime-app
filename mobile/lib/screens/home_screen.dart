import 'dart:async';

import 'package:flutter/material.dart';

import '../api.dart';
import '../models.dart';
import '../widgets/video_card.dart';
import 'detail_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.api});

  final Api api;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _searchController = TextEditingController();
  final _scrollController = ScrollController();

  final List<Video> _videos = [];
  List<TagCount> _tags = const [];
  final Set<String> _selectedTags = {};

  Timer? _debounce;
  String _query = '';
  String _sort = 'released_at_unix:desc';
  int _page = 0;
  int _pages = 1;
  int _total = 0;
  bool _loading = false;
  bool _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _bootstrap();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    await _loadTags();
    await _reload();
  }

  Future<void> _loadTags() async {
    try {
      final tags = await widget.api.tags();
      if (!mounted) return;
      setState(() {
        // Only well-represented tags make useful filters.
        _tags = tags.where((t) => t.count >= 25).take(28).toList();
      });
    } catch (_) {
      // Tag filters are a convenience; the grid still works without them.
    }
  }

  Future<void> _reload() async {
    setState(() {
      _loading = true;
      _error = null;
      _page = 0;
    });
    try {
      final page = await _fetch(0);
      if (!mounted) return;
      setState(() {
        _videos
          ..clear()
          ..addAll(page.data);
        _pages = page.pages;
        _total = page.total;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not reach the server at ${widget.api.baseUrl}\n$e';
      });
    }
  }

  Future<VideoPage> _fetch(int page) {
    final parts = _sort.split(':');
    return widget.api.videos(
      page: page,
      perPage: 30,
      query: _query,
      tags: _selectedTags.toList(),
      orderBy: parts[0],
      ordering: parts.length > 1 ? parts[1] : 'desc',
    );
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final remaining = _scrollController.position.maxScrollExtent - _scrollController.position.pixels;
    if (remaining < 600) _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loadingMore || _loading || _page + 1 >= _pages) return;
    setState(() => _loadingMore = true);
    try {
      final next = await _fetch(_page + 1);
      if (!mounted) return;
      setState(() {
        _page += 1;
        _videos.addAll(next.data);
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
    }
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 260), () {
      setState(() => _query = value);
      _reload();
    });
  }

  void _toggleTag(String tag) {
    setState(() {
      if (!_selectedTags.remove(tag)) _selectedTags.add(tag);
    });
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 0),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _searchController,
                      onChanged: _onSearchChanged,
                      textInputAction: TextInputAction.search,
                      decoration: const InputDecoration(
                        hintText: 'Search titles, studios…',
                        prefixIcon: Icon(Icons.search, size: 20),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  _SortButton(
                    value: _sort,
                    onChanged: (value) {
                      setState(() => _sort = value);
                      _reload();
                    },
                  ),
                ],
              ),
            ),
            if (_tags.isNotEmpty)
              SizedBox(
                height: 54,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
                  itemCount: _tags.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 6),
                  itemBuilder: (context, i) {
                    final tag = _tags[i];
                    final on = _selectedTags.contains(tag.name);
                    return FilterChip(
                      label: Text('${tag.name}  ${tag.count}'),
                      selected: on,
                      onSelected: (_) => _toggleTag(tag.name),
                      showCheckmark: false,
                      labelStyle: TextStyle(
                        fontSize: 12.5,
                        color: on ? Colors.black : theme.hintColor,
                        fontWeight: on ? FontWeight.w600 : FontWeight.normal,
                      ),
                    );
                  },
                ),
              ),
            if (_total > 0)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '$_total titles',
                    style: TextStyle(fontSize: 12, color: theme.hintColor),
                  ),
                ),
              ),
            Expanded(child: _body()),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    if (_loading && _videos.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null && _videos.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              FilledButton(onPressed: _reload, child: const Text('Retry')),
            ],
          ),
        ),
      );
    }
    if (_videos.isEmpty) {
      return const Center(child: Text('Nothing matches that.'));
    }

    return GridView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.fromLTRB(14, 0, 14, 24),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 220,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 0.78,
      ),
      itemCount: _videos.length + (_loadingMore ? 1 : 0),
      itemBuilder: (context, i) {
        if (i >= _videos.length) {
          return const Center(child: CircularProgressIndicator(strokeWidth: 2));
        }
        final video = _videos[i];
        return VideoCard(
          video: video,
          onTap: () async {
            await Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => DetailScreen(api: widget.api, slug: video.slug, preview: video),
              ),
            );
          },
        );
      },
    );
  }
}

class _SortButton extends StatelessWidget {
  const _SortButton({required this.value, required this.onChanged});

  final String value;
  final ValueChanged<String> onChanged;

  static const _options = <String, String>{
    'released_at_unix:desc': 'Newest',
    'views:desc': 'Most viewed',
    'likes:desc': 'Most liked',
    'name:asc': 'A–Z',
  };

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<String>(
      initialValue: value,
      onSelected: onChanged,
      color: Theme.of(context).cardColor,
      itemBuilder: (_) => [
        for (final entry in _options.entries)
          PopupMenuItem(value: entry.key, child: Text(entry.value)),
      ],
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: Theme.of(context).cardColor,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Text(_options[value] ?? 'Sort', style: const TextStyle(fontSize: 13)),
            const Icon(Icons.arrow_drop_down, size: 18),
          ],
        ),
      ),
    );
  }
}
