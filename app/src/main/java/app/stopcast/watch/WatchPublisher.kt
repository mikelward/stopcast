package app.stopcast.watch

import app.stopcast.data.WatchEnvelopes
import app.stopcast.data.WatchPayload
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.StarredRow
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce

/** The phone's side of the Data Layer, a seam so [WatchPublisher] is testable without Play services. */
interface WatchChannel {
    /** Whether a paired watch, connected or not, has the watch app installed. */
    suspend fun watchInstalled(): Boolean

    /** Writes [payload] as the latest snapshot item; the Data Layer syncs it when the watch is in reach. */
    suspend fun put(payload: WatchPayload)
}

/** The durable record of what was last published, so a lost publish is noticed on the next start. */
interface PublishMarker {
    fun get(): String?

    fun set(hash: String)
}

/**
 * Publishes the widget's snapshot to the watch (dev-docs/wear-os.md *Sync*), only when a paired
 * watch has the app, and only when the envelope differs from the last one published. The marker
 * is written only after a successful write, so a publish lost to a failure or to process death is
 * retried by the next attempt rather than suppressed.
 */
class WatchPublisher(
    private val channel: WatchChannel,
    private val marker: PublishMarker,
    private val log: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    sealed interface Outcome {
        data object Published : Outcome

        /** The same envelope was already published. */
        data object Unchanged : Outcome

        /** No snapshot has been stored yet: the watch keeps saying "Open StopCast on your phone". */
        data object NothingStored : Outcome

        /** No paired watch has the app: nothing leaves the phone. */
        data object NoWatch : Outcome

        /** The Data Layer failed; the caller schedules a retry. */
        data object Failed : Outcome
    }

    suspend fun publish(snapshot: DeparturesSnapshot?, starred: Set<StarredRow>, force: Boolean = false): Outcome {
        snapshot ?: return Outcome.NothingStored
        return try {
            // Asked first, so a phone with no watch app never builds or hashes an envelope.
            if (!channel.watchInstalled()) return Outcome.NoWatch
            val payload = WatchEnvelopes.build(snapshot, starred, now = now())
            val hash = sha256(payload.bytes)
            if (!force && hash == marker.get()) return Outcome.Unchanged
            channel.put(payload)
            marker.set(hash)
            Outcome.Published
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The failure type only: the payload is the user's stops, never logged.
            log("watch publish failed: ${e::class.simpleName}")
            Outcome.Failed
        }
    }

    companion object {
        /** A burst of writes (one refresh saves several times) publishes once, at most this often. */
        val COALESCE: Duration = 2.seconds

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * One publish request per settled change to the stored [snapshots] or [starred] rows: every
         * write, from any writer, with bursts coalesced to the latest inside [window]. The first
         * value is the state at start, which the durable marker compares against.
         */
        @OptIn(FlowPreview::class)
        fun requests(
            snapshots: Flow<DeparturesSnapshot?>,
            starred: Flow<Set<StarredRow>>,
            window: Duration = COALESCE,
        ): Flow<Pair<DeparturesSnapshot?, Set<StarredRow>>> =
            combine(snapshots, starred) { snapshot, stars -> snapshot to stars }.debounce(window)
    }
}
