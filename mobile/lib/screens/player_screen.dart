import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../api.dart';
import '../models.dart';

/// Plays the relay's HLS. The relay rewrites every nested URI to itself, so the
/// player only ever talks to the proxy and needs no upstream headers.
class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.api, required this.video});

  final Api api;
  final Video video;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  VideoPlayerController? _controller;
  List<StreamSource> _sources = const [];
  int _selected = 0;
  String? _error;
  bool _initializing = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final sources = await widget.api.sources(widget.video.slug);
      if (!mounted) return;
      if (sources.isEmpty) {
        setState(() {
          _initializing = false;
          _error = 'No playable source was returned for this entry.';
        });
        return;
      }
      setState(() {
        _sources = sources;
        _selected = 0;
      });
      await _open(sources.first.url);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _initializing = false;
        _error = 'Could not resolve a stream: $e';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _initializing = false;
        _error = '$e';
      });
    }
  }

  Future<void> _open(String url) async {
    final previous = _controller;
    final keepPosition = previous?.value.position ?? Duration.zero;
    await previous?.dispose();

    final controller = VideoPlayerController.networkUrl(Uri.parse(url));
    setState(() {
      _controller = controller;
      _initializing = true;
      _error = null;
    });

    await controller.initialize();
    if (!mounted) {
      await controller.dispose();
      return;
    }
    if (keepPosition > Duration.zero && keepPosition < controller.value.duration) {
      await controller.seekTo(keepPosition);
    }
    await controller.play();
    setState(() => _initializing = false);
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: Text(widget.video.name, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      body: Column(
        children: [
          AspectRatio(
            aspectRatio: controller?.value.isInitialized == true
                ? controller!.value.aspectRatio
                : 16 / 9,
            child: _error != null
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Text(_error!, textAlign: TextAlign.center),
                    ),
                  )
                : _initializing || controller == null
                    ? const Center(child: CircularProgressIndicator())
                    : Stack(
                        alignment: Alignment.bottomCenter,
                        children: [
                          VideoPlayer(controller),
                          _PlaybackControls(controller: controller),
                        ],
                      ),
          ),
          if (_sources.length > 1)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Wrap(
                  spacing: 8,
                  children: [
                    for (var i = 0; i < _sources.length; i++)
                      ChoiceChip(
                        label: Text(_sources[i].label),
                        selected: _selected == i,
                        onSelected: (_) async {
                          if (_selected == i) return;
                          setState(() => _selected = i);
                          await _open(_sources[i].url);
                        },
                      ),
                  ],
                ),
              ),
            ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    formatCount(widget.video.views) +
                        ' views · ' +
                        formatCount(widget.video.likes) +
                        ' likes',
                    style: TextStyle(color: Theme.of(context).hintColor, fontSize: 13),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final tag in widget.video.tags)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
                          decoration: BoxDecoration(
                            color: Theme.of(context).cardColor,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(tag, style: TextStyle(fontSize: 11.5, color: Theme.of(context).hintColor)),
                        ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  if (widget.video.plainDescription.isNotEmpty)
                    Text(
                      widget.video.plainDescription,
                      style: const TextStyle(fontSize: 14, height: 1.55, color: Color(0xFFC9C9D8)),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Minimal tap-to-toggle overlay. The platform controls are hidden so a tap on
/// the video toggles play/pause instead of fighting two sets of hit targets.
class _PlaybackControls extends StatelessWidget {
  const _PlaybackControls({required this.controller});

  final VideoPlayerController controller;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<VideoPlayerValue>(
      valueListenable: controller,
      builder: (context, value, _) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => value.isPlaying ? controller.pause() : controller.play(),
        child: Container(
          color: Colors.black.withValues(alpha: .25),
          padding: const EdgeInsets.fromLTRB(8, 4, 8, 4),
          child: Row(
            children: [
              IconButton(
                icon: Icon(value.isPlaying ? Icons.pause : Icons.play_arrow),
                onPressed: () => value.isPlaying ? controller.pause() : controller.play(),
              ),
              Expanded(
                child: VideoProgressIndicator(
                  controller,
                  allowScrubbing: true,
                  padding: const EdgeInsets.symmetric(vertical: 10),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(
                  '${_fmt(value.position)} / ${_fmt(value.duration)}',
                  style: const TextStyle(fontSize: 11.5, color: Colors.white70),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(h > 0 ? 2 : 1, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}
