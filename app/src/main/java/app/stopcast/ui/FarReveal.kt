package app.stopcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Whether the rider has tapped "Faraway favorites" for the nearby stop set [key] (SPEC *Journeys*).
 * Saved as the set it was tapped for, not a bare flag, so a restore after process death somewhere
 * else doesn't bring it back unchecked.
 */
@Stable
class FarRevealState internal constructor(private val revealedFor: MutableState<String?>, private val key: String) {
    val revealed: Boolean get() = revealedFor.value == key

    fun reveal() {
        revealedFor.value = key
    }
}

/**
 * The Faraway favorites tap for [nearbyKey], kept while that set stays (held above the overlays and
 * route page, like the list's scroll position, so opening one doesn't forget it) and forgotten on
 * leaving the set, so coming back later holds far journeys back again. A null key (the set not known
 * yet, e.g. while location resolves) changes nothing, as in [rememberListStateFor].
 */
@Composable
fun rememberFarReveal(nearbyKey: String?): FarRevealState {
    val revealedFor = rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(nearbyKey) {
        if (nearbyKey != null && revealedFor.value != nearbyKey) revealedFor.value = null
    }
    val key = nearbyKey ?: ""
    return remember(revealedFor, key) { FarRevealState(revealedFor, key) }
}
