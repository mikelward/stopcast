package app.stopcast.wear

import android.content.Context
import android.util.Log
import app.stopcast.data.WatchSyncContract
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * Receives each snapshot the phone publishes (dev-docs/wear-os.md *The snapshot over the wire*)
 * and stores it for the watch's surfaces. The Data Layer calls this on a background thread.
 */
class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val store = WatchEnvelopeStore.from(this)
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchSyncContract.SNAPSHOT_PATH }
            .forEach { event -> envelopeBytesRetrying(this, event.dataItem)?.let(store::ingest) }
    }

    private companion object {
        /** Waits before re-reading an envelope whose asset read failed; the phone counts its write
         *  done, so this event is the only cue until the next publish or the app's next start. */
        val READ_RETRIES_MS = listOf(1_000L, 3_000L)
    }

    /** [envelopeBytes], re-read a couple of times if it fails. Blocks, as the listener may. */
    private fun envelopeBytesRetrying(context: Context, item: DataItem): ByteArray? {
        envelopeBytes(context, item)?.let { return it }
        for (wait in READ_RETRIES_MS) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Log.w(TAG, "envelope read retry interrupted")
                Thread.currentThread().interrupt()
                return null
            }
            envelopeBytes(context, item)?.let { return it }
        }
        return null
    }
}


/**
 * The envelope carried by [item]: inline, or read from its `Asset` when it was too big for one.
 * Null (logged) when it has neither or the asset can't be read. Blocks; call off the main thread.
 */
internal fun envelopeBytes(context: Context, item: DataItem): ByteArray? {
    val map = DataMapItem.fromDataItem(item).dataMap
    map.getByteArray(WatchSyncContract.ENVELOPE_KEY)?.let { return it }
    val asset = map.getAsset(WatchSyncContract.ENVELOPE_KEY) ?: run {
        Log.w(TAG, "snapshot item carries no envelope")
        return null
    }
    return try {
        Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset)).inputStream.use { it.readBytes() }
    } catch (e: ExecutionException) {
        Log.w(TAG, "envelope asset read failed: ${(e.cause ?: e)::class.simpleName}")
        null
    } catch (e: IOException) {
        Log.w(TAG, "envelope asset read failed: ${e::class.simpleName}")
        null
    } catch (e: InterruptedException) {
        Log.w(TAG, "envelope asset read interrupted")
        Thread.currentThread().interrupt()
        null
    }
}

/**
 * Ingests the phone's latest snapshot item if the Data Layer already holds one: covers a watch app
 * installed, or its data cleared, after the phone last published. False (logged) when the lookup,
 * or reading the envelope it found, failed, so the caller can retry. Blocks; call off the main
 * thread.
 */
internal fun ingestExisting(context: Context, store: WatchEnvelopeStore): Boolean {
    return try {
        // The listener delivers the phone's writes in order; anything it stores while this reads
        // is newer, so this one is kept only if nothing arrived since it started.
        val since = store.ingestCount()
        val items = Tasks.await(Wearable.getDataClient(context).dataItems)
        try {
            // An item whose envelope couldn't be read (an asset fetch failing, logged) is a
            // failed lookup, so the caller retries; one the store refuses or skips is not.
            items.filter { it.uri.path == WatchSyncContract.SNAPSHOT_PATH }
                .map { item -> envelopeBytes(context, item)?.also { store.ingest(it, ifNoneSince = since) } }
                .all { it != null }
        } finally {
            items.release()
        }
    } catch (e: ExecutionException) {
        val cause = e.cause
        Log.w(TAG, "snapshot lookup failed: ${if (cause is ApiException) cause.statusCode else (cause ?: e)::class.simpleName}")
        false
    } catch (e: InterruptedException) {
        Log.w(TAG, "snapshot lookup interrupted")
        Thread.currentThread().interrupt()
        false
    }
}

private const val TAG = "StopCast.Watch"
