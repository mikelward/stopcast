package app.stopcast.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * A list's scroll position that belongs to the content identified by [contentKey] (the nearby stop
 * set): kept, and saved across rotation and process recreation, while that content stays the same,
 * and sent back to the top when different content arrives. A null key (the content not known yet,
 * e.g. while location resolves after process recreation) changes nothing, so a return to the same
 * content keeps the restored position rather than starting over (Codex).
 */
@Composable
fun rememberListStateFor(contentKey: String?): LazyListState {
    val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // The content the position belongs to, saved beside it.
    var ownerKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(contentKey) {
        if (contentKey != null && contentKey != ownerKey) {
            if (ownerKey != null) state.requestScrollToItem(0)
            ownerKey = contentKey
        }
    }
    return state
}
