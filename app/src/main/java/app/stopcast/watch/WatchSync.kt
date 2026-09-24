package app.stopcast.watch

import android.content.Context
import android.content.SharedPreferences
import app.stopcast.StopcastDebugLog
import app.stopcast.data.DataStoreSnapshotStore
import app.stopcast.data.DataStoreStarredRowsStore
import app.stopcast.data.HiddenModesSetting
import app.stopcast.data.WatchPayload
import app.stopcast.data.WatchSyncContract
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StarredRowSet
import app.stopcast.widget.logWidgetSnapshotWarning
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** The Wearable Data Layer as a [WatchChannel]. */
class DataLayerWatchChannel(context: Context, private val generations: WriteGenerations) : WatchChannel {
    private val appContext = context.applicationContext

    override suspend fun watchInstalled(): Boolean =
        try {
            Wearable.getCapabilityClient(appContext)
                .getCapability(WatchSyncContract.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                .await()
                .nodes
                .isNotEmpty()
        } catch (e: ApiException) {
            // No Wearable API on this phone (no Play services, or no Wear OS app): no watch, not a
            // failure to retry after every refresh. Anything else is a real failure.
            if (e.statusCode != CommonStatusCodes.API_NOT_CONNECTED) throw e
            false
        }

    override suspend fun put(payload: WatchPayload) {
        val request = PutDataMapRequest.create(WatchSyncContract.SNAPSHOT_PATH).apply {
            if (payload.asAsset) {
                dataMap.putAsset(WatchSyncContract.ENVELOPE_KEY, Asset.createFromBytes(payload.bytes))
            } else {
                dataMap.putByteArray(WatchSyncContract.ENVELOPE_KEY, payload.bytes)
            }
            // Identical bytes wouldn't change the item, so a forced republish would never reach
            // the watch; the publisher only writes on a change or a force, so this costs nothing.
            dataMap.putLong(WatchSyncContract.GENERATION_KEY, generations.next())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request).await()
    }
}

/**
 * The Data Layer write generation ([WatchSyncContract.GENERATION_KEY]), in app-private preferences:
 * a counter, so every write differs from the last. Only a change marker, never an ordering.
 */
class WriteGenerations(private val prefs: SharedPreferences) {
    fun next(): Long {
        val generation = prefs.getLong(KEY, 0L) + 1
        prefs.edit().putLong(KEY, generation).apply()
        return generation
    }

    private companion object {
        const val KEY = "write_generation"
    }
}

/** The last-published hash in app-private preferences. */
class PrefsPublishMarker(private val prefs: SharedPreferences) : PublishMarker {
    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(hash: String) {
        prefs.edit().putString(KEY, hash).apply()
    }

    private companion object {
        const val KEY = "published_hash"
    }
}

/**
 * Wires the phone's watch sync (dev-docs/wear-os.md *Sync*): every stored snapshot and every star
 * change is published once it settles, a failure is retried by [WatchPublishWorker], and a watch
 * that reconnects gets the current snapshot again ([PhoneWearListenerService]).
 */
object WatchSync {
    /** One publish at a time in the process, the collector's and the worker's alike, so an older
     *  envelope can never land after a newer one. */
    private val publishing = Mutex()

    @Volatile
    private var publisher: WatchPublisher? = null

    private fun publisher(context: Context): WatchPublisher =
        publisher ?: synchronized(this) {
            publisher ?: run {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences("watch_sync", Context.MODE_PRIVATE)
                WatchPublisher(
                    DataLayerWatchChannel(appContext, WriteGenerations(prefs)),
                    PrefsPublishMarker(prefs),
                    log = { StopcastDebugLog.warning("watch: %s", it) },
                ).also { publisher = it }
            }
        }

    /**
     * Publishes what is stored now, read under the process-wide publish lock, so whichever of the
     * collector and the worker runs last sends the newest snapshot, stars and hidden modes.
     */
    suspend fun publishCurrent(context: Context, force: Boolean): WatchPublisher.Outcome {
        val appContext = context.applicationContext
        return publishing.withLock {
            val (snapshot, stars) = try {
                snapshots(appContext).first() to starred(appContext).first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store read failed: a failed publish, which the worker retries, never a crash.
                StopcastDebugLog.warning("watch: stored state unreadable: %s", e::class.simpleName)
                return@withLock WatchPublisher.Outcome.Failed
            }
            // The same in-process setting the widget reads, so the watch leaves out what it does.
            publisher(appContext).publish(snapshot, stars, HiddenModesSetting.loaded(), force = force)
        }
    }

    fun snapshots(context: Context): Flow<DeparturesSnapshot?> =
        DataStoreSnapshotStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning).snapshots()

    /** The starred rows; a set this build can't read counts as none, never as a reason not to publish. */
    fun starred(context: Context): Flow<Set<StarredRow>> =
        // The store is a process singleton that keeps its first caller's sink, and this runs first.
        DataStoreStarredRowsStore.from(context.applicationContext, warn = { StopcastDebugLog.warning("stars: %s", it) }).starred()
            .map { (it as? StarredRowSet.Loaded)?.starred ?: emptySet() }

    /** Starts publishing for the life of the process. */
    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // A store that can't be read stops the collection, never the app; it's restarted a few
            // times with backoff, then left to the next start (or a watch reconnecting). The watch
            // keeps its last envelope meanwhile, which ages to stale on its own clock.
            WatchPublisher.keepCollecting(log = { StopcastDebugLog.warning("watch: %s", it) }) {
                // Each settled change is a cue; the publish itself reads the latest stored state.
                WatchPublisher.requests(snapshots(appContext), starred(appContext), HiddenModesSetting.changes).collect {
                    if (publishCurrent(appContext, force = false) == WatchPublisher.Outcome.Failed) {
                        WatchPublishWorker.enqueue(appContext, force = false)
                    }
                }
            }
        }
    }
}
