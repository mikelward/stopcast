package app.stopdash.wear

import android.content.Context
import android.util.Log
import app.stopdash.data.WatchDecode
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchEnvelopes
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the watch last received from the phone. */
sealed interface WatchReceived {
    /** Not read from disk yet. */
    data object Loading : WatchReceived

    /** Nothing has arrived since install. */
    data object NeverSynced : WatchReceived

    data class Received(val envelope: WatchEnvelope, val receivedAt: Instant) : WatchReceived
}

/**
 * The latest envelope the phone sent, kept on the watch so every surface renders from it without
 * waiting on the phone (the network is never on a render path). One file, written atomically; an
 * envelope this build can't read is logged and dropped, keeping the last good one, which the
 * surfaces age honestly.
 */
class WatchEnvelopeStore(
    private val file: File,
    private val now: () -> Instant = Instant::now,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val _state = MutableStateFlow<WatchReceived>(WatchReceived.Loading)
    val state: StateFlow<WatchReceived> = _state.asStateFlow()

    private val lock = Any()

    /** Envelopes stored this process, so a startup lookup can tell the listener got a newer one
     *  while it was reading ([ingestCount]). */
    private var ingests = 0L

    /** How many envelopes have been stored this process; pass it back as `ifNoneSince`. */
    fun ingestCount(): Long = synchronized(lock) { ingests }

    /** Reads the stored envelope once. Call off the main thread. */
    fun load() {
        synchronized(lock) {
            if (_state.value != WatchReceived.Loading) return
            _state.value = read()
        }
    }

    /**
     * Stores [bytes] if they decode, and publishes them. Returns false (logged, nothing stored)
     * for an envelope this build refuses or can't read, and (quietly) when [ifNoneSince] is given
     * and another envelope was stored since that [ingestCount]: the listener's is newer. Call off the main thread.
     */
    fun ingest(bytes: ByteArray, ifNoneSince: Long? = null): Boolean {
        val envelope = when (val decoded = WatchEnvelopes.decode(bytes)) {
            is WatchDecode.Ok -> decoded.envelope
            is WatchDecode.UnsupportedVersion -> {
                log("envelope refused: version ${decoded.version}")
                return false
            }
            is WatchDecode.Unreadable -> {
                log("envelope unreadable: ${decoded.reason}")
                return false
            }
        }
        synchronized(lock) {
            if (ifNoneSince != null && ingests != ifNoneSince) return false
            ingests++
            try {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            } catch (e: IOException) {
                // Still shown this session; the next envelope tries the write again.
                log("envelope write failed: ${e::class.simpleName}")
            }
            _state.value = WatchReceived.Received(envelope, now())
        }
        return true
    }

    private fun read(): WatchReceived {
        if (!file.exists()) return WatchReceived.NeverSynced
        return try {
            when (val decoded = WatchEnvelopes.decode(file.readBytes())) {
                is WatchDecode.Ok -> WatchReceived.Received(decoded.envelope, Instant.ofEpochMilli(file.lastModified()))
                is WatchDecode.UnsupportedVersion -> {
                    log("stored envelope refused: version ${decoded.version}")
                    WatchReceived.NeverSynced
                }
                is WatchDecode.Unreadable -> {
                    log("stored envelope unreadable: ${decoded.reason}")
                    WatchReceived.NeverSynced
                }
            }
        } catch (e: IOException) {
            log("stored envelope read failed: ${e::class.simpleName}")
            WatchReceived.NeverSynced
        }
    }

    companion object {
        private const val TAG = "StopDash.Watch"

        @Volatile
        private var instance: WatchEnvelopeStore? = null

        fun from(context: Context): WatchEnvelopeStore =
            instance ?: synchronized(this) {
                instance ?: WatchEnvelopeStore(File(context.applicationContext.filesDir, "watch-envelope.json"))
                    .also { instance = it }
            }
    }
}
