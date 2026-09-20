package app.trackmo.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings wrapper's read/write mapping over a fake in-memory [DataStore], so no Android
 * file or Context is needed (the JSON serialization is a plain default-bearing shape). Mirrors
 * [DataStoreStarredRowsStoreTest]. A missing file reads the documented default; a write persists
 * and re-emits.
 */
class DataStoreAppSettingsTest {
    private class FakeDataStore(initial: PersistedSettings?) : DataStore<PersistedSettings?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedSettings?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedSettings?) -> PersistedSettings?,
        ): PersistedSettings? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `live widget refresh reads the default when nothing is stored`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertFalse(store.liveWidgetRefresh().first())
    }

    @Test
    fun `setting live widget refresh on persists and re-emits`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        store.setLiveWidgetRefresh(true)
        assertTrue(store.liveWidgetRefresh().first())
    }

    @Test
    fun `setting live widget refresh off persists`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(liveWidgetRefresh = true)))
        assertTrue(store.liveWidgetRefresh().first())
        store.setLiveWidgetRefresh(false)
        assertFalse(store.liveWidgetRefresh().first())
    }

    @Test
    fun `the documented default is off`() {
        assertEquals(false, DataStoreAppSettings.DEFAULT_LIVE_WIDGET_REFRESH)
    }
}
