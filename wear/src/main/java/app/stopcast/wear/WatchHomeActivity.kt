package app.stopcast.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The watch app's entry point. Renders from the stored envelope at once; the read runs off-thread. */
class WatchHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchEnvelopeStore.from(this)
        lifecycleScope.launch(Dispatchers.IO) {
            store.load()
            WatchRefresh.resume(this@WatchHomeActivity)
            // Each time the app comes to the front it looks up the phone's latest item, so an
            // update the listener couldn't read is picked up; a lookup that fails (logged) is
            // retried a few times while the app stays open.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Opening the app asks the phone for fresh departures (debounced).
                WatchRefresh.request(this@WatchHomeActivity)
                var looked = ingestExisting(this@WatchHomeActivity, store)
                for (wait in LOOKUP_RETRIES) {
                    if (looked) break
                    delay(wait)
                    looked = ingestExisting(this@WatchHomeActivity, store)
                }
            }
        }
        setContent {
            val received by store.state.collectAsStateWithLifecycle()
            val home = remember(received) { watchHome(received) }
            val refresh by WatchRefresh.state.collectAsStateWithLifecycle()
            // The refresh notice, dropped when it expires without waiting for another change.
            val notice by produceState<RefreshNotice?>(null, refresh) {
                while (true) {
                    val current = RefreshPolicy.notice(refresh, Instant.now())
                    value = current
                    current ?: break
                    delay(Duration.between(Instant.now(), current.until).toMillis().coerceAtLeast(0) + 1)
                }
            }
            WatchHomeScreen(home, notice?.kind) { WatchRefresh.request(this@WatchHomeActivity) }
        }
    }

    private companion object {
        /** The waits before each retry; after the last, the next start (or a publish) tries again. */
        val LOOKUP_RETRIES = listOf(5.seconds, 15.seconds, 45.seconds)
    }
}
