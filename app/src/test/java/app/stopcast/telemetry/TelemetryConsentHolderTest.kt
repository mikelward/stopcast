package app.stopcast.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConsentHolderTest {

    private class FakeStore(
        var stored: Boolean?,
        var failWrites: Boolean = false,
        var failDeletes: Boolean = false,
    ) : ConsentStore {
        override fun read() = stored
        override fun save(optedIn: Boolean): Boolean {
            if (failWrites) return false
            stored = optedIn
            return true
        }
        override fun forget(): Boolean {
            if (failDeletes) return false
            stored = null // a delete, which works where the write didn't
            return true
        }
    }

    private class FakeBackend(override var collecting: Boolean, val waiting: Boolean? = false) : TelemetryBackend {
        override fun switchCollection(enabled: Boolean) {
            collecting = enabled
        }
        override fun checkUnsent(result: (Boolean?) -> Unit) = result(waiting)
        override fun discardUnsent() {}
    }

    private class FakePending(var value: Boolean = false) : PendingMarker {
        override fun read() = value
        override fun save(pending: Boolean): Boolean {
            value = pending
            return true
        }
    }

    @Test
    fun `the choice is unknown until loaded, and a fresh install loads as off`() {
        val holder = TelemetryConsentHolder()
        assertNull(holder.state.value)
        val backend = FakeBackend(collecting = false)
        holder.load(FakeStore(stored = null), TelemetryGate(backend, FakePending()))
        assertEquals(false, holder.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `an opted-in install reloads as opted in`() {
        val holder = TelemetryConsentHolder()
        val backend = FakeBackend(collecting = true)
        holder.load(FakeStore(stored = true), TelemetryGate(backend, FakePending()))
        assertEquals(true, holder.state.value)
        assertTrue(backend.collecting)
    }

    @Test
    fun `an opt-out reaches the SDKs before the tap returns`() {
        val holder = TelemetryConsentHolder()
        val store = FakeStore(stored = true)
        val backend = FakeBackend(collecting = true)
        holder.load(store, TelemetryGate(backend, FakePending()))
        holder.set(false)
        assertFalse(backend.collecting)
        assertEquals(false, store.stored)
        assertEquals(false, holder.state.value)
    }

    @Test
    fun `an opt-out whose write failed still loads as off next start`() {
        val store = FakeStore(stored = true)
        val backend = FakeBackend(collecting = true)
        TelemetryConsentHolder().apply { load(store, TelemetryGate(backend, FakePending())) }.run {
            store.failWrites = true
            store.failDeletes = true
            set(false)
        }
        // Next process: the store still says yes, but the SDKs were switched off with the tap.
        store.failWrites = false
        val next = TelemetryConsentHolder()
        next.load(store, TelemetryGate(backend, FakePending()))
        assertEquals(false, next.state.value)
        assertFalse(backend.collecting)
        assertEquals(false, store.stored)
    }

    @Test
    fun `a restored yes on a fresh install asks again`() {
        val store = FakeStore(stored = true)
        val backend = FakeBackend(collecting = false)
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, FakePending()))
        assertEquals(false, holder.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `without Firebase the stored choice is shown as is`() {
        val holder = TelemetryConsentHolder()
        holder.load(FakeStore(stored = true), gate = null)
        assertEquals(true, holder.state.value)
    }

    /** A store that records whether the SDKs were collecting at the moment each value was saved. */
    private class OrderStore(private val backend: FakeBackend) : ConsentStore {
        var stored: Boolean? = null
        val collectingAtSave = mutableListOf<Boolean>()
        override fun read() = stored
        override fun save(optedIn: Boolean): Boolean {
            collectingAtSave += backend.collecting
            stored = optedIn
            return true
        }
    }

    @Test
    fun `a half-done change always leaves the SDKs off`() {
        val backend = FakeBackend(collecting = false)
        val store = OrderStore(backend)
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, FakePending()))
        // Opt-in: stored while the SDKs are still off, so a kill before they start reads as off.
        holder.set(true)
        // Opt-out: the SDKs stop before it's stored, so a kill before the save reads as off too.
        holder.set(false)
        assertEquals(listOf(false, false), store.collectingAtSave)
    }

    @Test
    fun `an opt-in whose write failed is not applied`() {
        val store = FakeStore(stored = false)
        val backend = FakeBackend(collecting = false)
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, FakePending()))
        store.failWrites = true
        holder.set(true)
        assertFalse(backend.collecting)
        assertEquals(false, holder.state.value)
    }

    @Test
    fun `an opt-in pending on a pre-consent crash reloads as opted in`() {
        val store = FakeStore(stored = false)
        val backend = FakeBackend(collecting = false, waiting = true)
        val pending = FakePending()
        TelemetryConsentHolder().run {
            load(store, TelemetryGate(backend, pending))
            set(true)
        }
        assertFalse(backend.collecting)
        // Next launch: still yes, not reset as a half-done switch.
        val next = TelemetryConsentHolder()
        next.load(store, TelemetryGate(backend, pending))
        assertEquals(true, next.state.value)
        assertEquals(true, store.stored)
    }

    @Test
    fun `a pending opt-in that can't be saved is withdrawn, not left showing on`() {
        val store = FakeStore(stored = false)
        val backend = FakeBackend(collecting = false, waiting = true)
        val pending = object : PendingMarker {
            override fun read() = false
            override fun save(pending: Boolean) = !pending // saving "pending" fails
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, pending))
        holder.set(true)
        assertEquals(false, holder.state.value)
        assertEquals(false, store.stored)
        assertFalse(backend.collecting)
    }

    @Test
    fun `an unreadable store fails closed, with the switch still usable`() {
        val backend = FakeBackend(collecting = true)
        val broken = object : ConsentStore {
            override fun read(): Boolean? = throw IllegalStateException("prefs")
            override fun save(optedIn: Boolean) = true
        }
        val holder = TelemetryConsentHolder()
        holder.load(broken, TelemetryGate(backend, FakePending()))
        assertEquals(false, holder.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `a load that can't run at all still switches the SDKs off`() {
        val backend = FakeBackend(collecting = true)
        val holder = TelemetryConsentHolder()
        holder.loadFailed(TelemetryGate(backend, FakePending()))
        assertEquals(false, holder.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `an opt-in after a failed load is not applied, with no store to record it`() {
        val backend = FakeBackend(collecting = false)
        val holder = TelemetryConsentHolder()
        holder.loadFailed(TelemetryGate(backend, FakePending()))
        holder.set(true)
        assertEquals(false, holder.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `a pending opt-in withdrawn while every other step fails can't come back`() {
        val store = FakeStore(stored = false)
        val pending = FakePending()
        var collecting = false
        val backend = object : TelemetryBackend {
            override val collecting get() = collecting
            override fun switchCollection(enabled: Boolean) {
                if (!enabled) throw IllegalStateException("sdk")
                collecting = true
            }
            override fun checkUnsent(result: (Boolean?) -> Unit) = result(true) // a pre-consent crash
            override fun discardUnsent() {}
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, pending))
        holder.set(true)
        assertTrue(pending.value)
        // The withdrawal's SDK switch throws and its store write and delete fail; only the marker clears.
        store.failWrites = true
        store.failDeletes = true
        holder.set(false)
        assertEquals(true, store.stored)
        assertFalse(pending.value)
        // Next launch: the stale yes isn't vouched for by a pending marker, so it loads as off.
        val next = TelemetryConsentHolder()
        next.load(FakeStore(stored = store.stored), TelemetryGate(FakeBackend(collecting = false), pending))
        assertEquals(false, next.state.value)
    }

    @Test
    fun `an opt-in whose late completion throws is withdrawn, with the SDKs off`() {
        val store = FakeStore(stored = false)
        var answer: ((Boolean?) -> Unit)? = null
        var analytics = false
        val backend = object : TelemetryBackend {
            override var collecting = false
            override fun switchCollection(enabled: Boolean) {
                analytics = enabled
                if (enabled) throw IllegalStateException("crashlytics")
                collecting = false
            }
            override fun checkUnsent(result: (Boolean?) -> Unit) {
                answer = result // answered later, after set() has returned
            }
            override fun discardUnsent() {}
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, FakePending()))
        holder.set(true)
        assertEquals(true, holder.state.value)
        answer!!(false)
        assertEquals(false, holder.state.value)
        assertEquals(false, store.stored)
        assertFalse(analytics)
    }

    @Test
    fun `a withdrawal that can clear neither the marker nor write off deletes the stored yes`() {
        val store = FakeStore(stored = false)
        val backend = FakeBackend(collecting = false, waiting = true)
        val stuck = object : PendingMarker {
            var value = false
            override fun read() = value
            override fun save(pending: Boolean): Boolean {
                if (!pending) return false // clearing fails
                value = true
                return true
            }
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, stuck))
        holder.set(true)
        assertTrue(stuck.read())
        store.failWrites = true
        holder.set(false)
        assertTrue(stuck.read())
        assertNull(store.stored)
        // Next launch: the leftover marker has no yes to vouch for.
        val next = TelemetryConsentHolder()
        next.load(store, TelemetryGate(FakeBackend(collecting = false), stuck))
        assertEquals(false, next.state.value)
        assertFalse(backend.collecting)
    }

    @Test
    fun `an opted-in start whose SDKs throw both ways stores off, so the next start reads off`() {
        val store = FakeStore(stored = true)
        val backend = object : TelemetryBackend {
            override val collecting = true
            override fun switchCollection(enabled: Boolean) = throw IllegalStateException("sdk")
            override fun checkUnsent(result: (Boolean?) -> Unit) = result(false)
            override fun discardUnsent() {}
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(backend, FakePending()))
        assertEquals(false, holder.state.value)
        assertEquals(false, store.stored)
    }

    @Test
    fun `a withdrawal whose SDK switch throws still stores and shows off`() {
        val store = FakeStore(stored = true)
        val throwing = object : TelemetryBackend {
            override val collecting = true
            override fun switchCollection(enabled: Boolean) {
                if (!enabled) throw IllegalStateException("sdk")
            }
            override fun checkUnsent(result: (Boolean?) -> Unit) = result(false)
            override fun discardUnsent() {}
        }
        val holder = TelemetryConsentHolder()
        holder.load(store, TelemetryGate(throwing, FakePending()))
        holder.set(false)
        assertEquals(false, holder.state.value)
        assertEquals(false, store.stored)
    }

    @Test
    fun `a withdrawal closes the log sink before the SDKs clear their reports`() {
        val holder = TelemetryConsentHolder()
        val stateAtClear = mutableListOf<Boolean?>()
        val backend = object : TelemetryBackend {
            override var collecting = true
            override fun switchCollection(enabled: Boolean) {
                collecting = enabled
            }
            override fun checkUnsent(result: (Boolean?) -> Unit) = result(false)
            override fun discardUnsent() {
                stateAtClear += holder.state.value
            }
        }
        holder.load(FakeStore(stored = true), TelemetryGate(backend, FakePending()))
        holder.set(false)
        // The sink reads the state at delivery; it already said no when the reports were cleared.
        assertEquals(listOf<Boolean?>(false), stateAtClear)
    }
}
