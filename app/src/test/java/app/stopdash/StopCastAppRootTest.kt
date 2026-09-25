package app.stopdash

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the dark-mode contrast fix: [StopCastAppRoot] must wrap content in a themed `Surface`,
 * so a screen without its own background paints on `colorScheme.surface` and inherits
 * `onSurface` as its content color. Drop the Surface and `LocalContentColor` falls back to the
 * black default — the unreadable-in-dark-mode bug this PR fixes. `LocationGateScreenshotTest`
 * can't catch that (it installs its own Surface), so this asserts the invariant directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StopCastAppRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `wraps content in a themed surface so content color is onSurface`() {
        var contentColor = Color.Unspecified
        var onSurface = Color.Unspecified
        composeRule.setContent {
            StopCastAppRoot {
                contentColor = LocalContentColor.current
                onSurface = MaterialTheme.colorScheme.onSurface
            }
        }
        composeRule.waitForIdle()
        assertEquals(
            "content sits on the theme surface, inheriting onSurface as its content color",
            onSurface,
            contentColor,
        )
    }
}
