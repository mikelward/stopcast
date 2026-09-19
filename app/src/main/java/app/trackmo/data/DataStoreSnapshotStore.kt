package app.trackmo.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.SnapshotStore
import app.trackmo.domain.shouldClearWidgetSnapshot
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [SnapshotStore]. DataStore serializes reads and writes to one file
 * and survives process death, which is exactly what the cross-session restore and the
 * widget need: the app writes the last-good snapshot on every refresh, and a later launch —
 * or the widget, in the same app process — reads it back.
 *
 * The store adds no off-device channel of its own: it is a private app file, so it carries
 * the real stop and departure data the restore needs, unredacted, the same as any on-device
 * cache. It is not, however, strictly device-local — the app allows Android backup, so this
 * file can travel via the platform's backup and device-to-device transfer like the rest of
 * the app's data (SPEC §12 / *Privacy*). That is a platform path the user controls, not data
 * this code sends anywhere.
 */
class DataStoreSnapshotStore internal constructor(
    private val dataStore: DataStore<PersistedSnapshot?>,
) : SnapshotStore {

    override suspend fun load(): DeparturesSnapshot? = dataStore.data.first()?.toDomain()

    override suspend fun save(snapshot: DeparturesSnapshot) {
        dataStore.updateData { snapshot.toPersisted() }
    }

    /**
     * Atomically clears the snapshot iff the stored one describes a different stop set than
     * [resolvedStopIds] (or resolves empty); returns whether it cleared. The compare and the
     * clear are one `updateData` transaction, so a concurrent authoritative `save` of the new
     * set — which starts the moment the same `Ready` state composes — can't be read then
     * deleted: if that save lands first, the transform sees the new set and keeps it (Codex).
     * [shouldClearWidgetSnapshot] is the rule, applied to the *current* persisted value here
     * rather than to a stale earlier read. `updateData` runs the transform once and writes the
     * result atomically, so the captured [cleared] reflects what was committed.
     */
    suspend fun clearIfStopSetNot(resolvedStopIds: Set<String>): Boolean {
        var cleared = false
        dataStore.updateData { current ->
            val persistedIds = current?.toDomain()?.stops?.map { it.stopId }?.toSet().orEmpty()
            cleared = shouldClearWidgetSnapshot(persistedIds, resolvedStopIds)
            // writeTo(null) empties the file, which readFrom takes as "nothing saved" (a fresh
            // install), so the next load returns null; keeping `current` is a no-op write.
            if (cleared) null else current
        }
        return cleared
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "departures-snapshot.json"

        @Volatile
        private var instance: DataStoreSnapshotStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), and both the app and the widget read this file in the
         * same app process, so the [DataStore] is created once here and shared. Built from the
         * application context so it outlives any one Activity or widget update.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands, the
         * same as the ViewModel's): a corrupt or truncated file is logged and then discarded
         * by the corruption handler rather than swallowed, so the failure leaves a trace and
         * the next read starts clean. Only the first caller's [warn] is used, since the store
         * is a process singleton.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreSnapshotStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreSnapshotStore(
                    DataStoreFactory.create(
                        serializer = SnapshotSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            // Sanitized: the exception can quote the malformed JSON, which
                            // holds stop/departure data, so only the fact is logged, never its
                            // message (SPEC *Privacy*). Returning the default (null) replaces
                            // the bad file so the next launch reads clean.
                            warn("persisted snapshot was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }
    }
}

/**
 * Reads and writes [PersistedSnapshot] as JSON. An empty file is "nothing saved yet" and
 * reads back as null; a **corrupt** one throws [CorruptionException] rather than being
 * silently taken for empty, so the failure is surfaced — the store's corruption handler
 * logs it (sanitized) and replaces the file, instead of the loss going untraced and the next
 * save quietly overwriting the evidence. `ignoreUnknownKeys` lets a snapshot written by a
 * newer build (extra fields) still parse on an older one; a `version` mismatch is caught in
 * [PersistedSnapshot.toDomain] and discarded as "no last-good" without being corruption.
 */
internal object SnapshotSerializer : Serializer<PersistedSnapshot?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedSnapshot? = null

    override suspend fun readFrom(input: InputStream): PersistedSnapshot? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedSnapshot.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            // Signal corruption rather than returning null: the handler logs and replaces the
            // file. The message is generic — the exception can quote the malformed bytes,
            // which hold stop/departure data (SPEC *Privacy*), so the cause is not attached.
            throw CorruptionException("persisted snapshot could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedSnapshot?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedSnapshot.serializer(), t).encodeToByteArray())
    }
}
