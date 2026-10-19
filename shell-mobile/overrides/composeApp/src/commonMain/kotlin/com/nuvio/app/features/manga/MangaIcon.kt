package com.nuvio.app.features.manga

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The manga tab's mark: an open book, drawn here rather than pulled from an
 * icon pack.
 *
 * The navigation bar tints whatever it is given, so the shape is filled black
 * and carries no colour of its own. The spine is the gap the two pages leave
 * between them rather than a line drawn over the fill, which keeps the mark
 * legible at the bar's small size.
 */
internal val MangaIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MangaBook",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Left page, swept up from the gutter to the spine.
            moveTo(11.2f, 6.1f)
            curveTo(9.7f, 4.9f, 7.6f, 4.2f, 5.2f, 4.2f)
            lineTo(3.2f, 4.2f)
            lineTo(3.2f, 17.9f)
            lineTo(5.3f, 17.9f)
            curveTo(7.5f, 17.9f, 9.6f, 18.5f, 11.2f, 19.7f)
            close()
            // Right page, mirrored.
            moveTo(12.8f, 6.1f)
            curveTo(14.3f, 4.9f, 16.4f, 4.2f, 18.8f, 4.2f)
            lineTo(20.8f, 4.2f)
            lineTo(20.8f, 17.9f)
            lineTo(18.7f, 17.9f)
            curveTo(16.5f, 17.9f, 14.4f, 18.5f, 12.8f, 19.7f)
            close()
        }
    }.build()
}
