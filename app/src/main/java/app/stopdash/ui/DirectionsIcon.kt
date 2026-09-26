package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material "directions" glyph (a turn arrow in a diamond), inlined as [CrosshairIcon] is, since
 * `Icons.Filled.Directions` lives in `material-icons-extended`. It's the app bar's one-tap *To…*.
 */
internal val DirectionsIcon: ImageVector = ImageVector.Builder(
    name = "Directions",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M21.71 11.29l-9-9c-.39-.39-1.02-.39-1.41 0l-9 9c-.39.39-.39 1.02 0 1.41l9 9c.39.39 1.02.39 " +
            "1.41 0l9-9c.39-.38.39-1.01 0-1.41zM14 14.5V12h-4v3H8v-4c0-.55.45-1 1-1h5V7.5l3.5 3.5-3.5 3.5z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()
