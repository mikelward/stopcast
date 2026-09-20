package app.trackmo.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.trackmo.domain.AppSettings
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [AppSettings] (mirrors [DataStoreStarredRowsStore]). DataStore serializes
 * reads and writes to one file and survives process death, and its [DataStore.data] flow re-emits
 * on every write — so a Settings screen collecting a setting reflects a toggle at once and a later
 * launch reads it back.
 *
 * A setting is not irreplaceable user work, so — unlike the starred rows — a corrupt or
 * newer-schema file is discarded and read as the defaults rather than preserved. The store adds
 * no off-device channel of its own: it is a private app file, riding Android backup /
 * device-to-device transfer like the rest of the app's data (SPEC §12 / *Privacy*).
 */
class DataStoreAppSettings internal constructor(
    private val dataStore: DataStore<PersistedSettings?>,
) : AppSettings {

    override fun liveWidgetRefresh(): Flow<Boolean> =
        dataStore.data
            // A transient read failure (an I/O error, not the recognized corruption the handler
            // replaces) is emitted into `data` as an exception. Retry it rather than collapsing to
            // a terminal default: a `catch`-and-emit would end the flow, leaving a long-lived
            // collector stuck at the default after storage recovered and making the worker read a
            // synthetic `false` and retire the chain (Codex P2 on #56). retryWhen keeps the flow
            // alive and recovers when the read succeeds; a non-IO cause rethrows. Sanitized log.
            .retryWhen { cause, _ ->
                if (cause is IOException) {
                    logAppSettingsWarning("settings read failed, retrying: ${cause::class.simpleName}")
                    delay(SETTINGS_READ_RETRY_MILLIS)
                    true
                } else {
                    false
                }
            }
            .map { it?.liveWidgetRefresh ?: DEFAULT_LIVE_WIDGET_REFRESH }

    override suspend fun setLiveWidgetRefresh(enabled: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(liveWidgetRefresh = enabled) }
    }

    companion object {
        /** The documented default, used before anything is saved and after a discard. */
        const val DEFAULT_LIVE_WIDGET_REFRESH = false

        /** Backoff between retries of a transient settings read, so [liveWidgetRefresh]'s
         *  retryWhen doesn't hot-loop while storage is briefly unavailable. */
        private const val SETTINGS_READ_RETRY_MILLIS = 1_000L

        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "app-settings.json"

        @Volatile
        private var instance: DataStoreAppSettings? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), so the [DataStore] is created once here and shared. Built
         * from the application context so it outlives any one Activity.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands): a
         * corrupt or truncated file is logged and then discarded rather than swallowed. Only the
         * first caller's [warn] is used (process singleton).
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreAppSettings =
            instance ?: synchronized(this) {
                instance ?: DataStoreAppSettings(
                    DataStoreFactory.create(
                        serializer = SettingsSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("app settings file was unreadable and has been discarded")
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
 * Sanitized log sink for a discarded corrupt/unreadable settings file. A fixed reason only —
 * never a setting value (SPEC *Privacy* / *Error handling*). Wired into [DataStoreAppSettings.from]
 * by the production callers so a silent reset-to-defaults leaves a diagnostic (Codex P2 on #56).
 * A top-level function so every caller passes the same sink (the singleton keeps the first).
 */
internal fun logAppSettingsWarning(message: String) = Log.w("Trackmo.Settings", message)

/**
 * The persisted settings shape. Every field carries a default so a file written by an older
 * build (missing a field) reads back complete, and `ignoreUnknownKeys` lets a newer build's
 * extra fields parse rather than corrupt.
 */
@Serializable
data class PersistedSettings(
    val liveWidgetRefresh: Boolean = DataStoreAppSettings.DEFAULT_LIVE_WIDGET_REFRESH,
)

/**
 * Reads and writes [PersistedSettings] as JSON (mirrors [StarredRowsSerializer]). An empty file
 * is "nothing saved yet" and reads back as null (→ the defaults); a **corrupt** one throws
 * [CorruptionException] so the corruption handler logs it and replaces the file with the
 * defaults — acceptable here because a setting, unlike the user's stars, is not irreplaceable.
 */
internal object SettingsSerializer : Serializer<PersistedSettings?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedSettings? = null

    override suspend fun readFrom(input: InputStream): PersistedSettings? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedSettings.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("app settings could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedSettings?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedSettings.serializer(), t).encodeToByteArray())
    }
}
