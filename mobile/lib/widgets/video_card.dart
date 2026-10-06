import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../models.dart';

class VideoCard extends StatelessWidget {
  const VideoCard({super.key, required this.video, required this.onTap});

  final Video video;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Ink(
        decoration: BoxDecoration(
          color: theme.cardColor,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: Colors.white10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: 16 / 10,
              child: ClipRRect(
                borderRadius: const BorderRadius.vertical(top: Radius.circular(13)),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (video.cover != null)
                      CachedNetworkImage(
                        imageUrl: video.cover!,
                        fit: BoxFit.cover,
                        // Covers are decorative; a failure should not tear the
                        // grid layout, so fall back to a flat placeholder.
                        placeholder: (_, __) => const ColoredBox(color: Color(0xFF1C1C26)),
                        errorWidget: (_, __, ___) => const ColoredBox(color: Color(0xFF1C1C26)),
                      )
                    else
                      const ColoredBox(color: Color(0xFF1C1C26)),
                    Positioned(
                      left: 7,
                      bottom: 7,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: .72),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          formatCount(video.views),
                          style: const TextStyle(fontSize: 11, color: Colors.white),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 9, 10, 11),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    video.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, height: 1.3),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    [
                      if (video.brand != null && video.brand!.isNotEmpty) video.brand!,
                      if (video.releasedAt != null) video.releasedAt!.substring(0, 4),
                    ].join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11.5, color: theme.hintColor),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
