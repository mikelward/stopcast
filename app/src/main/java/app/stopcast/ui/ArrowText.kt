package app.stopcast.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

/**
 * The "heading to" arrow a label carries as plain text ("➔ Archway", "Victoria ➔ Warren Street").
 * Labels keep this character so they stay plain, testable strings; [withArrowIcons] draws it as
 * Material's ArrowForward icon, which sits centered on the letters at a lighter weight than any
 * font glyph (maintainer, 2026-09-24).
 */
internal const val ARROW = "➔"

private const val ARROW_ID = "arrow"

// The arrow and the single spaces around it: the icon box brings its own side gaps, so the spaces
// are folded into it rather than doubling the gap.
private val ARROW_WITH_SPACES = Regex(" ?$ARROW ?")

/**
 * [text] with each [ARROW] (and the spaces around it) drawn by [arrowInlineContent]. The replaced
 * characters stay as the placeholder's alternate text, so the semantics text is unchanged.
 */
internal fun withArrowIcons(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in ARROW_WITH_SPACES.findAll(text)) {
        append(text, last, match.range.first)
        appendInlineContent(ARROW_ID, match.value)
        last = match.range.last + 1
    }
    append(text, last, text.length)
}

/**
 * The inline content [withArrowIcons] refers to: ArrowForward in [color], centered on the text. The
 * icon draws its arrow across the middle two-thirds of its box, so a box a little over 1em wide
 * leaves about a space's gap either side.
 */
internal fun arrowInlineContent(color: Color): Map<String, InlineTextContent> = mapOf(
    ARROW_ID to InlineTextContent(Placeholder(1.15.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
        // Fill the placeholder: Icon's default 24dp would overflow a text-sized box and draw off-center.
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
    },
)
