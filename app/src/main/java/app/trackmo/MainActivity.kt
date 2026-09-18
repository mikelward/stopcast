package app.trackmo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackmo.data.DataStoreSnapshotStore
import app.trackmo.data.KtorTflClient
import app.trackmo.domain.LineRef
import app.trackmo.ui.MainScreen
import app.trackmo.ui.MainViewModel
import app.trackmo.ui.StopRef
import app.trackmo.ui.theme.TrackmoTheme
import java.time.Instant
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    client = KtorTflClient(httpClient),
                    seedStops = SEED_STOPS,
                    snapshotStore = DataStoreSnapshotStore.from(applicationContext),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackmoTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
                RefreshOnForeground(viewModel)
                MainScreen(
                    state = state,
                    now = tickingNow(),
                    onRefresh = viewModel::refresh,
                    refreshing = refreshing,
                )
            }
        }
    }

    companion object {
        // Process-scoped: one OkHttp engine and connection pool shared by every
        // MainViewModel the process creates, rather than a fresh client leaked per
        // ViewModel (nothing closes a Ktor client, so a per-launch one accumulates
        // engine/pool resources). A single long-lived client is OkHttp's own
        // recommended shape; it lives for the process and dies with it.
        private val httpClient by lazy { KtorTflClient.defaultHttpClient() }

        // A temporary public-station seed until Phase 2 adds watched stops the user
        // chooses (SPEC D1). Public infrastructure, not anyone's saved route. The `lines`
        // are the stations' served tube lines (public facts), carried so a suspended line
        // that returns no arrivals still surfaces as a status row (SPEC *Departures*);
        // Phase 2's watched stops will carry this from TfL's own stop→line data.
        private fun tube(id: String, name: String) = LineRef(id = id, name = name, mode = "tube")

        private val SEED_STOPS = listOf(
            StopRef(
                id = "940GZZLUOXC",
                name = "Oxford Circus",
                lines = listOf(
                    tube("bakerloo", "Bakerloo"),
                    tube("central", "Central"),
                    tube("victoria", "Victoria"),
                ),
            ),
            StopRef(
                id = "940GZZLUKSX",
                name = "King's Cross St. Pancras",
                lines = listOf(
                    tube("circle", "Circle"),
                    tube("hammersmith-city", "Hammersmith & City"),
                    tube("metropolitan", "Metropolitan"),
                    tube("northern", "Northern"),
                    tube("piccadilly", "Piccadilly"),
                    tube("victoria", "Victoria"),
                ),
            ),
        )
    }
}

/**
 * Refresh when the user returns to the foregrounded screen (SPEC D6: refresh on open),
 * so they don't come back from Recents to withheld stale countdowns and have to refresh
 * by hand. The ViewModel's `init` does the first load and survives configuration change,
 * so the **first** foreground per activity instance is skipped — only a genuine return
 * from the background triggers a re-fetch, never a duplicate of the initial request nor
 * a refetch on rotation. (Lifecycle wiring: verified by inspection, wants a device check.)
 */
@Composable
private fun RefreshOnForeground(viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        refreshOnForeground(lifecycleOwner.lifecycle) { viewModel.refresh() }
    }
}

/**
 * Runs [onForeground] each time [lifecycle] re-enters STARTED **except the first**: the
 * first foreground is the initial load (done in the ViewModel's `init`, and re-run on a
 * fresh activity after process death), and a configuration change restarts this with its
 * own first-skip, so neither path double-fetches while a genuine return from the
 * background does refresh (SPEC D6). Extracted so the skip-first/return-again rule is
 * unit-testable off a device.
 */
internal suspend fun refreshOnForeground(lifecycle: Lifecycle, onForeground: () -> Unit) {
    var firstForeground = true
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (firstForeground) firstForeground = false else onForeground()
    }
}

/**
 * A clock that advances on screen so countdowns and the freshness stamp recompute
 * without a new fetch (SPEC D4). Ten seconds is enough to keep "N min"/"Due" honest
 * while staying off a per-frame recomposition. The tick is gated on the RESUMED
 * lifecycle so a backgrounded screen isn't woken every 10 s for nothing (battery).
 */
@Composable
private fun tickingNow(): Instant {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(initialValue = Instant.now(), lifecycleOwner) {
        val scope = this
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                scope.value = Instant.now()
                delay(10_000)
            }
        }
    }
    return now
}
