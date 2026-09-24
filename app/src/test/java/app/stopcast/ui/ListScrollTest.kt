package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [rememberListStateFor]: the position follows its content, and survives the content being unknown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var key by mutableStateOf<String?>("A")
    private lateinit var state: LazyListState

    private fun setUp(restoration: StateRestorationTester) {
        restoration.setContent {
            state = rememberListStateFor(key)
            LazyColumn(state = state) { items(100) { Text("Item $it") } }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { runBlocking { state.scrollToItem(40) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `the same content keeps its position and new content starts at the top`() {
        setUp(StateRestorationTester(composeRule))
        key = "A"
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(40, state.firstVisibleItemIndex) }

        key = "B"
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    @Test
    fun `a restore keeps its position through a spell of unknown content back to the same`() {
        val restoration = StateRestorationTester(composeRule)
        setUp(restoration)
        // Process recreation: the content is briefly unknown while location resolves.
        key = null
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        key = "A"
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(40, state.firstVisibleItemIndex) }
    }
}
