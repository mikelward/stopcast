package app.trackmo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Official TfL Color Standard hexes for the traditional Underground lines, keyed by
 * TfL's `lineId`. These are the long-stable line colors (Northern black, Central red,
 * Piccadilly dark blue, …). Newer or multi-color modes — the Elizabeth line, the
 * individually-named Overground lines, trams, DLR — are deliberately absent: they fall
 * back to a neutral pill rather than ship a shade this isn't sure of, and get added
 * here once their exact hex is confirmed against TfL's standard.
 */
private val tubeLineColors: Map<String, Color> = mapOf(
    "bakerloo" to Color(0xFFB36305),
    "central" to Color(0xFFE32017),
    "circle" to Color(0xFFFFD300),
    "district" to Color(0xFF00782A),
    "hammersmith-city" to Color(0xFFF3A9BB),
    "jubilee" to Color(0xFFA0A5A9),
    "metropolitan" to Color(0xFF9B0056),
    "northern" to Color(0xFF000000),
    "piccadilly" to Color(0xFF003688),
    "victoria" to Color(0xFF0098D4),
    "waterloo-city" to Color(0xFF95CDBA),
)

/** London buses are red — TfL's corporate/roundel red. */
private val londonBusRed = Color(0xFFDC241F)

/**
 * The pill fill color for a service, or `null` when its line/mode has no defined color
 * yet (so the caller shows a neutral pill). A tube line resolves by [lineId]; any bus
 * resolves to London-bus red by [mode]; everything else is unmapped for now.
 */
fun lineFillColor(lineId: String, mode: String): Color? =
    tubeLineColors[lineId] ?: londonBusRed.takeIf { mode.equals("bus", ignoreCase = true) }

/**
 * Black or white text, whichever has the higher WCAG contrast ratio against [fill]. A
 * plain 0.5 luminance split picks white too eagerly — a mid-luminance fill like Victoria
 * blue or Jubilee gray then drops below the 4.5:1 floor on white when black clears it —
 * so this compares the two directly: contrast to black is `(L + 0.05) / 0.05`, to white
 * `1.05 / (L + 0.05)`, and black wins from about L = 0.179 up.
 */
fun textColorOn(fill: Color): Color {
    val l = fill.luminance()
    val contrastToBlack = (l + 0.05f) / 0.05f
    val contrastToWhite = 1.05f / (l + 0.05f)
    return if (contrastToBlack >= contrastToWhite) Color.Black else Color.White
}

/**
 * The line name in its line's color — a filled pill, so the list scans by line at a
 * glance. An outline defines every pill and, in particular, keeps a black Northern pill
 * visible against the dark theme's near-black surface; the text color flips to stay
 * legible on the fill. A line/mode with no defined color (see [lineFillColor]) shows a
 * neutral pill rather than an invented one.
 */
@Composable
fun LinePill(lineName: String, lineId: String, mode: String, modifier: Modifier = Modifier) {
    val fill = lineFillColor(lineId, mode)
    val background = fill ?: MaterialTheme.colorScheme.surfaceVariant
    val content = if (fill == null) MaterialTheme.colorScheme.onSurfaceVariant else textColorOn(fill)
    Surface(
        color = background,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Text(
            text = lineName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
