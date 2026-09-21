package app.stopcast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// StopCast's brand accent is red — a nod to the London transit palette — applied the
// Material way: it seeds `primary` (and its container/secondary/tertiary partners), so it
// surfaces as an accent on buttons, the refresh and progress indicators, and selection,
// over otherwise-neutral surfaces. It is deliberately *not* the app-bar container, to keep
// the red an accent rather than a wash. Tonal values are the Material 3 red-seed scheme, so
// contrast against the on-* roles holds in both themes. First pass at the brand color
// (maintainer, 2026-09-19) — revisit freely; see TODO.md.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF725B2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEDFA6),
    onTertiaryContainer = Color(0xFF261A00),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFE0C38C),
    onTertiary = Color(0xFF3F2D04),
    tertiaryContainer = Color(0xFF584419),
    onTertiaryContainer = Color(0xFFFEDFA6),
)

// The border color that marks a starred (pinned-to-top) departure card — gold, so the pin
// reads as a mark of favor and stays distinct from the red brand accent. Two tones so it holds
// contrast on each card surface: a deeper gold on the light surface, a brighter one on the dark.
// Provided through [LocalStarredBorderColor] from the resolved theme, so a forced-dark preview
// (screenshot tests) gets the dark gold rather than following the system.
private val LightStarredBorder = Color(0xFFB8860B)
private val DarkStarredBorder = Color(0xFFE7C34C)

/** The gold border for a starred card, resolved to the current theme (see [StopCastTheme]). */
val LocalStarredBorderColor = staticCompositionLocalOf { LightStarredBorder }

/**
 * The app theme. Uses the stopcast red-accent scheme (light or dark by the system setting).
 *
 * Dynamic color (Material You) is **off by default**: it would let the device wallpaper
 * override the brand — which is exactly why the app read as a neutral charcoal on-device —
 * so the red identity is shown consistently instead. A caller can still opt into dynamic
 * color, and it is the one thing to flip if the brand ever yields to Material You.
 */
@Composable
fun StopCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val starredBorder = if (darkTheme) DarkStarredBorder else LightStarredBorder
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalStarredBorderColor provides starredBorder, content = content)
    }
}
