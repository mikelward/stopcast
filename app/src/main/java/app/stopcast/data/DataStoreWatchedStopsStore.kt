package app.stopcast.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopcast.domain.WatchedStop
import app.stopcast.domain.WatchedStopSet
import app.stopcast.domain.WatchedStops
import app.stopcast.domain.WatchedStopsStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [WatchedStopsStore]. DataStore serializes reads and writes to one file
 * and survives process death, and its [DataStore.data] flow re-emits on every write — so a
 * surface collecting [watched] re-renders the moment the user adds or removes a stop, and a
 * later launch (or the widget, in the same app process) reads the set back.
 *
 * The store adds no off-device channel of its own: it is a private app file carrying the
 * watched set unredacted, the same as any on-device config. It is not strictly device-local —
 * the app allows Android backup, so this file rides the platform's backup and device-to-device
 * transfer like the rest of the app's data (SPEC §12 / *Privacy*), a platform path the user
 * controls, not data this code sends anywhere.
 */
class DataStoreWatchedStopsStore internal constructor(
    private val dataStore: DataStore<PersistedWatchedStops?>,
    // Sanitized log seam (no-op default, like the corruption handler): records the one
    // notable non-happy write outcome — preserving a newer-version file rather than
    // overwriting it. Never carries a stop name (SPEC *Privacy*).
    private val warn: (String) -> Unit = {},
) : WatchedStopsStore {

    // Absent/discarded (null) → an empty set the user can add to. Present and readable → the
    // set. Present but a version this build can't read → Unavailable, kept distinct from empty
    // so a surface never shows a newer-version set as "no watched stops" (SPEC principle 2).
    override fun watched(): Flow<WatchedStopSet> =
        dataStore.data.map { stored ->
            when {
                stored == null -> WatchedStopSet.Loaded(emptyList())
                else -> stored.toDomain()?.let { WatchedStopSet.Loaded(it) }
                    ?: WatchedStopSet.Unavailable
            }
        }

    override suspend fun add(stop: WatchedStop) {
        editReadable { WatchedStops.add(it, stop) }
    }

    override suspend fun remove(stopId: String) {
        editReadable { WatchedStops.remove(it, stopId) }
    }

    /**
     * Apply [edit] to the current set and persist the result — but **only when the stored file
     * is one this build can read**. A file written by a newer schema version is preserved
     * untouched: overwriting it would downgrade it to this build's version and silently erase
     * the user's watched stops on a downgrade or rollback (SPEC *never lose the user's work*).
     * An absent/discarded file (null) is a genuine empty set the edit starts from.
     */
    private suspend fun editReadable(edit: (List<WatchedStop>) -> List<WatchedStop>) {
        dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                // Present but unreadable version — leave it exactly as it is.
                warn("watched stops file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                edit(stored?.toDomain() ?: emptyList()).toPersisted()
            }
        }
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "watched-stops.json"

        @Volatile
        private var instance: DataStoreWatchedStopsStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), and both the app and the widget read this file in the same
         * app process, so the [DataStore] is created once here and shared. Built from the
         * application context so it outlives any one Activity or widget update.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands): a
         * corrupt or truncated file is logged and then discarded by the corruption handler
         * rather than swallowed, so the failure leaves a trace and the next read starts clean.
         * Only the first caller's [warn] is used, since the store is a process singleton.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreWatchedStopsStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreWatchedStopsStore(
                    DataStoreFactory.create(
                        serializer = WatchedStopsSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            // Sanitized: the exception can quote the malformed JSON, which holds
                            // stop names (SPEC *Privacy*), so only the fact is logged, never its
                            // message. Returning the default (null) replaces the bad file so the
                            // next read starts from an empty set.
                            warn("watched stops file was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                    warn,
                ).also { instance = it }
            }
    }
}

/**
 * Reads and writes [PersistedWatchedStops] as JSON. An empty file is "nothing saved yet" and
 * reads back as null (→ an empty set); a **corrupt** one throws [CorruptionException] rather
 * than being silently taken for empty, so the failure is surfaced — the store's corruption
 * handler logs it (sanitized) and replaces the file. `ignoreUnknownKeys` lets a set written by
 * a newer build (extra fields) still parse on an older one; a `version` mismatch is caught in
 * [PersistedWatchedStops.toDomain] and read as empty without being corruption.
 */
internal object WatchedStopsSerializer : Serializer<PersistedWatchedStops?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedWatchedStops? = null

    override suspend fun readFrom(input: InputStream): PersistedWatchedStops? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedWatchedStops.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            // Signal corruption rather than returning null: the handler logs and replaces the
            // file. The message is generic — the exception can quote the malformed bytes, which
            // hold stop names (SPEC *Privacy*), so the cause is not attached.
            throw CorruptionException("watched stops could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedWatchedStops?, output: OutputStream) {
        if (t == null) return
        output.write(
            json.encodeToString(PersistedWatchedStops.serializer(), t).encodeToByteArray(),
        )
    }
}
