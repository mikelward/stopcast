package app.stopcast

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A model made inside a per-set store can take a [SavedStateHandle] once the store's owner carries
 * the activity's creation extras. Without them `createSavedStateHandle()` throws — the crash on
 * To… from a searched station, whose To search is created inside that station's store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NearbyDeparturesStoresSavedStateTest {
    class HandleHolder(val handle: SavedStateHandle) : ViewModel()

    private val factory = viewModelFactory { initializer { HandleHolder(createSavedStateHandle()) } }

    @Test
    fun `a store given the activity's defaults can create a SavedStateHandle`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val owner = NearbyDeparturesStores().ownerFor("a", activity)

        val holder = ViewModelProvider.create(owner, factory)["from-to-search", HandleHolder::class]
        holder.handle["query"] = "x"

        assertEquals("x", ViewModelProvider.create(owner, factory)["from-to-search", HandleHolder::class].handle["query"])
    }

    @Test
    fun `a bare store cannot`() {
        val owner = NearbyDeparturesStores().ownerFor("a")
        assertThrows(IllegalArgumentException::class.java) {
            ViewModelProvider.create(owner, factory)["from-to-search", HandleHolder::class]
        }
    }
}
