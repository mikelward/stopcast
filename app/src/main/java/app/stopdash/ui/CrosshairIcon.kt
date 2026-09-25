package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material "my location" crosshair glyph, inlined. It's the same artwork as
 * `Icons.Filled.MyLocation`, but that icon lives in `material-icons-extended`; the app
 * depends only on `material-icons-core` (SPEC *Cost*), so pulling the whole extended set
 * in for one glyph isn't worth it. It's the app bar's "use my location" button. The path is
 * Material's, tinted by `Icon` at the call site like any core icon.
 */
internal val CrosshairIcon: ImageVector = ImageVector.Builder(
    name = "MyLocation",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48" +
            "-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 " +
            "7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3." +
            "13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()
