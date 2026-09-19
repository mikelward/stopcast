package app.trackmo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material **star_border** glyph (an outlined star) vendored as an [ImageVector], because
 * `material-icons-core` ships the filled `Icons.Filled.Star` but not its outline, and pulling
 * in `material-icons-extended` for one icon is a large dependency for a single glyph. The
 * filled state uses core's `Star`; this is its matching outline for the unstarred state, so the
 * two toggle states are the same star shape. Same vendoring approach as the locate-button
 * crosshair — the path is Google's own 24dp `star_border` path data.
 */
val StarBorderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "StarBorder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21" +
                    "l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1" +
                    "l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
