package app.trackmo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Official TfL line colors for the traditional Underground lines, keyed by TfL's
 * `lineId` — the long-stable colors (Northern black, Central red, Piccadilly dark blue,
 * …). Single-color *modes* (bus, DLR, Elizabeth, Overground, tram) resolve by mode
 * instead (see [modeColors]), since they share one color across every line of the mode.
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

/**
 * Single-color modes, keyed by TfL's `modeName` — every line of the mode shares the color,
 * so these resolve by mode rather than by line id. Buses are TfL's roundel red; DLR
 * turquoise, the Elizabeth line purple, and London Trams green are their TfL line colors.
 *
 * **Overground is a deliberate placeholder**: since the 2024 renaming each named Overground
 * line has its own color *and* a two-tone scheme the single-fill pill can't render, so every
 * Overground line shows the legacy single orange until a two-color pill lands (see SPEC /
 * TODO). Anything not here (e.g. national rail) falls back to a neutral pill.
 */
private val modeColors: Map<String, Color> = mapOf(
    "bus" to Color(0xFFDC241F),
    "dlr" to Color(0xFF00A4A7),
    "elizabeth-line" to Color(0xFF6950A1),
    "overground" to Color(0xFFEE7C0E),
    "tram" to Color(0xFF84B817),
    "trams" to Color(0xFF84B817),
)

/**
 * The pill fill color for a service, or `null` when its line/mode has no defined color yet
 * (so the caller shows a neutral pill). A tube line resolves by [lineId]; a single-color
 * mode (bus, DLR, Elizabeth, Overground, tram) resolves by [mode]; everything else is
 * unmapped for now.
 */
fun lineFillColor(lineId: String, mode: String): Color? =
    tubeLineColors[lineId] ?: modeColors[mode.lowercase()]

/**
 * Black or white text, whichever **APCA** rates as higher-contrast on [fill]. APCA (the
 * perceptual model headed into WCAG 3) is used instead of the WCAG-2 contrast ratio
 * because that ratio is luminance-only and misreads white on saturated mid-tones: it puts
 * black on Victoria blue, DLR turquoise and Bakerloo brown, where white is clearly the more
 * readable choice to the eye. APCA models polarity and lightness and agrees with the eye on
 * those. The chosen picks are recorded in `AGENTS.md` / `SPEC.md`; the bold weight and halo
 * add real margin neither model credits. Ties (near-neutral fills) fall to black.
 */
fun textColorOn(fill: Color): Color {
    val onBlack = apcaLc(textLuminance = BLACK_APCA_Y, backgroundLuminance = apcaLuminance(fill))
    val onWhite = apcaLc(textLuminance = WHITE_APCA_Y, backgroundLuminance = apcaLuminance(fill))
    return if (onBlack >= onWhite) Color.Black else Color.White
}

/**
 * APCA screen luminance for a color: a plain 2.4-power of each sRGB channel (APCA's own
 * transfer curve, not WCAG's piecewise one), weighted by the same coefficients.
 */
private fun apcaLuminance(color: Color): Double =
    0.2126 * Math.pow(color.red.toDouble(), 2.4) +
        0.7152 * Math.pow(color.green.toDouble(), 2.4) +
        0.0722 * Math.pow(color.blue.toDouble(), 2.4)

private val BLACK_APCA_Y = apcaLuminance(Color.Black)
private val WHITE_APCA_Y = apcaLuminance(Color.White)

/**
 * Absolute APCA lightness contrast (Lc, 0–~108) of a text luminance on a background
 * luminance — the W3 APCA-0.1.9 constants: a soft black clamp, polarity-aware exponents,
 * and the low-contrast clip. Magnitude only, since [textColorOn] just compares the two
 * candidates; higher is more readable.
 */
private fun apcaLc(textLuminance: Double, backgroundLuminance: Double): Double {
    fun clamp(y: Double) = if (y < 0.022) y + Math.pow(0.022 - y, 1.414) else y
    val txt = clamp(textLuminance)
    val bg = clamp(backgroundLuminance)
    if (Math.abs(bg - txt) < 0.0005) return 0.0
    val contrast = if (bg > txt) {
        val sapc = (Math.pow(bg, 0.56) - Math.pow(txt, 0.57)) * 1.14
        if (sapc < 0.1) 0.0 else sapc - 0.027
    } else {
        val sapc = (Math.pow(bg, 0.65) - Math.pow(txt, 0.62)) * 1.14
        if (sapc > -0.1) 0.0 else sapc + 0.027
    }
    return Math.abs(contrast * 100)
}

/**
 * A soft halo tone for label [text] over a colored fill — the opposite of the text, at
 * partial alpha. On the mid-luminance fills where black or white only just clears the
 * contrast floor (Bakerloo brown is ~4.7:1), a faint glow in the opposite tone lifts the
 * label off the fill without darkening or lightening the official color itself. The alpha
 * keeps it a lift, not a second visible outline.
 */
fun haloFor(text: Color): Color =
    (if (text == Color.Black) Color.White else Color.Black).copy(alpha = HALO_ALPHA)

private const val HALO_ALPHA = 0.72f

/**
 * A border tone for a colored pill: the fill nudged toward its own contrasting text color,
 * so every pill stays outlined against the card in both themes — a light-ish fill (Circle
 * yellow, most lines) darkens into a defined edge on a light card, while a near-black fill
 * (Northern) lightens into a gray edge that survives the dark theme's near-black surface. A
 * dark fill already separates from a light card by its own darkness, and a light fill from
 * a dark card, so nudging toward the text color always moves the edge the useful way. Tied
 * to the line's color rather than a flat neutral, so the outline reads as part of the line.
 */
fun borderColorOn(fill: Color): Color = lerp(fill, textColorOn(fill), BORDER_BLEND)

private const val BORDER_BLEND = 0.4f

/**
 * The line name in its line's color — a filled pill, so the list scans by line at a
 * glance. An outline defines every pill and, in particular, keeps a black Northern pill
 * visible against the dark theme's near-black surface; the text color flips to stay
 * legible on the fill, with a halo lifting it off the mid-luminance fills where the
 * contrast is tightest (Bakerloo brown, where WCAG makes black-vs-white a near-tie). The
 * label is bold, and the outline is the line's own color nudged for contrast (see
 * [borderColorOn]) so it reads as part of the line. A line/mode with no defined color (see
 * [lineFillColor]) shows a neutral pill rather than an invented one — a neutral theme
 * outline and no halo, since the theme already guarantees its contrast.
 */
@Composable
fun LinePill(lineName: String, lineId: String, mode: String, modifier: Modifier = Modifier) {
    val fill = lineFillColor(lineId, mode)
    val background = fill ?: MaterialTheme.colorScheme.surfaceVariant
    val content = if (fill == null) MaterialTheme.colorScheme.onSurfaceVariant else textColorOn(fill)
    val borderColor = if (fill == null) MaterialTheme.colorScheme.outlineVariant else borderColorOn(fill)
    // Zero-offset blurred shadow = a symmetric glow around the glyphs. Only for a defined
    // fill; the neutral pill's theme colors are already contrast-safe.
    val haloBlurPx = with(LocalDensity.current) { 2.dp.toPx() }
    val textStyle = MaterialTheme.typography.labelLarge.let { base ->
        if (fill != null) base.copy(shadow = Shadow(haloFor(content), Offset.Zero, haloBlurPx))
        else base
    }
    Surface(
        color = background,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, borderColor),
        // The caller caps the width (to a fraction of the card) so a long name at a large
        // font ellipsizes rather than starving the countdown; the label already ellipsizes.
        modifier = modifier,
    ) {
        Text(
            text = lineName,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
