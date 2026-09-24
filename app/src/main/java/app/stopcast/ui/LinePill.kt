package app.stopcast.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopcast.domain.lineCode

/**
 * The line's short [lineCode] (VIC, BAK, …) in its line's color. Three shapes:
 * - a **tube/single-color-mode** line is a filled pill — the code in black/white ([textColorOn]),
 *   the fill the official color, a halo lifting the label on the tightest mid-luminance fills;
 * - a **named Overground** line is a **hollow** pill — the card surface shows through, with the
 *   line accent as the border and the label ([accentInkOn]/[accentEdgeOn], nudged to stay
 *   legible on the surface), so it reads as Overground even where its color is near a tube
 *   line's;
 * - a line/mode with **no defined color** is a neutral pill — theme colors, which already
 *   guarantee their own contrast, so no halo.
 *
 * The full [lineName] is the pill's accessible label, so a screen reader announces "Victoria"
 * rather than "VIC". The label is bold and ellipsizes; the caller caps the width so a long
 * name doesn't starve the countdown.
 */
@Composable
fun LinePill(lineName: String, lineId: String, mode: String, modifier: Modifier = Modifier) {
    val colors = pillColors(lineName, lineId, mode, MaterialTheme.colorScheme.surface)
    val background = when (colors) {
        is PillColors.Solid -> colors.fill
        is PillColors.Hollow -> Color.Transparent
        PillColors.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (colors) {
        is PillColors.Solid -> colors.label
        is PillColors.Hollow -> colors.label
        PillColors.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = when (colors) {
        is PillColors.Solid -> colors.border
        is PillColors.Hollow -> colors.border
        PillColors.Neutral -> MaterialTheme.colorScheme.outlineVariant
    }
    // Zero-offset blurred shadow = a symmetric glow around the glyphs. Only for a solid fill;
    // the hollow and neutral pills read their color off the surface and need no halo.
    val haloBlurPx = with(LocalDensity.current) { 2.dp.toPx() }
    // A fixed label width so every pill is exactly the same size down the column — uniform
    // by construction, not just "no narrower than a floor": a two-digit bus number, a
    // three-letter tube code, and a four-character bus route all render in the same box.
    // The width holds the *widest* code this app shows (see [LINE_PILL_LABEL_WIDTH]), so
    // nothing truncates — a shorter code just gets more centering room. Scaled by the font
    // scale so it still holds those codes at a large accessibility text size rather than
    // clipping them.
    val labelWidth = LINE_PILL_LABEL_WIDTH * LocalDensity.current.fontScale
    val textStyle = MaterialTheme.typography.labelLarge.let { base ->
        if (colors is PillColors.Solid) base.copy(shadow = Shadow(colors.halo, Offset.Zero, haloBlurPx))
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
            text = lineCode(lineName, mode),
            style = textStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fixed width + centered, so every pill is exactly the same width down the column
            // (see [labelWidth]) — a two-digit bus number and a three-letter code sit in the
            // same box instead of stepping ragged.
            textAlign = TextAlign.Center,
            // The visible label is the short code; the accessible label stays the full line
            // name so a screen reader announces "Victoria", not "VIC".
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .width(labelWidth)
                .semantics { contentDescription = lineName },
        )
    }
}

/**
 * The fixed label width every pill shares at the default font scale, before [LinePill] scales
 * it by the current font scale. Sized to the *widest* code [lineCode] produces — a
 * four-character bus route (`N550`, `SL10`), which is wider than any three-letter tube code
 * (VIC, HAM) or three-digit route — so every supported code renders complete; a shorter code
 * just centers with more room. `LinePillWidthTest` pins that a four-character code neither
 * clips nor widens the column past the others.
 */
private val LINE_PILL_LABEL_WIDTH = 48.dp
