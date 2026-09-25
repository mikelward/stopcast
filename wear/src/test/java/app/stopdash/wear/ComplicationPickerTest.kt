package app.stopdash.wear

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.StarredRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The complication row picker's behavior: synthetic rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class ComplicationPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val row = StarredRow("940GA", "victoria", "inbound")
    private val choices = listOf(ComplicationChoice(row, "VIC", "Brixton", "Oxford Circus"))

    @Test
    fun `nothing can be picked until the current pick has loaded`() {
        val picks = mutableListOf<StarredRow?>()
        val state = mutableStateOf<PickState>(PickState.Loading)
        compose.setContent { ComplicationPickerScreen(choices, state.value) { picks += it } }

        compose.onNodeWithText("Top row").assertIsNotEnabled().performClick()
        compose.onNodeWithText("VIC · Brixton").assertIsNotEnabled().performClick()
        assertEquals(emptyList<StarredRow?>(), picks)

        state.value = PickState.Loaded(row)
        compose.onNodeWithText("Top row").assertIsEnabled().performClick()
        assertEquals(listOf<StarredRow?>(null), picks)
    }
}
