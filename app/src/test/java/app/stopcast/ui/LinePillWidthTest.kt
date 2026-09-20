package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.stopcast.ui.theme.StopCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every line pill is exactly the same width down the column — the fixed label width (see
 * [LinePill]) makes it uniform by construction, so a two-digit bus number, a three-letter tube
 * code, and a four-character bus route all measure the same, at the default font scale and at
 * a large one, and none of them truncates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinePillWidthTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Pills() {
        Surface {
            Row {
                LinePill(lineName = "12", lineId = "12", mode = "bus")
                LinePill(lineName = "Victoria", lineId = "victoria", mode = "tube")
                // Hammersmith & City → "HAM", a wide three-letter code.
                LinePill(lineName = "Hammersmith & City", lineId = "hammersmith-city", mode = "tube")
                // A four-character night-bus route — the widest code lineCode() emits.
                LinePill(lineName = "N550", lineId = "n550", mode = "bus")
            }
        }
    }

    private fun widthOf(label: String) =
        composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot().width.value.toDouble()

    @Test
    fun `every code, two-digit through four-character, is the same width`() {
        composeRule.setContent { StopCastTheme { Pills() } }
        val bus = widthOf("12")
        val tube = widthOf("Victoria")
        val wide = widthOf("Hammersmith & City")
        val route = widthOf("N550")
        assertEquals(tube, bus, 0.5)
        assertEquals(tube, wide, 0.5)
        assertEquals(tube, route, 0.5)
    }

    @Test
    fun `a four-character route code is not truncated`() {
        // The fixed box must hold the widest code the app shows without ellipsizing it —
        // uniform width is worthless if N550 and N551 both collapse to "N5…". Compare the
        // pill against a bare label of the same code, same style and padding but no width
        // cap: if the pill (fixed box) is at least as wide as the unconstrained text, the
        // code fits and nothing is dropped.
        composeRule.setContent {
            StopCastTheme {
                Surface {
                    Row {
                        LinePill(lineName = "N550", lineId = "n550", mode = "bus")
                        Text(
                            text = "N550",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = "ref-N550" },
                        )
                    }
                }
            }
        }
        val pill = widthOf("N550")
        val natural = widthOf("ref-N550")
        assertTrue(
            "the fixed pill ($pill) must hold the full N550 code (natural $natural) without ellipsis",
            pill + 0.5 >= natural,
        )
    }

    @Test
    fun `pills stay uniform and grow with the font scale`() {
        // At 2x font scale the pill scales up (holding a wide code rather than clipping it)
        // and both codes still measure the same width. The default-scale label is 48dp; at
        // 2x it is 96dp, so a width well past the default proves it scaled with the font
        // rather than clipping. Only two pills here: at 2x, four wide pills overflow the test
        // window's width (the app never puts more than one pill in a row), which would clamp
        // the trailing pill and defeat the uniformity check.
        composeRule.setContent {
            StopCastTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    Surface {
                        Row {
                            LinePill(lineName = "12", lineId = "12", mode = "bus")
                            LinePill(lineName = "N550", lineId = "n550", mode = "bus")
                        }
                    }
                }
            }
        }
        val bus2x = widthOf("12")
        val route2x = widthOf("N550")
        assertEquals(bus2x, route2x, 0.5)
        assertTrue("pill should widen at 2x font scale", bus2x > 90.0)
    }
}
