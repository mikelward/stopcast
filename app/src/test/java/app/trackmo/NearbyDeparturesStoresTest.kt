package app.trackmo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NearbyDeparturesStores] retains a nearby set's store across recomposition (so a rotation
 * doesn't rebuild the departures ViewModel and re-fetch) and clears a set the user moved away
 * from (so an old location's models and its in-flight fetch don't pile up).
 */
class SentinelViewModel : ViewModel() {
    var cleared = false
    public override fun onCleared() {
        cleared = true
    }
}

class NearbyDeparturesStoresTest {

    @Test
    fun `the same key reuses the same store`() {
        val holder = NearbyDeparturesStores()
        assertSame(holder.ownerFor("a").viewModelStore, holder.ownerFor("a").viewModelStore)
    }

    @Test
    fun `switching keys clears the previous set's models`() {
        val holder = NearbyDeparturesStores()
        val ownerA = holder.ownerFor("a")
        val vm = ViewModelProvider(ownerA, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]
        assertFalse(vm.cleared)

        // Moving to a new set clears the old one's store, running onCleared (which cancels
        // that MainViewModel's viewModelScope and any in-flight fetch in production).
        holder.ownerFor("b")
        assertTrue(vm.cleared)
    }
}
