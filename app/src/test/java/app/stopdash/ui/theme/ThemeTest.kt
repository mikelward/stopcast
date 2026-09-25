package app.stopdash.ui.theme

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
 * Pins the brand: [StopDashTheme]'s red `primary` in both themes, and that dynamic color is
 * **off by default** so the wallpaper can't override the brand (the reason the app read as a
 * neutral charcoal on-device before). The screenshot job only records — it doesn't diff — so
 * these assertions, not a snapshot, are what fail if the palette or the default regresses.
 * Reads the resolved `MaterialTheme.colorScheme` from inside the theme; no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun primaryUnder(darkTheme: Boolean, dynamicColor: Boolean?): Color {
        var primary = Color.Unspecified
        composeRule.setContent {
            val capture: @androidx.compose.runtime.Composable () -> Unit = {
                primary = MaterialTheme.colorScheme.primary
            }
            if (dynamicColor == null) {
                StopDashTheme(darkTheme = darkTheme) { capture() }
            } else {
                StopDashTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) { capture() }
            }
        }
        composeRule.waitForIdle()
        return primary
    }

    @Test
    fun `light theme uses the red brand primary`() {
        assertEquals(Color(0xFFB3261E), primaryUnder(darkTheme = false, dynamicColor = false))
    }

    @Test
    fun `dark theme uses the red brand primary`() {
        assertEquals(Color(0xFFFFB4A9), primaryUnder(darkTheme = true, dynamicColor = false))
    }

    @Test
    fun `dynamic color is off by default, so the brand red shows`() {
        // With the default dynamicColor, a `true` default would resolve the wallpaper-derived
        // dynamic scheme on this API level instead of the brand red; getting the red back
        // proves the default is off.
        assertEquals(Color(0xFFB3261E), primaryUnder(darkTheme = false, dynamicColor = null))
    }
}
